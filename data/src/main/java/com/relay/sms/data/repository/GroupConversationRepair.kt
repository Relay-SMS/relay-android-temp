/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.repository

import androidx.room.withTransaction
import com.relay.sms.core.ext.blockKey
import com.relay.sms.core.ext.canonicalPhone
import com.relay.sms.core.ext.stripMmsAddressSuffix
import com.relay.sms.core.result.runCatchingCancellable
import com.relay.sms.data.local.db.AppDatabase
import com.relay.sms.data.local.db.dao.ConversationDao
import com.relay.sms.data.local.db.dao.MessageDao
import com.relay.sms.data.local.db.dao.ScheduledMessageDao
import com.relay.sms.data.local.db.entity.ConversationEntity
import com.relay.sms.data.sms.PhoneIdentity
import com.relay.sms.data.sms.TelephonyReader
import com.relay.sms.di.IoDispatcher
import com.relay.sms.domain.model.GroupTitle
import com.relay.sms.domain.model.PhoneAddress
import com.relay.sms.domain.model.PhoneAddress.Companion.toCsv
import com.relay.sms.domain.notification.ConversationNotificationCanceller
import com.relay.sms.domain.repository.ContactRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 50 — one-shot repair for group threads the pre-round-50 import
 * filed as 1:1s.
 *
 * The old MMS import filed every thread with ONLY the first message's
 * sender (`ensureConversationByThread` with a single address), so every
 * group thread came back as a 1:1 after a reinstall — the group members
 * were lost from the conversation row even though every message
 * survived. The import now files groups correctly (see
 * `TelephonyReader.readThreadRecipients`), but installs that already
 * imported need their misfiled rows fixed without a reinstall or a
 * clear-data.
 *
 * The misfiled 1:1 row already carries the CORRECT system thread_id
 * (`ensureConversationByThread` stored it), so the repair re-reads the
 * thread's recipient set from the provider and re-files the row in
 * place — no message re-pointing is needed (messages are keyed by
 * `conversation_id`), no message is lost, and unread counts are
 * untouched (only `addresses_csv`, `display_name` and the sticky
 * category change).
 *
 * Member-ID fix — a second arm for groups the round-52 import filed
 * with recipient IDS as members ("23", "59"): `readThreadRecipients`
 * read column 0 of the `canonical-addresses` cursor, which AOSP
 * fills with `_id` (the provider ignores the caller's projection
 * for the plural URI). Those groups are filed as groups, so the
 * refile arm never sees them — the CorrectMembers arm re-reads
 * their recipient set with the fixed lookup and corrects
 * `addresses_csv` + the group title in place. A group whose members
 * already match is left exactly alone; a corrected set that would
 * collide with another existing group is left alone too (never
 * create a duplicate group).
 *
 * Safety rules (each is load-bearing):
 *   - only a conversation whose thread's recipient set has MORE THAN
 *     ONE member is touched: a legit 1:1 keeps its row exactly. The
 *     recipient set comes from the provider (`recipient_ids`), which
 *     keys a thread by its members — a 1:1 thread yields one address.
 *   - vault conversations are never touched, and a merge never crosses
 *     the vault boundary: merging a vault victim into a visible group
 *     would expose hidden messages, and the reverse would hide visible
 *     ones. A misfiled 1:1 whose group already exists in the vault is
 *     left alone.
 *   - when a group conversation already exists for the same members
 *     (created by the live path while the import filed the 1:1), the
 *     two are MERGED, never duplicated: messages + scheduled messages
 *     are reparented onto the group first (FK CASCADE on
 *     `messages.conversation_id` — delete first would erase them),
 *     metadata merges like the dedupe pass, and only then is the 1:1
 *     row deleted. The group's thread_id is adopted from the victim
 *     when it lacks one (or differs — the provider is authoritative).
 *   - provider reads and contact lookups run OUTSIDE the Room
 *     transaction (phase 1 plans, phase 2 applies): a ContentResolver
 *     call must never hold the database lock.
 *
 * [Result.complete] is `false` when any provider read failed: the
 * caller ([com.relay.sms.system.startup.StartupMigrations]) only sets
 * its once-flag on a complete pass, so a failed read retries at the
 * next cold start instead of leaving threads misfiled forever.
 */
