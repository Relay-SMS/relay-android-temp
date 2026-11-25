/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.repository

import com.google.common.truth.Truth.assertThat
import com.relay.sms.data.local.db.AppDatabase
import com.relay.sms.data.local.db.dao.AttachmentDao
import com.relay.sms.data.local.db.dao.ConversationDao
import com.relay.sms.data.local.db.dao.MessageDao
import com.relay.sms.data.local.db.dao.ScheduledMessageDao
import com.relay.sms.data.local.db.entity.MessageEntity
import com.relay.sms.data.sms.PhoneIdentity
import com.relay.sms.domain.model.MessageDirection
import com.relay.sms.domain.model.MessageStatus
import com.relay.sms.domain.model.MessageType
import com.relay.sms.domain.notification.ConversationNotificationCanceller
import com.relay.sms.domain.repository.ContactRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Reparse v3 normalize guard (round-52 emoji fix): the normalize step
 * keeps the first grapheme cluster of a stored multi-emoji reaction —
 * but a stored "‹invisible›👍" (a BOM / ZWSP / ZWNJ prepended to the
 * emoji by a carrier / gateway / system store, folded before the
 * decoder stripped format chars) used to normalize to the invisible
 * char alone, painting an empty pill on every reparse run. Two layers
 * now prevent that: [IncomingReactionDecoder.firstGraphemeCluster]
 * skips leading format chars (so the row normalizes to its visible
 * emoji), and the normalize loop never writes an invisible-only (or
 * empty) value back at all.
 *
 * Strict MockK doubles (no Room): with no candidate rows the fold
 * phase does nothing, so the reparse only touches the two DAO reads
 * below and `setReaction`. A strict double called unexpectedly fails
 * the test instead of silently satisfying the reparse.
 */
class ConversationMirrorReactionReparseNormalizeTest {

    private val db = mockk<AppDatabase>()
    private val conversationDao = mockk<ConversationDao>()
    private val messageDao = mockk<MessageDao>()
    private val phoneIdentity = mockk<PhoneIdentity>()

    private fun mirror() = ConversationMirror(
        database = db,
        conversationDao = conversationDao,
        messageDao = messageDao,
        scheduledMessageDao = mockk<ScheduledMessageDao>(),
        attachmentDao = mockk<AttachmentDao>(),
        contacts = mockk<ContactRepository>(),
        phoneIdentity = phoneIdentity,
        notifications = mockk<ConversationNotificationCanceller>(),
        io = Dispatchers.Unconfined,
    )

    private fun messageEntity(id: Long, reactionEmoji: String?) = MessageEntity(
        id = id,
        conversationId = 9L,
        telephonyUri = null,
        address = "+33600000001",
        body = "salut",
        type = MessageType.SMS,
        direction = MessageDirection.INCOMING,
        date = 1000L,
        dateSent = null,
        status = MessageStatus.RECEIVED,
        reactionEmoji = reactionEmoji,
    )

    private fun stubReactions(rows: List<MessageEntity>) {
        coEvery { messageDao.findReactionCandidates() } returns emptyList()
        coEvery { messageDao.findMessagesWithReactions() } returns rows
        coEvery { messageDao.setReaction(any(), any()) } just Runs
    }

    @Test
    fun `contaminated stored emoji normalizes to the clean thumbs-up`() = runTest {
        stubReactions(listOf(messageEntity(id = 42L, reactionEmoji = "\uFEFF👍")))

        val result = mirror().reparseStoredReactions()

        assertThat(result.normalized).isEqualTo(1)
        coVerify { messageDao.setReaction(42L, "👍") }
    }

    @Test
    fun `invisible-only stored emoji is left alone, never rewritten`() = runTest {
        stubReactions(listOf(messageEntity(id = 42L, reactionEmoji = "\uFEFF")))

        val result = mirror().reparseStoredReactions()

        assertThat(result.normalized).isEqualTo(0)
        coVerify(exactly = 0) { messageDao.setReaction(any(), any()) }
    }

    @Test
    fun `clean stored emoji is untouched`() = runTest {
        stubReactions(listOf(messageEntity(id = 42L, reactionEmoji = "👍")))

        val result = mirror().reparseStoredReactions()

        assertThat(result.normalized).isEqualTo(0)
        coVerify(exactly = 0) { messageDao.setReaction(any(), any()) }
    }

    @Test
    fun `stacked stored emojis still normalize to the first`() = runTest {
        stubReactions(listOf(messageEntity(id = 42L, reactionEmoji = "👍😂😅")))

        val result = mirror().reparseStoredReactions()

        assertThat(result.normalized).isEqualTo(1)
        coVerify { messageDao.setReaction(42L, "👍") }
    }
}
