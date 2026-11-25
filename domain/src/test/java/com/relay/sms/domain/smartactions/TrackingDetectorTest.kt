/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.smartactions

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Positive and negative cases for [TrackingDetector].
 *
 * The valid vectors are the carriers' own published examples where one
 * exists (UPS `1Z999AA10123456784`, USPS `9400111206206406260787`, S10
 * `EE123456785US`, FedEx `449044304137821`); the others are constructed
 * under the documented check-digit rule and marked as such. The negative
 * cases are the point: an order number or a mistyped digit must never
 * surface a "Track package" action.
 */
class TrackingDetectorTest {

    private fun first(text: String): SmartAction.Tracking? =
        TrackingDetector.findFirst(text)?.action as? SmartAction.Tracking

    // ──────────────── UPS ────────────────

    @Test fun `ups canonical example`() {
        assertThat(first("Your package 1Z999AA10123456784 is on its way"))
            .isEqualTo(SmartAction.Tracking(TrackingCarrier.UPS, "1Z999AA10123456784"))
    }

    @Test fun `ups worked example`() {
        assertThat(first("1Z5R89390357567127"))
            .isEqualTo(SmartAction.Tracking(TrackingCarrier.UPS, "1Z5R89390357567127"))
    }

    @Test fun `ups with a wrong check digit is not detected`() {
        assertThat(first("1Z999AA10123456785")).isNull()
    }

    @Test fun `ups too short is not detected`() {
        assertThat(first("1Z999AA1012345678")).isNull()
    }

    @Test fun `ups glued to letters is not detected`() {
        assertThat(first("X1Z999AA10123456784")).isNull()
        assertThat(first("1Z999AA10123456784X")).isNull()
    }

    // ──────────────── USPS ────────────────

    @Test fun `usps 22 digit impb`() {
        assertThat(first("9400111206206406260787"))
            .isEqualTo(SmartAction.Tracking(TrackingCarrier.USPS, "9400111206206406260787"))
    }

    @Test fun `usps 22 digit with a wrong check digit is not detected`() {
        assertThat(first("9434611206206407667131")).isNull()
    }

    @Test fun `usps 20 digit legacy form`() {
        // Constructed under the `91`-prefixed legacy rule (see the
        // detector KDoc): no published 20-digit vector was available.
        assertThat(first("94001112062064062600"))
            .isEqualTo(SmartAction.Tracking(TrackingCarrier.USPS, "94001112062064062600"))
    }

    @Test fun `usps s10 international`() {
        assertThat(first("EE123456785US"))
            .isEqualTo(SmartAction.Tracking(TrackingCarrier.USPS, "EE123456785US"))
    }

    @Test fun `s10 with a wrong check digit is not detected`() {
        assertThat(first("EE123456784US")).isNull()
    }

    // ──────────────── FedEx ────────────────

    @Test fun `fedex 15 digit`() {
        assertThat(first("449044304137821"))
            .isEqualTo(SmartAction.Tracking(TrackingCarrier.FEDEX, "449044304137821"))
    }

    @Test fun `fedex 12 digit`() {
        // Constructed under the mod-10 3/1 rule (check digit 2).
        assertThat(first("123456789012"))
            .isEqualTo(SmartAction.Tracking(TrackingCarrier.FEDEX, "123456789012"))
    }

    @Test fun `fedex 20 digit smartpost`() {
        // Constructed under the mod-10 3/1 rule (check digit 4).
        assertThat(first("96120190000000000004"))
            .isEqualTo(SmartAction.Tracking(TrackingCarrier.FEDEX, "96120190000000000004"))
    }

    @Test fun `fedex with a wrong check digit is not detected`() {
        assertThat(first("449044304137822")).isNull()
        assertThat(first("123456789013")).isNull()
    }

    // ──────────────── Negatives ────────────────

    @Test fun `order numbers and ids are not tracking numbers`() {
        assertThat(first("Your order 12345 ships today")).isNull()
        assertThat(first("Reference 987654321")).isNull()
    }

    @Test fun `a phone number is not a tracking number`() {
        assertThat(first("Call me on 5551234567")).isNull()
    }

    @Test fun `a 13 digit run matches no carrier`() {
        assertThat(first("1234567890123")).isNull()
    }

    @Test fun `a 34 digit fedex run is not detected in v1`() {
        assertThat(first("1234567890123456789012345678901234")).isNull()
    }

    @Test fun `plain text has no tracking number`() {
        assertThat(first("hello, how are you?")).isNull()
        assertThat(first("")).isNull()
    }

    @Test fun `two tracking numbers in order`() {
        val found = TrackingDetector.findAll("1Z999AA10123456784 then 449044304137821")
        assertThat(found).hasSize(2)
        assertThat((found[0].action as SmartAction.Tracking).carrier).isEqualTo(TrackingCarrier.UPS)
        assertThat((found[1].action as SmartAction.Tracking).carrier).isEqualTo(TrackingCarrier.FEDEX)
    }
}
