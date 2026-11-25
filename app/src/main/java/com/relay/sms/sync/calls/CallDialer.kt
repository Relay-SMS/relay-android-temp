/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.sync.calls

/**
 * The platform seam for dialing and ending calls: executes a
 * `dial_request` from a linked desktop as a cellular call on the
 * hub phone, and an `end_call_request` as a hangup of the
 * current call. The real implementation is [AndroidCallDialer];
 * tests use a fake that records the number and returns a canned
 * outcome.
 *
 * The outcomes map 1:1 onto the `dial_result` / `end_call_result`
 * frames' `error` codes (see `SyncProtocol.DialResult` /
 * `SyncProtocol.EndCallResult`) — the engine never invents a
 * code of its own.
 */
interface CallDialer {
    /**
     * Dials [number] on the phone. Never throws: every failure
     * mode is a [DialOutcome].
     */
    suspend fun dial(number: String): DialOutcome

    /**
     * Ends the current call on the phone, if there is one. Never
     * throws: every failure mode is an [EndCallOutcome].
     * `TelecomManager.endCall` exists only since API 28, so
     * below that the answer is [EndCallOutcome.Failed].
     */
    suspend fun endCall(): EndCallOutcome
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

/** What an end-call attempt came back with. */
sealed interface EndCallOutcome {
    /** The call was ended. */
    data object Success : EndCallOutcome

    /** ANSWER_PHONE_CALLS is not granted on the phone. */
    data object PermissionDenied : EndCallOutcome

    /** No call is in progress, so there is nothing to end. */
    data object NoCall : EndCallOutcome

    /** Anything else: no telephony on the device, the OS refused, or the device is below API 28. */
    data object Failed : EndCallOutcome
}
