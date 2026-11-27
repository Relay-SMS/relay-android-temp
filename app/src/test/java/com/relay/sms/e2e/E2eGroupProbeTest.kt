/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.relay.sms.data.local.db.dao.ConversationDao
import com.relay.sms.data.local.db.entity.ConversationEntity
import com.relay.sms.data.local.db.entity.E2ePeerEntity
import com.relay.sms.data.local.db.entity.E2ePeerState
import com.relay.sms.data.sms.SimSelfNumber
import com.relay.sms.domain.settings.AppSettings
import com.relay.sms.domain.settings.AppSettingsSource
import com.relay.sms.domain.settings.EncryptionSettings
import com.relay.sms.domain.settings.SendingSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Round 58 — group-thread probing (design §2.4): opening a group
 * thread probes each member who is not already ENCRYPTED, through
 * the same per-address core as a 1:1 probe, so every gate applies
 * per member.
 *
 * The suspend core ([E2eProbeManager.probeGroupMembers]) is the
 * testable surface; the fire-and-forget entry point
 * ([E2eProbeManager.onThreadOpened]) is exercised through a
 * [TestScope] the manager owns, driven with `advanceUntilIdle`.
 *
 * Two gates are NOT testable here and are verified by code
 * instead: client mode and once-per-open both live in the thread
 * ViewModel's shared `e2eShouldProbe` condition (hub-only,
 * `e2eProbeOnOpenDone`) — the group branch runs inside that same
 * `if`, so the 1:1 and group paths share the guard verbatim, and
 * the probe manager itself is unreachable from a plain-JVM
 * ViewModel test (it arrives via a Hilt entry point).
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class E2eGroupProbeTest {

    private companion object {
        const val ALICE = "+33612345678"
        const val BOB = "+33622222222"
        const val OWN = "+33699999999"
        const val CAROL = "+14848860560"
        const val CAROL_PLAIN = "4848860560"
        const val NOW = 1_800_000_000_000L
        const val CHOSEN_SUB_ID = 7
    }

    private class Settings(
        private val subId: Int?,
        private val autoDiscover: Boolean = true,
        private val userMsisdn: String? = null,
    ) : AppSettingsSource {
        private val value = AppSettings(
            sending = SendingSettings(defaultSubId = subId, userMsisdn = userMsisdn),
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
        val conversationDao: ConversationDao = mockk(),
        val settings: AppSettingsSource = Settings(CHOSEN_SUB_ID),
        val crypto: FakeE2eCrypto = FakeE2eCrypto("alice"),
        val recorder: E2eFailureRecorder = mockk(relaxed = true),
        val mediaCapability: E2eMediaCapabilityManager = mockk(relaxed = true),
        // Relaxed: the SIM is fail-soft by design (it returns null
        // when it does not know the number), so an unstubbed call
        // answers null — the "SIM does not know it" case. The SIM
        // test stubs it explicitly.
        val simSelfNumber: SimSelfNumber = mockk(relaxed = true),
        scope: CoroutineScope,
        // Round 58 — when true, the manager under test gets a real
        // automatic fallback built from this harness's fakes.
        val withAutoFallback: Boolean = false,
    ) {
        val peers = E2ePeerRepository(peerDao)
        val accountManager = E2eAccountManager(accountDao, crypto, fakeKeyManager())

        val autoFallback: E2eAutoFallback? = if (withAutoFallback) {
            E2eAutoFallback(
                peers = peers,
                accountManager = lazyOf(accountManager),
                dataSmsSender = sender,
                settings = settings,
                blockedRepo = blocked,
                recorder = recorder,
                context = mockk<Context>(relaxed = true),
            )
        } else {
            null
        }

        val manager = E2eProbeManager(
            conversationDao = conversationDao,
            blockedRepo = blocked,
            peers = peers,
            accountManager = lazyOf(accountManager),
            dataSmsSender = sender,
            settings = settings,
            scope = scope,
            recorder = recorder,
            mediaCapability = mediaCapability,
            autoFallback = autoFallback,
            simSelfNumber = simSelfNumber,
        )

        fun seedPeer(
            address: String = ALICE,
            state: E2ePeerState = E2ePeerState.UNKNOWN,
            attempts: Int = 0,
            windowStartedAt: Long? = null,
            lastProbeAt: Long? = null,
            theirCurveKey: String? = null,
            lastBundleReplyAt: Long? = null,
            lastAutoFallbackAt: Long? = null,
        ) {
            peerDao.peers[address] = E2ePeerEntity(
                address = address,
                state = state,
                probeAttempts = attempts,
                windowStartedAt = windowStartedAt,
                lastProbeAt = lastProbeAt,
                theirCurveKey = theirCurveKey,
                lastBundleReplyAt = lastBundleReplyAt,
                lastAutoFallbackAt = lastAutoFallbackAt,
                updatedAt = NOW,
            )
        }

        fun conversationWith(addressesCsv: String): ConversationEntity {
            val conv = mockk<ConversationEntity>()
            every { conv.addressesCsv } returns addressesCsv
            return conv
        }
    }

    // ──────────────────────── Group open probes members ────────────────────────

    @Test
    fun `group open probes each non-ENCRYPTED member`() = runTest {
        val harness = Harness(scope = this)
        coEvery { harness.conversationDao.findById(11L) } returns
            harness.conversationWith("$ALICE;$BOB")

        harness.manager.onThreadOpened(11L)
        testScheduler.advanceUntilIdle()

        assertThat(harness.sender.sent).hasSize(2)
        assertThat(harness.sender.sent.map { it.address }).containsExactly(ALICE, BOB)
        assertThat(harness.sender.sent.map { it.subId }.toSet()).containsExactly(CHOSEN_SUB_ID)
        assertThat(harness.sender.sent.map { it.port }.toSet())
            .containsExactly(E2eHandshake.E2E_DATA_SMS_PORT)
        // The probes carry the group-thread-open trigger in the log.
        verify {
            harness.recorder.recordEvent(
                "e2e-probe-sent",
                match { it.contains("group-thread-open") },
            )
        }
    }

    @Test
    fun `already-ENCRYPTED member is skipped but still gets the media announcement`() = runTest {
        val harness = Harness(scope = this)
        harness.seedPeer(ALICE, state = E2ePeerState.ENCRYPTED, theirCurveKey = "curve")

        harness.manager.probeGroupMembers(listOf(ALICE, BOB), NOW)

        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.sender.sent.single().address).isEqualTo(BOB)
        // The shared core still runs the round-48 announcement hook
        // for the ENCRYPTED member (the defaulted `now` is verified
        // with `any()` — see the round-54 lesson).
        coVerify { harness.mediaCapability.announceIfNeeded(ALICE, CHOSEN_SUB_ID, any()) }
    }

    @Test
    fun `our own number from settings is skipped`() = runTest {
        val harness = Harness(scope = this, settings = Settings(CHOSEN_SUB_ID, userMsisdn = OWN))

        harness.manager.probeGroupMembers(listOf(OWN, BOB), NOW)

        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.sender.sent.single().address).isEqualTo(BOB)
    }

    @Test
    fun `our own number from the SIM is skipped when settings does not know it`() = runTest {
        val harness = Harness(scope = this)
        every { harness.simSelfNumber.ownNumber(any()) } returns OWN

        harness.manager.probeGroupMembers(listOf(OWN, BOB), NOW)

        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.sender.sent.single().address).isEqualTo(BOB)
    }

    @Test
    fun `member at probe cap is not probed while others are`() = runTest {
        val harness = Harness(scope = this)
        harness.seedPeer(ALICE, attempts = 3, windowStartedAt = NOW, lastProbeAt = NOW)

        harness.manager.probeGroupMembers(listOf(ALICE, BOB), NOW)

        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.sender.sent.single().address).isEqualTo(BOB)
    }

    @Test
    fun `discovery-off probes nobody`() = runTest {
        val harness = Harness(scope = this, settings = Settings(CHOSEN_SUB_ID, autoDiscover = false))

        harness.manager.probeGroupMembers(listOf(ALICE, BOB), NOW)

        assertThat(harness.sender.sent).isEmpty()
        assertThat(harness.sender.sentTexts).isEmpty()
    }

    @Test
    fun `blocked member is skipped while others are probed`() = runTest {
        val harness = Harness(scope = this, blocked = FakeBlockedRepo(blocked = setOf(ALICE)))

        harness.manager.probeGroupMembers(listOf(ALICE, BOB), NOW)

        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.sender.sent.single().address).isEqualTo(BOB)
    }

    @Test
    fun `invalid address member is skipped while others are probed`() = runTest {
        val harness = Harness(scope = this)

        harness.manager.probeGroupMembers(listOf("123", BOB), NOW)

        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.sender.sent.single().address).isEqualTo(BOB)
    }

    @Test
    fun `the same member twice in different forms is probed once`() = runTest {
        val harness = Harness(scope = this)

        harness.manager.probeGroupMembers(listOf(CAROL, CAROL_PLAIN), NOW)

        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.sender.sent.single().address).isEqualTo(CAROL)
    }

    // ──────────────────── Group open + automatic fallback ────────────────────

    @Test
    fun `group open evaluates the fallback per stalled member`() = runTest {
        val harness = Harness(scope = this, withAutoFallback = true)
        // ALICE is stalled on the evidence side (real-clock anchors,
        // like the 1:1 fallback test — the fallback evaluates with
        // System.currentTimeMillis()): her probe went out 2 hours ago,
        // so a second data probe is gated by the 24-hour interval,
        // but the 10-second stall has passed and her keys prove she
        // runs Relay. BOB is fresh: he gets a data probe, no fallback.
        val twoHoursAgo = System.currentTimeMillis() - 2L * 3_600_000L
        harness.seedPeer(
            ALICE,
            state = E2ePeerState.PROBING,
            attempts = 1,
            windowStartedAt = twoHoursAgo,
            theirCurveKey = "curve",
            lastProbeAt = twoHoursAgo,
            lastBundleReplyAt = twoHoursAgo,
        )
        coEvery { harness.conversationDao.findById(11L) } returns
            harness.conversationWith("$ALICE;$BOB")

        harness.manager.onThreadOpened(11L)
        testScheduler.advanceUntilIdle()

        assertThat(harness.sender.sent).hasSize(1)
        assertThat(harness.sender.sent.single().address).isEqualTo(BOB)
        assertThat(harness.sender.sentTexts).hasSize(1)
        assertThat(harness.sender.sentTexts.single().address).isEqualTo(ALICE)
        assertThat(harness.sender.sentTexts.single().text).startsWith("RLYH:")
    }
}
