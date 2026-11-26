/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.system.receiver

import android.provider.Telephony
import com.google.common.truth.Truth.assertThat
import com.relay.sms.domain.settings.AppSettings
import com.relay.sms.domain.settings.AppSettingsSource
import com.relay.sms.domain.settings.DeviceMode
import com.relay.sms.e2e.E2eDataSmsGate
import com.relay.sms.e2e.E2eFailureRecorder
import com.relay.sms.e2e.E2eHandshakeHandler
import com.relay.sms.e2e.ParsedDataSms
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The client-mode gate on [E2eDataSmsReceiver], tested through
 * [E2eDataSmsGate] — the receiver delegates every decision to it.
 *
 * The receiver itself cannot be exercised on plain JVM: it is a Hilt
 * entry point, and Hilt weaves an `inject(context)` call into its real
 * `onReceive` — a mocked `Context` has no application component, so
 * calling it under MockK throws `MockKException` (CI run 44935640392:
 * all three tests failed at the `callOriginal()` stub, while the same
 * tests passed locally where no Hilt transform runs). The gate holds
 * the whole decision, so testing it directly covers the behavior.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class E2eDataSmsReceiverTest {

    /**
     * A settings source fixed at [mode], always hydrated. A null mode
     * fails on any read — for the test that must return before the
     * settings are consulted at all.
     */
    private class FakeSettings(private val mode: DeviceMode?) : AppSettingsSource {
        private val value = AppSettings().copy(mode = mode ?: DeviceMode.HUB)
        override val flow: Flow<AppSettings> = flowOf(value)
        override val state: StateFlow<AppSettings> = MutableStateFlow(value)
        override suspend fun hydratedOrNull(): AppSettings =
            mode?.let { AppSettings().copy(mode = it) }
                ?: error("settings must not be read for this action")
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private fun gate(
        mode: DeviceMode?,
        handler: E2eHandshakeHandler,
        recorder: E2eFailureRecorder = mockk(relaxed = true),
    ) = E2eDataSmsGate(
        Lazy { FakeSettings(mode) },
        Lazy { handler },
        Dispatchers.Unconfined,
        Lazy { recorder },
    )

    @Test
    fun `client mode drops the handshake packet without parsing it or calling the handler`() =
        runTest {
            val handler = mockk<E2eHandshakeHandler>()
            coEvery { handler.handleDataSms(any(), any(), any(), any()) } returns true

            val handled = gate(DeviceMode.CLIENT, handler).handle(
                Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION,
                null,
            ) { error("the packet must not be parsed in client mode") }

            // The packet is dropped silently: no identity is
            // provisioned, no bundle reply goes out.
            assertThat(handled).isFalse()
            coVerify(exactly = 0) { handler.handleDataSms(any(), any(), any(), any()) }
        }

    @Test
    fun `hub mode hands the packet to the handler unchanged`() = runTest {
        val handler = mockk<E2eHandshakeHandler>()
        coEvery { handler.handleDataSms(any(), any(), any(), any()) } returns true
        val data = byteArrayOf(9, 8, 7)

        val handled = gate(DeviceMode.HUB, handler).handle(
            Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION,
            3,
        ) { ParsedDataSms("+33612345678", data) }

        // The fourth argument is `now`, defaulted to the current time
        // at the call site, so it can only be matched with `any()` —
        // an `eq` on the default would compare two different instants
        // (and an `exactly = 0` check with the default could false-pass).
        assertThat(handled).isTrue()
        coVerify(exactly = 1) {
            handler.handleDataSms("+33612345678", data, 3, any())
        }
    }

    @Test
    fun `a non-data-sms intent is ignored before any mode read`() = runTest {
        val handler = mockk<E2eHandshakeHandler>()

        val handled = gate(null, handler).handle(
            "android.intent.action.SMS_RECEIVED",
            null,
        ) { error("the packet must not be parsed for this action") }

        assertThat(handled).isFalse()
        coVerify(exactly = 0) { handler.handleDataSms(any(), any(), any(), any()) }
    }

    // ──────────────────── Gate drops are recorded ────────────────────
    // Every drop carries a fixed reason word in the error log, so
    // an arrival line in the receiver with no handling narrows to
    // exactly one of these.

    @Test
    fun `a wrong action records the drop with its reason`() = runTest {
        val handler = mockk<E2eHandshakeHandler>()
        val recorder = mockk<E2eFailureRecorder>(relaxed = true)

        val handled = gate(null, handler, recorder).handle(
            "android.intent.action.SMS_RECEIVED",
            null,
        ) { error("the packet must not be parsed for this action") }

        assertThat(handled).isFalse()
        verify(exactly = 1) {
            recorder.recordEvent(
                "e2e-data-sms-dropped",
                match { it.contains("wrong-action") },
            )
        }
        coVerify(exactly = 0) { handler.handleDataSms(any(), any(), any(), any()) }
    }

    @Test
    fun `client mode records the drop with its reason`() = runTest {
        val handler = mockk<E2eHandshakeHandler>()
        val recorder = mockk<E2eFailureRecorder>(relaxed = true)

        val handled = gate(DeviceMode.CLIENT, handler, recorder).handle(
            Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION,
            null,
        ) { error("the packet must not be parsed in client mode") }

        assertThat(handled).isFalse()
        verify(exactly = 1) {
            recorder.recordEvent(
                "e2e-data-sms-dropped",
                match { it.contains("client-mode") },
            )
        }
        coVerify(exactly = 0) { handler.handleDataSms(any(), any(), any(), any()) }
    }

    @Test
    fun `a missing message records the drop with its reason`() = runTest {
        val handler = mockk<E2eHandshakeHandler>()
        val recorder = mockk<E2eFailureRecorder>(relaxed = true)

        val handled = gate(DeviceMode.HUB, handler, recorder).handle(
            Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION,
            null,
        ) { null }

        assertThat(handled).isFalse()
        verify(exactly = 1) {
            recorder.recordEvent(
                "e2e-data-sms-dropped",
                match { it.contains("no-message") },
            )
        }
        coVerify(exactly = 0) { handler.handleDataSms(any(), any(), any(), any()) }
    }

    @Test
    fun `a null address records the drop with its reason`() = runTest {
        val handler = mockk<E2eHandshakeHandler>()
        val recorder = mockk<E2eFailureRecorder>(relaxed = true)

        val handled = gate(DeviceMode.HUB, handler, recorder).handle(
            Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION,
            null,
        ) { ParsedDataSms(null, byteArrayOf(9, 8, 7)) }

        assertThat(handled).isFalse()
        verify(exactly = 1) {
            recorder.recordEvent(
                "e2e-data-sms-dropped",
                match { it.contains("null-address") },
            )
        }
        coVerify(exactly = 0) { handler.handleDataSms(any(), any(), any(), any()) }
    }

    @Test
    fun `null data records the drop with its reason`() = runTest {
        val handler = mockk<E2eHandshakeHandler>()
        val recorder = mockk<E2eFailureRecorder>(relaxed = true)

        val handled = gate(DeviceMode.HUB, handler, recorder).handle(
            Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION,
            null,
        ) { ParsedDataSms("+33612345678", null) }

        assertThat(handled).isFalse()
        verify(exactly = 1) {
            recorder.recordEvent(
                "e2e-data-sms-dropped",
                match { it.contains("null-data") },
            )
        }
        coVerify(exactly = 0) { handler.handleDataSms(any(), any(), any(), any()) }
    }

    @Test
    fun `a handled packet records no drop`() = runTest {
        val handler = mockk<E2eHandshakeHandler>()
        coEvery { handler.handleDataSms(any(), any(), any(), any()) } returns true
        val recorder = mockk<E2eFailureRecorder>(relaxed = true)

        val handled = gate(DeviceMode.HUB, handler, recorder).handle(
            Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION,
            3,
        ) { ParsedDataSms("+33612345678", byteArrayOf(9, 8, 7)) }

        assertThat(handled).isTrue()
        verify(exactly = 0) { recorder.recordEvent("e2e-data-sms-dropped", any()) }
    }
}
