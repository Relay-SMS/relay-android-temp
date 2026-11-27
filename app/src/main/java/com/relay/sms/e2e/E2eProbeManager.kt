/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import com.relay.sms.core.ext.canonicalPhone
import com.relay.sms.data.local.db.dao.ConversationDao
import com.relay.sms.data.sms.SimSelfNumber
import com.relay.sms.di.ApplicationScope
import com.relay.sms.domain.model.PhoneAddress
import com.relay.sms.domain.repository.BlockedNumberRepository
import com.relay.sms.domain.settings.AppSettingsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.relay.sms.data.local.db.entity.E2ePeerState as StoredPeerState

/**
 * Probe orchestration (design §2.3–§2.4): the "automatic" half of
 * "Relay to Relay auto-encrypts".
 *
 * A probe is a 70-byte data SMS carrying our identity keys. It goes out
 * on three triggers — opening a 1:1 thread ([onThreadOpened]) and sending
 * a 1:1 message ([probeForSend]) — plus, since round 58, opening a
 * GROUP thread ([onThreadOpened] group branch, [probeGroupMembers]):
 * group encryption rides on the pairwise sessions, so each member who
 * is not already ENCRYPTED gets one probe through the same per-address
 * path (same budget, toggle, blocked/invalid gates). The probe is for
 * a peer in state UNKNOWN, PROBING, or NOT_RELAY, gated by the probe
 * budget (at most 3 attempts per 30 days, at least 24 hours apart,
 * owned by [E2ePeerRepository]) and by eligibility ([E2eProbePolicy]:
 * real phone number, not blocked).
 *
 * On the state gate: the design's trigger text names UNKNOWN /
 * NOT_RELAY, but the budget only makes sense if a PROBING peer with
 * attempts left is re-probed (the repository always records a probe as
 * PROBING, so a strict UNKNOWN/NOT_RELAY gate would make attempts 2–3
 * unreachable and the 3-attempt budget dead code). The state machine
 * ([E2ePeerStateMachine.canProbe]) is the authority here: every
 * non-ENCRYPTED state is probeable within the budget, and the 3rd
 * attempt flips the peer to NOT_RELAY until the window elapses —
 * for automatic probes. A manual retry (round 44) leaves the
 * peer PROBING even at 3 attempts: the probe is in flight, so
 * "Checking if…" is the honest state, and the panel must not
 * flop back to "Tap to retry" the moment the user taps Retry.
 *
 * The decrypt-failure re-probe ([reprobeAfterDecryptFailure]) is the
 * one path that probes an ENCRYPTED peer: an undecryptable message from
 * a known contact usually means they reinstalled, and the re-probe
 * re-establishes the session (design §3.5, §4.4). It respects the same
 * budget — the state exclusion is the only thing lifted.
 *
 * Round 40 — the discovery toggle (Encryption settings, default ON):
 * when OFF, this phone stops SENDING probes ([gateFor] refuses);
 * inbound handshakes stay fully active (answering probes, receiving
 * bundles, decrypting), so encryption still starts when the other
 * side initiates. The gate lives in [gateFor], so it covers all
 * three triggers, including the decrypt-failure re-probe.
 *
 * Round 41 — the manual retry (Details panel encryption-row tap):
 * an explicit user tap sends ONE probe now, bypassing the budget
 * and the discovery toggle (the tap is consent for this one SMS).
 * The other gates still apply (real number, not blocked, not
 * already ENCRYPTED), and a failed send still records
 * `e2e-probe-send` — the manual path goes through the same sender.
 *
 * Round 42 — the manual retry records its failures: a tap that did
 * nothing used to leave no trace anywhere (the Boolean was
 * discarded, the exception Timber-only, and Timber is a no-op in
 * release). [retryProbe] now runs the detailed core
 * ([probeConversationDetailed]) and records every non-[Sent]
 * outcome in the error log under `e2e-manual-retry`, gated or
 * failed, with the reason — fixed messages, no addresses. Success
 * records nothing (the state flip to PROBING is the signal).
 * Automatic probes record too (round 57): every automatic gate
 * refusal under `e2e-probe-gated` (with the gate reason, the
 * trigger, and the address) and every automatic send under
 * `e2e-probe-sent` (with the attempt count, the channel, the
 * trigger, and the address) — see [recordAutomaticResult]. A
 * failed automatic send records nothing here: the sender already
 * recorded the radio refusal under the kind's failure tag.
 *
 * Round 42 — the peer row is keyed by the NORMALIZED address (see
 * [E2ePeerRepository]): the probe used to write under the raw
 * conversation form while the thread UI observed the normalized
 * form, so a peer whose number was stored with formatting never
 * showed its state change in the panel.
 *
 * Round 45 — the second manual retry ([retryProbeViaSms], Details
 * panel "Send via SMS"): the same one-probe-now shape as
 * [retryProbe], but the probe travels as a single `RLYH:` text
 * instead of a data SMS — for peers whose data SMS never arrive.
 * The two retries are separate buttons, one transport each; the
 * detailed cores take a [HandshakeChannel] and share everything
 * else (gates, budget, the round-44 PROBING rule, the
 * `e2e-manual-retry` recording). Automatic probing stays
 * data-SMS-only.
 *
 * Round 47 — a manual probe carries the manual flag (bit 7 of the
 * type byte, [E2eHandshake]): the RECEIVER bypasses its
 * one-bundle-per-sender-per-day rate limit for it. The sender-side
 * budget bypass alone did not help — the receiver's limit still
 * blocked the bundle reply, so a retry failed unless both phones
 * reset timing first. Both retry buttons mark their probes
 * ([manual] flows through the shared detailed core, so the data
 * and text retries and the desktop retry command all mark theirs);
 * automatic probes are never marked manual.
 *
 * Round 48 — the same funnel carries the media-capability
 * declaration for ALREADY-ENCRYPTED peers: a probe to one is
 * refused ([ProbeGate.AlreadyEncrypted]), but the trigger is
 * still a contact point, so the detailed core sends our
 * declaration instead when we have not announced to them yet
 * ([E2eMediaCapabilityManager.announceIfNeeded]). That is what
 * covers sessions established before round 48 — no establishment
 * event ever fires for them again, so without this hook they
 * would never learn we can do encrypted media. Manual retries
 * never announce (the tap is about the key exchange, and the
 * panel only offers it for non-ENCRYPTED peers).
 *
 * Desktop encryption-box update — the manual retry is now
 * result-aware on both surfaces: [retryProbeDetailed] runs the
 * detailed core and returns the outcome (the fire-and-forget
 * buttons and the desktop `retry_e2e_probe` command share it),
 * and [ProbeResult.Sent] carries whether the send consumed the
 * last probe-budget slot ([ProbeResult.Sent.budgetExhausted]).
 * A manual retry BYPASSES the budget ([gateFor] returns before
 * the budget check), so a manual retry can never be REFUSED for
 * budget reasons — but it still CONSUMES budget, and the send
 * that spends the last slot is the moment the user should hear
 * about the reset (Settings → Advanced → "Reset encryption
 * timing"): automatic checks for that contact are paused until
 * the window elapses.
 *
 * All entry points are fire-and-forget (non-suspend, own scope): a
 * probe must never delay opening a thread or sending a message. The
 * suspend cores ([probeConversation], [probeAddress],
 * [probeGroupMembers]) are the testable surface. Probes serialize on
 * one mutex: a thread-open racing a send must not spend two attempts
 * on the same peer.
 *
 * The account manager is taken lazily: resolving it provisions
 * [E2eCrypto] (the first native call loads the crypto library, and
 * the Account load that follows does Keystore + database I/O), and
 * this manager is injected into the thread ViewModel — it must be
 * cheap to construct on the main thread. Resolution happens inside the
 * background probe coroutine.
 */

