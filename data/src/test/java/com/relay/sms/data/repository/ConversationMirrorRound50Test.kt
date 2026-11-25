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
import com.relay.sms.data.local.db.entity.AttachmentEntity
import com.relay.sms.data.local.db.entity.ConversationEntity
import com.relay.sms.data.local.db.entity.MessageEntity
import com.relay.sms.data.sms.PhoneIdentity
import com.relay.sms.data.sms.TelephonyReader
import com.relay.sms.domain.categories.MessageCategory
import com.relay.sms.domain.model.MessageDirection
import com.relay.sms.domain.model.MessageStatus
import com.relay.sms.domain.model.MessageType
import com.relay.sms.domain.notification.ConversationNotificationCanceller
import com.relay.sms.domain.repository.ContactRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/**
 * Round 50 — import fixes.
 *
 * Part 1: the MMS import files a thread as a GROUP when the reader
 * resolved more than one recipient for it (before: every thread was
 * filed 1:1 with only the first message's sender); the SMS import
 * files a group thread from the union of its rows' addresses (sent
 * group SMS lands one row per recipient in the shared group thread).
 * Part 2: the MMS import folds tapbacks like the SMS path does.
 * Part 3: both importers classify the thread's newest incoming
 * message through the sticky store (the live path's hook), so
 * imported spam is filed without waiting for a new message.
 *
 * Strict MockK doubles (no Room) + the Room 2.8 `withTransaction`
 * stubs (see [ConversationMirrorE2eImportTest]).
 */
class ConversationMirrorRound50Test {

    private companion object {
        const val ALICE = "+16100000001"
        const val BOB = "+16100000002"
        const val CAROL = "+16100000003"
        const val SPAMMER = "+18335551234"
        const val THREAD_ID = 7L
        const val CONV_ID = 9L
        const val GROUP_CSV = "$ALICE;$BOB;$CAROL"
    }

    private val database = mockk<AppDatabase>()
    private val conversationDao = mockk<ConversationDao>()
    private val messageDao = mockk<MessageDao>()
    private val attachmentDao = mockk<AttachmentDao>()
    private val contacts = mockk<ContactRepository>()
    private val phoneIdentity = mockk<PhoneIdentity>()

    private fun mirror() = ConversationMirror(
        database = dbWithTransaction(),
        conversationDao = conversationDao,
        messageDao = messageDao,
        scheduledMessageDao = mockk<ScheduledMessageDao>(),
        attachmentDao = attachmentDao,
        contacts = contacts,
        phoneIdentity = phoneIdentity,
        notifications = mockk<ConversationNotificationCanceller>(),
        io = Dispatchers.Unconfined,
    )

    private fun dbWithTransaction(): AppDatabase {
        every { database.transactionExecutor } returns Executor { it.run() }
        every { database.suspendingTransactionContext } returns ThreadLocal()
        every { database.beginTransaction() } just Runs
        every { database.setTransactionSuccessful() } just Runs
        every { database.endTransaction() } just Runs
        return database
    }

    private fun convEntity(
        csv: String = ALICE,
        displayName: String? = null,
        lastMessageAt: Long = 0L,
    ) = ConversationEntity(
        id = CONV_ID,
        threadId = THREAD_ID,
        addressesCsv = csv,
        displayName = displayName,
        lastMessageAt = lastMessageAt,
        lastMessagePreview = null,
    )

    private fun mmsRow(
        telephonyId: Long,
        address: String,
        body: String,
        date: Long,
        direction: MessageDirection = MessageDirection.INCOMING,
        recipients: List<String> = emptyList(),
        attachments: List<TelephonyReader.MmsPartImport> = emptyList(),
    ) = TelephonyReader.MmsImportRow(
        telephonyId = telephonyId,
        threadId = THREAD_ID,
        dateMs = date,
        read = true,
        direction = direction,
        status = MessageStatus.RECEIVED,
        address = address,
        subId = null,
        textBody = body,
        attachments = attachments,
        threadRecipients = recipients,
    )

    private fun smsRow(
        address: String,
        body: String,
        date: Long,
        direction: MessageDirection = MessageDirection.INCOMING,
        uri: String = "content://sms/$date",
    ) = MessageEntity(
        // `conversationId` carries the SYSTEM thread id on import rows.
        conversationId = THREAD_ID,
        telephonyUri = uri,
        address = address,
        body = body,
        type = MessageType.SMS,
        direction = direction,
        date = date,
        dateSent = null,
        status = MessageStatus.RECEIVED,
    )