@Singleton
class GroupConversationRepair @Inject constructor(
    private val database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val scheduledMessageDao: ScheduledMessageDao,
    private val telephonyReader: TelephonyReader,
    private val phoneIdentity: PhoneIdentity,
    private val contacts: ContactRepository,
    private val notifications: ConversationNotificationCanceller,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Outcome of one [repair] run. */
    data class Result(
        /** Misfiled 1:1s re-filed as groups in place. */
        val repaired: Int,
        /** Misfiled 1:1s merged into an existing group conversation. */
        val merged: Int,
        /** `false` when a provider read failed — the pass must re-run. */
        val complete: Boolean,
        /** Groups whose member list was corrected in place (member-ID fix). */
        val corrected: Int = 0,
    )

    private sealed interface Plan {
        /** Re-file the 1:1 row in place as the group [csv] / [title]. */
        data class Refile(val victim: ConversationEntity, val csv: String, val title: String?) : Plan

        /** Merge the 1:1's messages into the group [survivorId]. */
        data class Merge(
            val victim: ConversationEntity,
            val survivorId: Long,
            val threadId: Long,
            /** Group title for the survivor, when it has no display name yet. */
            val survivorTitle: String?,
        ) : Plan

        /** Correct a group's member list in place to [csv] / [title]. */
        data class CorrectMembers(
            val victim: ConversationEntity,
            val csv: String,
            val title: String?,
        ) : Plan
    }

    /** Outcome of planning one candidate in [repair]'s phase 1. */
    private sealed interface PlanOutcome {
        /** A plan to apply in phase 2. */
        data class Planned(val plan: Plan) : PlanOutcome

        /** Not a misfiled group (1:1 thread, or a vault group match). */
        data object Skipped : PlanOutcome

        /** The provider read failed — the pass is incomplete. */
        data object ProviderFailed : PlanOutcome
    }

    /**
     * Plans the repair for one misfiled-1:1 candidate: re-reads the
     * thread's recipient set and matches it against the existing group
     * conversations. A single `when` keeps the phase-1 loop free of
     * jump statements (Detekt's LoopWithTooManyJumpStatements).
     */
    private suspend fun planFor(
        conv: ConversationEntity,
        groups: List<ConversationEntity>,
        matches: (String, String) -> Boolean,
    ): PlanOutcome {
        val threadId = conv.threadId
        val recipients = threadId?.let {
            runCatching { telephonyReader.readThreadRecipients(it) }.getOrNull()
        }
        return when {
            threadId == null -> PlanOutcome.Skipped
            recipients == null -> PlanOutcome.ProviderFailed
            recipients.size < 2 -> PlanOutcome.Skipped
            else -> {
                val members = recipients.map { PhoneAddress.of(it) }
                val csv = members.sortedBy { it.normalized }.toCsv()
                val existing = conversationDao.findByAddressesCsv(csv)
                    ?: ConversationRepositoryImpl.matchGroupByIdentity(groups, members, matches)
                when {
                    existing == null -> PlanOutcome.Planned(
                        Plan.Refile(victim = conv, csv = csv, title = groupTitle(members)),
                    )
                    existing.inVault -> {
                        // Never merge across the vault boundary (see header).
                        Timber.i(
                            "GroupConversationRepair: conversation %d matches vault group %d — left alone",
                            conv.id,
                            existing.id,
                        )
                        PlanOutcome.Skipped
                    }
                    else -> PlanOutcome.Planned(
                        Plan.Merge(
                            victim = conv,
                            survivorId = existing.id,
                            threadId = threadId,
                            survivorTitle = existing.displayName
                                ?: groupTitle(PhoneAddress.list(existing.addressesCsv)),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Plans the correction for one group candidate whose stored
     * members may be recipient ids (member-ID fix): re-reads the
     * thread's recipient set with the fixed lookup and compares it
     * with the stored member set (canonical comparison — the same
     * matcher the refile arm uses). A healthy group is left exactly
     * alone; a corrected set that would collide with ANOTHER
     * existing group is left alone too (never create a duplicate
     * group — the dedupe pass owns duplicates).
     */
    private suspend fun planCorrection(
        conv: ConversationEntity,
        groups: List<ConversationEntity>,
        matches: (String, String) -> Boolean,
    ): PlanOutcome {
        val threadId = conv.threadId
        val recipients = threadId?.let {
            runCatching { telephonyReader.readThreadRecipients(it) }.getOrNull()
        }
        return when {
            threadId == null -> PlanOutcome.Skipped
            recipients == null -> PlanOutcome.ProviderFailed
            recipients.size < 2 -> PlanOutcome.Skipped
            else -> {
                val members = recipients.map { PhoneAddress.of(it) }
                val csv = members.sortedBy { it.normalized }.toCsv()
                val alreadyCorrect =
                    ConversationRepositoryImpl.matchGroupByIdentity(listOf(conv), members, matches) != null
                val collision = if (alreadyCorrect) {
                    null
                } else {
                    conversationDao.findByAddressesCsv(csv)
                        ?: ConversationRepositoryImpl.matchGroupByIdentity(
                            groups.filter { it.id != conv.id },
                            members,
                            matches,
                        )
                }
                when {
                    alreadyCorrect -> PlanOutcome.Skipped
                    collision != null -> {
                        Timber.i(
                            "GroupConversationRepair: group %d would collide with group %d — left alone",
                            conv.id,
                            collision.id,
                        )
                        PlanOutcome.Skipped
                    }
                    else -> PlanOutcome.Planned(
                        Plan.CorrectMembers(victim = conv, csv = csv, title = groupTitle(members)),
                    )
                }
            }
        }
    }

    suspend fun repair(): Result = withContext(io) {
        val groups = conversationDao.snapshotGroupConversations()
        val candidates = conversationDao.snapshotOneToOneConversations()
            .filter { !it.inVault && (it.threadId ?: 0L) > 0L }
        // Member-ID fix — groups filed with recipient ids as members
        // are group rows already, so the refile arm never sees them;
        // they get their own candidate list (same filters).
        val groupCandidates = groups.filter { !it.inVault && (it.threadId ?: 0L) > 0L }
        if (candidates.isEmpty() && groupCandidates.isEmpty()) {
            return@withContext Result(0, 0, complete = true)
        }
        val ident = phoneIdentity.snapshot()
        val matches = repairMatches(ident::matches)
        // Phase 1 — plan (provider + contacts reads, NO transaction).
        val (plans, complete) = planAll(candidates, groupCandidates, groups, matches)
        if (plans.isEmpty()) return@withContext Result(0, 0, complete)
        // Phase 2 — apply (one transaction; rows are re-read — a live
        // receive may have changed them since the snapshot).
        val deletedVictims = mutableListOf<Long>()
        var repaired = 0
        var merged = 0
        var corrected = 0
        database.withTransaction {
            for (plan in plans) {
                when (plan) {
                    is Plan.Refile -> {
                        val current = conversationDao.findById(plan.victim.id)
                        if (current != null) {
                            conversationDao.update(
                                current.copy(
                                    addressesCsv = plan.csv,
                                    // The 1:1's display name is ONE member's
                                    // name — it must not survive as the group
                                    // title. `null` falls back to the member
                                    // numbers, like any unnamed group.
                                    displayName = plan.title,
                                    // A group never stores a sticky category
                                    // (the render rule only honors it for a
                                    // stranger 1:1) — the misfiled row may
                                    // have been filed as spam while 1:1.
                                    stickyCategory = null,
                                ),
                            )
                            repaired++
                        }
                    }
                    is Plan.Merge -> {
                        val victim = conversationDao.findById(plan.victim.id)
                        if (victim != null) {
                            val survivor = conversationDao.findById(plan.survivorId)
                            if (survivor != null) {
                                mergeIntoGroup(victim, survivor, plan)
                                deletedVictims += victim.id
                                merged++
                            }
                        }
                    }
                    is Plan.CorrectMembers -> {
                        val current = conversationDao.findById(plan.victim.id)
                        if (current != null) {
                            conversationDao.update(
                                current.copy(
                                    addressesCsv = plan.csv,
                                    displayName = plan.title,
                                ),
                            )
                            corrected++
                        }
                    }
                }
            }
            if (merged > 0) conversationDao.recomputeAllUnreadCounts()
        }
        // After commit, and only for victims actually deleted (the
        // dedupe pass's rule): their notifications pointed at a row
        // that no longer exists.
        for (victimId in deletedVictims) {
            notifications.cancelAllForConversation(victimId)
        }
        Timber.i(
            "GroupConversationRepair: done — repaired=%d merged=%d corrected=%d complete=%b",
            repaired,
            merged,
            corrected,
            complete,
        )
        Result(repaired, merged, complete, corrected)
    }

    /**
     * Phase 1 — plans both arms (provider + contacts reads, NO
     * transaction). Returns the plans plus whether every provider
     * read succeeded (`false` = the pass must re-run next start).
     */
    private suspend fun planAll(
        candidates: List<ConversationEntity>,
        groupCandidates: List<ConversationEntity>,
        groups: List<ConversationEntity>,
        matches: (String, String) -> Boolean,
    ): Pair<List<Plan>, Boolean> {
        var complete = true
        val plans = ArrayList<Plan>(candidates.size + groupCandidates.size)
        for (conv in candidates) {
            when (val outcome = planFor(conv, groups, matches)) {
                is PlanOutcome.Planned -> plans += outcome.plan
                // Provider read failed: fail-soft, retry next cold start.
                PlanOutcome.ProviderFailed -> complete = false
                PlanOutcome.Skipped -> Unit
            }
        }
        for (conv in groupCandidates) {
            when (val outcome = planCorrection(conv, groups, matches)) {
                is PlanOutcome.Planned -> plans += outcome.plan
                PlanOutcome.ProviderFailed -> complete = false
                PlanOutcome.Skipped -> Unit
            }
        }
        return plans to complete
    }

    /**
     * Merges [victim] (a misfiled 1:1) into [survivor] (the group for
     * the same members). Same shape as the dedupe pass: reparent
     * BEFORE delete (FK CASCADE), metadata merges onto the survivor,
     * the victim's system thread_id is adopted (after the delete — the
     * `thread_id` index is UNIQUE and the victim still held it).
     * The victim's display name is NEVER carried over: it names one
     * member, not the group.
     */
    private suspend fun mergeIntoGroup(
        victim: ConversationEntity,
        survivor: ConversationEntity,
        plan: Plan.Merge,
    ) {
        messageDao.reparentMessages(
            fromConversationId = victim.id,
            toConversationId = survivor.id,
        )
        scheduledMessageDao.reparentConversationId(
            fromConversationId = victim.id,
            toConversationId = survivor.id,
        )
        val newest = if (victim.lastMessageAt > survivor.lastMessageAt) victim else survivor
        conversationDao.update(
            survivor.copy(
                lastMessageAt = newest.lastMessageAt,
                lastMessagePreview = newest.lastMessagePreview,
                pinned = survivor.pinned || victim.pinned,
                muted = survivor.muted || victim.muted,
                // Archived only when BOTH were (dedupe D1): an active
                // thread merged into an archived one must not vanish.
                archived = survivor.archived && victim.archived,
                displayName = survivor.displayName ?: plan.survivorTitle,
                avatarUri = survivor.avatarUri ?: victim.avatarUri,
                bubbleColorArgb = survivor.bubbleColorArgb ?: victim.bubbleColorArgb,
                // Dedupe D2 — concatenate non-blank drafts instead of
                // dropping the victim's unsent text.
                draft = listOfNotNull(
                    survivor.draft?.takeIf { it.isNotBlank() },
                    victim.draft?.takeIf { it.isNotBlank() },
                )
                    .distinct()
                    .joinToString("\n\n")
                    .takeIf { it.isNotBlank() },
            ),
        )
        conversationDao.delete(victim.id)
        if (survivor.threadId != plan.threadId) {
            conversationDao.setThreadId(survivor.id, plan.threadId)
        }
    }

    /**
     * Group-member matcher for the repair: the region-aware identity
     * rule first, then `canonicalPhone()` (reunites a NANP number's
     * E.164 and national spellings with no region — the case the
     * identity rule cannot cover when neither the SIM nor the network
     * yields one), then the 9-digit `blockKey` fallback the mirror's
     * `rapprocherGroupe` uses for the same situation. Every arm fails
     * closed: a missed match leaves a duplicate group to reconcile,
     * never a wrong merge.
     */
    private fun repairMatches(strict: (String, String) -> Boolean): (String, String) -> Boolean =
        { a, b ->
            strict(a, b) || run {
                val ka = a.canonicalPhone()
                val kb = b.canonicalPhone()
                ka.isNotBlank() && ka == kb
            } || run {
                val ka = a.stripMmsAddressSuffix().blockKey()
                val kb = b.stripMmsAddressSuffix().blockKey()
                ka.length == 9 && kb.length == 9 &&
                    ka.all { it.isDigit() } && kb.all { it.isDigit() } && ka == kb
            }
        }

    /** Group title from resolved contact names (the mirror's `nomDeGroupe` twin). */
    private suspend fun groupTitle(members: List<PhoneAddress>): String? =
        GroupTitle.of(members.map { it.raw to resolveDisplayName(it.raw) })

    private suspend fun resolveDisplayName(rawAddress: String): String? =
        runCatchingCancellable { contacts.lookupByPhone(rawAddress)?.displayName }.getOrNull()
}
