/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import android.app.Activity
import android.telephony.SmsManager
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Round 43 — the sent-intent verdict: RESULT_OK records an
 * `e2e-sms-sent-ok` event (kind + the code name + the address),
 * any other code records under the kind's failure tag with the
 * result-code name and the address — the same shape as the
 * synchronous send failure, so the two read alike in the error
 * log. The wording says "Handshake SMS" (not "Data SMS"): the
 * sent intent is shared by the data and text sends and the kind
 * does not carry the channel. The address rides the sent intent
 * (round 57); a null address records the verdict without one.
 */
class E2eDataSmsSentHandlerTest {

    private val recorder = mockk<E2eFailureRecorder>(relaxed = true)
    private val handler = E2eDataSmsSentHandler(recorder)

    /** A send the radio accepted records the sent-ok event, for either kind. */
    @Test
    fun `RESULT_OK records the sent-ok event for either kind`() {
        handler.handle(Activity.RESULT_OK, DataSmsKind.PROBE, ADDRESS)
        handler.handle(Activity.RESULT_OK, DataSmsKind.BUNDLE, BOB)

        val messages = mutableListOf<String>()
        verify(exactly = 2) { recorder.recordEvent("e2e-sms-sent-ok", capture(messages)) }
        assertThat(messages[0]).contains("kind=PROBE")
        assertThat(messages[0]).contains("RESULT_OK")
        assertThat(messages[0]).contains(ADDRESS)
        assertThat(messages[1]).contains("kind=BUNDLE")
        assertThat(messages[1]).contains(BOB)
        verify(exactly = 0) { recorder.record(any(), any()) }
    }

    /** A missing address (an intent built before round 57) records the verdict without one. */
    @Test
    fun `RESULT_OK without an address records the old wording`() {
        handler.handle(Activity.RESULT_OK, DataSmsKind.PROBE)

        val message = slot<String>()
        verify(exactly = 1) { recorder.recordEvent("e2e-sms-sent-ok", capture(message)) }
        assertThat(message.captured).isEqualTo("Handshake SMS sent (kind=PROBE): RESULT_OK.")
    }

    /** A generic failure records e2e-probe-send with the code name and the port. */
    @Test
    fun `generic failure records e2e-probe-send with the code name`() {
        handler.handle(SmsManager.RESULT_ERROR_GENERIC_FAILURE, DataSmsKind.PROBE, ADDRESS)

        val throwable = slot<Throwable>()
        verify(exactly = 1) { recorder.record("e2e-probe-send", capture(throwable)) }
        val recorded = throwable.captured
        assertThat(recorded).isInstanceOf(E2eCryptoException::class.java)
        assertThat(recorded.message).contains("Handshake SMS send rejected by radio")
        assertThat(recorded.message).doesNotContain("Data SMS send rejected")
        assertThat(recorded.message).contains("RESULT_ERROR_GENERIC_FAILURE")
        assertThat(recorded.message).contains("47832")
        assertThat(recorded.message).contains(ADDRESS)
        assertThat(recorded.cause).isNull()
    }

    /** A no-service failure on a bundle records e2e-bundle-send, not e2e-probe-send. */
    @Test
    fun `no-service records e2e-bundle-send with the code name`() {
        handler.handle(SmsManager.RESULT_ERROR_NO_SERVICE, DataSmsKind.BUNDLE, BOB)

        val throwable = slot<Throwable>()
        verify(exactly = 1) { recorder.record("e2e-bundle-send", capture(throwable)) }
        verify(exactly = 0) { recorder.record("e2e-probe-send", any()) }
        assertThat(throwable.captured.message).contains("RESULT_ERROR_NO_SERVICE")
        assertThat(throwable.captured.message).contains("47832")
        assertThat(throwable.captured.message).contains(BOB)
    }

    /** Radio-off and null-PDU map to their own names. */
    @Test
    fun `radio-off and null-pdu map to their names`() {
        handler.handle(SmsManager.RESULT_ERROR_RADIO_OFF, DataSmsKind.PROBE)
        handler.handle(SmsManager.RESULT_ERROR_NULL_PDU, DataSmsKind.PROBE)

        val throwables = mutableListOf<Throwable>()
        verify(exactly = 2) { recorder.record("e2e-probe-send", capture(throwables)) }
        assertThat(throwables[0].message).contains("RESULT_ERROR_RADIO_OFF")
        assertThat(throwables[1].message).contains("RESULT_ERROR_NULL_PDU")
    }

    /** An unknown code still records, with the numeric code in the name. */
    @Test
    fun `an unknown code records RESULT_UNKNOWN with the code`() {
        handler.handle(99, DataSmsKind.PROBE)

        val throwable = slot<Throwable>()
        verify(exactly = 1) { recorder.record("e2e-probe-send", capture(throwable)) }
        assertThat(throwable.captured.message).contains("RESULT_UNKNOWN(99)")
    }

    private companion object {
        const val ADDRESS = "5551234567"
        const val BOB = "+33612345678"
    }
}
