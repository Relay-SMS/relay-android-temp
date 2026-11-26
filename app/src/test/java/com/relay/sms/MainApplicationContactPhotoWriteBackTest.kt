/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms

import com.relay.sms.data.contacts.ContactsReader
import com.relay.sms.data.local.datastore.SettingsRepository
import com.relay.sms.data.repository.ConversationMirror
import com.relay.sms.domain.settings.AppSettings
import com.relay.sms.sync.LinkedDeviceStore
import com.relay.sms.sync.SyncServer
import com.relay.sms.system.sync.ContactPhotoWriteBack
import dagger.Lazy
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Photo write-back trigger — the `MainApplication` contacts
 * ContentObserver's debounced handler calls
 * [ContactPhotoWriteBack.writeBackPendingPhotos] after the
 * name refresh runs. This application owns only the TRIGGER
 * (one call + the injection); the write-back logic itself
 * lives in the parallel workstream's class, mocked here.
 *
 * The handler is exercised directly: booting the real
 * application under Robolectric would build the Hilt graph and
 * the SQLCipher-backed database, which the JVM cannot load
 * (see `HiltRobolectricSmokeTest` — a database-backed binding
 * raises `UnsatisfiedLinkError` there). Instead the test
 * constructs the application without running `onCreate`,
 * injects mocked lazies by reflection, and invokes the private
 * observer handler — which proves the wiring (debounce, name
 * refresh first, write-back after) with the write-back class
 * mocked. The ContentObserver → handler glue itself is three
 * lines (`onChange` → `onContactsChanged` → the debounced job)
 * and is covered by invoking `onContactsChanged`, the method
 * the observer calls.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class MainApplicationContactPhotoWriteBackTest {

    @Test
    fun `a contacts change triggers the photo write-back after the name refresh`() = runTest {
        val app = MainApplication()
        val contactsReader = mockk<ContactsReader>()
        every { contactsReader.invalidate() } just Runs
        val mirror = mockk<ConversationMirror>()
        coEvery { mirror.refreshContactNames(any()) } just Runs
        val writeBack = mockk<ContactPhotoWriteBack>()
        coEvery { writeBack.writeBackPendingPhotos() } just Runs
        val settings = mockk<SettingsRepository>()
        coEvery { settings.hydratedOrNull() } returns AppSettings()
        val linkedStore = mockk<LinkedDeviceStore>()
        every { linkedStore.serverEnabled } returns flowOf(false)
        setField(app, "appScope", this)
        setField(app, "settingsRepository", settings)
        setField(app, "linkedDeviceStore", linkedStore)
        setField(app, "contactsReaderLazy", Lazy { contactsReader })
        setField(app, "syncServerLazy", Lazy { mockk<SyncServer>() })
        setField(app, "conversationMirrorLazy", Lazy { mirror })
        setField(app, "contactPhotoWriteBackLazy", Lazy { writeBack })

        invokePrivate(app, "onContactsChanged")
        advanceTimeBy(600L)
        advanceUntilIdle()

        coVerifyOrder {
            mirror.refreshContactNames(clearMissing = true)
            writeBack.writeBackPendingPhotos()
        }
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = MainApplication::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun invokePrivate(target: Any, name: String) {
        val method = MainApplication::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(target)
    }
}
