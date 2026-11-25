/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.smartactions

import com.google.common.truth.Truth.assertThat
import com.relay.sms.domain.smartactions.SmartAction
import com.relay.sms.domain.smartactions.TrackingCarrier
import org.junit.jupiter.api.Test

/**
 * The pure part of [SmartActionIntents]: the carrier tracking URLs.
 *
 * The intent builders themselves (`emailIntent`, `dialIntent`,
 * `calendarIntent`, `trackingIntent`, `mapsIntent`, `geoUriString`,
 * `mapsWebUrl`) call `android.net.Uri` / `Intent`, which throw `Stub!`
 * on plain JVM — they are covered by the device test, not here. The URL
 * shaping they hand to the browser is pinned here instead: a wrong
 * tracking URL sends the user to a carrier 404, so the three carriers'
 * forms are asserted exactly.
 */
class SmartActionIntentsTest {

    @Test fun `ups tracking url`() {
        assertThat(
            SmartActionIntents.trackingUrl(
                SmartAction.Tracking(TrackingCarrier.UPS, "1Z999AA10123456784"),
            ),
        ).isEqualTo("https://www.ups.com/track?tracknum=1Z999AA10123456784")
    }

    @Test fun `usps tracking url`() {
        assertThat(
            SmartActionIntents.trackingUrl(
                SmartAction.Tracking(TrackingCarrier.USPS, "9400111206206406260787"),
            ),
        ).isEqualTo(
            "https://tools.usps.com/go/TrackConfirmAction?tLabels=9400111206206406260787",
        )
    }

    @Test fun `fedex tracking url`() {
        assertThat(
            SmartActionIntents.trackingUrl(
                SmartAction.Tracking(TrackingCarrier.FEDEX, "449044304137821"),
            ),
        ).isEqualTo("https://www.fedex.com/fedextrack/?trknbr=449044304137821")
    }

    @Test fun `google maps package name`() {
        assertThat(SmartActionIntents.GOOGLE_MAPS_PACKAGE)
            .isEqualTo("com.google.android.apps.maps")
    }

    @Test fun `osm packages in preference order`() {
        assertThat(SmartActionIntents.OSM_PACKAGES)
            .containsExactly("net.osmand.plus", "net.osmand", "app.organicmaps").inOrder()
    }
}
