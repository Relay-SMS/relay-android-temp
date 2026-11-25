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
import com.relay.sms.data.local.db.entity.ConversationEntity
import com.relay.sms.data.local.db.entity.MessageEntity
import com.relay.sms.data.sms.PhoneIdentity
import com.relay.sms.domain.model.MessageDirection
import com.relay.sms.domain.model.MessageStatus
import com.relay.sms.domain.model.MessageType
import com.relay.sms.domain.notification.ConversationNotificationCanceller
import com.relay.sms.domain.reaction.IncomingReactionDecoder.DecodedReaction.Kind
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
 * Own-reaction fold: our sent tapback must fold onto its target on
 * re-import (fresh install) or the sent-box row surfaces as a stray
 * `Liked "…"` bubble. Since our own reactions now send for our own
 * texts too, an own tapback whose target is one of OUR outgoing
 * messages must fold — with their incoming messages still winning
 * when both match.
 *
 * Strict MockK doubles (no Room): the fold only touches the two
 * DAOs, the phone identity, and — for incoming tapbacks — the
 * conversation touch. A strict double called unexpectedly fails the
 * test instead of silently satisfying the fold.
 */
class ConversationMirrorOwnReactionFoldTest {

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

    private fun convEntity() = ConversationEntity(
        id = 9L,
        threadId = null,
        addressesCsv = "+33600000001",
        displayName = null,
        lastMessageAt = 0L,
        lastMessagePreview = null,
    )

    private fun messageEntity(
        id: Long,
        direction: MessageDirection,
        body: String,
        date: Long = 1000L,
        reactionEmoji: String? = null,
        reactionByAddress: String? = null,
        reactionIsOwn: Boolean = false,
    ) = MessageEntity(
        id = id,
        conversationId = 9L,
        telephonyUri = null,
        address = "+33600000001",
        body = body,
        type = MessageType.SMS,
        direction = direction,
        date = date,
        dateSent = null,
        status = MessageStatus.RECEIVED,
        reactionEmoji = reactionEmoji,
        reactionByAddress = reactionByAddress,
        reactionIsOwn = reactionIsOwn,
    )

    private fun stubConversation() {
        coEvery { phoneIdentity.snapshot() } returns PhoneIdentity.Snapshot("US")
        coEvery { conversationDao.findByAddressesCsv(any()) } returns convEntity()
        coEvery { conversationDao.snapshotGroupConversations() } returns emptyList()
        coEvery { messageDao.setReactionWithAuthor(any(), any(), any(), any()) } just Runs
    }

    @Test
    fun `own tapback folds onto our own outgoing message when no incoming matches`() = runTest {
        stubConversation()
        val ours = messageEntity(id = 42L, direction = MessageDirection.OUTGOING, body = "salut")
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns listOf(ours)

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isOwnReaction = true,
        )

