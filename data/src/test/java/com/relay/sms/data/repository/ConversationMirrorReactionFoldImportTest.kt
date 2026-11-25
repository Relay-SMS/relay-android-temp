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
 * Full import-path reproduction for the round-52 empty reaction pills
 * (Curt thread): the decoder alone was proven correct for the 16 wire
 * forms of Curt's thumbs-up, so these tests feed candidate SMS rows
 * through [foldImportedTapback] — decode (+ Google fallback) →
 * [ConversationMirror.applyIncomingReaction] → `setReaction` — and assert
 * what actually gets stored.
 *
 * The rule the fold must hold: a row either stores a VISIBLE emoji (a
 * base emoji codepoint, IncomingReactionDecoder.isVisibleEmoji) or
 * stores nothing at all. An invisible-only token (lone VS16 / skin
 * tone / ZWJ) decoded to a stored value before the round-52 hardening
 * and painted an empty pill; the fold now refuses it in every form
 * family. Wire forms outside the 16 decoder forms are covered too:
 * single curly quotes, German quotes, leading/trailing whitespace,
 * BOM prefix, empty preview, bare emoji, Google framing (add + removal).
 * The MMS import path calls the same [foldImportedTapback], so these
 * cases cover both import paths.
 *
 * ROOT CAUSE FOUND (this round, via the BOM probe): a reaction SMS
 * whose emoji token carries a LEADING invisible format char (BOM
 * U+FEFF, ZWSP U+200B, ZWNJ U+200C — a carrier / gateway / system
 * store can prepend one) used to decode to a stored emoji of
 * "‹invisible›👍": visible at first, but the reparse's v3 normalize
 * ([firstGraphemeCluster]) then took the invisible char as the
 * "first cluster" and wrote it back as the reaction — an empty pill,
 * deterministically, on every fresh import. The decoder now strips
 * format chars from the token, the cluster function skips them, and
 * the normalize never writes an invisible-only value back.
 *
 * Strict MockK doubles (no Room): the fold only touches the two DAOs,
 * the phone identity, and — for incoming tapbacks — the conversation
 * touch. A strict double called unexpectedly fails the test instead of
 * silently satisfying the fold. `telephonyUri = null` skips the sentinel
 * path ([ConversationMirror.upsertReactionSentinel] early-returns), so
 * no Room transaction needs mocking.
 */
