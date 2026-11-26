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
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Capability declaration tests (round 48 media, round 49 groups,
 * design §2 / §13): the one-shot announce, the proven-channel
 * rule (recorded channel only; both channels when none is
 * recorded), the announce-back on receipt, and the blocked /
 * not-ENCRYPTED / radio-refused gates. Round 49: one packet
 * announces media + groups, and the announce re-fires when
 * EITHER announced flag is unset (a round-48 peer still needs
 * the group bit).
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose `runTest` body returns a value.
 */
class E2eMediaCapabilityManagerTest {

    private companion object {
        const val BOB = "+33612345678"
        const val SUB_ID = 2
        const val NOW = 1_800_000_000_000L
    }

    private class Harness(
        val peerDao: FakeE2ePeerDao = FakeE2ePeerDao(),
        val sender: RecordingDataSmsSender = RecordingDataSmsSender(),
        blocked: Set<String> = emptySet(),
    ) {
        val peers = E2ePeerRepository(peerDao)
        val recorder: E2eFailureRecorder = mockk(relaxed = true)
        val manager = E2eMediaCapabilityManager(
            peers = peers,
            dataSmsSender = sender,
            blockedRepo = FakeBlockedRepo(blocked),
            recorder = recorder,
        )

        fun seedPeer(
            state: E2ePeerState = E2ePeerState.ENCRYPTED,
            mediaCapable: Boolean = false,
            mediaAnnounced: Boolean = false,
            groupCapable: Boolean = false,
            groupAnnounced: Boolean = false,
            groupMediaCapable: Boolean = false,
            groupMediaAnnounced: Boolean = false,
            handshakeChannel: String? = null,
        ) {
            peerDao.peers[BOB] = E2ePeerEntity(
                address = BOB,
                state = state,
                mediaCapable = mediaCapable,
                mediaAnnounced = mediaAnnounced,
                groupCapable = groupCapable,
                groupAnnounced = groupAnnounced,
                groupMediaCapable = groupMediaCapable,
                groupMediaAnnounced = groupMediaAnnounced,
                handshakeChannel = handshakeChannel,
                updatedAt = NOW,
            )
        }
    }