/**
 * The outcome of one probe attempt (round 42): the manual path
 * needs the REASON a probe did not go out, not just a Boolean, so
 * a tap that does nothing leaves a trace in the error log.
 */
sealed interface ProbeResult {
    /**
     * The probe went out. [budgetExhausted] is true when this
     * send consumed the last probe-budget slot (attempts reached
     * [E2ePeerRepository.MAX_PROBE_ATTEMPTS] — automatic checks
     * for the contact are paused until the window elapses). A
     * manual retry can never be REFUSED for budget reasons
     * (the gate lifts the budget for manual retries), but it
     * still consumes budget, so the flag is what tells the
     * user the reset exists.
     */
    data class Sent(val budgetExhausted: Boolean = false) : ProbeResult

    /** No probe went out: the attempt was refused before sending. */
    data class Gated(val reason: ProbeGate) : ProbeResult

    /** The send was attempted but the radio refused it. */
    data object SendFailed : ProbeResult
}

/**
 * Why a probe attempt was refused before sending. [label] is the
 * fixed, PII-free wording recorded in the error log.
 */
enum class ProbeGate(val label: String) {
    /** No conversation exists for the id. */
    NoConversation("no conversation"),

    /** The conversation is not 1:1 (group, or no address). */
    NotOneToOne("not a 1:1 conversation"),