    /** Shared stubs for a thread with no conversation yet. */
    private fun stubNewConversation() {
        coEvery { contacts.lookupByPhone(any()) } returns null
        coEvery { conversationDao.findByThreadId(THREAD_ID) } returns null
        coEvery { conversationDao.findByAddressesCsv(any()) } returns null
        coEvery { conversationDao.snapshotGroupConversations() } returns emptyList()
        coEvery { conversationDao.snapshotOneToOneConversations() } returns emptyList()
        coEvery { phoneIdentity.snapshot() } returns PhoneIdentity.Snapshot(null)
        coEvery { conversationDao.insert(any()) } returns CONV_ID
        coEvery { conversationDao.update(any()) } just Runs
        coEvery { conversationDao.setStickyCategory(any(), any()) } just Runs
    }

    // ──────────────────── Part 1 — MMS group filing ────────────────────

    @Test
    fun `mms import with several recipients files the thread as a group`() = runTest {
        stubNewConversation()
        val created = slot<ConversationEntity>()
        coEvery { conversationDao.insert(capture(created)) } returns CONV_ID
        coEvery { conversationDao.findById(CONV_ID) } returns convEntity(csv = GROUP_CSV)
        coEvery { messageDao.insert(any()) } returns 55L

        mirror().bulkImportMmsFromTelephony(
            listOf(
                mmsRow(101L, ALICE, "hello group", 1000L, recipients = listOf(ALICE, BOB, CAROL)),
                mmsRow(102L, BOB, "hi all", 2000L, MessageDirection.OUTGOING, listOf(ALICE, BOB, CAROL)),
            ),
        )

        assertThat(created.isCaptured).isTrue()
        assertThat(created.captured.addressesCsv).isEqualTo(GROUP_CSV)
        assertThat(created.captured.threadId).isEqualTo(THREAD_ID)
        coVerify(exactly = 2) { messageDao.insert(match { it.conversationId == CONV_ID }) }
        // Groups never store a sticky category.
        coVerify(exactly = 0) { conversationDao.setStickyCategory(any(), any()) }
    }

    @Test
    fun `mms import with no recipient set keeps the 1-1 filing`() = runTest {
        stubNewConversation()
        val created = slot<ConversationEntity>()
        coEvery { conversationDao.insert(capture(created)) } returns CONV_ID
        coEvery { conversationDao.findById(CONV_ID) } returns convEntity()
        coEvery { messageDao.insert(any()) } returns 55L

        mirror().bulkImportMmsFromTelephony(
            listOf(mmsRow(101L, ALICE, "hello", 1000L)),
        )

        assertThat(created.isCaptured).isTrue()
        assertThat(created.captured.addressesCsv).isEqualTo(ALICE)
    }

    // ──────────────────── Round 54 — encrypted group media import ────────────────────

    /**
     * Round 54, group media — a group MMS carrying a `relay-media-`
     * part imports flagged encrypted, pending its group media key
     * (same as the 1:1 import since round 48); an OUTGOING one is
     * failed outright, because the sender keeps no copy of the
     * per-file keys and the plaintext original is gone with the
     * reinstall that triggered this import.
     */
    @Test
    fun `mms import flags a group relay-media part encrypted, outgoing failed`() = runTest {
        stubNewConversation()
        coEvery { conversationDao.insert(any()) } returns CONV_ID
        coEvery { conversationDao.findById(CONV_ID) } returns convEntity(csv = GROUP_CSV)
        coEvery { attachmentDao.insertAll(any()) } just Runs
        val inserted = mutableListOf<MessageEntity>()
        coEvery { messageDao.insert(capture(inserted)) } returns 55L
        val relayPart = TelephonyReader.MmsPartImport(
            partId = 1L,
            contentType = "application/octet-stream",
            filename = "relay-media-0123456789abcdef.bin",
        )

        mirror().bulkImportMmsFromTelephony(
            listOf(
                mmsRow(
                    101L,
                    ALICE,
                    "photo",
                    1000L,
                    recipients = listOf(ALICE, BOB, CAROL),
                    attachments = listOf(relayPart),
                ),
                mmsRow(
                    102L,
                    BOB,
                    "photo",
                    2000L,
                    direction = MessageDirection.OUTGOING,
                    recipients = listOf(ALICE, BOB, CAROL),
                    attachments = listOf(relayPart),
                ),
            ),
        )

        assertThat(inserted).hasSize(2)
        assertThat(inserted[0].encrypted).isTrue()
        assertThat(inserted[0].decryptFailed).isFalse()
        assertThat(inserted[1].encrypted).isTrue()
        assertThat(inserted[1].decryptFailed).isTrue()
    }

