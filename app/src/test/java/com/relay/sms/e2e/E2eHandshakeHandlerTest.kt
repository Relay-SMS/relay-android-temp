/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import com.google.common.truth.Truth.assertThat
import com.relay.sms.data.local.db.entity.E2ePeerState
import com.relay.sms.domain.purge.MS_PER_DAY
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * The handshake handler (design §2.3–§2.4, §4.4): probes, bundle
 * replies, the bundle rate limit, the manual-probe bypass, and the
 * handshake over regular SMS (round 45) — plus the round-58
 * automatic-fallback arming.
 *
 * Two [FakeE2eCrypto] parties ("alice" = us, "bob" = the peer) with a
 * third ("carol") for key changes. The stores are the real ones over
 * in-memory fake DAOs — the handler's state transitions are the thing
 * under test, and they must compose with the real repository/store
 * behavior. The harness lives in [E2eHandshakeHandlerHarness]
 * (round 59 split); the session half of this suite is
 * [E2eHandshakeHandlerSessionTest].
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class E2eHandshakeHandlerTest {

    // ──────────────────────────── Probe → bundle reply ────────────────────────────

    @Test
    fun `a probe records the keys and earns a bundle reply`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        val handled = harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), 3, HANDLER_NOW)

        assertThat(handled).isTrue()
        // One bundle reply, on the handshake port, carrying OUR keys.
        assertThat(harness.sender.sent).hasSize(1)
        // A data-SMS probe earns a data-SMS reply — never a text.
        assertThat(harness.sender.sentTexts).isEmpty()
        val reply = harness.sender.sent.single()
        assertThat(reply.address).isEqualTo(HANDLER_BOB)
        assertThat(reply.subId).isEqualTo(3)
        assertThat(reply.port).isEqualTo(E2eHandshake.E2E_DATA_SMS_PORT)
        val bundle = E2eHandshake.decode(reply.data) as E2eHandshake.HandshakePacket.Bundle
        assertThat(bundle.curveKey).isEqualTo(harness.aliceCrypto.identityKeys().curve25519)
        assertThat(bundle.edKey).isEqualTo(harness.aliceCrypto.identityKeys().ed25519)
        // The peer row: PROBING, bob's keys on file, reply timestamped.
        val peer = harness.peerDao.peers[HANDLER_BOB]
        assertThat(peer?.state).isEqualTo(E2ePeerState.PROBING)
        val b64 = Base64.getEncoder()
        assertThat(peer?.theirCurveKey).isEqualTo(
            b64.encodeToString(harness.bobCrypto.identityKeys().curve25519),
        )
        assertThat(peer?.theirEdKey).isEqualTo(
            b64.encodeToString(harness.bobCrypto.identityKeys().ed25519),
        )
        assertThat(peer?.lastBundleReplyAt).isEqualTo(HANDLER_NOW)
    }

    @Test
    fun `a bundle reply arms the automatic fallback check`() = runTest {
        val autoFallback = mockk<E2eAutoFallback>(relaxed = true)
        val harness = E2eHandshakeHandlerHarness(autoFallback = autoFallback)

        val handled = harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), 3, HANDLER_NOW)

        assertThat(handled).isTrue()
        assertThat(harness.sender.sent).hasSize(1)
        verify(exactly = 1) { autoFallback.schedule(HANDLER_BOB) }
    }

    @Test
    fun `a failed bundle reply does not arm the automatic fallback check`() = runTest {
        val autoFallback = mockk<E2eAutoFallback>(relaxed = true)
        val harness = E2eHandshakeHandlerHarness(autoFallback = autoFallback)
        harness.sender.result = false

        val handled = harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), 3, HANDLER_NOW)

        assertThat(handled).isFalse()
        verify(exactly = 0) { autoFallback.schedule(any()) }
    }

    @Test
    fun `a second probe the same day earns no second bundle`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), null, HANDLER_NOW)

        val handled = harness.handler.handleDataSms(
            HANDLER_BOB,
            harness.probeFrom(harness.bobCrypto),
            null,
            HANDLER_NOW,
        )

        assertThat(handled).isTrue()
        assertThat(harness.sender.sent).hasSize(1)
    }

    @Test
    fun `a probe the next day earns a bundle again`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), null, HANDLER_NOW)

        harness.handler.handleDataSms(
            HANDLER_BOB,
            harness.probeFrom(harness.bobCrypto),
            null,
            HANDLER_NOW + MS_PER_DAY + 1,
        )

        assertThat(harness.sender.sent).hasSize(2)
    }

    @Test
    fun `a failed bundle send does not consume the day's reply`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        harness.sender.result = false

        val handled = harness.handler.handleDataSms(
            HANDLER_BOB,
            harness.probeFrom(harness.bobCrypto),
            null,
            HANDLER_NOW,
        )

        assertThat(handled).isFalse()
        assertThat(harness.peerDao.peers[HANDLER_BOB]?.lastBundleReplyAt).isNull()
        // The keys are still recorded: only the reply is gated.
        assertThat(harness.peerDao.peers[HANDLER_BOB]?.theirCurveKey).isNotNull()
        // And a retry the same day may still send.
        harness.sender.result = true
        harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), null, HANDLER_NOW)
        assertThat(harness.sender.sent).hasSize(2)
    }

    // ──────────────────── Round 47: manual-probe bypass ────────────────────
    // A manual probe (flag bit, bit 7 of the type byte) bypasses the
    // receiver's one-bundle-per-day rate limit: the sender's user
    // tapped Retry, and the tap is pointless if the reply is still
    // gated. Automatic probes are never marked manual, so the limit
    // still applies to them — and a successful manual bundle send
    // still consumes the limit, so the next automatic probe is
    // gated. Both channels (data SMS + `RLYH:` text) honor it.

    @Test
    fun `a manual probe bypasses the bundle rate limit`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), null, HANDLER_NOW)

        val handled = harness.handler.handleDataSms(
            HANDLER_BOB,
            harness.probeFrom(harness.bobCrypto, manual = true),
            null,
            HANDLER_NOW,
        )

        assertThat(handled).isTrue()
        // A second bundle went out the same day — the manual flag
        // bypassed the limit.
        assertThat(harness.sender.sent).hasSize(2)
        verify(exactly = 2) { harness.recorder.recordEvent("e2e-bundle-sent", any()) }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `an automatic probe after a manual bundle is still rate-limited`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), null, HANDLER_NOW)
        harness.handler.handleDataSms(
            HANDLER_BOB,
            harness.probeFrom(harness.bobCrypto, manual = true),
            null,
            HANDLER_NOW,
        )

        val handled = harness.handler.handleDataSms(
            HANDLER_BOB,
            harness.probeFrom(harness.bobCrypto),
            null,
            HANDLER_NOW,
        )

        assertThat(handled).isTrue()
        // The manual bundle consumed the day's reply: the automatic
        // probe earns nothing.
        assertThat(harness.sender.sent).hasSize(2)
        assertThat(harness.peerDao.peers[HANDLER_BOB]?.lastBundleReplyAt).isEqualTo(HANDLER_NOW)
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `a manual SMS probe bypasses the bundle rate limit`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        harness.handler.handleSms(HANDLER_BOB, harness.probeSmsFrom(harness.bobCrypto), null, HANDLER_NOW)

        val handled = harness.handler.handleSms(
            HANDLER_BOB,
            harness.probeSmsFrom(harness.bobCrypto, manual = true),
            null,
            HANDLER_NOW,
        )

        assertThat(handled).isTrue()
        // The second bundle goes out by text, like the first — the
        // reply follows the probe's arrival channel.
        assertThat(harness.sender.sentTexts).hasSize(2)
        assertThat(harness.sender.sent).isEmpty()
        verify(exactly = 2) { harness.recorder.recordEvent("e2e-bundle-sent", any()) }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `a manual probe from a peer with no prior bundle still works`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        val handled = harness.handler.handleDataSms(
            HANDLER_BOB,
            harness.probeFrom(harness.bobCrypto, manual = true),
            null,
            HANDLER_NOW,
        )

        assertThat(handled).isTrue()
        assertThat(harness.sender.sent).hasSize(1)
        val peer = harness.peerDao.peers[HANDLER_BOB]
        assertThat(peer?.state).isEqualTo(E2ePeerState.PROBING)
        assertThat(peer?.theirCurveKey).isNotNull()
        assertThat(peer?.lastBundleReplyAt).isEqualTo(HANDLER_NOW)
    }

    // ──────────────────── Round 42: inbound-path diagnostics ────────────────────
    // The inbound path is Timber-only, and Timber is a no-op in
    // release builds — so the handler records the handshake events
    // in the error log (no keys, no payload — the address names
    // the sender, which is the whole question when a handshake
    // fails). The three tags make every outcome separable: no
    // `e2e-probe-received` means the probe never arrived;
    // `e2e-probe-received` + `e2e-bundle-sent` means inbound +
    // reply work; `e2e-bundle-rate-limited` means the path worked
    // recently (a reply already went out today).

    @Test
    fun `a probe records probe-received and bundle-sent`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), null, HANDLER_NOW)

        val probeMessage = slot<String>()
        verify(exactly = 1) {
            harness.recorder.recordEvent("e2e-probe-received", capture(probeMessage))
        }
        assertThat(probeMessage.captured).contains(HANDLER_BOB)
        val bundleMessage = slot<String>()
        verify(exactly = 1) {
            harness.recorder.recordEvent("e2e-bundle-sent", capture(bundleMessage))
        }
        assertThat(bundleMessage.captured).contains(HANDLER_BOB)
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `a rate-limited probe records rate-limited and no second bundle-sent`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), null, HANDLER_NOW)

        harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), null, HANDLER_NOW)

        verify(exactly = 2) { harness.recorder.recordEvent("e2e-probe-received", any()) }
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-bundle-sent", any()) }
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `a failed bundle send records probe-received but no bundle-sent`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        harness.sender.result = false

        harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), null, HANDLER_NOW)

        verify(exactly = 1) { harness.recorder.recordEvent("e2e-probe-received", any()) }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-bundle-sent", any()) }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `a dropped packet records the decode failure with its reason`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        val handled = harness.handler.handleDataSms(HANDLER_BOB, "not a handshake".toByteArray(), null, HANDLER_NOW)

        assertThat(handled).isFalse()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.peerDao.peers).isEmpty()
        verify(exactly = 1) {
            harness.recorder.recordEvent(
                "e2e-decode-failed",
                match {
                    it.contains("bad-magic") && it.contains("15 bytes") && it.contains("type 0x20")
                },
            )
        }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-probe-received", any()) }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-bundle-received", any()) }
    }

    @Test
    fun `the bundle reply send passes the BUNDLE kind`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        harness.handler.handleDataSms(HANDLER_BOB, harness.probeFrom(harness.bobCrypto), null, HANDLER_NOW)

        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.sender.sent.single().kind).isEqualTo(DataSmsKind.BUNDLE)
    }

    // ──────────────────── Handshake over regular SMS (round 45) ────────────────────

    @Test
    fun `an SMS probe earns a bundle reply by text, not by data SMS`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        val handled = harness.handler.handleSms(HANDLER_BOB, harness.probeSmsFrom(harness.bobCrypto), 3, HANDLER_NOW)

        assertThat(handled).isTrue()
        // One bundle reply, as a regular SMS carrying OUR keys.
        assertThat(harness.sender.sentTexts).hasSize(1)
        assertThat(harness.sender.sent).isEmpty()
        val reply = harness.sender.sentTexts.single()
        assertThat(reply.address).isEqualTo(HANDLER_BOB)
        assertThat(reply.subId).isEqualTo(3)
        assertThat(reply.kind).isEqualTo(DataSmsKind.BUNDLE)
        assertThat(reply.text).startsWith("RLYH:")
        val bundle = E2eHandshake.decode(E2eHandshakeSms.parse(reply.text)!!)
            as E2eHandshake.HandshakePacket.Bundle
        assertThat(bundle.curveKey).isEqualTo(harness.aliceCrypto.identityKeys().curve25519)
        assertThat(bundle.edKey).isEqualTo(harness.aliceCrypto.identityKeys().ed25519)
        // The peer row: PROBING, bob's keys on file, reply timestamped.
        val peer = harness.peerDao.peers[HANDLER_BOB]
        assertThat(peer?.state).isEqualTo(E2ePeerState.PROBING)
        assertThat(peer?.lastBundleReplyAt).isEqualTo(HANDLER_NOW)
        // Same diagnostic tags as the data path — the channel is not logged.
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-probe-received", any()) }
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-bundle-sent", any()) }
        verify(exactly = 0) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `an SMS bundle creates the session and flips the peer to ENCRYPTED`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        val handled = harness.handler.handleSms(
            HANDLER_BOB,
            harness.bundleSmsFrom(harness.bobCrypto),
            null,
            HANDLER_NOW,
        )

        assertThat(handled).isTrue()
        assertThat(harness.peers.state(HANDLER_BOB)).isEqualTo(E2ePeerState.ENCRYPTED)
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `a non-handshake text is ignored`() = runTest {
        val harness = E2eHandshakeHandlerHarness()

        val plain = harness.handler.handleSms(HANDLER_BOB, "hello", null, HANDLER_NOW)
        val fragment = harness.handler.handleSms(
            HANDLER_BOB,
            "RLY1:abc123:01/01:AQEB3q2+7w==",
            null,
            HANDLER_NOW,
        )
        val badMagic = harness.handler.handleSms(HANDLER_BOB, "RLYH:!!!", null, HANDLER_NOW)

        assertThat(plain).isFalse()
        assertThat(fragment).isFalse()
        assertThat(badMagic).isFalse()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
        assertThat(harness.peerDao.peers).isEmpty()
        verify(exactly = 0) { harness.recorder.recordEvent(any(), any()) }
    }

    @Test
    fun `a second SMS probe the same day earns no second bundle text`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        harness.handler.handleSms(HANDLER_BOB, harness.probeSmsFrom(harness.bobCrypto), null, HANDLER_NOW)

        val handled = harness.handler.handleSms(HANDLER_BOB, harness.probeSmsFrom(harness.bobCrypto), null, HANDLER_NOW)

        assertThat(handled).isTrue()
        assertThat(harness.sender.sentTexts).hasSize(1)
        assertThat(harness.sender.sent).isEmpty()
        verify(exactly = 1) { harness.recorder.recordEvent("e2e-bundle-rate-limited", any()) }
    }

    @Test
    fun `a failed text bundle send does not consume the day's reply`() = runTest {
        val harness = E2eHandshakeHandlerHarness()
        harness.sender.result = false

        val failed = harness.handler.handleSms(HANDLER_BOB, harness.probeSmsFrom(harness.bobCrypto), null, HANDLER_NOW)

        assertThat(failed).isFalse()
        assertThat(harness.peerDao.peers[HANDLER_BOB]?.lastBundleReplyAt).isNull()
        // The keys are still recorded: only the reply is gated.
        assertThat(harness.peerDao.peers[HANDLER_BOB]?.theirCurveKey).isNotNull()
        // And a retry the same day may still send (the fake records
        // the failed attempt too, so the retry is the second entry).
        harness.sender.result = true
        val retried = harness.handler.handleSms(HANDLER_BOB, harness.probeSmsFrom(harness.bobCrypto), null, HANDLER_NOW)

        assertThat(retried).isTrue()
        assertThat(harness.sender.sentTexts).hasSize(2)
        assertThat(harness.peerDao.peers[HANDLER_BOB]?.lastBundleReplyAt).isEqualTo(HANDLER_NOW)
    }
}
