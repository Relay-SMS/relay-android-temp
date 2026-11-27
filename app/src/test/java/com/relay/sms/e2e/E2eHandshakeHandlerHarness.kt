/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import com.relay.sms.data.local.db.dao.ConversationDao
import com.relay.sms.data.local.db.dao.MessageDao
import com.relay.sms.data.local.db.entity.ConversationEntity
import com.relay.sms.data.sms.PhoneIdentity
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk

/**
 * Shared harness for the handshake-handler suites
 * ([E2eHandshakeHandlerTest], [E2eHandshakeHandlerSessionTest]):
 * two [FakeE2eCrypto] parties ("alice" = us, "bob" = the peer)
 * with a third ("carol") for key changes, real stores over
 * in-memory fake DAOs, and the handler under test.
 *
 * Split out of [E2eHandshakeHandlerTest] in round 59: the suite
 * had grown past Detekt's LargeClass threshold (CI failure on
 * the round-58 push), and the harness is identical for both
 * halves. The shared constants ([HANDLER_BOB], [HANDLER_CONV_ID], [HANDLER_NOW]) live
 * here too, so both suites use the same fixtures.
 */

internal const val HANDLER_BOB = "+33612345678"
internal const val HANDLER_CONV_ID = 11L
internal const val HANDLER_NOW = 1_800_000_000_000L

internal class E2eHandshakeHandlerHarness(
    blocked: Set<String> = emptySet(),
    cryptoOverride: E2eCrypto? = null,
    accountManagerOverride: dagger.Lazy<E2eAccountManager>? = null,
    autoFallback: E2eAutoFallback? = null,
) {
    val aliceCrypto = FakeE2eCrypto("alice")
    val bobCrypto = FakeE2eCrypto("bob")
    val carolCrypto = FakeE2eCrypto("carol")

    val peerDao = FakeE2ePeerDao()
    val sessionDao = FakeE2eSessionDao()
    val accountDao = FakeE2eAccountDao()
    val noticeDao = FakeE2eNoticeDao()
    val sender = RecordingDataSmsSender()

    val conversationDao = mockk<ConversationDao>()
    val messageDao = mockk<MessageDao>()
    val phoneIdentity = mockk<PhoneIdentity>()
    val recorder = mockk<E2eFailureRecorder>(relaxed = true)

    // Round 48 — relaxed: the declaration is best-effort and
    // invisible to the existing assertions.
    val mediaCapability = mockk<E2eMediaCapabilityManager>(relaxed = true)

    // Round 49 — relaxed: the group key-change notices are
    // covered by the group manager tests, not here.
    val groupSessions = mockk<E2eGroupSessionManager>(relaxed = true)

    val peers = E2ePeerRepository(peerDao)
    val sessions = E2eSessionStore(sessionDao, aliceCrypto, fakeKeyManager())
    val accountManager = E2eAccountManager(accountDao, aliceCrypto, fakeKeyManager())
    val decryptor = E2eSessionDecryptor(
        lazyOf(aliceCrypto as E2eCrypto),
        lazyOf(sessions),
        lazyOf(accountManager),
    )

    val handler = E2eHandshakeHandler(
        peers = peers,
        sessions = lazyOf(sessions),
        accountManager = accountManagerOverride ?: lazyOf(accountManager),
        crypto = lazyOf(cryptoOverride ?: aliceCrypto as E2eCrypto),
        decryptor = decryptor,
        notices = E2eNoticeStore(noticeDao),
        dataSmsSender = sender,
        conversationDao = conversationDao,
        messageDao = messageDao,
        phoneIdentity = phoneIdentity,
        blockedRepo = FakeBlockedRepo(blocked),
        recorder = recorder,
        mediaCapability = mediaCapability,
        groupSessions = groupSessions,
        autoFallback = autoFallback,
    )

    init {
        // Default: the conversation exists (exact-match path), no
        // kept ciphertext, the identity fallback finds nothing.
        val conv = mockk<ConversationEntity>()
        every { conv.id } returns HANDLER_CONV_ID
        coEvery { conversationDao.findByAddressesCsv(any()) } returns conv
        coEvery { conversationDao.snapshotOneToOneConversations() } returns emptyList()
        coEvery { phoneIdentity.snapshot() } returns PhoneIdentity.Snapshot("FR")
        coEvery { messageDao.findDecryptFailed(any()) } returns emptyList()
        coEvery { messageDao.update(any()) } just Runs
        coEvery { messageDao.refreshConversationPreview(any()) } just Runs
    }

    /** A probe from [crypto]'s identity (as if it sent one). */
    fun probeFrom(crypto: FakeE2eCrypto, manual: Boolean = false): ByteArray {
        val keys = crypto.identityKeys()
        return E2eHandshake.encodeProbe(keys.curve25519, keys.ed25519, manual = manual)
    }

    /** A bundle from [crypto]: its identity keys + one one-time key. */
    fun bundleFrom(crypto: FakeE2eCrypto): ByteArray {
        val keys = crypto.identityKeys()
        val otk = crypto.nextOneTimeKey() ?: error("no one-time key left")
        return E2eHandshake.encodeBundle(keys.curve25519, keys.ed25519, otk)
    }

    /** A probe from [crypto]'s identity, as a regular SMS (round 45). */
    fun probeSmsFrom(crypto: FakeE2eCrypto, manual: Boolean = false): String =
        E2eHandshakeSms.encode(probeFrom(crypto, manual))

    /** A bundle from [crypto], as a regular SMS (round 45). */
    fun bundleSmsFrom(crypto: FakeE2eCrypto): String =
        E2eHandshakeSms.encode(bundleFrom(crypto))
}
