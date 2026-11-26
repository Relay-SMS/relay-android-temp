/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.system.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import com.relay.sms.domain.model.Contact
import com.relay.sms.domain.model.Conversation
import com.relay.sms.domain.model.PhoneAddress
import com.relay.sms.domain.repository.ContactPhotoWriteResult
import com.relay.sms.domain.repository.ContactRepository
import com.relay.sms.domain.repository.ConversationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.InputStream

/**
 * Round 57 — the pending-photos scan ([ContactPhotoWriteBack
 * .writeBackPendingPhotos]): an unsaved number that later becomes
 * a saved system contact gets Relay's stored avatar then, and a
 * system contact that already has a photo is never clobbered.
 *
 * The photo bytes come from a fake loader (the real one decodes
 * Bitmaps — Stub! on plain JVM), so these tests prove the decision
 * logic: matching, gating, fallback. The resolver call itself is
 * covered by the sync-path tests.
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose `runTest` body returns a value.
 */
class ContactPhotoWriteBackTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val photoBytes = byteArrayOf(1, 2, 3, 4)

    /** Fake contact repo: a lookup map + a configurable write verdict, with every write recorded. */
    private class FakeContacts(
        private val byNumber: Map<String, Contact?> = emptyMap(),
        private val writeResult: ContactPhotoWriteResult = ContactPhotoWriteResult.Ok,
    ) : ContactRepository {
        val writes = mutableListOf<Pair<String, ByteArray>>()

        override suspend fun lookupByPhone(rawPhone: String): Contact? = byNumber[rawPhone]

        override suspend fun listAll(): List<Contact> = byNumber.values.filterNotNull()

        override suspend fun listAllDetailed(): List<Contact> = listAll()

        override suspend fun openPhotoStream(address: String): InputStream? = null

        override suspend fun setContactPhoto(
            address: String,
            photoBytes: ByteArray,
        ): ContactPhotoWriteResult {
            writes += address to photoBytes
            return writeResult
        }
    }

    private lateinit var context: Context
    private lateinit var conversations: ConversationRepository

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = mockk(relaxed = true)
        every {
            context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS)
        } returns PackageManager.PERMISSION_GRANTED
        conversations = mockk(relaxed = true) {
            every { observeAll(true) } returns flowOf(emptyList())
            every { observeVault() } returns flowOf(emptyList())
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a newly saved contact receives Relay's stored photo`() = runTest(dispatcher) {
        val alice = Contact(42L, "Alice", listOf(PhoneAddress.of("+33612345678")))
        val contacts = FakeContacts(mapOf("+33612345678" to alice))
        val thread = conversation(7L, "+33612345678", avatarUri = "content://picked")
        every { conversations.observeAll(true) } returns flowOf(listOf(thread))
        val writeBack = writeBack(contacts)

        writeBack.writeBackPendingPhotos()
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).hasSize(1)
        assertThat(contacts.writes.single().first).isEqualTo("+33612345678")
        assertThat(contacts.writes.single().second).isEqualTo(photoBytes)
    }

    @Test
    fun `a system contact that already has a photo is left untouched`() = runTest(dispatcher) {
        val alice = Contact(
            42L,
            "Alice",
            listOf(PhoneAddress.of("+33612345678")),
            photoUri = "content://system-thumb",
        )
        val contacts = FakeContacts(mapOf("+33612345678" to alice))
        val thread = conversation(7L, "+33612345678", avatarUri = "content://picked")
        every { conversations.observeAll(true) } returns flowOf(listOf(thread))
        val writeBack = writeBack(contacts)

        writeBack.writeBackPendingPhotos()
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).isEmpty()
    }

    @Test
    fun `a full-size system photo also blocks the write`() = runTest(dispatcher) {
        val alice = Contact(
            42L,
            "Alice",
            listOf(PhoneAddress.of("+33612345678")),
            photoUri = null,
            photoFullUri = "content://system-full",
        )
        val contacts = FakeContacts(mapOf("+33612345678" to alice))
        val thread = conversation(7L, "+33612345678", avatarUri = "content://picked")
        every { conversations.observeAll(true) } returns flowOf(listOf(thread))
        val writeBack = writeBack(contacts)

        writeBack.writeBackPendingPhotos()
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).isEmpty()
    }

    @Test
    fun `a still-unsaved number is skipped, Relay-local only`() = runTest(dispatcher) {
        val contacts = FakeContacts(mapOf("+33612345678" to null))
        val thread = conversation(7L, "+33612345678", avatarUri = "content://picked")
        every { conversations.observeAll(true) } returns flowOf(listOf(thread))
        val writeBack = writeBack(contacts)

        writeBack.writeBackPendingPhotos()
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).isEmpty()
    }

    @Test
    fun `a thread without a Relay avatar writes nothing`() = runTest(dispatcher) {
        val alice = Contact(42L, "Alice", listOf(PhoneAddress.of("+33612345678")))
        val contacts = FakeContacts(mapOf("+33612345678" to alice))
        val thread = conversation(7L, "+33612345678", avatarUri = null)
        every { conversations.observeAll(true) } returns flowOf(listOf(thread))
        val writeBack = writeBack(contacts)

        writeBack.writeBackPendingPhotos()
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).isEmpty()
    }

    @Test
    fun `a group avatar never writes to a member contact`() = runTest(dispatcher) {
        val alice = Contact(42L, "Alice", listOf(PhoneAddress.of("+33612345678")))
        val contacts = FakeContacts(mapOf("+33612345678" to alice))
        val group = conversation(
            8L,
            "+33612345678",
            avatarUri = "content://picked",
            extraAddresses = listOf("+33698765432"),
        )
        every { conversations.observeAll(true) } returns flowOf(listOf(group))
        val writeBack = writeBack(contacts)

        writeBack.writeBackPendingPhotos()
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).isEmpty()
    }

    @Test
    fun `a vault thread's avatar is scanned too`() = runTest(dispatcher) {
        val alice = Contact(42L, "Alice", listOf(PhoneAddress.of("+33612345678")))
        val contacts = FakeContacts(mapOf("+33612345678" to alice))
        val thread = conversation(9L, "+33612345678", avatarUri = "content://picked")
        every { conversations.observeVault() } returns flowOf(listOf(thread))
        val writeBack = writeBack(contacts)

        writeBack.writeBackPendingPhotos()
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).hasSize(1)
    }

    @Test
    fun `a denied permission skips the whole scan`() = runTest(dispatcher) {
        every {
            context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS)
        } returns PackageManager.PERMISSION_DENIED
        val alice = Contact(42L, "Alice", listOf(PhoneAddress.of("+33612345678")))
        val contacts = FakeContacts(mapOf("+33612345678" to alice))
        val thread = conversation(7L, "+33612345678", avatarUri = "content://picked")
        every { conversations.observeAll(true) } returns flowOf(listOf(thread))
        val writeBack = writeBack(contacts)

        writeBack.writeBackPendingPhotos()
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).isEmpty()
        verify(exactly = 0) { conversations.observeAll(true) }
    }

    @Test
    fun `writeBackPhoto writes and reports the repository verdict`() = runTest(dispatcher) {
        val contacts = FakeContacts(writeResult = ContactPhotoWriteResult.Ok)
        val writeBack = writeBack(contacts)

        val result = writeBack.writeBackPhoto("+33612345678", "content://picked")

        assertThat(result).isEqualTo(ContactPhotoWriteResult.Ok)
        assertThat(contacts.writes).hasSize(1)
    }

    @Test
    fun `writeBackPhoto without the permission asks for it instead of writing`() =
        runTest(dispatcher) {
            every {
                context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS)
            } returns PackageManager.PERMISSION_DENIED
            val contacts = FakeContacts()
            val writeBack = writeBack(contacts)

            val result = writeBack.writeBackPhoto("+33612345678", "content://picked")

            assertThat(result).isEqualTo(ContactPhotoWriteResult.NoPermission)
            assertThat(contacts.writes).isEmpty()
        }

    @Test
    fun `writeBackPhoto with unreadable bytes fails without writing`() = runTest(dispatcher) {
        val contacts = FakeContacts()
        val writeBack = ContactPhotoWriteBack(conversations, contacts, context, dispatcher) { _ -> null }

        val result = writeBack.writeBackPhoto("+33612345678", "content://picked")

        assertThat(result).isEqualTo(ContactPhotoWriteResult.Failed)
        assertThat(contacts.writes).isEmpty()
    }

    private fun writeBack(contacts: ContactRepository): ContactPhotoWriteBack =
        ContactPhotoWriteBack(conversations, contacts, context, dispatcher) { _ -> photoBytes }

    private fun conversation(
        id: Long,
        rawAddress: String,
        avatarUri: String?,
        extraAddresses: List<String> = emptyList(),
    ): Conversation = Conversation(
        id = id,
        threadId = null,
        addresses = (listOf(rawAddress) + extraAddresses).map { PhoneAddress.of(it) },
        displayName = null,
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
        avatarUri = avatarUri,
    )
}
