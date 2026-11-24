/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.sync

import com.relay.sms.sync.calls.CallDialer
import com.relay.sms.sync.calls.DialOutcome
import com.relay.sms.sync.calls.EndCallOutcome

// -- Live calls: dial + end call ---------------------------------------
// The runners for `dial_request` / `end_call_request`, called by
// [SyncEngine.onDialRequest] / [SyncEngine.onEndCallRequest].
// They live here, not in SyncEngine.kt: the runners touch no
// engine state beyond the dialer and the send lambda, and
// SyncEngine.kt is at the Detekt function limit (35 per file) —
// the end-call runners pushed it over, so the dial runners moved
// with them to keep the pair together.

/**
 * Runs one `dial_request`: the dialer places the call (or refuses
 * it), and the outcome maps 1:1 onto the `dial_result` frame — a
 * session without a dialer (tests) answers `failed`. A refused
 * dial is never a protocol violation: the socket stays up, and
 * the desktop shows the reason.
 */
internal suspend fun runDialRequest(
    dialer: CallDialer?,
    send: suspend (String) -> Unit,
    request: SyncProtocol.DialRequest,
) {
    val outcome = dialer?.dial(request.number) ?: DialOutcome.Failed
    val result = when (outcome) {
        DialOutcome.Success -> SyncProtocol.DialResult(ok = true)
        DialOutcome.PermissionDenied -> SyncProtocol.DialResult(
            ok = false,
            error = SyncProtocol.DialResult.PERMISSION_DENIED,
        )
        DialOutcome.NoNumber -> SyncProtocol.DialResult(
            ok = false,
            error = SyncProtocol.DialResult.NO_NUMBER,
        )
        DialOutcome.Failed -> SyncProtocol.DialResult(
            ok = false,
            error = SyncProtocol.DialResult.FAILED,
        )
    }
    send(SyncProtocol.encodeDialResult(result))
}

internal fun dialResultInternal(): String =
    SyncProtocol.encodeDialResult(
        SyncProtocol.DialResult(
            ok = false,
            error = SyncProtocol.DialResult.FAILED,
        ),
    )

/**
 * Runs one `end_call_request`: the dialer ends the current call
 * (or refuses it), and the outcome maps 1:1 onto the
 * `end_call_result` frame — a session without a dialer (tests)
 * answers `failed`. A refused end is never a protocol
 * violation: the socket stays up, and the desktop shows the
 * reason.
 */
internal suspend fun runEndCallRequest(
    dialer: CallDialer?,
    send: suspend (String) -> Unit,
) {
    val outcome = dialer?.endCall() ?: EndCallOutcome.Failed
    val result = when (outcome) {
        EndCallOutcome.Success -> SyncProtocol.EndCallResult(ok = true)
        EndCallOutcome.PermissionDenied -> SyncProtocol.EndCallResult(
            ok = false,
            error = SyncProtocol.EndCallResult.PERMISSION_DENIED,
        )
        EndCallOutcome.NoCall -> SyncProtocol.EndCallResult(
            ok = false,
            error = SyncProtocol.EndCallResult.NO_CALL,
        )
        EndCallOutcome.Failed -> SyncProtocol.EndCallResult(
            ok = false,
            error = SyncProtocol.EndCallResult.FAILED,
        )
    }
    send(SyncProtocol.encodeEndCallResult(result))
}

internal fun endCallResultInternal(): String =
    SyncProtocol.encodeEndCallResult(
        SyncProtocol.EndCallResult(
            ok = false,
            error = SyncProtocol.EndCallResult.FAILED,
        ),
    )
