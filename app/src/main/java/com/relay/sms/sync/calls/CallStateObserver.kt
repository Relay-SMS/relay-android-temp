/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.sync.calls

import kotlinx.coroutines.flow.Flow

/**
 * The platform seam for cellular call-state observation: the
 * [SyncServer] starts it while the server runs and broadcasts
 * its [events] to every authenticated session. The real
 * implementation is [TelephonyCallStateObserver]; tests use a
 * fake over a MutableSharedFlow (see `FakeCallStateObserver` in
 * the tests).
 *
 * `start` / `stop` are idempotent and never throw: a phone
 * without telephony, or without READ_PHONE_STATE, simply
 * produces no events — the observer degrades to silence, never
 * to a failed server start.
 */
interface CallStateObserver {
    /** The call-state transitions, in arrival order. */
    val events: Flow<CallEvent>

    /** Starts listening for call-state changes. */
    fun start()

    /** Stops listening. */
    fun stop()
}