    /** The address is not a phone number (short code, service, empty). */
    InvalidAddress("not a phone number"),

    /** The number is blocked. */
    Blocked("number is blocked"),

    /** The peer is already ENCRYPTED (and the re-probe did not lift it). */
    AlreadyEncrypted("already encrypted"),

    /** The probe budget is exhausted (automatic probes only — a manual retry lifts it). */
    BudgetExhausted("probe budget exhausted"),

    /**
     * Round 59 — the last probe was less than 24 hours ago
     * (automatic probes only — a manual retry lifts it). Split
     * out of [BudgetExhausted]: the two refusals used to share
     * one reason, so the error log said "probe budget exhausted"
     * when the budget was not spent.
     */
    ProbeTooSoon("probe sent too recently"),

    /** The discovery toggle is off (automatic probes only — a manual retry lifts it). */
    DiscoveryOff("discovery is off"),
}

/**
 * What triggered an automatic probe. [label] is the fixed,
 * PII-free wording recorded in the error log alongside the
 * outcome (see [E2eProbeManager.recordAutomaticResult]).
 * Manual retries carry no trigger: they record under
 * `e2e-manual-retry` instead, with no automatic event.
 */
enum class ProbeTrigger(val label: String) {
    /** Opening a 1:1 thread ([E2eProbeManager.onThreadOpened]). */
    THREAD_OPEN("thread-open"),

    /** Opening a group thread ([E2eProbeManager.probeGroupMembers]). */
    GROUP_THREAD_OPEN("group-thread-open"),

    /** Sending a 1:1 message ([E2eProbeManager.probeForSend]). */
    SEND("send"),

    /** An undecryptable message ([E2eProbeManager.reprobeAfterDecryptFailure]). */
    DECRYPT_FAILURE("decrypt-failure re-probe"),

    /** A plain SMS from an ENCRYPTED peer ([E2eProbeManager.reprobeAfterPeerLeft]). */
    PEER_LEFT("peer-left re-probe"),
}

