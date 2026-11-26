/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens.compose

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.relay.sms.core.result.AppError
import com.relay.sms.core.result.Outcome
import com.relay.sms.domain.clientmode.ClientActionGateway
import com.relay.sms.domain.model.Note
import com.relay.sms.domain.repository.ContactRepository
import com.relay.sms.domain.repository.ConversationRepository
import com.relay.sms.domain.repository.NoteRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Notes — the compose screen's "New note" create: a valid title
 * creates the note and emits `NoteCreated` (the screen opens its
 * thread), a blank title is ignored, and a long title is capped at
 * 40 characters before it reaches the repository.
 */
class ComposeViewModelCreateNoteTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Harness(
        val vm: ComposeViewModel,
        val noteRepo: NoteRepository,
    )

    private fun harness(): Harness {
        val noteRepo = mockk<NoteRepository>()
        // The ViewModel reads the notes flow at construction (the
        // "Select note" picker section), so the strict mock needs
        // an answer for it before any test body runs.
        every { noteRepo.observeAllNotes() } returns MutableStateFlow(emptyList())
        val vm = ComposeViewModel(
            SavedStateHandle(),
            mockk<ContactRepository>(relaxed = true),
            mockk<ConversationRepository>(relaxed = true),
            dispatcher,
            noteRepo,
        )
        return Harness(vm, noteRepo)
    }

    private fun createdNote(id: Long = 42L, title: String = "Groceries") = Note(
        id = id,
        uuid = "note-uuid",
        title = title,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        lastMessageAt = null,
        lastMessagePreview = null,
        draft = null,
        pinned = false,
        archived = false,
        bubbleColorArgb = null,
    )

    @Test fun `createNote emits NoteCreated with the new id`() = runTest(dispatcher) {
        val h = harness()
        coEvery { h.noteRepo.createNote(any(), any(), any(), any()) } returns createdNote()

        h.vm.events.test {
            h.vm.createNote("Groceries")
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(ComposeViewModel.Event.NoteCreated(42L))
        }
        coVerify { h.noteRepo.createNote("Groceries", any(), any(), any()) }
    }

    @Test fun `blank title is ignored`() = runTest(dispatcher) {
        val h = harness()

        h.vm.createNote("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { h.noteRepo.createNote(any(), any(), any(), any()) }
    }

    @Test fun `long title is capped at 40 chars`() = runTest(dispatcher) {
        val h = harness()
        coEvery { h.noteRepo.createNote(any(), any(), any(), any()) } returns createdNote()

        h.vm.createNote("x".repeat(50))
        advanceUntilIdle()

        coVerify { h.noteRepo.createNote("x".repeat(40), any(), any(), any()) }
    }

    @Test fun `createNote in client mode goes through the create_note frame`() = runTest(dispatcher) {
        val noteRepo = mockk<NoteRepository>()
        every { noteRepo.observeAllNotes() } returns MutableStateFlow(emptyList())
        val clientActions = mockk<ClientActionGateway>()
        coEvery { clientActions.isClientMode() } returns true
        coEvery { clientActions.createNote("Groceries") } returns Outcome.Success(42L)
        val vm = ComposeViewModel(
            SavedStateHandle(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            dispatcher,
            noteRepo,
            clientActions,
        )

        vm.events.test {
            vm.createNote("Groceries")
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(ComposeViewModel.Event.NoteCreated(42L))
        }
        // No optimistic note: the hub creates it, `note_created` resolves the id.
        coVerify(exactly = 0) { noteRepo.createNote(any(), any(), any(), any()) }
    }

    @Test fun `a refused client-mode create emits CreateFailed`() = runTest(dispatcher) {
        val noteRepo = mockk<NoteRepository>()
        every { noteRepo.observeAllNotes() } returns MutableStateFlow(emptyList())
        val clientActions = mockk<ClientActionGateway>()
        coEvery { clientActions.isClientMode() } returns true
        coEvery { clientActions.createNote("Groceries") } returns
            Outcome.Failure(AppError.ClientOffline)
        val vm = ComposeViewModel(
            SavedStateHandle(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            dispatcher,
            noteRepo,
            clientActions,
        )

        vm.events.test {
            vm.createNote("Groceries")
            advanceUntilIdle()

            assertThat(awaitItem())
                .isEqualTo(ComposeViewModel.Event.CreateFailed(AppError.ClientOffline))
        }
    }
}
