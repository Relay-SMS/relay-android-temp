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
 * `calendarIntent`, `trackingIntent`, `directionsIntent`) call
 * `android.net.Uri` / `Intent`, which throw `Stub!` on plain JVM —
 * they are covered by the device test, not here. The URL shaping they
 * hand to the browser is pinned here instead: a wrong tracking URL
 * sends the user to a carrier 404, and a wrong directions URL to a
 * maps 404, so the carriers' and the providers' forms are asserted
 * exactly. [SmartActionIntents.mapsDirectionsUrl] stays pure on
 * purpose (URL-encoded with `URLEncoder`, not `Uri.encode`) so it is
 * testable here.
 */
class SmartActionIntentsTest {

    @Test fun `ups tracking url`() {
        assertThat(
            SmartActionIntents.trackingUrl(
                SmartAction.Tracking(TrackingCarrier.UPS, "1Z999AA10123456784"),
            ),
        ).isEqualTo(
            "https://www.ups.com/track?tracknum=1Z999AA10123456784" +
                "&accept_UPS_license_agreement=yes&loc=en_US&requester=ST/trackdetails",
        )
    }

    // Round 56 — the UPS deep link Kristin's device test verified: the
    // bare track?tracknum= URL redirects to a broken page; the
    // license-agreement + loc + /trackdetails form lands on the details
    // page (sample: her real 1Z7A377E0392030514).
    @Test fun `ups tracking url carries the license agreement and trackdetails`() {
        assertThat(
            SmartActionIntents.trackingUrl(
                SmartAction.Tracking(TrackingCarrier.UPS, "1Z7A377E0392030514"),
            ),
        ).isEqualTo(
            "https://www.ups.com/track?tracknum=1Z7A377E0392030514" +
                "&accept_UPS_license_agreement=yes&loc=en_US&requester=ST/trackdetails",
        )
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

    @Test fun `osm strip drops the locality before state and zip`() {
        assertThat(stripLocalityForOsm("5466 Elam Rd, Kinzers, PA 17535"))
            .isEqualTo("5466 Elam Rd, PA 17535")
    }

    @Test fun `osm strip keeps earlier components and drops only the locality`() {
        assertThat(stripLocalityForOsm("123 Main St, Apt 4, Kinzers, PA 17535"))
            .isEqualTo("123 Main St, Apt 4, PA 17535")
    }

    @Test fun `osm strip leaves short addresses unchanged`() {
        assertThat(stripLocalityForOsm("Kinzers, PA 17535"))
            .isEqualTo("Kinzers, PA 17535")
        assertThat(stripLocalityForOsm("5466 Elam Rd, PA 17535"))
            .isEqualTo("5466 Elam Rd, PA 17535")
        assertThat(stripLocalityForOsm("Kinzers")).isEqualTo("Kinzers")
        assertThat(stripLocalityForOsm("")).isEqualTo("")
    }

    @Test fun `osm strip leaves a non state zip tail unchanged`() {
        assertThat(stripLocalityForOsm("5466 Elam Rd, Kinzers, PA"))
            .isEqualTo("5466 Elam Rd, Kinzers, PA")
        assertThat(stripLocalityForOsm("5466 Elam Rd, Kinzers"))
            .isEqualTo("5466 Elam Rd, Kinzers")
        assertThat(stripLocalityForOsm("10 Downing St, London SW1A 2AA"))
            .isEqualTo("10 Downing St, London SW1A 2AA")
    }

    @Test fun `osm directions url carries the stripped query`() {
        assertThat(
            SmartActionIntents.mapsDirectionsUrl(
                "5466 Elam Rd, Kinzers, PA 17535",
                MapsProvider.OPENSTREETMAP,
            ),
        ).isEqualTo(
            "https://www.openstreetmap.org/directions?to=5466%20Elam%20Rd%2C%20PA%2017535",
        )
    }

    @Test fun `osm strip drops a lowercase locality before a lowercase state and zip`() {
        assertThat(stripLocalityForOsm("5466 elam rd, kinzers, pa 17535"))
            .isEqualTo("5466 elam rd, pa 17535")
    }

    // Round 60 — the comma-less shapes the detector's optional tail
    // commas produce (desktop parity, smartActions.ts): the city
    // glued to the street, or glued to the state + ZIP.
    @Test fun `osm strip drops a city glued to the street`() {
        assertThat(stripLocalityForOsm("5466 Elam Rd Kinzers, PA 17535"))
            .isEqualTo("5466 Elam Rd, PA 17535")
        assertThat(stripLocalityForOsm("5466 elam rd kinzers, pa 17535"))
            .isEqualTo("5466 elam rd, pa 17535")
    }

    @Test fun `osm strip drops a city glued to the state and zip`() {
        assertThat(stripLocalityForOsm("5466 Elam Rd, Kinzers PA 17535"))
            .isEqualTo("5466 Elam Rd, PA 17535")
        assertThat(stripLocalityForOsm("5466 elam rd, kinzers pa 17535"))
            .isEqualTo("5466 elam rd, pa 17535")
    }

    @Test fun `osm strip keeps the street when a street plus city component precedes state and zip`() {
        assertThat(stripLocalityForOsm("Apt 4, 5466 Elam Rd Kinzers, PA 17535"))
            .isEqualTo("Apt 4, 5466 Elam Rd, PA 17535")
    }

    @Test fun `osm strip leaves a comma-less single component unchanged`() {
        assertThat(stripLocalityForOsm("Kinzers PA 17535")).isEqualTo("Kinzers PA 17535")
        assertThat(stripLocalityForOsm("5466 Elam Rd Kinzers PA 17535"))
            .isEqualTo("5466 Elam Rd Kinzers PA 17535")
    }

    @Test fun `osm directions url strips the comma-less city like desktop`() {
        // The desktop's example: the same detected span builds this
        // exact URL on desktop (directionsUrl("osm", …)).
        assertThat(
            SmartActionIntents.mapsDirectionsUrl(
                "5466 Elam Rd Kinzers, PA 17535",
                MapsProvider.OPENSTREETMAP,
            ),
        ).isEqualTo(
            "https://www.openstreetmap.org/directions?to=5466%20Elam%20Rd%2C%20PA%2017535",
        )
        assertThat(
            SmartActionIntents.mapsDirectionsUrl(
                "5466 Elam Rd, Kinzers PA 17535",
                MapsProvider.OPENSTREETMAP,
            ),
        ).isEqualTo(
            "https://www.openstreetmap.org/directions?to=5466%20Elam%20Rd%2C%20PA%2017535",
        )
    }

    @Test fun `osm directions url matches the desktop url exactly`() {
        // Round 60 — parity with the desktop's `directionsUrl("osm",
        // …)` (smartActions.ts): the same `?to=` form, the same
        // city strip, the same `%20` encoding. Verified against the
        // desktop tree: mobile already builds this URL, so no code
        // change — this test pins the parity so a future drift fails
        // here instead of on device.
        assertThat(
            SmartActionIntents.mapsDirectionsUrl(
                "5466 elam rd, kinzers, pa 17535",
                MapsProvider.OPENSTREETMAP,
            ),
        ).isEqualTo(
            "https://www.openstreetmap.org/directions?to=5466%20elam%20rd%2C%20pa%2017535",
        )
    }

    @Test fun `osm directions url carries the stripped lowercase query`() {
        assertThat(
            SmartActionIntents.mapsDirectionsUrl(
                "5466 elam rd, kinzers, pa 17535",
                MapsProvider.OPENSTREETMAP,
            ),
        ).isEqualTo(
            "https://www.openstreetmap.org/directions?to=5466%20elam%20rd%2C%20pa%2017535",
        )
    }

    @Test fun `other providers keep the full lowercase address as written`() {
        assertThat(
            SmartActionIntents.mapsDirectionsUrl(
                "5466 elam rd, kinzers, pa 17535",
                MapsProvider.GOOGLE_MAPS,
            ),
        ).isEqualTo(
            "https://www.google.com/maps/dir/?api=1&destination=5466%20elam%20rd%2C%20kinzers%2C%20pa%2017535",
        )
    }

    @Test fun `other providers directions urls keep the full address`() {
        assertThat(
            SmartActionIntents.mapsDirectionsUrl(
                "5466 Elam Rd, Kinzers, PA 17535",
                MapsProvider.GOOGLE_MAPS,
            ),
        ).isEqualTo(
            "https://www.google.com/maps/dir/?api=1&destination=5466%20Elam%20Rd%2C%20Kinzers%2C%20PA%2017535",
        )
        assertThat(
            SmartActionIntents.mapsDirectionsUrl(
                "5466 Elam Rd, Kinzers, PA 17535",
                MapsProvider.SYSTEM_DEFAULT,
            ),
        ).isEqualTo("geo:0,0?q=5466%20Elam%20Rd%2C%20Kinzers%2C%20PA%2017535")
    }
}
