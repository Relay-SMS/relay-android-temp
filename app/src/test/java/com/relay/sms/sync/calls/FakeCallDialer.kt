/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.sync.calls

/** A recording [CallDialer]: tests read back the dialed numbers and set the outcome. */
internal class FakeCallDialer(
    var outcome: DialOutcome = DialOutcome.Success,
    var endOutcome: EndCallOutcome = EndCallOutcome.Success,
) : CallDialer {
    val dialedNumbers = mutableListOf<String>()

    /** How many times [endCall] ran — the end has no payload to record. */
    var endCallCount = 0
        private set

    override suspend fun dial(number: String): DialOutcome {
        dialedNumbers += number
        return outcome
    }

    override suspend fun endCall(): EndCallOutcome {
        endCallCount++
        return endOutcome
    }
}
