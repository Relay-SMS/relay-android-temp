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
) : CallDialer {
    val dialedNumbers = mutableListOf<String>()

    override suspend fun dial(number: String): DialOutcome {
        dialedNumbers += number
        return outcome
    }
}
