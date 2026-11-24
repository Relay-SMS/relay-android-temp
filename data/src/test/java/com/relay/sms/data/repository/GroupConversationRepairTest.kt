/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.repository

import com.google.common.truth.Truth.assertThat
import com.relay.sms.data.local.db.AppDatabase
import com.relay.sms.data.local.db.dao.ConversationDao
import com.relay.sms.data.local.db.dao.MessageDao
import com.relay.sms.data.local.db.dao.ScheduledMessageDao
import com.relay.sms.data.local.db.entity.ConversationEntity
import com.relay.sms.data.sms.PhoneIdentity
import com.relay.sms.data.sms.TelephonyReader
import com.relay.sms.domain.categories.MessageCategory
import com.relay.sms.domain.notification.ConversationNotificationCanceller
import com.relay.sms.domain.repository.ContactRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/**
 * Round 50 — the one-shot repair for group threads the pre-round-50
 * import filed as 1:1s.
 *
 * Strict MockK doubles (no Room) + the Room 2.8 `withTransaction`
 * stubs (see [ConversationMirrorE2eImportTest]).
 */
class GroupConversationRepairTest {

    private companion object {
        const val ALICE_E164 = "+16108423232"
        const val ALICE_NATIONAL = "6108423232"
        const val BOB_E164 = "+16109999999"
        const val BOB_NATIONAL = "6109999999"
        const val THREAD_ID = 7L
        const val VICTIM_ID = 11L
        const val GROUP_ID = 20L
    }

    private val database = mockk<AppDatabase>()
    private val conversationDao = mockk<ConversationDao>()
    private val messageDao = mockk<MessageDao>()
    private val scheduledMessageDao = mockk<ScheduledMessageDao>()
    private val telephonyReader = mockk<TelephonyReader>()
    private val phoneIdentity = mockk<PhoneIdentity>()
    private val contacts = mockk<ContactRepository>()
    private val notifications = mockk<ConversationNotificationCanceller>()