    // ──────────────────── Round 54 — voice-message import ────────────────────

    /**
     * Round 54, item 3 — a voice-only MMS (no text, one audio part)
     * imports exactly like an image/video MMS: the message
     * row carries `attachmentsCount = 1`, the part is stored as an
     * [AttachmentEntity] pointing at `content://mms/part/…`, and
     * the conversation preview is the 🎤 label — so the thread
     * renders an audio bubble, not a missing message.
     *
     * Regression guard: the reader ([TelephonyReader]) and the
     * mirror ([ConversationMirror.bulkImportMmsFromTelephony])
     * treat every non-SMIL, non-inline-text part as an attachment,
     * with no audio-specific filter — this test pins that, so a
     * future "unsupported type" carve-out cannot silently drop
     * voice notes again.
     */
    @Test
    fun `mms import keeps a voice-only mms as an audio attachment`() = runTest {
        stubNewConversation()
        coEvery { conversationDao.findById(CONV_ID) } returns convEntity()
        coEvery { messageDao.insert(any()) } returns 55L
        coEvery { attachmentDao.insertAll(any()) } just Runs

        mirror().bulkImportMmsFromTelephony(
            listOf(
                mmsRow(
                    telephonyId = 101L,
                    address = ALICE,
                    body = "",
                    date = 1000L,
                    attachments = listOf(
                        TelephonyReader.MmsPartImport(
                            partId = 77L,
                            contentType = "audio/3gpp",
                            filename = "voice.3gp",
                        ),
                    ),
                ),
            ),
        )

        // The message row exists, with no text but one attachment…
        val insertedMsg = slot<MessageEntity>()
        coVerify { messageDao.insert(capture(insertedMsg)) }
        assertThat(insertedMsg.captured.body).isEmpty()
        assertThat(insertedMsg.captured.type).isEqualTo(MessageType.MMS)
        assertThat(insertedMsg.captured.attachmentsCount).isEqualTo(1)
        // …the audio part is stored, pointing at the system part URI…
        val insertedAtts = slot<List<AttachmentEntity>>()
        coVerify { attachmentDao.insertAll(capture(insertedAtts)) }
        assertThat(insertedAtts.captured).hasSize(1)
        assertThat(insertedAtts.captured.single().messageId).isEqualTo(55L)
        assertThat(insertedAtts.captured.single().mimeType).isEqualTo("audio/3gpp")
        assertThat(insertedAtts.captured.single().fileName).isEqualTo("voice.3gp")
        assertThat(insertedAtts.captured.single().localUri)
            .isEqualTo("content://mms/part/77")
        // …and the conversation preview names the voice clip, so the
        // thread list shows it instead of an empty row.
        val touched = mutableListOf<ConversationEntity>()
        coVerify(atLeast = 1) { conversationDao.update(capture(touched)) }
        assertThat(touched.map { it.lastMessagePreview }).contains("🎤 voice.3gp")
    }

    // ──────────────────── Part 1 — SMS group threads ────────────────────

    @Test
    fun `sms import files a multi-address thread as a group`() = runTest {
        stubNewConversation()
        val created = slot<ConversationEntity>()
        coEvery { conversationDao.insert(capture(created)) } returns CONV_ID
        coEvery { conversationDao.findById(CONV_ID) } returns convEntity(csv = "$ALICE;$BOB")
        coEvery { messageDao.insertAll(any()) } answers { List(firstArg<List<*>>().size) { it + 1L } }

        // Sent group SMS (group-MMS off): one row per recipient in the
        // shared group thread, plus an incoming reply in the same thread.
        mirror().bulkImportFromTelephony(
            listOf(
                smsRow(ALICE, "hi", 1000L),
                smsRow(BOB, "hey", 2000L, MessageDirection.OUTGOING),
            ),
        )

        assertThat(created.isCaptured).isTrue()
        assertThat(created.captured.addressesCsv).isEqualTo("$ALICE;$BOB")
        // The hook ran (an incoming row exists) but skipped the group.
        coVerify(exactly = 0) { conversationDao.setStickyCategory(any(), any()) }
    }