@Singleton
class E2eProbeManager @Inject constructor(
    private val conversationDao: ConversationDao,
    private val blockedRepo: BlockedNumberRepository,
    private val peers: E2ePeerRepository,
    private val accountManager: dagger.Lazy<E2eAccountManager>,
    private val dataSmsSender: E2eDataSmsSender,
    private val settings: AppSettingsSource,
    @ApplicationScope private val scope: CoroutineScope,
    private val recorder: E2eFailureRecorder,
    private val mediaCapability: E2eMediaCapabilityManager,
    // Round 58 — the gated automatic `RLYH:` fallback
    // ([E2eAutoFallback]): the thread-open evaluation backstop
    // (see [onThreadOpened]). Nullable with a null default so the
    // existing manager tests — which construct the manager by hand
    // — keep compiling untouched; Hilt always provides the real
    // fallback.
    private val autoFallback: E2eAutoFallback? = null,
    // Round 58 — group-thread probing ([probeGroupMembers]) needs
    // our own number to exclude it from the member list (settings
    // first, SIM fallback — the same order as
    // [E2eGroupSessionManager.ownNumber]). Nullable with a null
    // default, same reason as [autoFallback]; Hilt always
    // provides the real one.
    private val simSelfNumber: SimSelfNumber? = null,
) {
    private val probeMutex = Mutex()

    private companion object {
        /** Error-log tag for manual-retry failures (round 42). */
        const val MANUAL_RETRY_TAG = "e2e-manual-retry"

        /** Error-log tag for automatic gate refusals (round 57). */
        const val AUTO_GATED_TAG = "e2e-probe-gated"

        /** Error-log tag for automatic probe sends (round 57). */
        const val AUTO_SENT_TAG = "e2e-probe-sent"
    }

    /**
     * Thread-open trigger (design §2.4): opening a thread is intent
     * to talk, so the session is usually live by the time the user hits
     * send. Called by the thread ViewModel; non-suspend by contract.
     * The ViewModel gates it once per ViewModel and hub-only; this
     * function dispatches on the conversation: a 1:1 thread probes
     * its single peer ([probeConversation]), a group thread probes
     * each member who is not already ENCRYPTED ([probeGroupMembers])
     * — group encryption rides on the pairwise sessions, so a
     * member whose 1:1 handshake never started blocks the group,
     * and the group open is the moment to start it. Probing a
     * group never probes our own number (see [probeGroupMembers]).
     *
     * Round 58 — the same open also evaluates the automatic
     * fallback gate ([checkAutoFallback]): the backstop for a pair
     * already stalled when the app updates (no new bundle reply
     * will arm a worker for them) and for a worker that skipped
     * as "not stalled yet" (a data-SMS probe sent after it was
     * armed). The fallback check never logs a skip — it runs on
     * every open — only a send is recorded. For a group the
     * fallback is evaluated per member ([checkAutoFallbackForGroup]).
     */
    fun onThreadOpened(conversationId: Long) {
        scope.launch {
            val conversation =
                runCatching { conversationDao.findById(conversationId) }.getOrNull()
            val addresses = conversation?.let {
                PhoneAddress.list(it.addressesCsv).map { address -> address.raw }
            }.orEmpty()
            if (addresses.size > 1) {
                runCatching { probeGroupMembers(addresses) }
                    .onFailure { Timber.w(it, "E2E: group probe on thread open failed") }
                runCatching { checkAutoFallbackForGroup(addresses) }
                    .onFailure { Timber.w(it, "E2E: auto fallback check on group thread open failed") }
            } else {
                runCatching { probeConversation(conversationId, trigger = ProbeTrigger.THREAD_OPEN) }
                    .onFailure { Timber.w(it, "E2E: probe on thread open failed") }
                runCatching { checkAutoFallback(conversationId) }
                    .onFailure { Timber.w(it, "E2E: auto fallback check on thread open failed") }
            }
        }
    }

    /**
     * Round 58 — group-thread probing (design §2.4): probes every
     * member of [addresses] through the same per-address core as a
     * 1:1 probe ([probeAddressDetailed]), so every gate applies per
     * member exactly as it does for 1:1 probes — the probe budget,
     * the discovery toggle, blocked/invalid-address gates, and the
     * already-ENCRYPTED skip (which still runs the round-48
     * media-capability announcement for that member). One probe per
     * member: the list is deduped by canonical address, and our own
     * number is excluded (the thread's addresses should not hold
     * it — see [E2eGroupStateProvider] — but the exclusion is the
     * guard if it ever does). Members are probed in list order,
     * serially under the probe mutex; one member's gate or send
     * failure never stops the others.
     */
    suspend fun probeGroupMembers(
        addresses: List<String>,
        now: Long = System.currentTimeMillis(),
    ) {
        val own = ownNumber()
        val subId = runCatching { settings.hydratedOrNull()?.sending?.defaultSubId }
            .getOrNull()
        addresses
            .distinctBy { it.canonicalPhone() }
            .filter { own == null || it.canonicalPhone() != own }
            .forEach { address ->
                probeAddressDetailed(
                    address,
                    subId,
                    now = now,
                    trigger = ProbeTrigger.GROUP_THREAD_OPEN,
                )
            }
    }

    /**
     * Our own number (canonical), from settings first, then the
     * SIM — the same order as [E2eGroupSessionManager.ownNumber].
     * Null when neither knows it: the exclusion in
     * [probeGroupMembers] then skips nothing, and the thread's
     * addresses are the members by construction.
     */
    private suspend fun ownNumber(): String? {
        val fromSettings = settings.hydratedOrNull()?.sending?.userMsisdn
            ?.takeIf { it.isNotBlank() }?.canonicalPhone()
        if (fromSettings != null) return fromSettings
        return simSelfNumber?.ownNumber(null)?.takeIf { it.isNotBlank() }?.canonicalPhone()
    }

    /**
     * Round 58 — the automatic fallback backstop (design §2.4):
     * resolves the conversation to its single 1:1 address and
     * evaluates the fallback gate ([E2eAutoFallback.maybeSend]).
     * Groups and missing conversations are skipped silently (the
     * fallback is 1:1 only), and a null [autoFallback] — the
     * hand-constructed tests — skips the check entirely.
     */
    private suspend fun checkAutoFallback(conversationId: Long) {
        val fallback = autoFallback ?: return
        val conversation = conversationDao.findById(conversationId) ?: return
        val address = PhoneAddress.list(conversation.addressesCsv).map { it.raw }.singleOrNull()
            ?: return
        fallback.maybeSend(address)
    }

    /**
     * Round 58 — the group form of [checkAutoFallback]: evaluates
     * the fallback gate per member address. The fallback's evidence
     * and stall are per peer, so a stalled group-member handshake
     * is rescued exactly like a 1:1 one. Our own number, if
     * present, skips harmlessly (no peer row, no evidence — the
     * gate refuses it); a null [autoFallback] skips the check
     * entirely.
     */
    private suspend fun checkAutoFallbackForGroup(addresses: List<String>) {
        val fallback = autoFallback ?: return
        addresses.forEach { address -> fallback.maybeSend(address) }
    }

    /**
     * Send-path trigger (design §2.4): covers sends that never opened
     * the thread (notification reply, desktop, scheduled). Called by
     * the send hook when the peer is not ENCRYPTED; non-suspend.
     */
    fun probeForSend(address: String, subId: Int?) {
        scope.launch {
            runCatching { probeAddress(address, subId, trigger = ProbeTrigger.SEND) }
                .onFailure { Timber.w(it, "E2E: probe on send failed") }
        }
    }

    /**
     * Decrypt-failure re-probe (design §3.5): an undecryptable message
     * from a known contact usually means they reinstalled. Probes even
     * an ENCRYPTED peer; the budget still applies.
     */
    fun reprobeAfterDecryptFailure(address: String, subId: Int?) {
        scope.launch {
            runCatching {
                probeAddress(address, subId, allowEncrypted = true, trigger = ProbeTrigger.DECRYPT_FAILURE)
            }.onFailure { Timber.w(it, "E2E: re-probe after decrypt failure failed") }
        }
    }

    /**
     * Peer-left re-probe (round 41): a plain SMS text arrived from an
     * ENCRYPTED peer, so they lost Relay — the peer is already UNKNOWN
     * by the time this runs, and the re-probe resumes E2E if they
     * reinstall. Automatic recovery, not a manual retry: the budget
     * and the discovery toggle still apply (no `manual` bypass).
     */
    fun reprobeAfterPeerLeft(address: String, subId: Int?) {
        scope.launch {
            runCatching { probeAddress(address, subId, trigger = ProbeTrigger.PEER_LEFT) }
                .onFailure { Timber.w(it, "E2E: re-probe after peer left failed") }
        }
    }

    /**
     * Manual retry (round 41, Details panel encryption-row tap): the
     * user asked to retry the key exchange now, so ONE probe goes out
     * immediately, bypassing the budget and the discovery toggle. The
     * other gates still apply (real number, not blocked, not already
     * ENCRYPTED). Fire-and-forget, like the other triggers.
     *
     * Round 42 — failures are recorded in the error log (see
     * [recordManualResult]): a tap that does nothing used to leave
     * no trace anywhere.
     */
    fun retryProbe(conversationId: Long) {
        manualRetry(conversationId, HandshakeChannel.DATA_SMS)
    }

    /**
     * Manual retry over regular SMS (round 45, Details panel "Send
     * via SMS"): the same one-probe-now shape as [retryProbe], but
     * the probe travels as a single `RLYH:` text instead of a data
     * SMS — for peers whose data SMS never arrive. Same gates
     * (manual lifts the budget and the discovery toggle only), same
     * budget accounting, same `e2e-manual-retry` failure recording.
     * Fire-and-forget, like the other triggers.
     */
    fun retryProbeViaSms(conversationId: Long) {
        manualRetry(conversationId, HandshakeChannel.TEXT_SMS)
    }

    /**
     * Shared fire-and-forget core of the two manual retries: runs
     * the detailed retry ([retryProbeDetailed]) and lets it record
     * every non-[ProbeResult.Sent] outcome in the error log (round
     * 42). A thrown error is recorded too, like before.
     */
    private fun manualRetry(conversationId: Long, channel: HandshakeChannel) {
        scope.launch {
            runCatching { retryProbeDetailed(conversationId, channel) }
                .onFailure { error ->
                    recorder.record(MANUAL_RETRY_TAG, error)
                    Timber.w(error, "E2E: manual probe retry failed")
                }
        }
    }

    /**
     * Suspend manual retry (desktop encryption-box update): the
     * same one-probe-now core as [manualRetry] — detailed core on
     * the chosen [channel], `e2e-manual-retry` recording on every
     * non-[ProbeResult.Sent] outcome — but the outcome is
     * RETURNED, so the caller can tell the user what happened:
     * the thread ViewModel shows a snackbar, and the desktop
     * `retry_e2e_probe` command answers `action_result` with the
     * mapped code. The fire-and-forget buttons route through here
     * too, so the recording lives in exactly one place.
     */
    suspend fun retryProbeDetailed(
        conversationId: Long,
        channel: HandshakeChannel = HandshakeChannel.DATA_SMS,
    ): ProbeResult {
        val result = probeConversationDetailed(conversationId, manual = true, channel = channel)
        recordManualResult(result)
        return result
    }

    /**
     * Records a manual retry that did not send (round 42). Success
     * records nothing — the state flip to PROBING is the signal —
     * and automatic probes never reach this (they use
     * [recordAutomaticResult] instead). Fixed messages only, no
     * addresses; the recorder's per-process dedupe collapses
     * repeated failed taps into one entry, which is all the
     * diagnosis needs.
     */
    private fun recordManualResult(result: ProbeResult) {
        when (result) {
            is ProbeResult.Sent -> Unit
            is ProbeResult.Gated -> recorder.recordEvent(
                MANUAL_RETRY_TAG,
                "Manual retry refused: ${result.reason.label}.",
            )
            ProbeResult.SendFailed -> recorder.recordEvent(
                MANUAL_RETRY_TAG,
                "Manual retry failed: the probe could not be sent.",
            )
        }
    }

    /**
     * Records a NON-manual probe outcome (round 57): every
     * automatic gate refusal under `e2e-probe-gated` (with the
     * gate reason, the trigger, and the address) and every
     * automatic send under `e2e-probe-sent` (with the attempt
     * count, the channel, the trigger, and the address). A failed
     * send records nothing here — the sender already recorded the
     * radio refusal under the kind's failure tag. Events are
     * never deduped, so each attempt leaves its own entry.
     * [address] is null only where no single address exists (a
     * missing conversation, or one that is not 1:1) — those
     * refusals record without one. Callers pass the result only
     * when `manual` is false: manual outcomes record under
     * `e2e-manual-retry` instead ([recordManualResult]) and must
     * never double-record.
     */
    private fun recordAutomaticResult(
        result: ProbeResult,
        trigger: ProbeTrigger?,
        channel: HandshakeChannel? = null,
        attempts: Int = 0,
        address: String? = null,
    ) {
        when (result) {
            is ProbeResult.Sent -> {
                val detail = listOfNotNull(
                    "attempt=$attempts",
                    channel?.let { "channel=${it.name}" },
                    trigger?.let { "trigger=${it.label}" },
                    "budget exhausted".takeIf { result.budgetExhausted },
                ).joinToString(", ")
                val to = address?.let { " to $it" } ?: ""
                recorder.recordEvent(AUTO_SENT_TAG, "Automatic probe sent$to ($detail).")
            }
            is ProbeResult.Gated -> {
                val triggerText = trigger?.let { " (trigger=${it.label})" } ?: ""
                val forAddress = address?.let { " for $it" } ?: ""
                recorder.recordEvent(
                    AUTO_GATED_TAG,
                    "Automatic probe refused$forAddress: ${result.reason.label}$triggerText.",
                )
            }
            ProbeResult.SendFailed -> Unit
        }
    }

    /**
     * Probes the single address of a conversation, if probeable.
     * Returns `true` when a probe data SMS went out. [manual] lifts
     * the budget and discovery-toggle gates for an explicit user
     * retry (round 41).
     */
    suspend fun probeConversation(
        conversationId: Long,
        now: Long = System.currentTimeMillis(),
        manual: Boolean = false,
        trigger: ProbeTrigger? = null,
    ): Boolean =
        probeConversationDetailed(conversationId, now, manual, trigger = trigger) is ProbeResult.Sent

    /**
     * Detailed core of [probeConversation]: resolves the conversation
     * to its single address, then probes it. [ProbeResult.Gated] with
     * [ProbeGate.NoConversation] / [ProbeGate.NotOneToOne] when the
     * conversation is missing or not 1:1. [channel] picks the
     * transport (round 45): data SMS by default, regular SMS for the
     * "Send via SMS" manual retry. [trigger] names the automatic
     * entry point for the error log (round 57); manual callers
     * leave it null and record under `e2e-manual-retry` instead.
     */
    suspend fun probeConversationDetailed(
        conversationId: Long,
        now: Long = System.currentTimeMillis(),
        manual: Boolean = false,
        channel: HandshakeChannel = HandshakeChannel.DATA_SMS,
        trigger: ProbeTrigger? = null,
    ): ProbeResult {
        val conversation = conversationDao.findById(conversationId)
        if (conversation == null) {
            val result = ProbeResult.Gated(ProbeGate.NoConversation)
            if (!manual) recordAutomaticResult(result, trigger)
            return result
        }
        val addresses = PhoneAddress.list(conversation.addressesCsv).map { it.raw }
        if (addresses.size != 1) {
            val result = ProbeResult.Gated(ProbeGate.NotOneToOne)
            if (!manual) recordAutomaticResult(result, trigger)
            return result
        }
        val address = addresses.first()
        // The probe leaves on the same subscription the thread's sends
        // use (design §11.1): the default SMS subscription from
        // settings, `null` (system default) when unreadable.
        val subId = runCatching { settings.hydratedOrNull()?.sending?.defaultSubId }
            .getOrNull()
        return probeAddressDetailed(
            address,
            subId,
            now = now,
            manual = manual,
            channel = channel,
            trigger = trigger,
        )
    }

    /**
     * Probes one address, if probeable. Returns `true` when a probe data
     * SMS went out. [allowEncrypted] lifts the ENCRYPTED exclusion for
     * the decrypt-failure re-probe only. [manual] lifts the budget and
     * discovery-toggle gates for an explicit user retry (round 41);
     * the address, blocked, and ENCRYPTED gates still apply.
     */
    suspend fun probeAddress(
        address: String,
        subId: Int?,
        allowEncrypted: Boolean = false,
        manual: Boolean = false,
        now: Long = System.currentTimeMillis(),
        trigger: ProbeTrigger? = null,
    ): Boolean =
        probeAddressDetailed(address, subId, allowEncrypted, manual, now, trigger = trigger) is ProbeResult.Sent

    /**
     * Detailed core of [probeAddress]: the same probe, with the
     * outcome instead of a Boolean. The manual path ([retryProbe],
     * [retryProbeViaSms]) records the non-[ProbeResult.Sent]
     * outcomes in the error log; the automatic triggers record
     * their gated and sent outcomes too (round 57 — see
     * [recordAutomaticResult]), with the [trigger] naming the
     * entry point. [channel] picks the transport (round 45):
     * data SMS by default, regular SMS for the "Send via SMS"
     * manual retry — the gates, the budget accounting, and the
     * round-44 PROBING rule are identical on both channels.
     */
    suspend fun probeAddressDetailed(
        address: String,
        subId: Int?,
        allowEncrypted: Boolean = false,
        manual: Boolean = false,
        now: Long = System.currentTimeMillis(),
        channel: HandshakeChannel = HandshakeChannel.DATA_SMS,
        trigger: ProbeTrigger? = null,
    ): ProbeResult = probeMutex.withLock {
        val state = peers.state(address)
        val gate = gateFor(address, state, allowEncrypted, manual, now)
        if (gate != null) {
            // Round 48 — an ENCRYPTED peer refuses the probe, but
            // the trigger is still a contact point: send our
            // media-capability declaration when we have not
            // announced to them yet (one-shot per peer — the
            // announced flag makes a repeat trigger a no-op).
            // Best-effort and never throws; the probe result is
            // unchanged. Manual retries never announce (see the
            // class KDoc).
            if (gate == ProbeGate.AlreadyEncrypted && !manual) {
                mediaCapability.announceIfNeeded(address, subId, now)
            }
            val result = ProbeResult.Gated(gate)
            if (!manual) recordAutomaticResult(result, trigger, address = address)
            return@withLock result
        }
        // Lazy account creation: the first probe sent (or received) is
        // the first E2E use, and the identity is created here — users
        // who never use E2E pay nothing (design §4.1).
        val keys = accountManager.get().ensureAccount()
        // Round 47 — a manual probe carries the manual flag, so the
        // receiver bypasses its bundle rate limit for it. Automatic
        // probes are never marked manual (manual = false here).
        val probe = E2eHandshake.encodeProbe(keys.curve25519, keys.ed25519, manual = manual)
        val sent = when (channel) {
            HandshakeChannel.DATA_SMS -> dataSmsSender.sendDataMessage(
                address = address,
                subId = subId,
                port = E2eHandshake.E2E_DATA_SMS_PORT,
                data = probe,
            )
            HandshakeChannel.TEXT_SMS -> dataSmsSender.sendTextMessage(
                address = address,
                subId = subId,
                text = E2eHandshakeSms.encode(probe),
            )
        }
        // A failed send must not consume budget: record only after the
        // radio accepted the probe. No probe-manager event here:
        // the sender already recorded the radio refusal under the
        // kind's failure tag.
        if (!sent) return@withLock ProbeResult.SendFailed
        peers.recordProbeSent(address, now)
        // The 3rd unanswered attempt exhausts the budget: the peer
        // becomes NOT_RELAY until the window elapses (state machine
        // `onProbeSent`). Re-read: the repository owns the counting.
        // Round 44 — automatic probes only: a manual retry leaves
        // the peer PROBING, so the panel shows "Checking if…" while
        // the probe the user just asked for is in flight (flipping
        // to NOT_RELAY here made the Retry tap feel broken — the box
        // flashed PROBING then flopped back). The manual retry still
        // consumes budget (recordProbeSent bumped the attempts
        // above); only the immediate state flip is skipped, and the
        // NOT_RELAY conclusion still applies to automatic probes.
        val attempts = peers.getPeer(address)?.probeAttempts ?: 0
        // Desktop encryption-box update — the send that spends
        // the last budget slot: automatic checks for this contact
        // are paused until the window elapses, and the caller
        // (phone snackbar, desktop dialog) tells the user about
        // the reset. The flag rides on the result for manual AND
        // automatic sends; automatic callers ignore it.
        val budgetExhausted = attempts >= E2ePeerRepository.MAX_PROBE_ATTEMPTS
        if (!manual && budgetExhausted) {
            peers.setState(address, StoredPeerState.NOT_RELAY, now)
        }
        Timber.i("E2E: probe sent (state=%s, attempts=%d)", state, attempts)
        val result = ProbeResult.Sent(budgetExhausted = budgetExhausted)
        if (!manual) recordAutomaticResult(result, trigger, channel, attempts, address = address)
        result
    }

    /**
     * Eligibility + state + budget, in one place (see the class KDoc):
     * the gate refusing this probe, or null when it may go out.
     * Round 41 — a manual retry is explicit consent for this one
     * probe: the budget and the discovery toggle do not apply.
     * Round 40 — the discovery toggle (Encryption settings, default
     * ON): OFF stops outbound probes. An unreadable store fails
     * OPEN: the default is ON, and a cold process must not silently
     * stop discovering.
     */
    private suspend fun gateFor(
        address: String,
        state: StoredPeerState,
        allowEncrypted: Boolean,
        manual: Boolean,
        now: Long,
    ): ProbeGate? {
        // The settings read happens only after the address, blocked
        // and ENCRYPTED gates pass (and never for a manual retry):
        // a settings read must not precede a cheap refusal.
        return when {
            !E2eProbePolicy.isProbeableAddress(address) -> ProbeGate.InvalidAddress
            blockedRepo.isBlocked(address) -> ProbeGate.Blocked
            state == StoredPeerState.ENCRYPTED && !allowEncrypted ->
                ProbeGate.AlreadyEncrypted
            manual -> null
            !(settings.hydratedOrNull()?.encryption?.autoDiscoverRelayContacts ?: true) ->
                ProbeGate.DiscoveryOff
            else -> peers.canProbeReason(address, now)
        }
    }
}
