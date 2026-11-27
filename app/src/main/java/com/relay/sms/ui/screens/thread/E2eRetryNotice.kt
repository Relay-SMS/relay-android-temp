/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens.thread

import com.relay.sms.R
import com.relay.sms.e2e.ProbeGate
import com.relay.sms.e2e.ProbeResult

/**
 * Desktop encryption-box update — the snackbar a manual probe
 * retry shows in the thread, if any. [messageRes] is the
 * user-facing string; [isError] marks the failure notices
 * (the budget notice is informational — the probe went out).
 */
internal data class E2eRetryNotice(val messageRes: Int, val isError: Boolean)

/**
 * Maps a manual probe-retry outcome to the notice the thread
 * shows — or null when the retry needs no notice. A plain send
 * needs none: the panel flips to "Checking if…", which is the
 * feedback. A send that spent the last budget slot DOES show
 * one: automatic checks for this contact are paused until the
 * window elapses, and the notice names the reset (Settings →
 * Advanced → "Reset encryption timing"). Every non-send
 * outcome shows its reason — before this, a tap that did
 * nothing left the user with no feedback at all (only the
 * error-log entry).
 */
internal fun e2eRetryNotice(result: ProbeResult): E2eRetryNotice? = when (result) {
    is ProbeResult.Sent ->
        if (result.budgetExhausted) {
            E2eRetryNotice(R.string.e2e_retry_budget_exhausted, isError = false)
        } else {
            null
        }
    is ProbeResult.Gated -> E2eRetryNotice(result.reason.noticeRes(), isError = true)
    ProbeResult.SendFailed -> E2eRetryNotice(R.string.e2e_retry_send_failed, isError = true)
}

/** The snackbar string for one refusal reason. */
private fun ProbeGate.noticeRes(): Int = when (this) {
    ProbeGate.NoConversation -> R.string.e2e_retry_no_conversation
    ProbeGate.NotOneToOne -> R.string.e2e_retry_not_one_to_one
    ProbeGate.InvalidAddress -> R.string.e2e_retry_invalid_address
    ProbeGate.Blocked -> R.string.e2e_retry_blocked
    ProbeGate.AlreadyEncrypted -> R.string.e2e_retry_already_encrypted
    // Unreachable from a manual retry (the budget and the
    // discovery toggle are lifted there) — defensive only, so
    // the mapping stays total.
    ProbeGate.BudgetExhausted -> R.string.e2e_retry_rate_limited
    // Round 59 — same: the 24-hour spacing is lifted for a
    // manual retry, so this never fires from one either.
    ProbeGate.ProbeTooSoon -> R.string.e2e_retry_rate_limited
    ProbeGate.DiscoveryOff -> R.string.e2e_retry_discovery_off
}