        assertThat(applied).isNotNull()
        assertThat(applied!!.targetMessageId).isEqualTo(42L)
        // Round 54 — an own tapback is recorded as ours.
        coVerify { messageDao.setReactionWithAuthor(42L, "👍", null, true) }
    }

    @Test
    fun `own tapback still prefers their incoming message when both match`() = runTest {
        stubConversation()
        val theirs = messageEntity(
            id = 43L,
            direction = MessageDirection.INCOMING,
            body = "salut",
            date = 2000L,
        )
        val ours = messageEntity(id = 42L, direction = MessageDirection.OUTGOING, body = "salut")
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns listOf(theirs, ours)

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isOwnReaction = true,
        )

        assertThat(applied).isNotNull()
        assertThat(applied!!.targetMessageId).isEqualTo(43L)
        coVerify { messageDao.setReactionWithAuthor(43L, "👍", null, true) }
    }

    @Test
    fun `own tapback removal clears our own badge`() = runTest {
        stubConversation()
        val ours = messageEntity(
            id = 42L,
            direction = MessageDirection.OUTGOING,
            body = "salut",
            reactionEmoji = "👍",
            reactionIsOwn = true,
        )
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns listOf(ours)

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isRemoval = true,
            isOwnReaction = true,
        )

        assertThat(applied).isNotNull()
        assertThat(applied!!.targetMessageId).isEqualTo(42L)
        coVerify { messageDao.setReactionWithAuthor(42L, null, null, false) }
    }

    @Test
    fun `incoming tapback still matches any direction`() = runTest {
        stubConversation()
        // An incoming tapback bumps the conversation: the touch reads
        // the row back and writes it.
        coEvery { conversationDao.findById(9L) } returns convEntity()
        coEvery { conversationDao.update(any()) } just Runs
        val ours = messageEntity(id = 42L, direction = MessageDirection.OUTGOING, body = "salut")
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns listOf(ours)

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isOwnReaction = false,
        )

        assertThat(applied).isNotNull()
        assertThat(applied!!.targetMessageId).isEqualTo(42L)
        // Round 54 — an incoming tapback records its sender.
        coVerify { messageDao.setReactionWithAuthor(42L, "👍", "+33600000001", false) }
    }

    @Test
    fun `own tapback with no match at all returns null`() = runTest {
        stubConversation()
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns emptyList()

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isOwnReaction = true,
        )

        assertThat(applied).isNull()
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(any(), any(), any(), any()) }
    }

    // Round 54 — removal ownership: a removal only clears a badge
    // its sender could have set. The fold still reports the target
    // (so the removal text is suppressed as a bubble) even when the
    // badge itself is left alone.

    @Test
    fun `incoming removal clears their own badge`() = runTest {
        stubConversation()
        val theirs = messageEntity(
            id = 42L,
            direction = MessageDirection.OUTGOING,
            body = "salut",
            reactionEmoji = "👍",
            reactionByAddress = "+33600000001",
            reactionIsOwn = false,
        )
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns listOf(theirs)

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isRemoval = true,
            isOwnReaction = false,
        )

        assertThat(applied).isNotNull()
        assertThat(applied!!.targetMessageId).isEqualTo(42L)
        coVerify { messageDao.setReactionWithAuthor(42L, null, null, false) }
    }

    @Test
    fun `incoming removal does not clear our own badge`() = runTest {
        stubConversation()
        val ours = messageEntity(
            id = 42L,
            direction = MessageDirection.OUTGOING,
            body = "salut",
            reactionEmoji = "👍",
            reactionIsOwn = true,
        )
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns listOf(ours)

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isRemoval = true,
            isOwnReaction = false,
        )

        // Target found (removal text suppressed) but badge untouched.
        assertThat(applied).isNotNull()
        assertThat(applied!!.targetMessageId).isEqualTo(42L)
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(any(), any(), any(), any()) }
    }

    @Test
    fun `own removal does not clear their badge`() = runTest {
        stubConversation()
        val theirs = messageEntity(
            id = 42L,
            direction = MessageDirection.OUTGOING,
            body = "salut",
            reactionEmoji = "👍",
            reactionByAddress = "+33600000001",
            reactionIsOwn = false,
        )
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns listOf(theirs)

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isRemoval = true,
            isOwnReaction = true,
        )

        assertThat(applied).isNotNull()
        assertThat(applied!!.targetMessageId).isEqualTo(42L)
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(any(), any(), any(), any()) }
    }

    @Test
    fun `incoming removal clears an unknown-ownership badge`() = runTest {
        stubConversation()
        // Legacy row: stored before the author columns existed, so
        // neither address nor own flag is set — the old
        // clear-on-emoji-match behaviour is kept for those rows.
        val legacy = messageEntity(
            id = 42L,
            direction = MessageDirection.OUTGOING,
            body = "salut",
            reactionEmoji = "👍",
        )
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns listOf(legacy)

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isRemoval = true,
            isOwnReaction = false,
        )

        assertThat(applied).isNotNull()
        assertThat(applied!!.targetMessageId).isEqualTo(42L)
        coVerify { messageDao.setReactionWithAuthor(42L, null, null, false) }
    }

    @Test
    fun `incoming removal of a different emoji leaves the badge alone`() = runTest {
        stubConversation()
        val theirs = messageEntity(
            id = 42L,
            direction = MessageDirection.OUTGOING,
            body = "salut",
            reactionEmoji = "❤️",
            reactionByAddress = "+33600000001",
            reactionIsOwn = false,
        )
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns listOf(theirs)

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isRemoval = true,
            isOwnReaction = false,
        )

        assertThat(applied).isNotNull()
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(any(), any(), any(), any()) }
    }
}
