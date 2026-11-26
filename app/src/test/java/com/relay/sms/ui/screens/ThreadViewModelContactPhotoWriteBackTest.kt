/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.relay.sms.R
import com.relay.sms.data.local.datastore.SettingsRepository
import com.relay.sms.domain.model.Contact
import com.relay.sms.domain.model.Conversation
import com.relay.sms.domain.model.PhoneAddress
import com.relay.sms.domain.repository.ContactPhotoWriteResult
import com.relay.sms.domain.repository.ContactRepository
import com.relay.sms.domain.repository.ConversationRepository
import com.relay.sms.domain.settings.AppSettings
import com.relay.sms.domain.settings.DeviceMode
import com.relay.sms.security.AppLockManager
import com.relay.sms.ui.screens.thread.ThreadViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Round 57 — a photo picked locally (Details panel camera badge)
 * also lands on the system contact: [ThreadViewModel.setAppearance]
 * writes it back after the Relay-local save, asks for
 * WRITE_CONTACTS when the grant is missing, and keeps Relay-local
 * with a snackbar when there is no contact to write to or the
 * grant is denied.
 *
 * The photo bytes ride the real loader (a small in-memory stream
 * — BitmapFactory throws Stub! on plain JVM, so the loader falls
 * back to the raw bytes, which already fit the cap); the decision
 * logic is what these tests pin down.
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose `runTest` body returns a value.
 */
class ThreadViewModelContactPhotoWriteBackTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** Fake contact repo with a configurable write verdict, with every write recorded. */
    private class FakeContacts(
        private val writeResult: ContactPhotoWriteResult = ContactPhotoWriteResult.Ok,
    ) : ContactRepository {
        val writes = mutableListOf<Pair<String, ByteArray>>()

        override suspend fun lookupByPhone(rawPhone: String): Contact? = null

        override suspend fun listAll(): List<Contact> = emptyList()

        override suspend fun listAllDetailed(): List<Contact> = emptyList()

        override suspend fun openPhotoStream(address: String): InputStream? = null

        override suspend fun setContactPhoto(
            address: String,
            photoBytes: ByteArray,
        ): ContactPhotoWriteResult {
            writes += address to photoBytes
            return writeResult
        }
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // The real photo loader parses the avatar URI
        // (`Uri.parse` is a Stub! on plain JVM, like the
        // BitmapFactory calls the loader already guards) — a
        // mock parse lets the mocked resolver's stream through.
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk()
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Uri::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `a newly picked avatar is written to the system contact`() = runTest(dispatcher) {
        val contacts = FakeContacts()
        val (vm, events) = viewModelWithEvents(contacts)

        vm.setAppearance(null, "content://picked")
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).hasSize(1)
        assertThat(contacts.writes.single().first).isEqualTo("+33612345678")
        assertThat(snackbars(events)).containsExactly("res-${R.string.contact_photo_saved}")
    }

    @Test
    fun `no system contact keeps Relay-local and says so`() = runTest(dispatcher) {
        val contacts = FakeContacts(writeResult = ContactPhotoWriteResult.NotFound)
        val (vm, events) = viewModelWithEvents(contacts)

        vm.setAppearance(null, "content://picked")
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).hasSize(1)
        assertThat(snackbars(events)).containsExactly("res-${R.string.contact_photo_no_contact}")
    }

    @Test
    fun `a failed write keeps Relay-local and reports an error`() = runTest(dispatcher) {
        val contacts = FakeContacts(writeResult = ContactPhotoWriteResult.Failed)
        val (vm, events) = viewModelWithEvents(contacts)

        vm.setAppearance(null, "content://picked")
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).hasSize(1)
        val snackbar = events.filterIsInstance<ThreadViewModel.Event.ShowSnackbar>().single()
        assertThat(snackbar.message).isEqualTo("res-${R.string.contact_photo_save_failed}")
        assertThat(snackbar.isError).isTrue()
    }

    @Test
    fun `an unchanged avatar writes nothing back`() = runTest(dispatcher) {
        val contacts = FakeContacts()
        val (vm, events) = viewModelWithEvents(contacts, avatarUri = "content://current")

        // The appearance dialog passes the avatar through untouched
        // when only the colour changes.
        vm.setAppearance(0x112233, "content://current")
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).isEmpty()
        assertThat(snackbars(events)).containsExactly("res-${R.string.appearance_saved}")
    }

    @Test
    fun `resetting the avatar writes nothing back`() = runTest(dispatcher) {
        val contacts = FakeContacts()
        val (vm, events) = viewModelWithEvents(contacts, avatarUri = "content://current")

        vm.setAppearance(null, null)
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).isEmpty()
        assertThat(snackbars(events)).containsExactly("res-${R.string.appearance_saved}")
    }

    @Test
    fun `a group avatar never writes to a member contact`() = runTest(dispatcher) {
        val contacts = FakeContacts()
        val (vm, events) = viewModelWithEvents(
            contacts,
            addresses = listOf("+33612345678", "+33698765432"),
        )

        vm.setAppearance(null, "content://picked")
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).isEmpty()
        assertThat(snackbars(events)).containsExactly("res-${R.string.appearance_saved}")
    }

    @Test
    fun `client mode never writes to the local provider`() = runTest(dispatcher) {
        val contacts = FakeContacts()
        val (vm, events) = viewModelWithEvents(contacts, mode = DeviceMode.CLIENT)

        vm.setAppearance(null, "content://picked")
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).isEmpty()
        assertThat(snackbars(events)).containsExactly("res-${R.string.appearance_saved}")
    }

    @Test
    fun `a missing grant asks for it and writes nothing yet`() = runTest(dispatcher) {
        val contacts = FakeContacts()
        val context = testContext(permissionGranted = false)
        val (vm, events) = viewModelWithEvents(contacts, context = context)

        vm.setAppearance(null, "content://picked")
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).isEmpty()
        assertThat(events.filterIsInstance<ThreadViewModel.Event.RequestContactsWritePhoto>())
            .hasSize(1)
    }

    @Test
    fun `a denied grant keeps Relay-local and says so`() = runTest(dispatcher) {
        val contacts = FakeContacts()
        val context = testContext(permissionGranted = false)
        val (vm, events) = viewModelWithEvents(contacts, context = context)

        vm.setAppearance(null, "content://picked")
        testScheduler.advanceUntilIdle()
        vm.onContactsWriteDenied()
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).isEmpty()
        assertThat(snackbars(events))
            .containsExactly("res-${R.string.contact_photo_needs_permission}")
    }

    @Test
    fun `a granted retry writes the parked photo`() = runTest(dispatcher) {
        val contacts = FakeContacts()
        val context = testContext(permissionGranted = false)
        val (vm, events) = viewModelWithEvents(contacts, context = context)

        vm.setAppearance(null, "content://picked")
        testScheduler.advanceUntilIdle()
        assertThat(contacts.writes).isEmpty()

        every {
            context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS)
        } returns PackageManager.PERMISSION_GRANTED
        vm.retryPendingPhotoWriteBack()
        testScheduler.advanceUntilIdle()

        assertThat(contacts.writes).hasSize(1)
        assertThat(snackbars(events)).containsExactly("res-${R.string.contact_photo_saved}")
    }

    private fun snackbars(events: List<ThreadViewModel.Event>): List<String> =
        events.filterIsInstance<ThreadViewModel.Event.ShowSnackbar>().map { it.message }

    /**
     * A mockk context whose photo reads return a small in-memory
     * stream and whose string reads echo the resource id, so the
     * snackbar under test is assertable without Robolectric.
     */
    private fun testContext(permissionGranted: Boolean = true): Context {
        val context: Context = mockk(relaxed = true)
        val resolver: ContentResolver = mockk {
            every { openInputStream(any()) } returns
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        }
        every { context.contentResolver } returns resolver
        every { context.getString(any<Int>()) } answers { "res-${firstArg<Int>()}" }
        every {
            context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS)
        } returns if (permissionGranted) {
            PackageManager.PERMISSION_GRANTED
        } else {
            PackageManager.PERMISSION_DENIED
        }
        return context
    }

    private fun TestScope.viewModelWithEvents(
        contacts: FakeContacts,
        avatarUri: String? = null,
        addresses: List<String> = listOf("+33612345678"),
        mode: DeviceMode = DeviceMode.HUB,
        context: Context = testContext(),
    ): Pair<ThreadViewModel, MutableList<ThreadViewModel.Event>> {
        val conversation = Conversation(
            id = 7L,
            threadId = null,
            addresses = addresses.map { PhoneAddress.of(it) },
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
        val repo: ConversationRepository = mockk(relaxed = true) {
            every { observeOne(any()) } returns flowOf(conversation)
            every { observeVaultHidden(any()) } returns flowOf(false)
            every { observeMessagesWindow(any(), any()) } returns emptyFlow()
        }
        val settings: SettingsRepository = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(AppSettings(mode = mode))
        }
        val appLock: AppLockManager = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(AppLockManager.LockState.Unlocked)
        }
        val vm = ThreadViewModel(
            savedStateHandle = androidx.lifecycle.SavedStateHandle(
                mapOf("conversationId" to conversation.id),
            ),
            repo = repo,
            envoyer = mockk(relaxed = true),
            sendVoiceMms = mockk(relaxed = true),
            retrySend = mockk(relaxed = true),
            markRead = mockk(relaxed = true),
            segCounter = mockk(relaxed = true),
            exportPdf = mockk(relaxed = true),
            voiceRecorder = mockk(relaxed = true),
            playbackController = mockk(relaxed = true),
            settings = settings,
            blockNumber = mockk(relaxed = true),
            toggleConvState = mockk(relaxed = true),
            contactRepo = contacts,
            sendReaction = mockk(relaxed = true),
            scheduleMessage = mockk(relaxed = true),
            incomingShare = mockk(relaxed = true),
            activeConversationTracker = mockk(relaxed = true),
            context = context,
            io = dispatcher,
            appLock = appLock,
        )
        val events = mutableListOf<ThreadViewModel.Event>()
        backgroundScope.launch { vm.events.toList(events) }
        // Let init settle (the conversation row lands in state)
        // before the test drives setAppearance.
        testScheduler.advanceUntilIdle()
        return vm to events
    }
}
