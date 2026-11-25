/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.system.notifications

import com.google.common.truth.Truth.assertThat
import com.relay.sms.domain.smartactions.SmartAction
import com.relay.sms.domain.smartactions.SmartActionMatch
import com.relay.sms.domain.smartactions.TrackingCarrier
import org.junit.jupiter.api.Test

/**
 * Round 53 — the notification's single smart action
 * ([pickNotificationSmartAction]): the earliest of the first detector
 * match and the first phone number, by text position. Pure — the caller
 * supplies both candidates, so no `Context` or `Patterns` is needed
 * here.
 */
class NotificationSmartActionTest {

    private fun emailMatch(start: Int, end: Int) = SmartActionMatch(
        start..end,
        SmartAction.Email("bob@example.com"),
    )

    private fun trackingMatch(start: Int, end: Int) = SmartActionMatch(
        start..end,
        SmartAction.Tracking(TrackingCarrier.UPS, "1Z999AA10123456784"),
    )

    @Test fun `no match and no phone means no action`() {
        assertThat(pickNotificationSmartAction(null, null)).isNull()
    }

    @Test fun `a detector match alone is the action`() {
        assertThat(pickNotificationSmartAction(emailMatch(5, 20), null))
            .isEqualTo(SmartAction.Email("bob@example.com"))
    }

    @Test fun `a phone alone is a call action`() {
        assertThat(pickNotificationSmartAction(null, 5 to "+33612345678"))
            .isEqualTo(SmartAction.Call("+33612345678"))
    }

    @Test fun `an earlier phone beats a later detector match`() {
        assertThat(pickNotificationSmartAction(trackingMatch(30, 48), 5 to "+33612345678"))
            .isEqualTo(SmartAction.Call("+33612345678"))
    }

    @Test fun `an earlier detector match beats a later phone`() {
        assertThat(pickNotificationSmartAction(emailMatch(5, 20), 30 to "+33612345678"))
            .isEqualTo(SmartAction.Email("bob@example.com"))
    }

    @Test fun `a tie goes to the detector match`() {
        assertThat(pickNotificationSmartAction(emailMatch(5, 20), 5 to "+33612345678"))
            .isEqualTo(SmartAction.Email("bob@example.com"))
    }
}
