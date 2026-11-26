/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.components

import com.google.common.truth.Truth.assertThat
import com.relay.sms.data.local.datastore.SettingsRepository
import com.relay.sms.domain.model.Contact
import com.relay.sms.domain.model.Conversation
import com.relay.sms.domain.model.PhoneAddress
import com.relay.sms.domain.repository.ContactPhotoWriteResult
import com.relay.sms.domain.repository.ContactRepository
import com.relay.sms.domain.repository.ConversationRepository
import com.relay.sms.domain.settings.AppSettings
import com.relay.sms.domain.settings.ConversationSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Round 56 — the forward sheet's avatars resolve contact photos like the list
 * does: a 1:1 without a custom avatar shows the contact's photo, groups show
 * their first two members' photos, and the show-avatars setting hides them.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class ForwardPickerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** Fake contact repo: only the photo lookup matters here. */
    private class FakeContacts(
        private val photosByPhone: Map<String, String> = emptyMap(),
    ) : ContactRepository {
        override suspend fun lookupByPhone(rawPhone: String): Contact? =
            photosByPhone[rawPhone]?.let {
                Contact(1L, "Someone", listOf(PhoneAddress.of(rawPhone)), it)
            }

        override suspend fun listAll(): List<Contact> = emptyList()

        override suspend fun listAllDetailed(): List<Contact> = emptyList()

        override suspend fun openPhotoStream(address: String): java.io.InputStream? = null

        override suspend fun setContactPhoto(
            address: String,
            photoBytes: ByteArray,
        ): ContactPhotoWriteResult = ContactPhotoWriteResult.NotFound
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `contact photo resolves for a one-to-one without a custom avatar`() = runTest(dispatcher) {
        val vm = viewModel(
            conversations = listOf(conv(1L, "+33612345678")),
            contacts = FakeContacts(mapOf("+33612345678" to "content://photo")),
        )
        val collected = mutableListOf<Map<Long, String>>()
        backgroundScope.launch { vm.contactPhotos.toList(collected) }

        testScheduler.advanceUntilIdle()

        assertThat(collected.last()).isEqualTo(mapOf(1L to "content://photo"))
    }

    @Test
    fun `a conversation with a custom avatar is skipped for contact photos`() =
        runTest(dispatcher) {
            val vm = viewModel(
                conversations = listOf(
                    conv(1L, "+33612345678", avatarUri = "content://custom"),
                ),
                contacts = FakeContacts(mapOf("+33612345678" to "content://photo")),
            )
            val collected = mutableListOf<Map<Long, String>>()
            backgroundScope.launch { vm.contactPhotos.toList(collected) }

            testScheduler.advanceUntilIdle()

            assertThat(collected.last()).isEmpty()
        }

    @Test
    fun `group member photos resolve for the first two members`() = runTest(dispatcher) {
        val vm = viewModel(
            conversations = listOf(conv(2L, "+33611111111", "+33622222222")),
            contacts = FakeContacts(
                mapOf(
                    "+33611111111" to "content://a",
                    "+33622222222" to "content://b",
                ),
            ),
        )
        val collected = mutableListOf<Map<Long, List<String?>>>()
        backgroundScope.launch { vm.groupMemberPhotos.toList(collected) }

        testScheduler.advanceUntilIdle()

        assertThat(collected.last())
            .isEqualTo(mapOf(2L to listOf("content://a", "content://b")))
    }

    @Test
    fun `showAvatars follows the setting`() = runTest(dispatcher) {
        val vm = viewModel(
            conversations = emptyList(),
            settingsState = AppSettings(
                conversations = ConversationSettings(showAvatars = false),
            ),
        )
        val collected = mutableListOf<Boolean>()
        backgroundScope.launch { vm.showAvatars.toList(collected) }

        testScheduler.advanceUntilIdle()

        assertThat(collected.last()).isFalse()
    }

    private fun conv(
        id: Long,
        vararg rawPhones: String,
        avatarUri: String? = null,
    ): Conversation = Conversation(
        id = id,
        threadId = null,
        addresses = rawPhones.map { PhoneAddress.of(it) },
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

    private fun viewModel(
        conversations: List<Conversation>,
        contacts: ContactRepository = FakeContacts(),
        settingsState: AppSettings = AppSettings(),
    ): ForwardPickerViewModel {
        val repo: ConversationRepository = mockk(relaxed = true) {
            every { observeAll(any()) } returns flowOf(conversations)
        }
        val settings: SettingsRepository = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(settingsState)
        }
        return ForwardPickerViewModel(
            conversationRepo = repo,
            contacts = contacts,
            settings = settings,
            io = dispatcher,
        )
    }
}
