/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import android.app.PendingIntent
import android.content.Context
import android.telephony.SmsManager
import com.google.common.truth.Truth.assertThat
import com.relay.sms.data.sms.PhoneNumberWireFormatter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The handshake data-SMS sender: a send the radio refuses is
 * recorded in the local error log (tag `e2e-probe-send` for a
 * probe, `e2e-bundle-send` for a bundle reply) with the
 * exception's class only — never its message, so a destination
 * address can never leak into the log — and a send the radio
 * accepts records an `e2e-sms-accepted` event (kind + channel +
 * byte/char length + port for data, and the destination address).
 *
 * Round 43 — the send also carries a sent intent (built by
 * [E2eSentIntentFactory], mocked here — `PendingIntent` is an
 * Android-framework class): the radio's verdict lands in
 * [E2eDataSmsSentReceiver], and a null factory result falls back
 * to fire-and-forget.
 *
 * Plain-JVM note: `Build.VERSION.SDK_INT` is 0 under the unit
 * tests, so the sender takes the legacy branch
 * ([SmsManager.getDefault]), statically mocked here.
 */
@Suppress("DEPRECATION")
class E2eDataSmsSenderTest {

    private lateinit var recorder: E2eFailureRecorder
    private lateinit var manager: SmsManager
    private lateinit var sentIntentFactory: E2eSentIntentFactory
    private lateinit var sender: E2eDataSmsSender

