/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.smartactions

import com.google.common.truth.Truth.assertThat
import com.relay.sms.domain.smartactions.MapCoordinates
import org.junit.jupiter.api.Test

/**
 * The pure parts of [MapPreviewFetcher]: the static-map URL, the
 * Nominatim answer parsing and the cache-key normalisation. No network
 * tests — the fetcher's network path is fail-soft and covered by the
 * same shape as the link preview fetcher.
 */
class MapPreviewFetcherTest {

    @Test fun `static map url`() {
        assertThat(staticMapUrl(37.422, -122.084)).isEqualTo(
            "https://staticmap.openstreetmap.de/staticmap.php" +
                "?center=37.422000,-122.084000&zoom=15&size=640x360" +
                "&maptype=mapnik&markers=37.422000,-122.084000,red-pushpin",
        )
    }

    @Test fun `static map url rounds to six decimals`() {
        assertThat(staticMapUrl(37.422123456, -122.084987654)).isEqualTo(
            "https://staticmap.openstreetmap.de/staticmap.php" +
                "?center=37.422123,-122.084988&zoom=15&size=640x360" +
                "&maptype=mapnik&markers=37.422123,-122.084988,red-pushpin",
        )
    }

    @Test fun `nominatim first result parses`() {
        val json = """[{"place_id":123,"lat":"37.422000","lon":"-122.084000","display_name":""" +
            """"Google Building 41, 1600, Amphitheatre Parkway, Mountain View, Santa Clara """ +
            """County, California, 94043, United States"}]"""
        assertThat(parseNominatimFirstResult(json)).isEqualTo(
            MapCoordinates(
                latitude = 37.422,
                longitude = -122.084,
                displayName = "Google Building 41, 1600, Amphitheatre Parkway, Mountain View, " +
                    "Santa Clara County, California, 94043, United States",
            ),
        )
    }

    @Test fun `nominatim empty array is null`() {
        assertThat(parseNominatimFirstResult("[]")).isNull()
        assertThat(parseNominatimFirstResult("  []\n")).isNull()
    }

    @Test fun `nominatim malformed body is null`() {
        assertThat(parseNominatimFirstResult("not json")).isNull()
        assertThat(parseNominatimFirstResult("")).isNull()
        assertThat(parseNominatimFirstResult("[{")).isNull()
    }

    @Test fun `nominatim result without coordinates is null`() {
        assertThat(parseNominatimFirstResult("""[{"place_id":123,"display_name":"Nowhere"}]"""))
            .isNull()
    }

    @Test fun `nominatim result with non-numeric coordinates is null`() {
        assertThat(
            parseNominatimFirstResult("""[{"lat":"abc","lon":"-122.084"}]"""),
        ).isNull()
    }

    @Test fun `display name is optional`() {
        assertThat(
            parseNominatimFirstResult("""[{"lat":"37.422","lon":"-122.084"}]"""),
        ).isEqualTo(MapCoordinates(37.422, -122.084, null))
    }

    @Test fun `cache key normalisation`() {
        assertThat(normalizeAddressForCache("  1600  Amphitheatre   Parkway "))
            .isEqualTo("1600 amphitheatre parkway")
        assertThat(normalizeAddressForCache("123 Main St,\nSpringfield"))
            .isEqualTo("123 main st, springfield")
    }
}
