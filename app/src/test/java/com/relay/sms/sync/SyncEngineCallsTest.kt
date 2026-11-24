/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.sync

import com.google.common.truth.Truth.assertThat
import com.relay.sms.sync.calls.DialOutcome
import com.relay.sms.sync.calls.FakeCallDialer
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Live calls — the dial handler: a `dial_request` reaches the
 * dialer and is answered with `dial_result`, with the outcome
 * mapped 1:1 onto the frame's error codes. The dialer is a fake
 * here — the real ACTION_CALL executor cannot run on the JVM —
 * so "ACTION_CALL attempted" is the fake recording the number.
 */
class SyncEngineCallsTest {

    @Test fun `a granted dial dials the number and answers dial_result ok true`() = runTest {
        val dialer = FakeCallDialer(outcome = DialOutcome.Success)
        runEngineWithDialer(dialer) { engine, sent ->
            engine.onDialRequest(SyncProtocol.DialRequest(number = "+15551234567"))
            advanceUntilIdle()
            assertThat(dialer.dialedNumbers).containsExactly("+15551234567")
            assertThat(sent.map { it.frameType() }).containsExactly("dial_result")
            val ack = sent.first().asObject()
            assertThat(ack["ok"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(ack.containsKey("error")).isFalse()
        }
    }

    @Test fun `a denied dial answers dial_result ok false permission_denied`() = runTest {
        val dialer = FakeCallDialer(outcome = DialOutcome.PermissionDenied)
        runEngineWithDialer(dialer) { engine, sent ->
            engine.onDialRequest(SyncProtocol.DialRequest(number = "+15551234567"))
            advanceUntilIdle()
            assertThat(dialer.dialedNumbers).containsExactly("+15551234567")
            assertThat(sent.map { it.frameType() }).containsExactly("dial_result")
            val ack = sent.first().asObject()
            assertThat(ack["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(ack["error"]?.jsonPrimitive?.content).isEqualTo("permission_denied")
        }
    }

    @Test fun `a blank number answers dial_result ok false no_number`() = runTest {
        val dialer = FakeCallDialer(outcome = DialOutcome.NoNumber)
        runEngineWithDialer(dialer) { engine, sent ->
            engine.onDialRequest(SyncProtocol.DialRequest(number = "   "))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("dial_result")
            val ack = sent.first().asObject()
            assertThat(ack["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(ack["error"]?.jsonPrimitive?.content).isEqualTo("no_number")
        }
    }

    @Test fun `a failed dial answers dial_result ok false failed`() = runTest {
        val dialer = FakeCallDialer(outcome = DialOutcome.Failed)
        runEngineWithDialer(dialer) { engine, sent ->
            engine.onDialRequest(SyncProtocol.DialRequest(number = "+15551234567"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("dial_result")
            val ack = sent.first().asObject()
            assertThat(ack["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(ack["error"]?.jsonPrimitive?.content).isEqualTo("failed")
        }
    }

    @Test fun `an engine without a dialer answers dial_result failed instead of crashing`() = runTest {
        runEngineWithDialer(dialer = null) { engine, sent ->
            engine.onDialRequest(SyncProtocol.DialRequest(number = "+15551234567"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("dial_result")
            val ack = sent.first().asObject()
            assertThat(ack["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(ack["error"]?.jsonPrimitive?.content).isEqualTo("failed")
        }
    }

    /**
     * Like [runEngine], but with a dialer: the shared helper has
     * no dialer parameter, and the dial tests are the only ones
     * that need one.
     */
    private suspend fun TestScope.runEngineWithDialer(
        dialer: FakeCallDialer?,
        block: suspend TestScope.(SyncEngine, MutableList<String>) -> Unit,
    ) {
        val sent = mutableListOf<String>()
        val engine = SyncEngine(
            FakeSyncMessageSource(),
            this,
            send = { sent += it },
            wakelock = NoOpSyncWakelock,
            callDialer = dialer,
        )
        try {
            block(engine, sent)
        } finally {
            engine.stop()
            advanceUntilIdle()
        }
    }
}
