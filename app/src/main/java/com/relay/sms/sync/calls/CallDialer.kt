/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.sync.calls

/**
 * The platform seam for dialing: executes a `dial_request` from a
 * linked desktop as a cellular call on the hub phone. The real
 * implementation is [AndroidCallDialer]; tests use a fake that
 * records the number and returns a canned outcome.
 *
 * The outcome maps 1:1 onto the `dial_result` frame's `error`
 * codes (see `SyncProtocol.DialResult`) — the engine never
 * invents a code of its own.
 */
interface CallDialer {
    /**
     * Dials [number] on the phone. Never throws: every failure
     * mode is a [DialOutcome].
     */
    suspend fun dial(number: String): DialOutcome
}

/** What a dial attempt came back with. */
sealed interface DialOutcome {
    /** The call was placed (the dialer took the number). */
    data object Success : DialOutcome

    /** CALL_PHONE is not granted on the phone. */
    data object PermissionDenied : DialOutcome

    /** The request carried no usable number (blank). */
    data object NoNumber : DialOutcome

    /** Anything else: no dialer installed, or the OS refused. */
    data object Failed : DialOutcome
}
