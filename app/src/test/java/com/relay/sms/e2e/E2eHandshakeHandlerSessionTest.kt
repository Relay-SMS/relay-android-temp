/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import com.google.common.truth.Truth.assertThat
import com.relay.sms.data.local.db.entity.E2eNoticeKind
import com.relay.sms.data.local.db.entity.E2ePeerEntity
import com.relay.sms.data.local.db.entity.E2ePeerState
import com.relay.sms.data.local.db.entity.MessageEntity
import com.relay.sms.domain.model.MessageDirection
import com.relay.sms.domain.model.MessageType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * The handshake handler, session half (design §2.3–§2.4, §4.4):
 * bundle receipt, session establishment, first-reply-wins, key
 * changes, the kept ciphertext retry, capability declaration,
 * and the inbound-path diagnostics.
 *
 * Same harness and fake parties as [E2eHandshakeHandlerTest]
 * (see [E2eHandshakeHandlerHarness], split out in round 59):
 * two [FakeE2eCrypto] parties ("alice" = us, "bob" = the peer)
 * with a third ("carol") for key changes; real stores over
 * in-memory fake DAOs.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class E2eHandshakeHandlerSessionTest {

    // ──────────────────────────── Bundle → ENCRYPTED ────────────────────────────

    @Test
    fun `a bundle creates the session and flips the peer to ENCRYPTED`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        val handled = harness.handler.handleDataSms(
            HANDLER_BOB,
            harness.bundleFrom(harness.bobCrypto),
            null,
            HANDLER_NOW,
        )

        assertThat(handled).isTrue()
        val peer = harness.peerDao.peers[HANDLER_BOB]
        assertThat(peer?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        assertThat(peer?.theirCurveKey).isNotNull()
        // A current session exists, and it can encrypt to bob.
        val sessionId = harness.sessions.currentSessionId(HANDLER_BOB)
        assertThat(sessionId).isNotNull()
        val encrypted = harness.aliceCrypto.encrypt(sessionId!!, "hi bob".toByteArray(Charsets.UTF_8))
        assertThat(encrypted.type).isEqualTo(OlmMessageType.PRE_KEY)
        // The one-time ACTIVATED notice, in bob's thread.
        assertThat(harness.noticeDao.notices).hasSize(1)
        assertThat(harness.noticeDao.notices.single().conversationId).isEqualTo(HANDLER_CONV_ID)
        assertThat(harness.noticeDao.notices.single().kind).isEqualTo(E2eNoticeKind.ACTIVATED)
    }

    @Test
    fun `first reply wins - a duplicate bundle is ignored`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        val bundle = harness.bundleFrom(harness.bobCrypto)
        harness.handler.handleDataSms(HANDLER_BOB, bundle, null, HANDLER_NOW)
        val firstSessionId = harness.sessions.currentSessionId(HANDLER_BOB)

        val handled = harness.handler.handleDataSms(HANDLER_BOB, bundle, null, HANDLER_NOW)

        assertThat(handled).isTrue()
        // No second session, no second notice, same current session.
        assertThat(harness.sessionDao.sessions).hasSize(1)
        assertThat(harness.sessions.currentSessionId(HANDLER_BOB)).isEqualTo(firstSessionId)
        assertThat(harness.noticeDao.notices).hasSize(1)
    }

    @Test
    fun `a bundle after a deactivation records a second ACTIVATED notice`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        // First establishment: one ACTIVATED notice.
        harness.handler.handleDataSms(HANDLER_BOB, harness.bundleFrom(harness.bobCrypto), null, HANDLER_NOW)
        assertThat(harness.noticeDao.notices.filter { it.kind == E2eNoticeKind.ACTIVATED })
            .hasSize(1)
        // The peer-left fallback tore the session down (state + notice).
        harness.peers.setState(HANDLER_BOB, E2ePeerState.UNKNOWN, HANDLER_NOW + 1000)
        E2eNoticeStore(harness.noticeDao)
            .record(HANDLER_CONV_ID, E2eNoticeKind.DEACTIVATED, HANDLER_NOW + 1000)
        // The old session is gone (round 46: sessions stored under the
        // pre-canonical key are unreachable), so the re-probe's bundle
        // is NOT a duplicate — it re-establishes. Handing a key out
        // empties the pool (vodozemac marks the whole unpublished batch
        // published), so top it up before taking a second key.
        harness.sessionDao.deleteByAddress(HANDLER_BOB)
        harness.bobCrypto.generateOneTimeKeys()

        harness.handler.handleDataSms(HANDLER_BOB, harness.bundleFrom(harness.bobCrypto), null, HANDLER_NOW + 2000)

        assertThat(harness.peerDao.peers[HANDLER_BOB]?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        assertThat(harness.noticeDao.notices.filter { it.kind == E2eNoticeKind.ACTIVATED })
            .hasSize(2)
        assertThat(harness.noticeDao.notices.map { it.kind }).containsExactly(
            E2eNoticeKind.ACTIVATED,
            E2eNoticeKind.DEACTIVATED,
            E2eNoticeKind.ACTIVATED,
        ).inOrder()
    }

    @Test
    fun `a bundle with no conversation still encrypts, without a notice`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        coEvery { harness.conversationDao.findByAddressesCsv(any()) } returns null

        val handled = harness.handler.handleDataSms(
            HANDLER_BOB,
            harness.bundleFrom(harness.bobCrypto),
            null,
            HANDLER_NOW,
        )

        assertThat(handled).isTrue()
        assertThat(harness.peerDao.peers[HANDLER_BOB]?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        assertThat(harness.noticeDao.notices).isEmpty()
    }

    @Test
    fun `a non-handshake packet is dropped silently`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        val handled = harness.handler.handleDataSms(HANDLER_BOB, "not a handshake".toByteArray(), null, HANDLER_NOW)

        assertThat(handled).isFalse()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.peerDao.peers).isEmpty()
    }

    // ──────────────────────────── Key change ────────────────────────────

    @Test
    fun `a probe with different keys clears verified and records KEY_CHANGED_VERIFIED`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        harness.handler.handleDataSms(HANDLER_BOB, harness.bundleFrom(harness.bobCrypto), null, HANDLER_NOW)
        harness.peers.setVerified(HANDLER_BOB, true, HANDLER_NOW)
        harness.noticeDao.notices.clear()

        harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.carolCrypto), null, HANDLER_NOW)

        val peer = harness.peerDao.peers[HANDLER_BOB]
        assertThat(peer?.verified).isFalse()
        // The state stays ENCRYPTED: messages keep flowing (TOFU + notice).
        assertThat(peer?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        // Carol's keys are now on file, and the notice names the change.
        val b64 = Base64.getEncoder()
        assertThat(peer?.theirCurveKey).isEqualTo(
            b64.encodeToString(harness.carolCrypto.identityKeys().curve25519),
        )
        assertThat(harness.noticeDao.notices).hasSize(1)
        assertThat(harness.noticeDao.notices.single().kind)
            .isEqualTo(E2eNoticeKind.KEY_CHANGED_VERIFIED)
    }

    @Test
    fun `a key change also notifies the group session manager`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        harness.handler.handleDataSms(HANDLER_BOB, harness.bundleFrom(harness.bobCrypto), null, HANDLER_NOW)
        harness.peers.setVerified(HANDLER_BOB, true, HANDLER_NOW)

        harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.carolCrypto), null, HANDLER_NOW)

        // The group half of the key change: GROUP_KEY_CHANGED
        // notices in every group where the member held a sender
        // key (the manager's own tests cover the notice itself).
        coVerify { harness.groupSessions.onPeerKeyChanged(HANDLER_BOB, true, HANDLER_NOW) }
    }

    @Test
    fun `a bundle with different keys re-establishes and keeps the old session as fallback`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        harness.handler.handleDataSms(HANDLER_BOB, harness.bundleFrom(harness.bobCrypto), null, HANDLER_NOW)
        val oldSessionId = harness.sessions.currentSessionId(HANDLER_BOB)

        harness.handler.handleDataSms(HANDLER_BOB, harness.bundleFrom(harness.carolCrypto), null, HANDLER_NOW)

        // Two sessions: the new one current, the old one a fallback.
        assertThat(harness.sessionDao.sessions).hasSize(2)
        val current = harness.sessions.currentSessionId(HANDLER_BOB)
        assertThat(current).isNotEqualTo(oldSessionId)
        assertThat(harness.sessionDao.sessionsForPeer(HANDLER_BOB).map { it.sessionId })
            .contains(oldSessionId)
        assertThat(harness.peerDao.peers[HANDLER_BOB]?.state).isEqualTo(E2ePeerState.ENCRYPTED)
        // Unverified before, so the plain KEY_CHANGED notice — and no second
        // ACTIVATED: the thread already shows the encryption as active (no
        // DEACTIVATED since the first ACTIVATED), so recordActivated skips.
        // (The notice list is NOT cleared between bundles: the store dedupes
        // against the stored ACTIVATED record, and clearing it would fake a
        // duplicate.)
        assertThat(harness.noticeDao.notices.filter { it.kind == E2eNoticeKind.KEY_CHANGED })
            .hasSize(1)
        assertThat(harness.noticeDao.notices.filter { it.kind == E2eNoticeKind.ACTIVATED })
            .hasSize(1)
    }

    // ──────────────────────────── Kept ciphertext retry ────────────────────────────

    @Test
    fun `a bundle retries kept ciphertext and restores the message`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        // Bob's pre-key message to alice, kept because an earlier
        // decrypt attempt failed (the row is seeded directly — the
        // retry plumbing, not the original failure, is under test).
        val aliceOtk = harness.aliceCrypto.nextOneTimeKey()!!
        val bobSession = harness.bobCrypto.createOutboundSession(
            harness.aliceCrypto.identityKeys().curve25519,
            aliceOtk,
        )
        val encrypted = harness.bobCrypto.encrypt(bobSession, "kept secret".toByteArray(Charsets.UTF_8))
        val base64 = Base64.getEncoder().encodeToString(
            E2eWireFormat.encodePayload(encrypted, E2eWireFormat.PayloadType.TEXT),
        )
        val kept = MessageEntity(
            id = 99L,
            conversationId = HANDLER_CONV_ID,
            telephonyUri = null,
            address = HANDLER_BOB,
            body = "",
            type = MessageType.SMS,
            direction = MessageDirection.INCOMING,
            date = HANDLER_NOW,
            dateSent = null,
            encrypted = true,
            decryptFailed = true,
            ciphertext = base64,
        )
        coEvery { harness.messageDao.findDecryptFailed(HANDLER_BOB) } returns listOf(kept)
        val updated = slot<MessageEntity>()
        coEvery { harness.messageDao.update(capture(updated)) } just Runs

        harness.handler.handleDataSms(HANDLER_BOB, harness.bundleFrom(harness.bobCrypto), null, HANDLER_NOW)

        // The placeholder became the readable message.
        assertThat(updated.isCaptured).isTrue()
        assertThat(updated.captured.body).isEqualTo("kept secret")
        assertThat(updated.captured.decryptFailed).isFalse()
        assertThat(updated.captured.ciphertext).isNull()
        coVerify(exactly = 1) { harness.messageDao.refreshConversationPreview(HANDLER_CONV_ID) }
    }

    @Test
    fun `kept ciphertext that still cannot decrypt stays a placeholder`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        val kept = MessageEntity(
            id = 99L,
            conversationId = HANDLER_CONV_ID,
            telephonyUri = null,
            address = HANDLER_BOB,
            body = "",
            type = MessageType.SMS,
            direction = MessageDirection.INCOMING,
            date = HANDLER_NOW,
            dateSent = null,
            encrypted = true,
            decryptFailed = true,
            ciphertext = Base64.getEncoder().encodeToString("garbage, not a payload".toByteArray()),
        )
        coEvery { harness.messageDao.findDecryptFailed(HANDLER_BOB) } returns listOf(kept)

        harness.handler.handleDataSms(HANDLER_BOB, harness.bundleFrom(harness.bobCrypto), null, HANDLER_NOW)

        coVerify(exactly = 0) { harness.messageDao.update(any()) }
        coVerify(exactly = 0) { harness.messageDao.refreshConversationPreview(any()) }
    }

    // ──────────────── Round 48/49: capability declaration ────────────────

    @Test
    fun `a capability packet by data SMS is handled and delegated, with no bundle reply`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        val handled = harness.handler.handleDataSms(
            HANDLER_BOB,
            E2eHandshake.encodeCapabilityDeclaration(),
            3,
            HANDLER_NOW,
        )

        assertThat(handled).isTrue()
        coVerify { harness.mediaCapability.onDeclarationReceived(HANDLER_BOB, 3, HANDLER_NOW, true, true, true) }
        // A declaration earns no bundle reply — it is not a probe.
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `a capability packet by text SMS is handled and delegated`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        val text = E2eHandshakeSms.encode(E2eHandshake.encodeCapabilityDeclaration())

        val handled = harness.handler.handleSms(HANDLER_BOB, text, null, HANDLER_NOW)

        assertThat(handled).isTrue()
        coVerify { harness.mediaCapability.onDeclarationReceived(HANDLER_BOB, null, HANDLER_NOW, true, true, true) }
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `a bundle receipt records the channel and announces our capability`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        harness.handler.handleDataSms(HANDLER_BOB, harness.bundleFrom(harness.bobCrypto), null, HANDLER_NOW)

        // The bundle arrived on data SMS, so our probe reached them
        // on it: the channel is proven for our declaration.
        assertThat(harness.peerDao.peers[HANDLER_BOB]!!.handshakeChannel).isEqualTo("DATA_SMS")
        coVerify { harness.mediaCapability.announceNow(HANDLER_BOB, null, HANDLER_NOW) }
    }

    @Test
    fun `a probe receipt records the channel while not yet ENCRYPTED`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), null, HANDLER_NOW)

        assertThat(harness.peerDao.peers[HANDLER_BOB]!!.handshakeChannel).isEqualTo("DATA_SMS")
    }

    @Test
    fun `a probe from an already-ENCRYPTED peer does not overwrite the recorded channel`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        harness.peerDao.peers[HANDLER_BOB] = E2ePeerEntity(
            address = HANDLER_BOB,
            state = E2ePeerState.ENCRYPTED,
            handshakeChannel = "TEXT_SMS",
            updatedAt = HANDLER_NOW,
        )

        harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), null, HANDLER_NOW)

        // The proven channel survives a later exchange on another
        // channel (a key-change probe is not proof the new
        // channel delivers).
        assertThat(harness.peerDao.peers[HANDLER_BOB]!!.handshakeChannel).isEqualTo("TEXT_SMS")
    }

    // ──────────────── Inbound-path diagnostics ────────────────
    // Every inbound outcome leaves a separable line in the error
    // log, so the next handshake shows the underlying picture:
    // arrival (receiver) → decode → blocked → probe / bundle /
    // capability → session outcome.

    @Test
    fun `a bundle records bundle-received with session created and state flipped`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        val handled = harness.handler.handleDataSms(
            HANDLER_BOB,
            harness.bundleFrom(harness.bobCrypto),
            null,
            HANDLER_NOW,
        )

        assertThat(handled).isTrue()
        val message = slot<String>()
        verify(exactly = 1) {
            harness.recorder.recordEvent("e2e-bundle-received", capture(message))
        }
        assertThat(message.captured).contains(HANDLER_BOB)
        assertThat(message.captured).contains("session created: yes")
        assertThat(message.captured).contains("state flipped to ENCRYPTED: true")
        assertThat(message.captured).contains("duplicate: no")
    }

    @Test
    fun `a duplicate bundle records bundle-received as a duplicate ignore`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        val bundle = harness.bundleFrom(harness.bobCrypto)
        harness.handler.handleDataSms(HANDLER_BOB, bundle, null, HANDLER_NOW)

        val handled = harness.handler.handleDataSms(HANDLER_BOB, bundle, null, HANDLER_NOW)

        assertThat(handled).isTrue()
        val messages = mutableListOf<String>()
        verify(exactly = 2) {
            harness.recorder.recordEvent("e2e-bundle-received", capture(messages))
        }
        assertThat(messages).hasSize(2)
        assertThat(messages.last()).contains("duplicate ignored")
        assertThat(messages.last()).contains("session created: no")
        assertThat(messages.last()).contains("state flipped to ENCRYPTED: no")
    }

    @Test
    fun `a bad-magic handshake SMS records the decode failure`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        val badMagic = ByteArray(E2eHandshake.PROBE_SIZE)
        badMagic[0] = 'X'.code.toByte()
        val text = E2eHandshakeSms.encode(badMagic)

        val handled = harness.handler.handleSms(HANDLER_BOB, text, null, HANDLER_NOW)

        assertThat(handled).isFalse()
        assertThat(harness.peerDao.peers).isEmpty()
        verify(exactly = 1) {
            harness.recorder.recordEvent(
                "e2e-decode-failed",
                match {
                    it.contains("bad-magic") && it.contains("70 bytes") && it.contains("type 0x00")
                },
            )
        }
    }

    @Test
    fun `a capability packet records capability-received`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        val handled = harness.handler.handleDataSms(
            HANDLER_BOB,
            E2eHandshake.encodeCapabilityDeclaration(),
            3,
            HANDLER_NOW,
        )

        assertThat(handled).isTrue()
        verify(exactly = 1) {
            harness.recorder.recordEvent(
                "e2e-capability-received",
                match { it.contains(HANDLER_BOB) && it.contains("media=true") },
            )
        }
    }

    @Test
    fun `a packet from a blocked sender records blocked-sender and nothing else`() = runTest {
        val harness = E2eHandshakeHandlerHarness(blocked = setOf(HANDLER_BOB))

        val handled = harness.handler.handleDataSms(
            HANDLER_BOB,
            harness.probeFrom(harness.bobCrypto),
            null,
            HANDLER_NOW,
        )

        assertThat(handled).isFalse()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.peerDao.peers).isEmpty()
        verify(exactly = 1) {
            harness.recorder.recordEvent(
                "e2e-blocked-sender",
                match { it.contains(HANDLER_BOB) },
            )
        }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-probe-received", any()) }
    }

    @Test
    fun `a session-creation failure records session-create-failed and no ENCRYPTED flip`() = runTest {
        val failingCrypto = mockk<E2eCrypto>()
        every { failingCrypto.createOutboundSession(any(), any()) } throws
            RuntimeException("no session")
        val harness = E2eHandshakeHandlerHarness(cryptoOverride = failingCrypto)

        val handled = harness.handler.handleDataSms(
            HANDLER_BOB,
            harness.bundleFrom(harness.bobCrypto),
            null,
            HANDLER_NOW,
        )

        assertThat(handled).isFalse()
        assertThat(harness.peers.state(HANDLER_BOB)).isNotEqualTo(E2ePeerState.ENCRYPTED)
        verify(exactly = 1) {
            harness.recorder.recordEvent(
                "e2e-session-create-failed",
                match { it.contains(HANDLER_BOB) && it.contains("RuntimeException") },
            )
        }
        verify(exactly = 1) {
            harness.recorder.recordEvent(
                "e2e-bundle-received",
                match { it.contains("session created: no") },
            )
        }
    }

    @Test
    fun `an account-load failure records account-load-failed instead of propagating`() = runTest {
        val failingAccount = mockk<E2eAccountManager>()
        coEvery { failingAccount.ensureAccount() } throws RuntimeException("no account")
        val harness = E2eHandshakeHandlerHarness(accountManagerOverride = lazyOf(failingAccount))

        val handled = harness.handler.handleDataSms(
            HANDLER_BOB,
            harness.bundleFrom(harness.bobCrypto),
            null,
            HANDLER_NOW,
        )

        assertThat(handled).isFalse()
        assertThat(harness.peers.state(HANDLER_BOB)).isNotEqualTo(E2ePeerState.ENCRYPTED)
        verify(exactly = 1) {
            harness.recorder.recordEvent(
                "e2e-account-load-failed",
                match { it.contains(HANDLER_BOB) && it.contains("RuntimeException") },
            )
        }
        verify(exactly = 1) {
            harness.recorder.recordEvent(
                "e2e-bundle-received",
                match { it.contains("session created: no") },
            )
        }
    }
}
