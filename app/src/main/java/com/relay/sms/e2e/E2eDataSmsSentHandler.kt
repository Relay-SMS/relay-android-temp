/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import android.app.Activity
import android.telephony.SmsManager
import javax.inject.Inject

/**
 * Round 43 — the sent-intent verdict for a handshake data SMS: the
 * radio's answer to a probe or bundle send, delivered to
 * [E2eDataSmsSentReceiver].
 *
 * The send path used to be fire-and-forget, so a radio that
 * accepted the `sendDataMessage` call and then silently rejected
 * the send was invisible — the probe counted as sent, the budget
 * drained, and nothing ever arrived. The verdict separates that
 * from a carrier loss downstream: a rejection here means the send
 * never left the phone; RESULT_OK means it did, and a later loss
 * is the carrier's (or the peer's receiver).
 *
 * RESULT_OK records an event (`e2e-sms-sent-ok`, kind + the
 * code name + the address) — a send the radio accepted and then
 * silently rejected is the exact gap this closes: before,
 * RESULT_OK was silent, so an accepted-then-rejected send looked
 * exactly like a peer without Relay. Any other code records
 * under the kind's failure tag (`e2e-probe-send` /
 * `e2e-bundle-send`, the same tags the synchronous send failure
 * uses) with the result-code name and the address. The address
 * rides the sent intent ([E2eSentIntentFactory] puts it there,
 * [E2eDataSmsSentReceiver] reads it back) — round 57
 * consistency: every per-sender event carries the sender's
 * address, a phone number already in the user's threads. A null
 * address (an intent built before round 57, or a missing extra)
 * records the verdict without one instead of dropping it.
 * Never a key, a payload byte, or message content.
 *
 * Channel wording: the sent intent is shared by the data and
 * text sends, and the kind does not carry the channel — tagging
 * the channel would mean changing the intent factory and the
 * receiver, which this handler does not own. So both wordings
 * say "Handshake SMS" and the port is labelled as the data-SMS
 * port (it is meaningless for a text send); the sender-side
 * acceptance event (`e2e-sms-accepted`, see
 * [E2eDataSmsSenderImpl]) is the place the channel is recorded.
 *
 * Pure decision logic (the [E2eFailureRecorder] is the only
 * dependency) so it is testable on plain JVM — the receiver stays
 * thin glue. Never throws: [E2eFailureRecorder.record] cannot
 * throw, and the mapping is total.
 */
class E2eDataSmsSentHandler @Inject constructor(
    private val recorder: E2eFailureRecorder,
) {

    fun handle(resultCode: Int, kind: DataSmsKind, address: String? = null) {
        if (resultCode == Activity.RESULT_OK) {
            val to = address?.let { " to $it" } ?: ""
            recorder.recordEvent(
                SENT_OK_TAG,
                "Handshake SMS sent$to (kind=${kind.name}): RESULT_OK.",
            )
            return
        }
        val name = resultName(resultCode)
        val forAddress = address?.let { " for $it" } ?: ""
        recorder.record(
            kind.failureTag,
            E2eCryptoException(
                "Handshake SMS send rejected by radio$forAddress " +
                    "(data-SMS port=${E2eHandshake.E2E_DATA_SMS_PORT}): $name",
            ),
        )
    }

    private companion object {
        /** Error-log tag for the radio's RESULT_OK verdict. */
        const val SENT_OK_TAG = "e2e-sms-sent-ok"
    }

    private fun resultName(resultCode: Int): String = when (resultCode) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "RESULT_ERROR_GENERIC_FAILURE"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "RESULT_ERROR_NO_SERVICE"
        SmsManager.RESULT_ERROR_NULL_PDU -> "RESULT_ERROR_NULL_PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "RESULT_ERROR_RADIO_OFF"
        else -> "RESULT_UNKNOWN($resultCode)"
    }
}
