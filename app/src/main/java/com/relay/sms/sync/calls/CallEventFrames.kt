/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.sync.calls

import com.relay.sms.sync.SyncProtocol

/**
 * The wire shape of one [CallEvent] (SYNC_PROTOCOL.md, Live
 * calls): a ringing phone sends BOTH `incoming_call` (the
 * announcement, with the timestamp) and `call_state` `ringing`
 * (the state, like every other transition); every other
 * transition sends only its `call_state`. Pure — no Android
 * imports — so the broadcast mapping is unit-tested here rather
 * than through a live server.
 */
fun encodeCallEventFrames(event: CallEvent): List<String> = when (event) {
    is CallEvent.Ringing -> listOf(
        SyncProtocol.encodeIncomingCall(
            SyncProtocol.IncomingCall(number = event.number, timestamp = event.timestampMs),
        ),
        SyncProtocol.encodeCallState(
            SyncProtocol.CallState(
                state = SyncProtocol.CallState.RINGING,
                number = event.number,
            ),
        ),
    )
    is CallEvent.Dialing -> listOf(
        SyncProtocol.encodeCallState(
            SyncProtocol.CallState(
                state = SyncProtocol.CallState.DIALING,
                number = event.number,
            ),
        ),
    )
    is CallEvent.Active -> listOf(
        SyncProtocol.encodeCallState(
            SyncProtocol.CallState(
                state = SyncProtocol.CallState.ACTIVE,
                number = event.number,
            ),
        ),
    )
    is CallEvent.Ended -> listOf(
        SyncProtocol.encodeCallState(
            SyncProtocol.CallState(
                state = SyncProtocol.CallState.ENDED,
                number = event.number,
            ),
        ),
    )
}