    private fun repair() = GroupConversationRepair(
        database = dbWithTransaction(),
        conversationDao = conversationDao,
        messageDao = messageDao,
        scheduledMessageDao = scheduledMessageDao,
        telephonyReader = telephonyReader,
        phoneIdentity = phoneIdentity,
        contacts = contacts,
        notifications = notifications,
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

    private fun victim(
        inVault: Boolean = false,
        stickyCategory: MessageCategory? = MessageCategory.PROMOTIONAL,
    ) = ConversationEntity(
        id = VICTIM_ID,
        threadId = THREAD_ID,
        addressesCsv = ALICE_E164,
        displayName = "Alice",
        lastMessageAt = 5000L,
        lastMessagePreview = "hi",
        unreadCount = 2,
        inVault = inVault,
        stickyCategory = stickyCategory,
    )

    private fun group(inVault: Boolean = false, csv: String = "$ALICE_NATIONAL;$BOB_NATIONAL") =
        ConversationEntity(
            id = GROUP_ID,
            threadId = null,
            addressesCsv = csv,
            displayName = null,
            lastMessageAt = 3000L,
            lastMessagePreview = "hey",
            inVault = inVault,
        )

    private fun stubIdentity() {
        coEvery { phoneIdentity.snapshot() } returns PhoneIdentity.Snapshot(null)
        coEvery { contacts.lookupByPhone(any()) } returns null
    }

    @Test
    fun `misfiled 1-1 is re-filed as a group in place, messages untouched`() = runTest {
        stubIdentity()
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(victim())
        coEvery { conversationDao.snapshotGroupConversations() } returns emptyList()
        every { telephonyReader.readThreadRecipients(THREAD_ID) } returns
            setOf(ALICE_E164, BOB_E164)
        coEvery { conversationDao.findByAddressesCsv(any()) } returns null
        coEvery { conversationDao.findById(VICTIM_ID) } returns victim()
        val updated = slot<ConversationEntity>()
        coEvery { conversationDao.update(capture(updated)) } just Runs

        val result = repair().repair()

        assertThat(result).isEqualTo(GroupConversationRepair.Result(1, 0, true))
        assertThat(updated.isCaptured).isTrue()
        // The member list is the provider's recipient set…
        assertThat(updated.captured.addressesCsv).isEqualTo("$ALICE_E164;$BOB_E164")
        // …the 1:1's contact name must not survive as the group title…
        assertThat(updated.captured.displayName).isNull()
        // …a group never stores a sticky category…
        assertThat(updated.captured.stickyCategory).isNull()
        // …and nothing else moved: no re-pointing, no unread loss.
        assertThat(updated.captured.unreadCount).isEqualTo(2)
        assertThat(updated.captured.lastMessageAt).isEqualTo(5000L)
        assertThat(updated.captured.lastMessagePreview).isEqualTo("hi")
        assertThat(updated.captured.threadId).isEqualTo(THREAD_ID)
        coVerify(exactly = 0) { conversationDao.delete(any()) }
        coVerify(exactly = 0) { messageDao.reparentMessages(any(), any()) }
    }

    @Test
    fun `a legit 1-1 is left exactly alone`() = runTest {
        stubIdentity()
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(victim())
        coEvery { conversationDao.snapshotGroupConversations() } returns emptyList()
        every { telephonyReader.readThreadRecipients(THREAD_ID) } returns setOf(ALICE_E164)

        val result = repair().repair()

        assertThat(result).isEqualTo(GroupConversationRepair.Result(0, 0, true))
        coVerify(exactly = 0) { conversationDao.update(any()) }
        coVerify(exactly = 0) { conversationDao.delete(any()) }
    }

    @Test
    fun `a failed provider read makes the pass incomplete and touches nothing`() = runTest {
        stubIdentity()
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(victim())
        coEvery { conversationDao.snapshotGroupConversations() } returns emptyList()
        every { telephonyReader.readThreadRecipients(THREAD_ID) } returns null

        val result = repair().repair()

        assertThat(result.complete).isFalse()
        coVerify(exactly = 0) { conversationDao.update(any()) }
        coVerify(exactly = 0) { conversationDao.delete(any()) }
    }

    @Test
    fun `a vault 1-1 is never touched and never even read from the provider`() = runTest {
        stubIdentity()
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(victim(inVault = true))
        coEvery { conversationDao.snapshotGroupConversations() } returns emptyList()

        val result = repair().repair()

        assertThat(result).isEqualTo(GroupConversationRepair.Result(0, 0, true))
        verify(exactly = 0) { telephonyReader.readThreadRecipients(any()) }
        coVerify(exactly = 0) { conversationDao.update(any()) }
    }

    @Test
    fun `merges into the existing group when one matches the member set`() = runTest {
        stubIdentity()
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(victim())
        // The live-created group spells its members nationally; the
        // provider spells them E.164 — the repair must still match
        // them (canonicalPhone), not create a duplicate group.
        coEvery { conversationDao.snapshotGroupConversations() } returns listOf(group())
        every { telephonyReader.readThreadRecipients(THREAD_ID) } returns
            setOf(ALICE_E164, BOB_E164)
        coEvery { conversationDao.findByAddressesCsv(any()) } returns null
        coEvery { conversationDao.findById(VICTIM_ID) } returns victim()
        coEvery { conversationDao.findById(GROUP_ID) } returns group()
        coEvery { messageDao.reparentMessages(any(), any()) } just Runs
        coEvery { scheduledMessageDao.reparentConversationId(any(), any()) } just Runs
        val updated = slot<ConversationEntity>()
        coEvery { conversationDao.update(capture(updated)) } just Runs
        coEvery { conversationDao.delete(any()) } just Runs
        coEvery { conversationDao.setThreadId(any(), any()) } just Runs
        coEvery { conversationDao.recomputeAllUnreadCounts() } just Runs
        every { notifications.cancelAllForConversation(any()) } just Runs

        val result = repair().repair()

        assertThat(result).isEqualTo(GroupConversationRepair.Result(0, 1, true))
        // Messages move BEFORE the victim row is deleted…
        coVerify { messageDao.reparentMessages(VICTIM_ID, GROUP_ID) }
        coVerify { scheduledMessageDao.reparentConversationId(VICTIM_ID, GROUP_ID) }
        coVerify { conversationDao.delete(VICTIM_ID) }
        // …the group adopts the system thread id…
        coVerify { conversationDao.setThreadId(GROUP_ID, THREAD_ID) }
        // …unread counts are recomputed from the real messages…
        coVerify { conversationDao.recomputeAllUnreadCounts() }
        // …and the victim's notifications are cancelled, not orphaned.
        verify { notifications.cancelAllForConversation(VICTIM_ID) }
        // The survivor keeps the newest preview/date of the two…
        assertThat(updated.captured.lastMessageAt).isEqualTo(5000L)
        assertThat(updated.captured.lastMessagePreview).isEqualTo("hi")
        // …and never inherits the victim's single-member name.
        assertThat(updated.captured.displayName).isNull()
    }

    @Test
    fun `a matching group in the vault blocks the merge`() = runTest {
        stubIdentity()
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(victim())
        coEvery { conversationDao.snapshotGroupConversations() } returns listOf(group(inVault = true))
        every { telephonyReader.readThreadRecipients(THREAD_ID) } returns
            setOf(ALICE_E164, BOB_E164)
        coEvery { conversationDao.findByAddressesCsv(any()) } returns null

        val result = repair().repair()

        // The 1:1 is left alone: merging across the vault boundary
        // would hide its visible messages inside the vault.
        assertThat(result).isEqualTo(GroupConversationRepair.Result(0, 0, true))
        coVerify(exactly = 0) { conversationDao.update(any()) }
        coVerify(exactly = 0) { conversationDao.delete(any()) }
    }

    @Test
    fun `no candidates means a complete no-op without touching identity`() = runTest {
        coEvery { conversationDao.snapshotOneToOneConversations() } returns emptyList()

        val result = repair().repair()

        assertThat(result).isEqualTo(GroupConversationRepair.Result(0, 0, true))
        coVerify(exactly = 0) { phoneIdentity.snapshot() }
        verify(exactly = 0) { telephonyReader.readThreadRecipients(any()) }
    }
}
