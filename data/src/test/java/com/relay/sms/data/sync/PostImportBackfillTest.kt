/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.sync

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.common.truth.Truth.assertThat
import com.relay.sms.data.blocking.BlockedNumberSystem
import com.relay.sms.data.blocking.BlockedNumbersImporter
import com.relay.sms.data.categories.StickyCategoryStore
import com.relay.sms.data.local.datastore.SettingsRepository
import com.relay.sms.data.local.db.AppDatabase
import com.relay.sms.data.local.db.dao.ConversationDao
import com.relay.sms.data.local.db.dao.MessageDao
import com.relay.sms.data.sms.PhoneIdentity
import com.relay.sms.data.sms.TelephonyReader
import com.relay.sms.domain.repository.BlockedNumberRepository
import com.relay.sms.domain.settings.AppSettings
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Round 50 (Part 3) — the post-import sticky backfill's TIMING.
 *
 * The startup backfill runs at cold start, BEFORE the first import on
 * a fresh install, so it files nothing there and never runs again.
 * The post-import pass runs once, at the end of a sync in which the
 * MMS import has completed (the SMS delta ran in the same sync, so
 * both imports are done by then). These tests drive a real
 * [TelephonySyncManager.requestSync] with an empty provider and pin
 * the gate: it runs exactly when the imports are complete and the
 * flag is unset, and never otherwise.
 */
class PostImportBackfillTest {

    private val telephonyReader = mockk<TelephonyReader>()
    private val messageDao = mockk<MessageDao>()
    private val stickyCategories = mockk<StickyCategoryStore>()

    /**
     * `ContextCompat.checkSelfPermission` calls `TextUtils.equals`
     * internally, which throws Stub! on plain JVM (the same reason
     * the LocationResolver tests fail in this harness) — so the
     * permission check is stubbed statically. Undone after each test
     * so the static mock never leaks into another test class.
     */
    @BeforeEach
    fun grantReadSms() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns
            PackageManager.PERMISSION_GRANTED
    }

    @AfterEach
    fun unmockStatics() {
        unmockkAll()
    }

    /** A SettingsRepository backed by an in-memory state whose `update` applies the transform. */
    private fun fakeSettings(initial: AppSettings): Pair<SettingsRepository, MutableStateFlow<AppSettings>> {
        val state = MutableStateFlow(initial)
        val repo = mockk<SettingsRepository> {
            every { flow } returns state
            coEvery { update(any()) } coAnswers {
                val transform = firstArg<(AppSettings) -> AppSettings>()
                state.value = transform(state.value)
            }
        }
        return repo to state
    }

    private fun advanced(
        mmsImportCompleted: Boolean,
        backfilledPostImport: Boolean,
    ) = AppSettings().advanced.copy(
        lastSyncedSmsId = 5L,
        mmsImportCompleted = mmsImportCompleted,
        stickyCategoryBackfilledPostImport = backfilledPostImport,
    )

    private fun manager(
        settings: SettingsRepository,
        appScope: kotlinx.coroutines.CoroutineScope,
    ): TelephonySyncManager {
        // The permission check is stubbed statically (see grantReadSms);
        // the Context itself is never consulted.
        val context = mockk<Context>()
        return TelephonySyncManager(
            context = context,
            settings = settings,
            telephonyReader = telephonyReader,
            mirror = mockk(),
            messageDao = messageDao,
            conversationDao = mockk<ConversationDao>(),
            database = mockk<AppDatabase>(),
            notifications = mockk(),
            blockedRepo = mockk<BlockedNumberRepository> {
                coEvery { blockedRawSnapshot() } returns emptyList()
            },
            blockedSystem = mockk<BlockedNumberSystem>(),
            phoneIdentity = mockk<PhoneIdentity> {
                coEvery { snapshot() } returns PhoneIdentity.Snapshot(null)
            },
            blockedNumbersImporter = mockk<BlockedNumbersImporter> {
                coEvery { importFromSystem(any()) } just Runs
            },
            stickyCategories = stickyCategories,
            appScope = appScope,
            io = Dispatchers.Unconfined,
        )
    }

    /** Empty provider: no MMS to import, no SMS delta, nothing to reconcile. */
    private fun stubEmptySync() {
        coEvery { messageDao.hasAnyMms() } returns true
        coEvery { telephonyReader.readSmsSince(any(), any(), any()) } returns 5L
        coEvery { messageDao.listMirroredTelephonyUris() } returns emptyList()
        coEvery { stickyCategories.backfill() } returns 3
    }

    @Test
    fun `backfill runs once after a sync with both imports complete`() = runTest {
        stubEmptySync()
        val (settings, state) = fakeSettings(
            AppSettings().copy(advanced = advanced(mmsImportCompleted = true, backfilledPostImport = false)),
        )

        manager(settings, this).requestSync("test")
        advanceUntilIdle()

        coVerify(exactly = 1) { stickyCategories.backfill() }
        assertThat(state.value.advanced.stickyCategoryBackfilledPostImport).isTrue()
    }

    @Test
    fun `backfill waits while the mms import is incomplete`() = runTest {
        stubEmptySync()
        val (settings, state) = fakeSettings(
            AppSettings().copy(advanced = advanced(mmsImportCompleted = false, backfilledPostImport = false)),
        )

        manager(settings, this).requestSync("test")
        advanceUntilIdle()

        coVerify(exactly = 0) { stickyCategories.backfill() }
        assertThat(state.value.advanced.stickyCategoryBackfilledPostImport).isFalse()
    }

    @Test
    fun `backfill never re-runs once its flag is set`() = runTest {
        stubEmptySync()
        val (settings, state) = fakeSettings(
            AppSettings().copy(advanced = advanced(mmsImportCompleted = true, backfilledPostImport = true)),
        )

        manager(settings, this).requestSync("test")
        advanceUntilIdle()

        coVerify(exactly = 0) { stickyCategories.backfill() }
        assertThat(state.value.advanced.stickyCategoryBackfilledPostImport).isTrue()
    }
}
