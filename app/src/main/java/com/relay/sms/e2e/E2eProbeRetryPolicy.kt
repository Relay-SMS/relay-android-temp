/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import com.relay.sms.data.local.db.entity.E2ePeerEntity
import com.relay.sms.data.local.db.entity.E2ePeerState

/**
 * Round 60 — the automatic re-probe schedule for unanswered
 * handshakes (design §2.4).
 *
 * The problem (device-proven 2026-11-26): a handshake completes in
 * ~13 seconds once the first probe in either direction gets through,
 * but the hours were spent waiting for a first delivery — the carrier
 * drops data SMS after RESULT_OK. Once stalled, nothing retried
 * within 24 hours: the 24-hour probe spacing blocked re-probing, the
 * automatic fallback could not fire without evidence, and the 10-second
 * worker was armed only on probe receipt or bundle failure — never when
 * WE sent a probe.
 *
 * The fix: re-probe an unanswered handshake on a short schedule — every
 * few minutes for the first hour, then back off to the existing 24-hour
 * cadence. A data-SMS probe is invisible to a non-Relay phone, so no
 * evidence is needed for the re-probe itself (unlike the automatic
 * `RLYH:` fallback, whose evidence gate stays exactly as it is —
 * [E2eAutoFallback.evaluate]).
 *
 * The retries have their OWN allowance, separate from the 30-day probe
 * budget: at most [MAX_RETRIES] re-probes inside the first hour after
 * a probe, at the offsets in [RETRY_DELAYS_MS]. A retry never bumps
 * [E2ePeerRepository.MAX_PROBE_ATTEMPTS], never resets the 30-day
 * window, and never flips the peer to NOT_RELAY — the budget's
 * protection for genuinely unanswered non-Relay contacts is intact:
 * after the first-hour window the existing spacing and budget apply
 * unchanged, and a peer whose budget is spent (NOT_RELAY) gets no
 * retries at all. Worst case cost of one probe round is 1 + 4
 * segments in the first hour, then silence until the next 24-hour
 * attempt.
 *
 * The window is anchored per probe round: every probe recorded by
 * [E2ePeerRepository.recordProbeSent] (automatic or manual) opens a
 * fresh one-hour retry window ([E2ePeerEntity.retryWindowStartedAt])
 * with zero retries spent. A retry records itself in
 * [E2ePeerEntity.retryAttempts] (and moves `lastProbeAt`, so the
 * fallback's stall anchor and the 24-hour spacing both read the latest
 * handshake activity).
 *
 * Pure Kotlin, no Android APIs — fully unit-testable on the plain JVM.
 * The schedule itself (WorkManager one-time worker, armed on probe
 * send) lives in [E2eProbeRetryWorker]; the send goes through
 * [E2eProbeManager.probeAddressDetailed], which is also the
 * thread-open / send backstop: any trigger that finds the normal gate
 * refusing for spacing or budget, with a retry due, sends the retry
 * instead of gating.
 */
object E2eProbeRetryPolicy {

    /** The retry window: one hour after a probe (design §2.4). */
    const val RETRY_WINDOW_MS: Long = 3_600_000L

    /** Re-probes allowed inside one retry window. */
    const val MAX_RETRIES: Int = 4

    /**
     * When each re-probe is due, as an offset from the retry window
     * start: 5, 15, 30, then 50 minutes — a few minutes apart at
     * first, backing off inside the hour. The last retry is due ten
     * minutes before the window closes, so a worker deferred by Doze
     * still has room to fire it.
     */
    val RETRY_DELAYS_MS: LongArray = longArrayOf(
        5L * 60_000L,
        15L * 60_000L,
        30L * 60_000L,
        50L * 60_000L,
    )

    /**
     * Whether a re-probe may go out to this peer right now: the peer
     * must be PROBING (an unanswered handshake — never ENCRYPTED,
     * never NOT_RELAY, whose budget is spent), inside its retry
     * window, with retries left, and at or past the next retry's due
     * time. A peer with no retry window (never probed, or reset by
     * [E2ePeerRepository.resetEncryptionTiming]) never retries.
     */
    fun canRetry(peer: E2ePeerEntity, now: Long): Boolean {
        if (peer.state != E2ePeerState.PROBING) return false
        val due = nextDueAt(peer) ?: return false
        val windowStartedAt = peer.retryWindowStartedAt ?: return false
        return now - windowStartedAt < RETRY_WINDOW_MS && now >= due
    }

    /**
     * How long until the next re-probe is due, or null when no retry
     * will ever be due for this peer (not PROBING, no window, retries
     * spent, or the window already elapsed). Zero when the retry is
     * already due — a worker deferred by Doze arms with no delay and
     * fires as soon as it runs.
     */
    fun delayUntilNextRetry(peer: E2ePeerEntity, now: Long): Long? {
        val windowStartedAt = peer.retryWindowStartedAt
        val due = nextDueAt(peer)
        return when {
            peer.state != E2ePeerState.PROBING -> null
            windowStartedAt == null || due == null -> null
            now - windowStartedAt >= RETRY_WINDOW_MS -> null
            else -> maxOf(0L, due - now)
        }
    }

    /**
     * When the next re-probe is due (window start + the delay for the
     * retry count spent so far), or null when the retries are spent
     * or no window was ever opened.
     */
    fun nextDueAt(peer: E2ePeerEntity): Long? {
        val windowStartedAt = peer.retryWindowStartedAt ?: return null
        if (peer.retryAttempts >= MAX_RETRIES) return null
        return windowStartedAt + RETRY_DELAYS_MS[peer.retryAttempts]
    }
}
