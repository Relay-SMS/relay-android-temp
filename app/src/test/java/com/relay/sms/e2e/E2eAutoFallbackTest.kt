/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.relay.sms.data.local.db.entity.E2ePeerEntity
import com.relay.sms.data.local.db.entity.E2ePeerState
import com.relay.sms.domain.settings.AppSettings
import com.relay.sms.domain.settings.AppSettingsSource
import com.relay.sms.domain.settings.EncryptionSettings
import com.relay.sms.domain.settings.SendingSettings
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Gated automatic `RLYH:` fallback (design §2.4, round 58):
 * the pure gate ([E2eAutoFallback.evaluate]) and the send
 * ([E2eAutoFallback.maybeSend]).
 *
 * The gate's core promise — no evidence, no send, ever — is what
 * makes the gibberish objection moot: an automatic probe only
 * goes to a peer the phone has proof runs Relay.
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose `runTest` body returns a value.
 */
class E2eAutoFallbackTest {

    private companion object {
        const val ALICE = "+33612345678"
        const val NOW = 1_800_000_000_000L
        const val CHOSEN_SUB_ID = 7
        const val TWO_HOURS_MS = 2L * 3_600_000L
        const val FIVE_SECONDS_MS = 5L * 1_000L
        const val TWENTY_FIVE_HOURS_MS = 25L * 3_600_000L
    }

