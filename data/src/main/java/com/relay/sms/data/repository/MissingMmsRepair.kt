/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.repository

import com.relay.sms.data.local.db.dao.MessageDao
import com.relay.sms.data.sms.TelephonyReader
import com.relay.sms.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 60 — one-shot repair for MMS the import dropped because
 * they had no parts yet when it ran.
 *
 * The mechanism (device-proven by the symptom, code-verified):
 * an MMS that has not finished downloading has ZERO rows in
 * `content://mms/part`, so `TelephonyReader` drops it (no text,
 * no attachments — nothing to show) and, before round 60, the
 * import still called itself complete and set
 * `mmsImportCompleted`. No live receiver watches the system
 * store for a download finishing, so the MMS stayed missing
 * until a reinstall re-ran the whole import — which is exactly
 * what Kristin saw with voice messages (missing once, present
 * on a later reinstall; even QUIK misses them sometimes, same
 * system-store timing). The round-60 reader fix withholds
 * completion while a RECENT part-less MMS exists, so future
 * misses retry on the next sync — but installs that already
 * imported need their already-missed MMS back without a
 * reinstall or a clear-data. That is this pass.
 *
 * How it works: one provider walk ([TelephonyReader.readMmsBatched],
 * paged, same as the import) filtered against the URIs Room
 * already mirrors ([MessageDao.listMirroredTelephonyUris]), with
 * only the missing rows fed to the importer's
 * [ConversationMirror.bulkImportMmsFromTelephony] — the same
 * insert path as the import, so filing, sticky categories and
 * tapback folding behave identically. The filter is a cheap
 * optimisation, not a correctness gate: the import is
 * idempotent (`telephony_uri` UNIQUE + IGNORE), so a row that
 * slipped through the filter is skipped, never duplicated.
 *
 * Safety rules (each is load-bearing):
 *   - the pass only runs once the import has run (the caller —
 *     [com.relay.sms.system.startup.StartupMigrations] — gates
 *     on `mmsImportCompleted`): on a fresh install the database
 *     is empty, the pass would "complete" having imported
 *     nothing, and the flag would then never re-run it. The
 *     AGENTS.md lesson from the group repair applies verbatim.
 *   - [Result.complete] is `false` when any provider read failed
 *     (or the Room read failed): the caller only sets its
 *     once-flag on a complete pass, so a failed read retries at
 *     the next cold start instead of leaving MMS missing forever.
 *   - provider reads run outside any Room transaction (the
 *     mirror does its own writes per page, like the import).
 *   - a part-less MMS is still dropped by the reader walk, so
 *     this pass recovers an MMS only once it HAS parts. While a
 *     recent one is still downloading, the walk reports
 *     incomplete and the flag stays unset — the pass re-runs at
 *     the next cold start and picks it up then.
 */
@Singleton
class MissingMmsRepair @Inject constructor(
    private val telephonyReader: TelephonyReader,
    private val messageDao: MessageDao,
    private val conversationMirror: ConversationMirror,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Outcome of one [repair] run. */
    data class Result(
        /** MMS rows present in the provider but missing from Room, now imported. */
        val repaired: Int,
        /** `false` when a provider or Room read failed — the pass must re-run. */
        val complete: Boolean,
    )

    suspend fun repair(): Result = withContext(io) {
        val mirrored = runCatching { messageDao.listMirroredTelephonyUris() }.getOrNull()
            ?: return@withContext Result(repaired = 0, complete = false)
        val mirroredSet = mirrored.toHashSet()
        var repaired = 0
        val complete = telephonyReader.readMmsBatched(pageSize = 200) { page ->
            val missing = page.filter { row ->
                "content://mms/${row.telephonyId}" !in mirroredSet
            }
            if (missing.isNotEmpty()) {
                conversationMirror.bulkImportMmsFromTelephony(missing)
                repaired += missing.size
            }
        }
        if (repaired > 0) {
            Timber.i("missing MMS repair: imported %d message(s) (complete=%b)", repaired, complete)
        }
        Result(repaired = repaired, complete = complete)
    }
}
