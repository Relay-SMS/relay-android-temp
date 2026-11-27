/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.local.datastore

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Round 58 — the loading-logo swap: the synchronous mirror of
 * `AdvancedSettings.splashShown`.
 *
 * Plain JVM: the prefs are a MockK mock backed by a map, so a write
 * is visible to the next read — the mirror must behave like a
 * store, not like a stub that answers a fixed value.
 *
 * Block bodies, so every assertion ends the test with a void
 * statement (JUnit silently skips a `@Test` whose body returns a
 * value).
 */
class SplashShownStoreTest {

    /** In-memory prefs: reads see the writes, like the real thing. */
    private class InMemoryPrefs {
        val values = mutableMapOf<String, Boolean>()
        val editor: SharedPreferences.Editor = mockk {
            every { putBoolean(any(), any()) } answers {
                values[firstArg<String>()] = secondArg()
                editor
            }
            every { remove(any()) } answers {
                values.remove(firstArg<String>())
                editor
            }
            every { apply() } just Runs
        }
        val prefs: SharedPreferences = mockk {
            every { getBoolean(any(), any()) } answers {
                values[firstArg<String>()] ?: secondArg()
            }
            every { edit() } returns editor
        }
    }

    @Test
    fun `isShown defaults to false on a fresh install`() {
        val store = SplashShownStore(InMemoryPrefs().prefs)

        assertThat(store.isShown).isFalse()
    }

    @Test
    fun `setShown makes isShown true and writes the key`() {
        val backing = InMemoryPrefs()
        val store = SplashShownStore(backing.prefs)

        store.setShown()

        assertThat(store.isShown).isTrue()
        verify { backing.editor.putBoolean(SplashShownStore.KEY_SHOWN, true) }
        verify { backing.editor.apply() }
    }

    @Test
    fun `clear makes isShown false again`() {
        val backing = InMemoryPrefs()
        val store = SplashShownStore(backing.prefs)
        store.setShown()

        store.clear()

        assertThat(store.isShown).isFalse()
        verify { backing.editor.remove(SplashShownStore.KEY_SHOWN) }
    }

    @Test
    fun `clear(context) removes the flag without an instance`() {
        val backing = InMemoryPrefs()
        val store = SplashShownStore(backing.prefs)
        store.setShown()
        val context = mockk<Context> {
            every { getSharedPreferences(SplashShownStore.PREFS_NAME, Context.MODE_PRIVATE) } returns backing.prefs
        }

        SplashShownStore.clear(context)

        assertThat(store.isShown).isFalse()
        verify { backing.editor.remove(SplashShownStore.KEY_SHOWN) }
    }
}