    private class SettingsWithSubId(
        private val subId: Int?,
        private val autoDiscover: Boolean = true,
    ) : AppSettingsSource {
        private val value = AppSettings(
            sending = SendingSettings(defaultSubId = subId),
            encryption = EncryptionSettings(autoDiscoverRelayContacts = autoDiscover),
        )
        override val flow: Flow<AppSettings> = flowOf(value)
        override val state: StateFlow<AppSettings> = MutableStateFlow(value)
        override suspend fun hydratedOrNull(): AppSettings = value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private class Harness(
        val peerDao: FakeE2ePeerDao = FakeE2ePeerDao(),
        val accountDao: FakeE2eAccountDao = FakeE2eAccountDao(),
        val sender: RecordingDataSmsSender = RecordingDataSmsSender(),
        val blocked: FakeBlockedRepo = FakeBlockedRepo(),
        val settings: AppSettingsSource = SettingsWithSubId(CHOSEN_SUB_ID),
        val crypto: FakeE2eCrypto = FakeE2eCrypto("alice"),
        val recorder: E2eFailureRecorder = mockk(relaxed = true),
    ) {
        val peers = E2ePeerRepository(peerDao)
        val accountManager = E2eAccountManager(accountDao, crypto, fakeKeyManager())
        val fallback = E2eAutoFallback(
            peers = peers,
            accountManager = lazyOf(accountManager),
            dataSmsSender = sender,
            settings = settings,
            blockedRepo = blocked,
            recorder = recorder,
            context = mockk<Context>(relaxed = true),
        )

        fun seedPeer(
            address: String = ALICE,
            state: E2ePeerState = E2ePeerState.PROBING,
            theirCurveKey: String? = null,
            theirEdKey: String? = null,
            lastProbeAt: Long? = null,
            lastBundleReplyAt: Long? = null,
            mediaCapable: Boolean = false,
            groupCapable: Boolean = false,
            groupMediaCapable: Boolean = false,
            handshakeChannel: String? = null,
            lastAutoFallbackAt: Long? = null,
        ) {
            peerDao.peers[address] = E2ePeerEntity(
                address = address,
                state = state,
                theirCurveKey = theirCurveKey,
                theirEdKey = theirEdKey,
                lastProbeAt = lastProbeAt,
                lastBundleReplyAt = lastBundleReplyAt,
                mediaCapable = mediaCapable,
                groupCapable = groupCapable,
                groupMediaCapable = groupMediaCapable,
                handshakeChannel = handshakeChannel,
                lastAutoFallbackAt = lastAutoFallbackAt,
                updatedAt = NOW,
            )
        }
    }

    private fun peer(
        state: E2ePeerState = E2ePeerState.PROBING,
        theirCurveKey: String? = null,
        theirEdKey: String? = null,
        lastProbeAt: Long? = null,
        lastBundleReplyAt: Long? = null,
        mediaCapable: Boolean = false,
        groupCapable: Boolean = false,
        groupMediaCapable: Boolean = false,
        handshakeChannel: String? = null,
        lastAutoFallbackAt: Long? = null,
    ) = E2ePeerEntity(
        address = ALICE,
        state = state,
        theirCurveKey = theirCurveKey,
        theirEdKey = theirEdKey,
        lastProbeAt = lastProbeAt,
        lastBundleReplyAt = lastBundleReplyAt,
        mediaCapable = mediaCapable,
        groupCapable = groupCapable,
        groupMediaCapable = groupMediaCapable,
        handshakeChannel = handshakeChannel,
        lastAutoFallbackAt = lastAutoFallbackAt,
        updatedAt = NOW,
    )

    // ──────────────────────────── Pure gate ────────────────────────────

    @Test
    fun `evidence plus stall fires`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(theirCurveKey = "curve", lastBundleReplyAt = NOW - TWO_HOURS_MS),
            address = ALICE,
            isBlocked = false,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Fire("bundle-received"))
    }

    @Test
    fun `no peer row never fires`() {
        val decision = E2eAutoFallback.evaluate(
            peer = null,
            address = ALICE,
            isBlocked = false,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Skip(SkipReason.NoEvidence))
    }

    @Test
    fun `peer row without evidence never fires`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(lastProbeAt = NOW - TWO_HOURS_MS),
            address = ALICE,
            isBlocked = false,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Skip(SkipReason.NoEvidence))
    }

    @Test
    fun `already encrypted never fires`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(
                state = E2ePeerState.ENCRYPTED,
                theirCurveKey = "curve",
                lastBundleReplyAt = NOW - TWO_HOURS_MS,
            ),
            address = ALICE,
            isBlocked = false,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Skip(SkipReason.AlreadyEncrypted))
    }

    @Test
    fun `just stalled does not fire`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(theirCurveKey = "curve", lastBundleReplyAt = NOW - FIVE_SECONDS_MS),
            address = ALICE,
            isBlocked = false,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Skip(SkipReason.NotStalledYet))
    }

    @Test
    fun `stalled exactly 10 seconds fires`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(
                theirCurveKey = "curve",
                lastBundleReplyAt = NOW - E2eAutoFallback.STALL_MS,
            ),
            address = ALICE,
            isBlocked = false,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Fire("bundle-received"))
    }

    @Test
    fun `keys-only evidence stalls from the last probe`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(theirCurveKey = "curve", lastProbeAt = NOW - TWO_HOURS_MS),
            address = ALICE,
            isBlocked = false,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Fire("bundle-received"))
    }

    @Test
    fun `capability evidence fires`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(mediaCapable = true, lastProbeAt = NOW - TWO_HOURS_MS),
            address = ALICE,
            isBlocked = false,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Fire("capability"))
    }

    @Test
    fun `probe-received evidence fires`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(lastBundleReplyAt = NOW - TWO_HOURS_MS),
            address = ALICE,
            isBlocked = false,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Fire("probe-received"))
    }

    @Test
    fun `handshake-channel evidence fires`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(handshakeChannel = "DATA_SMS", lastProbeAt = NOW - TWO_HOURS_MS),
            address = ALICE,
            isBlocked = false,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Fire("handshake-channel"))
    }

    @Test
    fun `evidence without an anchor does not fire`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(mediaCapable = true),
            address = ALICE,
            isBlocked = false,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Skip(SkipReason.NotStalledYet))
    }

    @Test
    fun `capped within 24 hours does not fire`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(
                theirCurveKey = "curve",
                lastBundleReplyAt = NOW - TWO_HOURS_MS,
                lastAutoFallbackAt = NOW - E2eAutoFallback.STALL_MS,
            ),
            address = ALICE,
            isBlocked = false,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Skip(SkipReason.Capped))
    }

    @Test
    fun `capped 25 hours ago fires again`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(
                theirCurveKey = "curve",
                lastBundleReplyAt = NOW - TWENTY_FIVE_HOURS_MS,
                lastAutoFallbackAt = NOW - TWENTY_FIVE_HOURS_MS,
            ),
            address = ALICE,
            isBlocked = false,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Fire("bundle-received"))
    }

    @Test
    fun `discovery off never fires`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(theirCurveKey = "curve", lastBundleReplyAt = NOW - TWO_HOURS_MS),
            address = ALICE,
            isBlocked = false,
            autoDiscover = false,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Skip(SkipReason.DiscoveryOff))
    }

    @Test
    fun `blocked never fires`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(theirCurveKey = "curve", lastBundleReplyAt = NOW - TWO_HOURS_MS),
            address = ALICE,
            isBlocked = true,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Skip(SkipReason.Blocked))
    }

    @Test
    fun `short code never fires`() {
        val decision = E2eAutoFallback.evaluate(
            peer = peer(theirCurveKey = "curve", lastBundleReplyAt = NOW - TWO_HOURS_MS),
            address = "12345",
            isBlocked = false,
            autoDiscover = true,
            now = NOW,
        )

        assertThat(decision).isEqualTo(Decision.Skip(SkipReason.InvalidAddress))
    }

    // ──────────────────────────── Send ────────────────────────────

    @Test
    fun `firing sends one RLYH text with the manual flag and records the timestamp`() = runTest {
        val harness = Harness()
        harness.seedPeer(theirCurveKey = "curve", lastBundleReplyAt = NOW - TWO_HOURS_MS)

        val result = harness.fallback.maybeSend(ALICE, now = NOW)

        assertThat(result).isEqualTo(AutoFallbackResult.Sent)
        // Regular SMS only — no data SMS, no probe-budget consumption.
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).hasSize(1)
        val sent = harness.sender.sentTexts.single()
        assertThat(sent.address).isEqualTo(ALICE)
        assertThat(sent.subId).isEqualTo(CHOSEN_SUB_ID)
        assertThat(sent.kind).isEqualTo(DataSmsKind.AUTO_FALLBACK)
        assertThat(sent.text).startsWith(E2eHandshakeSms.MAGIC)
        val packet = E2eHandshake.decode(E2eHandshakeSms.parse(sent.text)!!)
        assertThat(packet).isInstanceOf(E2eHandshake.HandshakePacket.Probe::class.java)
        val probe = packet as E2eHandshake.HandshakePacket.Probe
        assertThat(probe.manual).isTrue()
        assertThat(probe.curveKey).isEqualTo(harness.crypto.identityKeys().curve25519)
        assertThat(probe.edKey).isEqualTo(harness.crypto.identityKeys().ed25519)
        // The cap timestamp is recorded, and nothing else moves.
        val peer = harness.peerDao.peers[ALICE]
        assertThat(peer?.lastAutoFallbackAt).isEqualTo(NOW)
        assertThat(peer?.probeAttempts).isEqualTo(0)
        assertThat(peer?.state).isEqualTo(E2ePeerState.PROBING)
        verify(exactly = 1) {
            harness.recorder.recordEvent(E2eAutoFallback.SENT_TAG, any())
        }
    }

    @Test
    fun `no evidence sends nothing and records nothing`() = runTest {
        val harness = Harness()
        harness.seedPeer(lastProbeAt = NOW - TWO_HOURS_MS)

        val result = harness.fallback.maybeSend(ALICE, now = NOW)

        assertThat(result).isEqualTo(AutoFallbackResult.Skipped(SkipReason.NoEvidence))
        assertThat(harness.sender.sentTexts).isEmpty()
        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.peerDao.peers[ALICE]?.lastAutoFallbackAt).isNull()
        verify(exactly = 0) { harness.recorder.recordEvent(any(), any()) }
    }

    @Test
    fun `capped sends nothing`() = runTest {
        val harness = Harness()
        harness.seedPeer(
            theirCurveKey = "curve",
            lastBundleReplyAt = NOW - TWO_HOURS_MS,
            lastAutoFallbackAt = NOW - E2eAutoFallback.STALL_MS,
        )

        val result = harness.fallback.maybeSend(ALICE, now = NOW)

        assertThat(result).isEqualTo(AutoFallbackResult.Skipped(SkipReason.Capped))
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `send failure records no timestamp`() = runTest {
        val harness = Harness(sender = RecordingDataSmsSender(result = false))
        harness.seedPeer(theirCurveKey = "curve", lastBundleReplyAt = NOW - TWO_HOURS_MS)

        val result = harness.fallback.maybeSend(ALICE, now = NOW)

        assertThat(result).isEqualTo(AutoFallbackResult.SendFailed)
        // The attempt went out (the sender recorded the refusal);
        // the cap timestamp must not be consumed by a failed send.
        assertThat(harness.sender.sentTexts).hasSize(1)
        assertThat(harness.peerDao.peers[ALICE]?.lastAutoFallbackAt).isNull()
        verify(exactly = 0) { harness.recorder.recordEvent(E2eAutoFallback.SENT_TAG, any()) }
    }

    @Test
    fun `discovery off sends nothing`() = runTest {
        val harness = Harness(settings = SettingsWithSubId(CHOSEN_SUB_ID, autoDiscover = false))
        harness.seedPeer(theirCurveKey = "curve", lastBundleReplyAt = NOW - TWO_HOURS_MS)

        val result = harness.fallback.maybeSend(ALICE, now = NOW)

        assertThat(result).isEqualTo(AutoFallbackResult.Skipped(SkipReason.DiscoveryOff))
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `blocked sends nothing`() = runTest {
        val harness = Harness(blocked = FakeBlockedRepo(blocked = setOf(ALICE)))
        harness.seedPeer(theirCurveKey = "curve", lastBundleReplyAt = NOW - TWO_HOURS_MS)

        val result = harness.fallback.maybeSend(ALICE, now = NOW)

        assertThat(result).isEqualTo(AutoFallbackResult.Skipped(SkipReason.Blocked))
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `worker skip is logged, thread-open skip is not`() = runTest {
        val harness = Harness()
        harness.seedPeer(
            theirCurveKey = "curve",
            lastBundleReplyAt = NOW - TWO_HOURS_MS,
            lastAutoFallbackAt = NOW - E2eAutoFallback.STALL_MS,
        )

        // The worker logs its skips (one evaluation per arm).
        val workerResult = harness.fallback.maybeSend(ALICE, now = NOW, logSkipped = true)
        assertThat(workerResult).isEqualTo(AutoFallbackResult.Skipped(SkipReason.Capped))
        verify(exactly = 1) {
            harness.recorder.recordEvent(E2eAutoFallback.SKIPPED_TAG, any())
        }

        // The thread-open backstop does not (it runs on every open).
        val openResult = harness.fallback.maybeSend(ALICE, now = NOW, logSkipped = false)
        assertThat(openResult).isEqualTo(AutoFallbackResult.Skipped(SkipReason.Capped))
        verify(exactly = 1) {
            harness.recorder.recordEvent(E2eAutoFallback.SKIPPED_TAG, any())
        }
    }
}
