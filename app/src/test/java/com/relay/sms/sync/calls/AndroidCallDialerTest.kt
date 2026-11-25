/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.sync.calls

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The platform hangup: `endCall()` ends the current call through
 * `TelecomManager.endCall` — permission first, then the API-28
 * floor (folded into the telecom fetch), then the in-call check,
 * then the end itself. The context and the telecom service
 * are MockK mocks, and the permission check is statically mocked
 * (`mockkStatic` on `ContextCompat`, like the `SmsManager` mock
 * in `E2eDataSmsSenderTest`) — the real service never runs here.
 * The API level is an injected constructor value, so the floor
 * is drivable here (plain JVM always reads SDK_INT 0).
 *
 * The dial path has no unit test of its own (it ends in
 * `placeCall` / an intent, both unobservable on the JVM); the
 * hangup is testable because every branch is an outcome.
 */
class AndroidCallDialerTest {

    private lateinit var context: Context
    private lateinit var telecom: TelecomManager
    private lateinit var dialer: AndroidCallDialer

    @BeforeEach
    fun setUp() {
        mockkStatic(ContextCompat::class)
        context = mockk()
        telecom = mockk()
        every { ContextCompat.checkSelfPermission(any(), any()) } returns
            PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(TelecomManager::class.java) } returns telecom
        every { telecom.isInCall } returns true
        every { telecom.endCall() } returns true
        dialer = AndroidCallDialer(context, Build.VERSION_CODES.P)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `a denied end answers PermissionDenied without touching telecom`() {
        runTest {
            every { ContextCompat.checkSelfPermission(any(), any()) } returns
                PackageManager.PERMISSION_DENIED

            assertThat(dialer.endCall()).isEqualTo(EndCallOutcome.PermissionDenied)
            verify(exactly = 0) { context.getSystemService(TelecomManager::class.java) }
        }
    }

    @Test
    fun `no call in progress answers NoCall without ending anything`() {
        runTest {
            every { telecom.isInCall } returns false

            assertThat(dialer.endCall()).isEqualTo(EndCallOutcome.NoCall)
            verify(exactly = 0) { telecom.endCall() }
        }
    }

    @Test
    fun `an accepted end answers Success`() {
        runTest {
            assertThat(dialer.endCall()).isEqualTo(EndCallOutcome.Success)
            verify(exactly = 1) { telecom.endCall() }
        }
    }

    @Test
    fun `an end the OS refuses answers Failed`() {
        runTest {
            every { telecom.endCall() } returns false

            assertThat(dialer.endCall()).isEqualTo(EndCallOutcome.Failed)
        }
    }

    @Test
    fun `a SecurityException answers PermissionDenied`() {
        runTest {
            every { telecom.endCall() } throws SecurityException("permission revoked")

            assertThat(dialer.endCall()).isEqualTo(EndCallOutcome.PermissionDenied)
        }
    }

    @Test
    fun `below API 28 answers Failed without touching telecom`() {
        runTest {
            val oldApiDialer = AndroidCallDialer(context, Build.VERSION_CODES.P - 1)

            assertThat(oldApiDialer.endCall()).isEqualTo(EndCallOutcome.Failed)
            verify(exactly = 0) { context.getSystemService(TelecomManager::class.java) }
            verify(exactly = 0) { telecom.endCall() }
        }
    }

    @Test
    fun `no telecom service answers Failed`() {
        runTest {
            every { context.getSystemService(TelecomManager::class.java) } returns null

            assertThat(dialer.endCall()).isEqualTo(EndCallOutcome.Failed)
        }
    }
}
