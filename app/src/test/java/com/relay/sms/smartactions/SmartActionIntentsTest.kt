/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.smartactions

import com.google.common.truth.Truth.assertThat
import com.relay.sms.domain.settings.MapsProvider
import com.relay.sms.domain.smartactions.SmartAction
import com.relay.sms.domain.smartactions.TrackingCarrier
import org.junit.jupiter.api.Test

/**
 * The pure part of [SmartActionIntents]: the carrier tracking URLs and
 * the directions URLs.
 *
 * The intent builders themselves (`emailIntent`, `dialIntent`,
 * `calendarIntent`, `trackingIntent`, `mapsIntent`, `geoUriString`,
 * `mapsWebUrl`, `directionsIntent`) call `android.net.Uri` / `Intent`,
 * which throw `Stub!` on plain JVM — they are covered by the device
 * test, not here. The URL shaping they hand to the browser is pinned
 * here instead: a wrong tracking URL sends the user to a carrier 404,
 * and a wrong directions URL to a maps 404, so the carriers' and the
 * providers' forms are asserted exactly. [SmartActionIntents
 * .mapsDirectionsUrl] stays pure on purpose (URL-encoded with
 * `URLEncoder`, not `Uri.encode`) so it is testable here.
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

    @Test fun `google directions url`() {
        assertThat(
            SmartActionIntents.mapsDirectionsUrl(
                "1600 Amphitheatre Parkway",
                MapsProvider.GOOGLE_MAPS,
            ),
        ).isEqualTo(
            "https://www.google.com/maps/dir/?api=1&destination=1600%20Amphitheatre%20Parkway",
        )
    }

    @Test fun `apple directions url`() {
        assertThat(
            SmartActionIntents.mapsDirectionsUrl(
                "1600 Amphitheatre Parkway",
                MapsProvider.APPLE_MAPS,
            ),
        ).isEqualTo("https://maps.apple.com/?daddr=1600%20Amphitheatre%20Parkway")
    }

    @Test fun `osm directions url`() {
        assertThat(
            SmartActionIntents.mapsDirectionsUrl(
                "1600 Amphitheatre Parkway",
                MapsProvider.OPENSTREETMAP,
            ),
        ).isEqualTo(
            "https://www.openstreetmap.org/directions?to=1600%20Amphitheatre%20Parkway",
        )
    }

    @Test fun `system default directions url is the geo search`() {
        assertThat(
            SmartActionIntents.mapsDirectionsUrl(
                "1600 Amphitheatre Parkway",
                MapsProvider.SYSTEM_DEFAULT,
            ),
        ).isEqualTo("geo:0,0?q=1600%20Amphitheatre%20Parkway")
    }

    @Test fun `directions url encodes the address`() {
        assertThat(
            SmartActionIntents.mapsDirectionsUrl(
                "123 Main St, Springfield",
                MapsProvider.GOOGLE_MAPS,
            ),
        ).isEqualTo(
            "https://www.google.com/maps/dir/?api=1&destination=123%20Main%20St%2C%20Springfield",
        )
    }
}
