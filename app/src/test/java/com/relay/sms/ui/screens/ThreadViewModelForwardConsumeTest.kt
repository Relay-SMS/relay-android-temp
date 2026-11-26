/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.relay.sms.data.local.datastore.SettingsRepository
import com.relay.sms.domain.model.Contact
import com.relay.sms.domain.model.Conversation
import com.relay.sms.domain.model.Message
import com.relay.sms.domain.model.PhoneAddress
import com.relay.sms.domain.repository.ContactPhotoWriteResult
import com.relay.sms.domain.repository.ContactRepository
import com.relay.sms.domain.repository.ConversationRepository
import com.relay.sms.domain.settings.AppSettings
import com.relay.sms.security.AppLockManager
import com.relay.sms.system.share.IncomingShareHolder
import com.relay.sms.ui.screens.thread.ThreadViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Round 56 — forwarding through [IncomingShareHolder].
 *
 * Two holes in the same path: AppRoot's share guard used to clear a forward
 * pending while the user sat in the source thread (covered by
 * [com.relay.sms.ui.SharePendingActionTest]), and the destination's consume
 * silently dropped the forwarded text when the thread already had a draft.
 * These tests cover the destination side: the text stages as a draft, and an
 * existing draft keeps its content with the forwarded text appended.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class ThreadViewModelForwardConsumeTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** Fake contact repo; the consume path never looks a contact up. */
    private class FakeContacts : ContactRepository {
        override suspend fun lookupByPhone(rawPhone: String): Contact? = null

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
    fun `forwarded text lands in an empty draft`() = runTest(dispatcher) {
        val holder = IncomingShareHolder()
        val vm = threadViewModel(holder)
        holder.set(
            IncomingShareHolder.Pending(
                uris = emptyList(),
                mimeType = null,
                text = "forwarded hello",
                isForward = true,
            ),
        )

        vm.onConversationOpened()
        testScheduler.advanceUntilIdle()

        assertThat(vm.state.value.draft).isEqualTo("forwarded hello")
    }

    @Test
    fun `forwarded text appends to an existing draft instead of dropping it`() =
        runTest(dispatcher) {
            val holder = IncomingShareHolder()
            val vm = threadViewModel(holder)
            vm.updateDraft("half-typed")
            holder.set(
                IncomingShareHolder.Pending(
                    uris = emptyList(),
                    mimeType = null,
                    text = "forwarded hello",
                    isForward = true,
                ),
            )

            vm.onConversationOpened()
            testScheduler.advanceUntilIdle()

            assertThat(vm.state.value.draft).isEqualTo("half-typed\nforwarded hello")
        }

    @Test
    fun `stageForward marks the pending as internal`() = runTest(dispatcher) {
        val holder = IncomingShareHolder()
        val vm = threadViewModel(holder)
        val message: Message = mockk {
            every { body } returns "forwarded hello"
            every { attachments } returns emptyList()
        }

        vm.stageForward(message)

        assertThat(holder.pending.value?.text).isEqualTo("forwarded hello")
        assertThat(holder.pending.value?.isForward).isTrue()
    }

    private fun threadViewModel(
        incomingShare: IncomingShareHolder,
        context: Context = mockk(relaxed = true),
    ): ThreadViewModel {
        val conversation = Conversation(
            id = 7L,
            threadId = null,
            addresses = listOf(PhoneAddress.of("+33612345678")),
            displayName = null,
            lastMessageAt = 0L,
            lastMessagePreview = null,
            unreadCount = 0,
            pinned = false,
            archived = false,
            muted = false,
            inVault = false,
            draft = null,
        )
        val repo: ConversationRepository = mockk(relaxed = true) {
            every { observeOne(any()) } returns flowOf(conversation)
            every { observeVaultHidden(any()) } returns flowOf(false)
            every { observeMessagesWindow(any(), any()) } returns emptyFlow()
        }
        val settings: SettingsRepository = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(AppSettings())
        }
        val appLock: AppLockManager = mockk(relaxed = true) {
            every { state } returns MutableStateFlow(AppLockManager.LockState.Unlocked)
        }
        return ThreadViewModel(
            savedStateHandle = SavedStateHandle(mapOf("conversationId" to conversation.id)),
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
            contactRepo = FakeContacts(),
            sendReaction = mockk(relaxed = true),
            scheduleMessage = mockk(relaxed = true),
            incomingShare = incomingShare,
            activeConversationTracker = mockk(relaxed = true),
            context = context,
            io = dispatcher,
            appLock = appLock,
        )
    }
}
