/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens.thread

import com.google.common.truth.Truth.assertThat
import com.relay.sms.R
import com.relay.sms.e2e.ProbeGate
import com.relay.sms.e2e.ProbeResult
import org.junit.jupiter.api.Test

/**
 * Desktop encryption-box update — the snackbar a manual probe
 * retry shows in the thread. The ViewModel itself has no test
 * harness (its E2E entry point throws on plain JVM), so the
 * mapping lives in a pure function ([e2eRetryNotice]) with its
 * own tests here.
 */
class E2eRetryNoticeTest {

    @Test fun `a plain send shows no notice`() {
        assertThat(e2eRetryNotice(ProbeResult.Sent())).isNull()
    }

    @Test fun `a send that spent the last budget slot shows the reset notice`() {
        val notice = e2eRetryNotice(ProbeResult.Sent(budgetExhausted = true))

        assertThat(notice).isNotNull()
        assertThat(notice!!.messageRes).isEqualTo(R.string.e2e_retry_budget_exhausted)
        assertThat(notice.isError).isFalse()
    }

    @Test fun `a failed send shows the send-failed notice`() {
        val notice = e2eRetryNotice(ProbeResult.SendFailed)

        assertThat(notice).isNotNull()
        assertThat(notice!!.messageRes).isEqualTo(R.string.e2e_retry_send_failed)
        assertThat(notice.isError).isTrue()
    }

    @Test fun `each refusal maps to its own notice`() {
        val cases = mapOf(
            ProbeGate.NoConversation to R.string.e2e_retry_no_conversation,
            ProbeGate.NotOneToOne to R.string.e2e_retry_not_one_to_one,
            ProbeGate.InvalidAddress to R.string.e2e_retry_invalid_address,
            ProbeGate.Blocked to R.string.e2e_retry_blocked,
            ProbeGate.AlreadyEncrypted to R.string.e2e_retry_already_encrypted,
            ProbeGate.BudgetExhausted to R.string.e2e_retry_rate_limited,
            ProbeGate.ProbeTooSoon to R.string.e2e_retry_rate_limited,
            ProbeGate.DiscoveryOff to R.string.e2e_retry_discovery_off,
        )
        for ((gate, res) in cases) {
            val notice = e2eRetryNotice(ProbeResult.Gated(gate))
            assertThat(notice).isNotNull()
            assertThat(notice!!.messageRes).isEqualTo(res)
            assertThat(notice.isError).isTrue()
        }
    }
}