    @Test
    fun `sms import does not fake a group from one member spelled two ways`() = runTest {
        stubNewConversation()
        val created = slot<ConversationEntity>()
        coEvery { conversationDao.insert(capture(created)) } returns CONV_ID
        coEvery { conversationDao.findById(CONV_ID) } returns convEntity(csv = "+16108423232")
        coEvery { messageDao.insertAll(any()) } answers { List(firstArg<List<*>>().size) { it + 1L } }

        mirror().bulkImportFromTelephony(
            listOf(
                smsRow("+16108423232", "hi", 1000L),
                smsRow("6108423232", "hey", 2000L, MessageDirection.OUTGOING),
            ),
        )

        assertThat(created.isCaptured).isTrue()
        assertThat(created.captured.addressesCsv).doesNotContain(";")
    }

    // ──────────────────── Part 2 — MMS tapback fold ────────────────────

    @Test
    fun `mms import folds an emoji tapback onto its target instead of a bubble`() = runTest {
        val group = convEntity(csv = "$ALICE;$BOB")
        val target = MessageEntity(
            id = 201L,
            conversationId = CONV_ID,
            telephonyUri = "content://mms/101",
            address = ALICE,
            body = "hello",
            type = MessageType.MMS,
            direction = MessageDirection.INCOMING,
            date = 1000L,
            dateSent = null,
            status = MessageStatus.RECEIVED,
        )
        coEvery { contacts.lookupByPhone(any()) } returns null
        coEvery { phoneIdentity.snapshot() } returns PhoneIdentity.Snapshot(null)
        coEvery { conversationDao.findByThreadId(THREAD_ID) } returns group
        coEvery { conversationDao.findById(CONV_ID) } returns group
        coEvery { conversationDao.findByAddressesCsv(any()) } returns null
        coEvery { conversationDao.snapshotOneToOneConversations() } returns emptyList()
        coEvery { conversationDao.snapshotGroupConversations() } returns listOf(group)
        coEvery { conversationDao.insert(any()) } returns 10L
        coEvery { conversationDao.update(any()) } just Runs
        coEvery { messageDao.insert(any()) } returns 201L
        coEvery { messageDao.findRecentForConversation(any(), any()) } returns listOf(target)
        coEvery { messageDao.setReactionWithAuthor(any(), any(), any(), any()) } just Runs

        mirror().bulkImportMmsFromTelephony(
            listOf(
                mmsRow(101L, ALICE, "hello", 1000L, recipients = listOf(ALICE, BOB)),
                mmsRow(102L, BOB, "👍 to \"hello\"", 2000L, recipients = listOf(ALICE, BOB)),
            ),
        )

        // The badge landed on the target…
        coVerify { messageDao.setReactionWithAuthor(201L, "👍", BOB, false) }
        // …and the tapback text was never inserted as a bubble…
        coVerify(exactly = 0) { messageDao.insert(match { it.body == "👍 to \"hello\"" }) }
        // …nor surfaced as the conversation preview (any touch).
        val touched = mutableListOf<ConversationEntity>()
        coVerify(atLeast = 1) { conversationDao.update(capture(touched)) }
        assertThat(touched.map { it.lastMessagePreview }).doesNotContain("👍 to \"hello\"")
    }

    // ──────────────────── Part 3 — classification during import ────────────────────

    @Test
    fun `sms import files an imported promo through the sticky store`() = runTest {
        coEvery { contacts.lookupByPhone(any()) } returns null
        coEvery { conversationDao.findByThreadId(THREAD_ID) } returns convEntity(csv = SPAMMER)
        coEvery { conversationDao.findById(CONV_ID) } returns convEntity(csv = SPAMMER)
        coEvery { conversationDao.update(any()) } just Runs
        coEvery { conversationDao.setStickyCategory(any(), any()) } just Runs
        coEvery { messageDao.insertAll(any()) } answers { List(firstArg<List<*>>().size) { it + 1L } }

        mirror().bulkImportFromTelephony(
            listOf(smsRow(SPAMMER, "Limited time offer! Sale ends Sunday!", 1000L)),
        )

        coVerify { conversationDao.setStickyCategory(CONV_ID, MessageCategory.PROMOTIONAL) }
    }

    @Test
    fun `mms import files an imported promo through the sticky store`() = runTest {
        stubNewConversation()
        coEvery { conversationDao.findById(CONV_ID) } returns convEntity(csv = SPAMMER)
        coEvery { messageDao.insert(any()) } returns 55L

        mirror().bulkImportMmsFromTelephony(
            listOf(mmsRow(101L, SPAMMER, "Limited time offer! Sale ends Sunday!", 1000L)),
        )

        coVerify { conversationDao.setStickyCategory(CONV_ID, MessageCategory.PROMOTIONAL) }
    }
}