    @BeforeEach
    fun setUp() {
        mockkStatic(SmsManager::class)
        val wireFormatter = mockk<PhoneNumberWireFormatter> {
            every { toWireFormat(any(), any()) } returns WIRE_NUMBER
        }
        recorder = mockk(relaxed = true)
        manager = mockk(relaxed = true)
        sentIntentFactory = mockk {
            every { sentIntent(any(), any()) } returns mockk<PendingIntent>()
        }
        every { SmsManager.getDefault() } returns manager
        sender = E2eDataSmsSenderImpl(mockk<Context>(), wireFormatter, recorder, sentIntentFactory)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SmsManager::class)
    }

    @Test
    fun `send failure returns false and records a sanitized probe-send entry`() {
        every {
            manager.sendDataMessage(any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("radio off")

        val sent = sender.sendDataMessage(
            ADDRESS,
            null,
            E2eHandshake.E2E_DATA_SMS_PORT,
            byteArrayOf(1),
        )

        assertThat(sent).isFalse()
        val throwable = slot<Throwable>()
        verify(exactly = 1) { recorder.record("e2e-probe-send", capture(throwable)) }
        val recorded = throwable.captured
        assertThat(recorded).isInstanceOf(E2eCryptoException::class.java)
        assertThat(recorded.message).contains("IllegalStateException")
        assertThat(recorded.message).contains("47832")
        assertThat(recorded.message).doesNotContain(ADDRESS)
        assertThat(recorded.message).doesNotContain(WIRE_NUMBER)
        assertThat(recorded.cause).isNull()
    }

    @Test
    fun `send failure never records the destination address`() {
        every {
            manager.sendDataMessage(any(), any(), any(), any(), any(), any())
        } throws IllegalArgumentException("Invalid destinationAddress: $WIRE_NUMBER")

        val sent = sender.sendDataMessage(
            ADDRESS,
            null,
            E2eHandshake.E2E_DATA_SMS_PORT,
            byteArrayOf(1),
        )

        assertThat(sent).isFalse()
        val throwable = slot<Throwable>()
        verify(exactly = 1) { recorder.record("e2e-probe-send", capture(throwable)) }
        assertThat(throwable.captured.message).doesNotContain(WIRE_NUMBER)
        assertThat(throwable.captured.message).doesNotContain("Invalid destinationAddress")
        assertThat(throwable.captured.message).contains("IllegalArgumentException")
    }

    @Test
    fun `successful send returns true and records the acceptance event`() {
        val sent = sender.sendDataMessage(
            ADDRESS,
            null,
            E2eHandshake.E2E_DATA_SMS_PORT,
            byteArrayOf(1, 2, 3),
        )

        assertThat(sent).isTrue()
        val message = slot<String>()
        verify(exactly = 1) { recorder.recordEvent("e2e-sms-accepted", capture(message)) }
        assertThat(message.captured).contains("Data SMS accepted by radio")
        assertThat(message.captured).contains("kind=PROBE")
        assertThat(message.captured).contains("port=47832")
        assertThat(message.captured).contains("bytes=3")
        assertThat(message.captured).contains(ADDRESS)
        assertThat(message.captured).doesNotContain(WIRE_NUMBER)
        verify(exactly = 0) { recorder.record(any(), any()) }
    }

    @Test
    fun `a bundle acceptance event carries the bundle kind`() {
        val sent = sender.sendDataMessage(
            ADDRESS,
            null,
            E2eHandshake.E2E_DATA_SMS_PORT,
            byteArrayOf(1, 2, 3),
            kind = DataSmsKind.BUNDLE,
        )

        assertThat(sent).isTrue()
        val message = slot<String>()
        verify(exactly = 1) { recorder.recordEvent("e2e-sms-accepted", capture(message)) }
        assertThat(message.captured).contains("kind=BUNDLE")
        assertThat(message.captured).contains("bytes=3")
    }

    @Test
    fun `a bundle send failure records e2e-bundle-send, not e2e-probe-send`() {
        every {
            manager.sendDataMessage(any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("radio off")

        val sent = sender.sendDataMessage(
            ADDRESS,
            null,
            E2eHandshake.E2E_DATA_SMS_PORT,
            byteArrayOf(1),
            kind = DataSmsKind.BUNDLE,
        )

        assertThat(sent).isFalse()
        val throwable = slot<Throwable>()
        verify(exactly = 1) { recorder.record("e2e-bundle-send", capture(throwable)) }
        verify(exactly = 0) { recorder.record("e2e-probe-send", any()) }
        assertThat(throwable.captured).isInstanceOf(E2eCryptoException::class.java)
        assertThat(throwable.captured.message).contains("IllegalStateException")
        assertThat(throwable.captured.message).doesNotContain(ADDRESS)
        assertThat(throwable.captured.cause).isNull()
    }

    @Test
    fun `an explicit probe kind records e2e-probe-send`() {
        every {
            manager.sendDataMessage(any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("radio off")

        val sent = sender.sendDataMessage(
            ADDRESS,
            null,
            E2eHandshake.E2E_DATA_SMS_PORT,
            byteArrayOf(1),
            kind = DataSmsKind.PROBE,
        )

        assertThat(sent).isFalse()
        verify(exactly = 1) { recorder.record("e2e-probe-send", any()) }
    }

    @Test
    fun `the sent intent is passed to the radio`() {
        val pendingIntent = mockk<PendingIntent>()
        every { sentIntentFactory.sentIntent(any(), any()) } returns pendingIntent

        val sent = sender.sendDataMessage(
            ADDRESS,
            null,
            E2eHandshake.E2E_DATA_SMS_PORT,
            byteArrayOf(1),
        )

        assertThat(sent).isTrue()
        val sentSlot = slot<PendingIntent>()
        verify(exactly = 1) {
            manager.sendDataMessage(any(), any(), any(), any(), capture(sentSlot), any())
        }
        assertThat(sentSlot.captured).isSameInstanceAs(pendingIntent)
        verify(exactly = 0) { recorder.record(any(), any()) }
    }

    @Test
    fun `a null sent intent still sends fire-and-forget`() {
        every { sentIntentFactory.sentIntent(any(), any()) } returns null

        val sent = sender.sendDataMessage(
            ADDRESS,
            null,
            E2eHandshake.E2E_DATA_SMS_PORT,
            byteArrayOf(1),
        )

        assertThat(sent).isTrue()
        verify(exactly = 1) {
            manager.sendDataMessage(any(), any(), any(), any(), null, any())
        }
    }

    @Test
    fun `the sent intent carries the send kind and the address`() {
        sender.sendDataMessage(
            ADDRESS,
            null,
            E2eHandshake.E2E_DATA_SMS_PORT,
            byteArrayOf(1),
            kind = DataSmsKind.BUNDLE,
        )

        verify(exactly = 1) { sentIntentFactory.sentIntent(DataSmsKind.BUNDLE, ADDRESS) }
    }

    // ──────────────────── Regular-SMS path (round 45) ────────────────────

    @Test
    fun `a text send passes the wire destination, the text and the sent intent`() {
        val pendingIntent = mockk<PendingIntent>()
        every { sentIntentFactory.sentIntent(any(), any()) } returns pendingIntent

        val sent = sender.sendTextMessage(ADDRESS, null, TEXT)

        assertThat(sent).isTrue()
        val destinationSlot = slot<String>()
        val textSlot = slot<String>()
        val sentSlot = slot<PendingIntent>()
        verify(exactly = 1) {
            manager.sendTextMessage(
                capture(destinationSlot),
                null,
                capture(textSlot),
                capture(sentSlot),
                null,
            )
        }
        assertThat(destinationSlot.captured).isEqualTo(WIRE_NUMBER)
        assertThat(textSlot.captured).isEqualTo(TEXT)
        assertThat(sentSlot.captured).isSameInstanceAs(pendingIntent)
        verify(exactly = 0) { recorder.record(any(), any()) }
        verify(exactly = 0) { manager.sendDataMessage(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a successful text send records the acceptance event with the char length`() {
        val sent = sender.sendTextMessage(ADDRESS, null, TEXT)

        assertThat(sent).isTrue()
        val message = slot<String>()
        verify(exactly = 1) { recorder.recordEvent("e2e-sms-accepted", capture(message)) }
        assertThat(message.captured).contains("Text SMS accepted by radio")
        assertThat(message.captured).contains("kind=PROBE")
        assertThat(message.captured).contains("chars=${TEXT.length}")
        assertThat(message.captured).doesNotContain("port=")
        assertThat(message.captured).contains(ADDRESS)
        verify(exactly = 0) { recorder.record(any(), any()) }
    }

    @Test
    fun `a text send failure returns false and records a sanitized text-send entry`() {
        every {
            manager.sendTextMessage(any(), any(), any(), any(), any())
        } throws IllegalStateException("radio off")

        val sent = sender.sendTextMessage(ADDRESS, null, TEXT)

        assertThat(sent).isFalse()
        val throwable = slot<Throwable>()
        verify(exactly = 1) { recorder.record("e2e-probe-send", capture(throwable)) }
        val recorded = throwable.captured
        assertThat(recorded).isInstanceOf(E2eCryptoException::class.java)
        assertThat(recorded.message).contains("Text SMS send failed")
        assertThat(recorded.message).doesNotContain("Data SMS send failed")
        assertThat(recorded.message).contains("IllegalStateException")
        assertThat(recorded.message).doesNotContain(ADDRESS)
        assertThat(recorded.message).doesNotContain(WIRE_NUMBER)
        assertThat(recorded.cause).isNull()
    }

    @Test
    fun `a text bundle send failure records e2e-bundle-send, not e2e-probe-send`() {
        every {
            manager.sendTextMessage(any(), any(), any(), any(), any())
        } throws IllegalStateException("radio off")

        val sent = sender.sendTextMessage(ADDRESS, null, TEXT, kind = DataSmsKind.BUNDLE)

        assertThat(sent).isFalse()
        val throwable = slot<Throwable>()
        verify(exactly = 1) { recorder.record("e2e-bundle-send", capture(throwable)) }
        verify(exactly = 0) { recorder.record("e2e-probe-send", any()) }
        assertThat(throwable.captured.message).contains("Text SMS send failed")
        assertThat(throwable.captured.message).contains("IllegalStateException")
        assertThat(throwable.captured.message).doesNotContain(ADDRESS)
        assertThat(throwable.captured.cause).isNull()
    }

    @Test
    fun `a null sent intent still sends the text fire-and-forget`() {
        every { sentIntentFactory.sentIntent(any(), any()) } returns null

        val sent = sender.sendTextMessage(ADDRESS, null, TEXT)

        assertThat(sent).isTrue()
        verify(exactly = 1) {
            manager.sendTextMessage(any(), any(), any(), null, any())
        }
    }

    @Test
    fun `the text sent intent carries the send kind and the address`() {
        sender.sendTextMessage(ADDRESS, null, TEXT, kind = DataSmsKind.BUNDLE)

        verify(exactly = 1) { sentIntentFactory.sentIntent(DataSmsKind.BUNDLE, ADDRESS) }
    }

    private companion object {
        const val ADDRESS = "5551234567"
        const val WIRE_NUMBER = "+15551234567"
        const val TEXT = "RLYH:QUJDREVGR0g="
    }
}