class ConversationMirrorReactionFoldImportTest {

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
        addressesCsv = ADDRESS,
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
    ) = MessageEntity(
        id = id,
        conversationId = 9L,
        telephonyUri = null,
        address = ADDRESS,
        body = body,
        type = MessageType.SMS,
        direction = direction,
        date = date,
        dateSent = null,
        status = MessageStatus.RECEIVED,
        reactionEmoji = reactionEmoji,
    )

    private fun stubConversation() {
        coEvery { phoneIdentity.snapshot() } returns PhoneIdentity.Snapshot("US")
        coEvery { conversationDao.findByAddressesCsv(any()) } returns convEntity()
        coEvery { conversationDao.snapshotGroupConversations() } returns emptyList()
        coEvery { messageDao.setReactionWithAuthor(any(), any(), any(), any()) } just Runs
        // Incoming adds bump the conversation: the touch reads the row
        // back and writes it.
        coEvery { conversationDao.findById(9L) } returns convEntity()
        coEvery { conversationDao.update(any()) } just Runs
    }

    /**
     * Folds one imported row onto Kristin's outgoing message [PREVIEW]
     * (Curt's reaction target), optionally pre-setting the target's
     * stored badge to [targetReaction]. Returns what the fold returned.
     */
    private suspend fun fold(body: String, targetReaction: String? = null): Boolean {
        val target = messageEntity(
            id = TARGET_ID,
            direction = MessageDirection.OUTGOING,
            body = PREVIEW,
            reactionEmoji = targetReaction,
        )
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns listOf(target)
        return mirror().foldImportedTapback(
            address = ADDRESS,
            body = body,
            date = 2000L,
            direction = MessageDirection.INCOMING,
            telephonyUri = null,
        )
    }

    /**
     * Minimal well-formed Google framing per
     * [com.relay.sms.domain.reaction.IncomingReactionDecoder.decodeGoogleFormat]:
     * frame + separator around the emoji, frame around the preview, one
     * tail char before the closing frame.
     */
    private fun googleFramed(emoji: String, separator: Char): String =
        "\u200A$separator$emoji$separator\u200A$PREVIEW\u200Ax\u200A"

    @Test
    fun `curt thumbs-up emoji-quote straight folds and stores the thumbs-up`() = runTest {
        stubConversation()

        assertThat(fold("👍 to \"$PREVIEW\"")).isTrue()
        coVerify { messageDao.setReactionWithAuthor(TARGET_ID, "👍", ADDRESS, false) }
    }

    @Test
    fun `curt thumbs-up emoji-quote curly folds and stores the thumbs-up`() = runTest {
        stubConversation()

        assertThat(fold("👍 to “$PREVIEW”")).isTrue()
        coVerify { messageDao.setReactionWithAuthor(TARGET_ID, "👍", ADDRESS, false) }
    }

    @Test
    fun `reacted curly-quote form folds and stores the thumbs-up`() = runTest {
        stubConversation()

        assertThat(fold("Reacted 👍 to “$PREVIEW”")).isTrue()
        coVerify { messageDao.setReactionWithAuthor(TARGET_ID, "👍", ADDRESS, false) }
    }

    @Test
    fun `lone VS16 emoji token does not fold or store`() = runTest {
        stubConversation()

        assertThat(fold("\uFE0F to \"$PREVIEW\"")).isFalse()
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(any(), any(), any(), any()) }
    }

    @Test
    fun `lone skin-tone emoji token does not fold or store`() = runTest {
        stubConversation()

        assertThat(fold("\uD83C\uDFFB to \"$PREVIEW\"")).isFalse()
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(any(), any(), any(), any()) }
    }

    @Test
    fun `lone ZWJ emoji token does not fold or store`() = runTest {
        stubConversation()

        assertThat(fold("\u200D to \"$PREVIEW\"")).isFalse()
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(any(), any(), any(), any()) }
    }

    @Test
    fun `lone VS16 in reacted form does not fold or store`() = runTest {
        stubConversation()

        assertThat(fold("Reacted \uFE0F to \"$PREVIEW\"")).isFalse()
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(any(), any(), any(), any()) }
    }

    @Test
    fun `google-framed thumbs-up folds and stores the thumbs-up`() = runTest {
        stubConversation()

        assertThat(fold(googleFramed("👍", '\u200B'))).isTrue()
        coVerify { messageDao.setReactionWithAuthor(TARGET_ID, "👍", ADDRESS, false) }
    }

    @Test
    fun `google-framed lone VS16 does not fold or store`() = runTest {
        stubConversation()

        assertThat(fold(googleFramed("\uFE0F", '\u200B'))).isFalse()
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(any(), any(), any(), any()) }
    }

    @Test
    fun `google-framed removal of the stored thumbs-up clears it`() = runTest {
        stubConversation()

        assertThat(fold(googleFramed("👍", '\u200C'), targetReaction = "👍")).isTrue()
        coVerify { messageDao.setReactionWithAuthor(TARGET_ID, null, null, false) }
    }

    @Test
    fun `removal of a different emoji does not clear the stored badge`() = runTest {
        stubConversation()

        // Curt's stored badge is 👍; a removal of ❤️ must not wipe it.
        // The fold still reports applied (the target matched) — only the
        // clear is skipped.
        assertThat(fold("Removed a heart from \"$PREVIEW\"", targetReaction = "👍")).isTrue()
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(TARGET_ID, null, null, false) }
    }

    @Test
    fun `single-curly-quote form does not fold or store`() = runTest {
        stubConversation()

        assertThat(fold("👍 to ‘$PREVIEW’")).isFalse()
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(any(), any(), any(), any()) }
    }

    @Test
    fun `german-quote form does not fold or store`() = runTest {
        stubConversation()

        assertThat(fold("👍 to „$PREVIEW“")).isFalse()
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(any(), any(), any(), any()) }
    }

    @Test
    fun `bare thumbs-up does not fold`() = runTest {
        stubConversation()

        // EmojiOnly is never folded on import: its 5-minute window is
        // anchored at "now", so an old bare-emoji row would mis-attach.
        assertThat(fold("👍")).isFalse()
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(any(), any(), any(), any()) }
    }

    @Test
    fun `valid fold followed by invisible-only fold keeps the thumbs-up`() = runTest {
        stubConversation()

        assertThat(fold("👍 to \"$PREVIEW\"")).isTrue()
        assertThat(fold("\uFE0F to \"$PREVIEW\"")).isFalse()
        coVerify(exactly = 1) { messageDao.setReactionWithAuthor(TARGET_ID, "👍", ADDRESS, false) }
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(TARGET_ID, "\uFE0F", ADDRESS, false) }
    }

    @Test
    fun `leading and trailing whitespace still folds`() = runTest {
        stubConversation()

        assertThat(fold("  👍 to \"$PREVIEW\"  \n")).isTrue()
        coVerify { messageDao.setReactionWithAuthor(TARGET_ID, "👍", ADDRESS, false) }
    }

    @Test
    fun `BOM-prefixed body folds and stores the clean thumbs-up`() = runTest {
        stubConversation()

        // The BOM used to ride into the stored emoji ("‹U+FEFF›👍") and
        // the reparse normalize then reduced it to the invisible char
        // alone. The decoder strips format chars from the token, so
        // the fold stores a clean 👍.
        assertThat(fold("\uFEFF👍 to \"$PREVIEW\"")).isTrue()
        coVerify { messageDao.setReactionWithAuthor(TARGET_ID, "👍", ADDRESS, false) }
    }

    @Test
    fun `ZWSP- and ZWNJ-prefixed bodies fold and store the clean thumbs-up`() = runTest {
        stubConversation()

        assertThat(fold("\u200B👍 to \"$PREVIEW\"")).isTrue()
        assertThat(fold("\u200C👍 to \"$PREVIEW\"")).isTrue()
        coVerify(exactly = 2) { messageDao.setReactionWithAuthor(TARGET_ID, "👍", ADDRESS, false) }
    }

    @Test
    fun `empty preview does not fold or store`() = runTest {
        stubConversation()

        assertThat(fold("👍 to \"\"")).isFalse()
        coVerify(exactly = 0) { messageDao.setReactionWithAuthor(any(), any(), any(), any()) }
    }

    private companion object {
        const val ADDRESS: String = "+33600000001"
        const val TARGET_ID: Long = 42L
        const val PREVIEW: String = "Sounds good, cyah then! Oh no worries :)"
    }
}
