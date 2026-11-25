/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.smartactions

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * The aggregator's own rules ([SmartActionDetector]): one run over all
 * four detectors, URL spans swallowing matches inside them, and the
 * earliest action first. The detectors' own cases live in their tests.
 */
class SmartActionDetectorTest {

    /** Tuesday 2026-11-24, noon UTC — the date detector's reference. */
    private val nowMillis: Long = Instant.parse("2026-11-24T12:00:00Z").toEpochMilli()
    private val zone = ZoneOffset.UTC

    @Test fun `all four kinds in one message, in position order`() {
        val found = SmartActionDetector.detectAll(
            "Mail bob@example.com, meet at 123 Main Street on Dec 25, parcel 1Z999AA10123456784",
            nowMillis,
            zone,
        )
        assertThat(found).hasSize(4)
        assertThat(found[0].action).isEqualTo(SmartAction.Email("bob@example.com"))
        assertThat(found[1].action).isEqualTo(SmartAction.Address("123 Main Street"))
        assertThat(found[2].action).isInstanceOf(SmartAction.DateTime::class.java)
        assertThat(found[3].action)
            .isEqualTo(SmartAction.Tracking(TrackingCarrier.UPS, "1Z999AA10123456784"))
    }

    @Test fun `first action is the earliest by position`() {
        val first = SmartActionDetector.firstAction(
            "Dec 25 at 123 Main Street",
            nowMillis,
            zone,
        )
        assertThat(first?.action).isInstanceOf(SmartAction.DateTime::class.java)
    }

    @Test fun `a tracking number inside a url is not an action`() {
        assertThat(
            SmartActionDetector.firstAction(
                "Track it: https://example.com/track/1Z999AA10123456784",
                nowMillis,
                zone,
            ),
        ).isNull()
    }

    @Test fun `an email inside a url is not an action`() {
        assertThat(
            SmartActionDetector.firstAction(
                "See https://example.com/?email=bob@example.com",
                nowMillis,
                zone,
            ),
        ).isNull()
    }

    @Test fun `a tracking number next to a url still matches`() {
        val found = SmartActionDetector.firstAction(
            "See https://example.com and parcel 1Z999AA10123456784",
            nowMillis,
            zone,
        )
        assertThat(found?.action)
            .isEqualTo(SmartAction.Tracking(TrackingCarrier.UPS, "1Z999AA10123456784"))
    }

    @Test fun `plain text has no action`() {
        assertThat(SmartActionDetector.detectAll("hello, how are you?", nowMillis, zone)).isEmpty()
        assertThat(SmartActionDetector.detectAll("", nowMillis, zone)).isEmpty()
    }
}
