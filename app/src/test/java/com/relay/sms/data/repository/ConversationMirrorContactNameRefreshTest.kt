/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.relay.sms.data.local.db.AppDatabase
import com.relay.sms.data.local.db.entity.ConversationEntity
import com.relay.sms.data.sms.PhoneIdentity
import com.relay.sms.domain.model.Contact
import com.relay.sms.domain.model.PhoneAddress
import com.relay.sms.domain.notification.ConversationNotificationCanceller
import com.relay.sms.domain.repository.ContactRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Contact-name live refresh — [ConversationMirror
 * .refreshContactNames] re-resolves the stored display name of
 * every conversation, not just the missing ones.
 *
 * The bug it closes: a contact renamed in the phone kept its old
 * name in Relay (list, thread header, desktop title) until the
 * app restarted — and even the restart only fixed a NEWLY ADDED
 * contact, because the old pass only filled null names. Every
 * `ensure*` path has the same shape (fill a null name, never
 * overwrite), so the stored name was a snapshot taken at import
 * time and nothing ever re-read it.
 *
 * JUnit 4 under Robolectric with a REAL in-memory Room database,
 * like `ConversationMirrorSyncTest`: the write-only-on-change
 * behaviour is a database invariant (a needless write re-emits
 * the Room flows and flickers the list), and a fake DAO would
 * only test the fake. CI-ONLY caveat from that file applies
 * here too (the local harness has no KSP, so the generated
 * `AppDatabase_Impl` does not exist there).
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [33])
class ConversationMirrorContactNameRefreshTest {

    private lateinit var db: AppDatabase
    private lateinit var contacts: ContactRepository
    private lateinit var mirror: ConversationMirror

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        contacts = mockk()
        coEvery { contacts.hasContactsPermission() } returns true
        mirror = ConversationMirror(
            db,
            db.conversationDao(),
            db.messageDao(),
            db.scheduledMessageDao(),
            db.attachmentDao(),
            contacts,
            mockk<PhoneIdentity>(relaxed = true),
            mockk<ConversationNotificationCanceller>(relaxed = true),
            Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test fun `a renamed contact updates the stored name`() = runTest {
        val id = insertConversation(ALICE, displayName = "Alice")
        coEvery { contacts.lookupByPhone(ALICE) } returns contact("Alicia", ALICE)

        mirror.refreshContactNames()

        assertThat(db.conversationDao().findById(id)?.displayName).isEqualTo("Alicia")
    }

    @Test fun `a newly added contact fills a missing name`() = runTest {
        val id = insertConversation(ALICE, displayName = null)
        coEvery { contacts.lookupByPhone(ALICE) } returns contact("Alice", ALICE)

        mirror.refreshContactNames()

        assertThat(db.conversationDao().findById(id)?.displayName).isEqualTo("Alice")
    }

    @Test fun `renaming one group member renames the group title`() = runTest {
        val id = insertConversation("$ALICE;$BOB", displayName = "Alice, Bob")
        coEvery { contacts.lookupByPhone(ALICE) } returns contact("Alicia", ALICE)
        coEvery { contacts.lookupByPhone(BOB) } returns contact("Bob", BOB)

        mirror.refreshContactNames()

        assertThat(db.conversationDao().findById(id)?.displayName).isEqualTo("Alicia, Bob")
    }

    @Test fun `an unchanged name is left alone`() = runTest {
        val id = insertConversation(ALICE, displayName = "Alice")
        coEvery { contacts.lookupByPhone(ALICE) } returns contact("Alice", ALICE)

        mirror.refreshContactNames()

        assertThat(db.conversationDao().findById(id)?.displayName).isEqualTo("Alice")
    }

    @Test fun `a deleted contact clears the name only when asked`() = runTest {
        val id = insertConversation(ALICE, displayName = "Alice")
        coEvery { contacts.lookupByPhone(ALICE) } returns null

        mirror.refreshContactNames()
        assertThat(db.conversationDao().findById(id)?.displayName).isEqualTo("Alice")

        mirror.refreshContactNames(clearMissing = true)
        assertThat(db.conversationDao().findById(id)?.displayName).isNull()
    }

    @Test fun `a denied permission never clears a stored name`() = runTest {
        val id = insertConversation(ALICE, displayName = "Alice")
        coEvery { contacts.hasContactsPermission() } returns false
        coEvery { contacts.lookupByPhone(ALICE) } returns null

        mirror.refreshContactNames(clearMissing = true)

        assertThat(db.conversationDao().findById(id)?.displayName).isEqualTo("Alice")
    }

    private suspend fun insertConversation(csv: String, displayName: String?): Long =
        db.conversationDao().insert(
            ConversationEntity(
                threadId = null,
                addressesCsv = csv,
                displayName = displayName,
                lastMessageAt = 0L,
                lastMessagePreview = null,
            ),
        )

    private fun contact(name: String, phone: String) =
        Contact(1L, name, listOf(PhoneAddress.of(phone)), null)
}

private const val ALICE = "+16100000001"
private const val BOB = "+16100000002"