    @Test
    fun `announceIfNeeded sends on the recorded data channel only`() = runTest {
        val harness = Harness()
        harness.seedPeer(handshakeChannel = "DATA_SMS")

        val sent = harness.manager.announceIfNeeded(BOB, SUB_ID, NOW)

        assertThat(sent).isTrue()
        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.sender.sentTexts).isEmpty()
        assertThat(harness.sender.sent[0].port).isEqualTo(E2eHandshake.E2E_DATA_SMS_PORT)
        assertThat(harness.sender.sent[0].kind).isEqualTo(DataSmsKind.CAPABILITY)
        assertThat(harness.sender.sent[0].data.size).isEqualTo(70)
        assertThat(harness.peerDao.peers[BOB]!!.mediaAnnounced).isTrue()
        assertThat(harness.peerDao.peers[BOB]!!.groupAnnounced).isTrue()
        val message = slot<String>()
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-capability-sent", capture(message)) }
        assertThat(message.captured).contains("channel=DATA_SMS")
        assertThat(message.captured).contains("to $BOB")
    }

    @Test
    fun `announceIfNeeded sends on the recorded text channel only`() = runTest {
        val harness = Harness()
        harness.seedPeer(handshakeChannel = "TEXT_SMS")

        val sent = harness.manager.announceIfNeeded(BOB, SUB_ID, NOW)

        assertThat(sent).isTrue()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).hasSize(1)
        assertThat(harness.sender.sentTexts[0].kind).isEqualTo(DataSmsKind.CAPABILITY)
        assertThat(harness.sender.sentTexts[0].text).startsWith("RLYH:")
        assertThat(harness.peerDao.peers[BOB]!!.mediaAnnounced).isTrue()
        val message = slot<String>()
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-capability-sent", capture(message)) }
        assertThat(message.captured).contains("channel=TEXT_SMS")
        assertThat(message.captured).contains("to $BOB")
    }

    @Test
    fun `announceIfNeeded with no recorded channel sends on BOTH channels`() = runTest {
        val harness = Harness()
        harness.seedPeer(handshakeChannel = null)

        val sent = harness.manager.announceIfNeeded(BOB, SUB_ID, NOW)

        // Both, even though the data send was accepted: an
        // accepted data send may still be dropped downstream
        // (the partner pair's line), so the text send is not
        // skipped when the data send succeeds.
        assertThat(sent).isTrue()
        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.sender.sentTexts).hasSize(1)
        assertThat(harness.peerDao.peers[BOB]!!.mediaAnnounced).isTrue()
        val message = slot<String>()
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-capability-sent", capture(message)) }
        assertThat(message.captured).contains("channel=DATA_SMS+TEXT_SMS")
        assertThat(message.captured).contains("to $BOB")
    }

    @Test
    fun `announceIfNeeded is a no-op once announced`() = runTest {
        val harness = Harness()
        harness.seedPeer(mediaAnnounced = true, groupAnnounced = true, groupMediaAnnounced = true)

        val sent = harness.manager.announceIfNeeded(BOB, SUB_ID, NOW)

        assertThat(sent).isFalse()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
        // A no-op records nothing: every thread-open on an
        // announced peer would otherwise spam the log.
        verify(exactly = 0) { harness.recorder.recordEvent(any(), any()) }
    }

    @Test
    fun `announceIfNeeded re-fires for a round-48 peer with only the media flag set`() = runTest {
        val harness = Harness()
        harness.seedPeer(mediaAnnounced = true, groupAnnounced = false, handshakeChannel = "DATA_SMS")

        val sent = harness.manager.announceIfNeeded(BOB, SUB_ID, NOW)

        assertThat(sent).isTrue()
        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.peerDao.peers[BOB]!!.groupAnnounced).isTrue()
    }

    @Test
    fun `announceIfNeeded re-fires for a round-49 peer with only the media and group flags set`() = runTest {
        val harness = Harness()
        harness.seedPeer(
            mediaAnnounced = true,
            groupAnnounced = true,
            groupMediaAnnounced = false,
            handshakeChannel = "DATA_SMS",
        )

        val sent = harness.manager.announceIfNeeded(BOB, SUB_ID, NOW)

        assertThat(sent).isTrue()
        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.peerDao.peers[BOB]!!.groupMediaAnnounced).isTrue()
    }

    @Test
    fun `announceIfNeeded is a no-op for a non-ENCRYPTED peer`() = runTest {
        val harness = Harness()
        harness.seedPeer(state = E2ePeerState.PROBING)

        val sent = harness.manager.announceIfNeeded(BOB, SUB_ID, NOW)

        assertThat(sent).isFalse()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `announceIfNeeded is a no-op for an unknown peer`() = runTest {
        val harness = Harness()
        // No peer row at all: state reads UNKNOWN.

        val sent = harness.manager.announceIfNeeded(BOB, SUB_ID, NOW)

        assertThat(sent).isFalse()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `announceIfNeeded sends nothing to a blocked peer`() = runTest {
        val harness = Harness(blocked = setOf(BOB))
        harness.seedPeer()

        val sent = harness.manager.announceIfNeeded(BOB, SUB_ID, NOW)

        assertThat(sent).isFalse()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
        assertThat(harness.peerDao.peers[BOB]!!.mediaAnnounced).isFalse()
        verify(exactly = 1) {
            harness.recorder.recordEvent(
                "e2e-capability-blocked",
                "Capability declaration refused for $BOB: number is blocked.",
            )
        }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-capability-sent", any()) }
    }

    @Test
    fun `a radio refusal leaves the announced flag clear for the next retry`() = runTest {
        val harness = Harness(sender = RecordingDataSmsSender(result = false))
        harness.seedPeer(handshakeChannel = "DATA_SMS")

        val sent = harness.manager.announceIfNeeded(BOB, SUB_ID, NOW)

        assertThat(sent).isFalse()
        assertThat(harness.peerDao.peers[BOB]!!.mediaAnnounced).isFalse()
        verify(exactly = 1) {
            harness.recorder.recordEvent(
                "e2e-capability-send-failed",
                "Capability declaration refused by radio for $BOB on every attempted channel.",
            )
        }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-capability-sent", any()) }
    }

    @Test
    fun `announceNow re-announces even when already announced`() = runTest {
        val harness = Harness()
        harness.seedPeer(mediaAnnounced = true, handshakeChannel = "DATA_SMS")

        val sent = harness.manager.announceNow(BOB, SUB_ID, NOW)

        assertThat(sent).isTrue()
        assertThat(harness.sender.sent).hasSize(1)
    }

    @Test
    fun `announceNow still requires ENCRYPTED`() = runTest {
        val harness = Harness()
        harness.seedPeer(state = E2ePeerState.UNKNOWN)

        val sent = harness.manager.announceNow(BOB, SUB_ID, NOW)

        assertThat(sent).isFalse()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `onDeclarationReceived marks the peer capable and announces back`() = runTest {
        val harness = Harness()
        harness.seedPeer(handshakeChannel = "TEXT_SMS")

        harness.manager.onDeclarationReceived(BOB, SUB_ID, NOW)

        assertThat(harness.peerDao.peers[BOB]!!.mediaCapable).isTrue()
        assertThat(harness.peerDao.peers[BOB]!!.groupCapable).isFalse()
        assertThat(harness.peerDao.peers[BOB]!!.groupMediaCapable).isFalse()
        assertThat(harness.sender.sentTexts).hasSize(1)
        assertThat(harness.peerDao.peers[BOB]!!.mediaAnnounced).isTrue()
        assertThat(harness.peerDao.peers[BOB]!!.groupAnnounced).isTrue()
        assertThat(harness.peerDao.peers[BOB]!!.groupMediaAnnounced).isTrue()
        // The receipt itself is recorded once, by the handler —
        // the manager writes only the announce-back send.
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-capability-received", any()) }
        val sentMessage = slot<String>()
        verify(exactly = 1) {
            harness.recorder.recordEvent("e2e-capability-sent", capture(sentMessage))
        }
        assertThat(sentMessage.captured).contains("channel=TEXT_SMS")
    }

    @Test
    fun `onDeclarationReceived records a group-media declaration`() = runTest {
        val harness = Harness()
        harness.seedPeer(handshakeChannel = "TEXT_SMS")

        harness.manager.onDeclarationReceived(
            BOB,
            SUB_ID,
            NOW,
            mediaCapable = true,
            groupCapable = true,
            groupMediaCapable = true,
        )

        assertThat(harness.peerDao.peers[BOB]!!.mediaCapable).isTrue()
        assertThat(harness.peerDao.peers[BOB]!!.groupCapable).isTrue()
        assertThat(harness.peerDao.peers[BOB]!!.groupMediaCapable).isTrue()
        // The receipt itself is recorded once, by the handler —
        // the manager writes only the announce-back send.
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-capability-received", any()) }
    }

    @Test
    fun `onDeclarationReceived does not announce back when not ENCRYPTED`() = runTest {
        val harness = Harness()
        // PROBING: we sent the bundle, the session does not exist
        // yet — the declaration is recorded, but the announce-back
        // waits for the session (the pre-key arrival announces).
        harness.seedPeer(state = E2ePeerState.PROBING, handshakeChannel = "DATA_SMS")

        harness.manager.onDeclarationReceived(BOB, SUB_ID, NOW)

        assertThat(harness.peerDao.peers[BOB]!!.mediaCapable).isTrue()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `onDeclarationReceived does not announce back when already announced`() = runTest {
        val harness = Harness()
        harness.seedPeer(
            mediaAnnounced = true,
            groupAnnounced = true,
            groupMediaAnnounced = true,
            handshakeChannel = "DATA_SMS",
        )

        harness.manager.onDeclarationReceived(BOB, SUB_ID, NOW)

        assertThat(harness.peerDao.peers[BOB]!!.mediaCapable).isTrue()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `onDeclarationReceived creates the peer row when none exists`() = runTest {
        val harness = Harness()
        // No peer row: the declaration is the first contact.
        // The announce-back is gated on ENCRYPTED, so nothing
        // goes out — but the capability is recorded.

        harness.manager.onDeclarationReceived(BOB, SUB_ID, NOW)

        assertThat(harness.peerDao.peers[BOB]).isNotNull()
        assertThat(harness.peerDao.peers[BOB]!!.mediaCapable).isTrue()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
    }
}
