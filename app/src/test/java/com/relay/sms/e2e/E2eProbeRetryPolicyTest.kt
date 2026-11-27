/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import com.google.common.truth.Truth.assertThat
import com.relay.sms.data.local.db.entity.E2ePeerEntity
import com.relay.sms.data.local.db.entity.E2ePeerState
import org.junit.jupiter.api.Test

/**
 * Round 60 — the automatic re-probe schedule (design §2.4):
 * [E2eProbeRetryPolicy], pure.
 *
 * The schedule's promises: a retry is allowed every few minutes
 * inside the first hour after a probe, only while the handshake is
 * still unanswered (PROBING), at most four times — and never after
 * the hour, never for an ENCRYPTED or NOT_RELAY peer, never without
 * a window. The 30-day probe budget is not this file's business,
 * but the policy is what keeps the retries off it: the count it
 * reads is the retry count, never the budget count.
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose body returns a value.
 */
class E2eProbeRetryPolicyTest {

    private companion object {
        const val ALICE = "+33612345678"
        const val NOW = 1_800_000_000_000L
        const val MINUTE_MS = 60_000L
    }

    private fun peer(
        state: E2ePeerState = E2ePeerState.PROBING,
        retryAttempts: Int = 0,
        retryWindowStartedAt: Long? = NOW - 6L * MINUTE_MS,
        probeAttempts: Int = 1,
    ) = E2ePeerEntity(
        address = ALICE,
        state = state,
        probeAttempts = probeAttempts,
        retryAttempts = retryAttempts,
        retryWindowStartedAt = retryWindowStartedAt,
        updatedAt = NOW,
    )

    // ──────────────────────────── canRetry ────────────────────────────

    @Test
    fun `first retry is due five minutes after the probe`() {
        assertThat(E2eProbeRetryPolicy.canRetry(peer(), NOW)).isTrue()
    }

    @Test
    fun `no retry before the first due time`() {
        val fourMinutesIn = peer(retryWindowStartedAt = NOW - 4L * MINUTE_MS)

        assertThat(E2eProbeRetryPolicy.canRetry(fourMinutesIn, NOW)).isFalse()
    }

    @Test
    fun `second retry is due fifteen minutes after the probe`() {
        val fourteenMinutesIn = peer(
            retryAttempts = 1,
            retryWindowStartedAt = NOW - 14L * MINUTE_MS,
        )
        assertThat(E2eProbeRetryPolicy.canRetry(fourteenMinutesIn, NOW)).isFalse()

        val fifteenMinutesIn = peer(
            retryAttempts = 1,
            retryWindowStartedAt = NOW - 15L * MINUTE_MS,
        )
        assertThat(E2eProbeRetryPolicy.canRetry(fifteenMinutesIn, NOW)).isTrue()
    }

    @Test
    fun `third and fourth retries follow the 30 and 50 minute marks`() {
        assertThat(
            E2eProbeRetryPolicy.canRetry(
                peer(retryAttempts = 2, retryWindowStartedAt = NOW - 30L * MINUTE_MS),
                NOW,
            ),
        ).isTrue()
        assertThat(
            E2eProbeRetryPolicy.canRetry(
                peer(retryAttempts = 3, retryWindowStartedAt = NOW - 50L * MINUTE_MS),
                NOW,
            ),
        ).isTrue()
        assertThat(
            E2eProbeRetryPolicy.canRetry(
                peer(retryAttempts = 3, retryWindowStartedAt = NOW - 49L * MINUTE_MS),
                NOW,
            ),
        ).isFalse()
    }

    @Test
    fun `no retry once the four retries are spent`() {
        val spent = peer(
            retryAttempts = E2eProbeRetryPolicy.MAX_RETRIES,
            retryWindowStartedAt = NOW - 55L * MINUTE_MS,
        )

        assertThat(E2eProbeRetryPolicy.canRetry(spent, NOW)).isFalse()
    }

    @Test
    fun `no retry after the first hour`() {
        val hourOld = peer(retryWindowStartedAt = NOW - 61L * MINUTE_MS)

        assertThat(E2eProbeRetryPolicy.canRetry(hourOld, NOW)).isFalse()
    }

    @Test
    fun `no retry without a window`() {
        assertThat(E2eProbeRetryPolicy.canRetry(peer(retryWindowStartedAt = null), NOW)).isFalse()
    }

    @Test
    fun `no retry once encrypted`() {
        assertThat(
            E2eProbeRetryPolicy.canRetry(peer(state = E2ePeerState.ENCRYPTED), NOW),
        ).isFalse()
    }

    @Test
    fun `no retry once not relay`() {
        assertThat(
            E2eProbeRetryPolicy.canRetry(peer(state = E2ePeerState.NOT_RELAY), NOW),
        ).isFalse()
    }

    @Test
    fun `no retry while unknown`() {
        assertThat(
            E2eProbeRetryPolicy.canRetry(peer(state = E2ePeerState.UNKNOWN), NOW),
        ).isFalse()
    }

    // ──────────────────────────── delayUntilNextRetry ────────────────────────────

    @Test
    fun `delay is zero once the first retry is due`() {
        val due = peer(retryWindowStartedAt = NOW - 5L * MINUTE_MS)

        assertThat(E2eProbeRetryPolicy.delayUntilNextRetry(due, NOW)).isEqualTo(0L)
    }

    @Test
    fun `delay counts down to the next due time`() {
        val fresh = peer(retryWindowStartedAt = NOW)

        assertThat(E2eProbeRetryPolicy.delayUntilNextRetry(fresh, NOW))
            .isEqualTo(5L * MINUTE_MS)
    }

    @Test
    fun `delay is zero when the retry is overdue but the window is open`() {
        val overdue = peer(retryWindowStartedAt = NOW - 20L * MINUTE_MS)

        assertThat(E2eProbeRetryPolicy.delayUntilNextRetry(overdue, NOW)).isEqualTo(0L)
    }

    @Test
    fun `no delay once the retries are spent`() {
        val spent = peer(retryAttempts = E2eProbeRetryPolicy.MAX_RETRIES)

        assertThat(E2eProbeRetryPolicy.delayUntilNextRetry(spent, NOW)).isNull()
    }

    @Test
    fun `no delay after the window elapsed`() {
        val hourOld = peer(retryWindowStartedAt = NOW - 61L * MINUTE_MS)

        assertThat(E2eProbeRetryPolicy.delayUntilNextRetry(hourOld, NOW)).isNull()
    }

    @Test
    fun `no delay without a window`() {
        assertThat(
            E2eProbeRetryPolicy.delayUntilNextRetry(peer(retryWindowStartedAt = null), NOW),
        ).isNull()
    }

    // ──────────────────────────── The schedule itself ────────────────────────────

    @Test
    fun `four retries fit inside the first hour`() {
        val delays = E2eProbeRetryPolicy.RETRY_DELAYS_MS

        assertThat(delays).hasLength(E2eProbeRetryPolicy.MAX_RETRIES)
        assertThat(delays.last()).isLessThan(E2eProbeRetryPolicy.RETRY_WINDOW_MS)
    }
}
