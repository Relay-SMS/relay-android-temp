/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.local.datastore

import android.content.Context
import android.content.SharedPreferences

/**
 * Round 58 — the loading-logo swap: whether the first-open intro has
 * already been shown, readable SYNCHRONOUSLY at cold start.
 *
 * The DataStore flag (`AdvancedSettings.splashShown`) is the source of
 * truth, but reading it is async — and the loading window IS that read
 * (plus the one-shot database repair). To decide, before the first
 * frame, whether the loading screen may show the animated logo,
 * `MainActivity` needs the answer without waiting: this tiny
 * SharedPreferences mirror gives it.
 *
 * Written by `MainActivity`'s cold-start coroutine once the DataStore
 * flag is observed `true` — that coroutine already waits for exactly
 * that flag, so on every cold start it re-syncs the mirror. If the
 * two ever diverge (a process death between the two writes), the
 * next launch heals it; the only cost of a divergence is one launch
 * without the loading logo (mirror `false`, DataStore `true`) or, in
 * the other direction, one brief loading logo before the intro.
 * `SplashViewModel.markShown` only writes the DataStore flag; it
 * does not touch this mirror.
 *
 * The mirror is cleared wherever the DataStore flag is reset to
 * `false` — "Reset all settings" and "Delete my data" (both
 * sessions) — via [clear]: without that, a reset would show the
 * loading logo and then the first-open intro back to back (a double
 * logo).
 *
 * It lives in the data module, next to the settings persistence,
 * because `PanicService` (data) must clear it. The primary
 * constructor takes the [SharedPreferences] itself (like
 * [ViewerChoiceStore] takes its DataStore) so plain-JVM tests can
 * pass a fake.
 */
class SplashShownStore(private val prefs: SharedPreferences) {

    companion object {
        /** Preferences file for the splash-shown mirror. */
        const val PREFS_NAME: String = "splash-shown"

        /** The mirror of `AdvancedSettings.splashShown` inside [PREFS_NAME]. */
        const val KEY_SHOWN: String = "shown"

        /**
         * Clears the mirror without an instance — the reset paths
         * ("Reset all settings", "Delete my data") do not hold a
         * store, they just need the flag gone.
         */
        fun clear(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_SHOWN).apply()
        }
    }

    /** Whether the first-open intro has already been shown. */
    val isShown: Boolean
        get() = prefs.getBoolean(KEY_SHOWN, false)

    /** Records that the first-open intro has been shown. */
    fun setShown() {
        prefs.edit().putBoolean(KEY_SHOWN, true).apply()
    }

    /** Forgets the flag — the next launch is a first open again. */
    fun clear() {
        prefs.edit().remove(KEY_SHOWN).apply()
    }
}
