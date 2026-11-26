/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import com.google.common.truth.Truth.assertThat
import com.relay.sms.data.sms.TelephonyReader
import com.relay.sms.domain.settings.AppSettings
import com.relay.sms.domain.settings.AppSettingsSource
import com.relay.sms.domain.settings.DeviceMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The handshake-SMS branch of [com.relay.sms.system.receiver
 * .SmsDeliverReceiver], tested through [E2eHandshakeSmsGate] — the
 * receiver delegates every decision to it (same extraction as
 * [E2eDataSmsGate]: the receiver is a Hilt entry point and cannot be
 * exercised on plain JVM).
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class E2eHandshakeSmsGateTest {

    /** A settings source fixed at [mode], always hydrated. */
    private class FakeSettings(private val mode: DeviceMode) : AppSettingsSource {
        private val value = AppSettings().copy(mode = mode)
        override val flow: Flow<AppSettings> = flowOf(value)
        override val state: StateFlow<AppSettings> = MutableStateFlow(value)
        override suspend fun hydratedOrNull(): AppSettings = value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private fun gate(
        mode: DeviceMode,
        reader: TelephonyReader,
        handler: E2eHandshakeHandler,
        recorder: E2eFailureRecorder = mockk(relaxed = true),
    ) = E2eHandshakeSmsGate(reader, FakeSettings(mode), handler, recorder)

    /**
     * A well-formed handshake SMS body. The handler is mocked, so
     * the packet bytes need not decode — only the size matters for
     * [E2eHandshakeSms.isHandshakeSms].
     */
    private fun handshakeBody(): String =
        E2eHandshakeSms.encode(ByteArray(E2eHandshake.PROBE_SIZE))

    @Test
    fun `hub mode passes processing time to the handler, not the carrier timestamp`() =
        runTest {
            val reader = mockk<TelephonyReader>()
            every { reader.insertInboxSms(any(), any(), any(), any()) } returns null
            val handler = mockk<E2eHandshakeHandler>()
            coEvery { handler.handleSms(any(), any(), any(), any()) } returns true
            // The symptom (round 46): the SMSC clock ran AHEAD of the
            // phone clock, so a carrier timestamp passed as `now`
            // dated the ACTIVATED notice after a message sent later —
            // and the sent message rendered above the notice.
            val carrierTs = System.currentTimeMillis() + CARRIER_SKEW_MS
            val body = handshakeBody()

            val before = System.currentTimeMillis()
            val handled = gate(DeviceMode.HUB, reader, handler)
                .handle(ADDRESS, body, carrierTs, 3)
            val after = System.currentTimeMillis()

            assertThat(handled).isTrue()
            // The inbox row keeps the carrier date — it is a telephony
            // record — but the handler's `now` is processing time.
            verify(exactly = 1) { reader.insertInboxSms(ADDRESS, body, carrierTs, 3) }
            val nowSlot = slot<Long>()
            coVerify(exactly = 1) {
                handler.handleSms(ADDRESS, body, 3, capture(nowSlot))
            }
            assertThat(nowSlot.captured).isAtLeast(before)
            assertThat(nowSlot.captured).isAtMost(after)
        }

    @Test
    fun `a non-handshake text is ignored before any write or handler call`() = runTest {
        val reader = mockk<TelephonyReader>()
        val handler = mockk<E2eHandshakeHandler>()

        val handled = gate(DeviceMode.HUB, reader, handler)
            .handle(ADDRESS, "hello", NOW, null)

        assertThat(handled).isFalse()
        verify(exactly = 0) { reader.insertInboxSms(any(), any(), any(), any()) }
        coVerify(exactly = 0) { handler.handleSms(any(), any(), any(), any()) }
    }

    @Test
    fun `client mode writes the inbox row but never calls the handler`() = runTest {
        val reader = mockk<TelephonyReader>()
        every { reader.insertInboxSms(any(), any(), any(), any()) } returns null
        val handler = mockk<E2eHandshakeHandler>()
        val body = handshakeBody()

        val handled = gate(DeviceMode.CLIENT, reader, handler)
            .handle(ADDRESS, body, NOW, null)

        // Swallowed: written to the system inbox, never a bubble, no
        // identity provisioned.
        assertThat(handled).isTrue()
        verify(exactly = 1) { reader.insertInboxSms(ADDRESS, body, NOW, null) }
        coVerify(exactly = 0) { handler.handleSms(any(), any(), any(), any()) }
    }

    // ──────────────────── Receipt logging ────────────────────
    // Light-touch only: a handshake SMS records its receipt, a
    // client-mode swallow records the swallow, and a non-handshake
    // text records nothing (every normal SMS would spam the log).

    @Test
    fun `hub mode records the handshake SMS receipt`() = runTest {
        val reader = mockk<TelephonyReader>()
        every { reader.insertInboxSms(any(), any(), any(), any()) } returns null
        val handler = mockk<E2eHandshakeHandler>()
        coEvery { handler.handleSms(any(), any(), any(), any()) } returns true
        val recorder = mockk<E2eFailureRecorder>(relaxed = true)
        val body = handshakeBody()

        val handled = gate(DeviceMode.HUB, reader, handler, recorder)
            .handle(ADDRESS, body, NOW, null)

        assertThat(handled).isTrue()
        verify(exactly = 1) {
            recorder.recordEvent(
                "e2e-handshake-sms-received",
                match { it.contains(ADDRESS) },
            )
        }
        verify(exactly = 0) { recorder.recordEvent("e2e-handshake-sms-client-mode", any()) }
    }

    @Test
    fun `client mode records the receipt and the swallow`() = runTest {
        val reader = mockk<TelephonyReader>()
        every { reader.insertInboxSms(any(), any(), any(), any()) } returns null
        val handler = mockk<E2eHandshakeHandler>()
        val recorder = mockk<E2eFailureRecorder>(relaxed = true)
        val body = handshakeBody()

        val handled = gate(DeviceMode.CLIENT, reader, handler, recorder)
            .handle(ADDRESS, body, NOW, null)

        assertThat(handled).isTrue()
        verify(exactly = 1) { recorder.recordEvent("e2e-handshake-sms-received", any()) }
        verify(exactly = 1) {
            recorder.recordEvent(
                "e2e-handshake-sms-client-mode",
                match { it.contains(ADDRESS) },
            )
        }
        coVerify(exactly = 0) { handler.handleSms(any(), any(), any(), any()) }
    }

    @Test
    fun `a non-handshake text records nothing`() = runTest {
        val reader = mockk<TelephonyReader>()
        val handler = mockk<E2eHandshakeHandler>()
        val recorder = mockk<E2eFailureRecorder>(relaxed = true)

        val handled = gate(DeviceMode.HUB, reader, handler, recorder)
            .handle(ADDRESS, "hello", NOW, null)

        assertThat(handled).isFalse()
        verify(exactly = 0) { recorder.recordEvent(any(), any()) }
    }

    companion object {
        private const val ADDRESS = "+33612345678"
        private const val NOW = 1_800_000_000_000L

        /** How far ahead of the phone clock the SMSC clock runs in the regression test. */
        private const val CARRIER_SKEW_MS = 3_600_000L
    }
}
