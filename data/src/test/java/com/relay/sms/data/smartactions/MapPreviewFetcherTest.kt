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
 * The pure parts of [MapPreviewFetcher]: the Apple Maps preview URL, the
 * Nominatim answer parsing and the cache-key normalisation. No network
 * tests — the fetcher's network path is fail-soft and covered by the
 * same shape as the link preview fetcher.
 */
class MapPreviewFetcherTest {

    @Test fun `apple maps preview url`() {
        assertThat(appleMapsPreviewUrl("1600 Amphitheatre Parkway", 37.422, -122.084))
            .isEqualTo(
                "https://maps.apple.com/?q=1600%20Amphitheatre%20Parkway" +
                    "&ll=37.422000,-122.084000",
            )
    }

    @Test fun `apple maps preview url rounds coordinates to six decimals`() {
        assertThat(appleMapsPreviewUrl("1600 Amphitheatre Parkway", 37.422123456, -122.084987654))
            .isEqualTo(
                "https://maps.apple.com/?q=1600%20Amphitheatre%20Parkway" +
                    "&ll=37.422123,-122.084988",
            )
    }

    @Test fun `apple maps preview url encodes the address`() {
        assertThat(appleMapsPreviewUrl("123 Main St, Springfield", 1.0, 2.0)).isEqualTo(
            "https://maps.apple.com/?q=123%20Main%20St%2C%20Springfield&ll=1.000000,2.000000",
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

    // ──────────────────── Round 55 — Google + OSM preview URLs, city strip ────────────────────

    @Test fun `google maps preview url pins ll`() {
        assertThat(googleMapsPreviewUrl("1600 Amphitheatre Parkway", 37.422, -122.084))
            .isEqualTo(
                "https://maps.google.com/maps?q=1600%20Amphitheatre%20Parkway" +
                    "&ll=37.422000,-122.084000",
            )
    }

    @Test fun `google maps search url has no ll`() {
        assertThat(googleMapsSearchUrl("New York, NY"))
            .isEqualTo("https://maps.google.com/maps?q=New%20York%2C%20NY")
    }

    @Test fun `osm search url encodes the query`() {
        assertThat(osmSearchUrl("1600 Amphitheatre Parkway, CA"))
            .isEqualTo(
                "https://www.openstreetmap.org/search?query=" +
                    "1600%20Amphitheatre%20Parkway%2C%20CA",
            )
    }

    @Test fun `city strip drops the city component`() {
        assertThat(
            stripCityFromAddress(
                "1600 Amphitheatre Parkway, Mountain View, CA 94043",
                "Mountain View",
            ),
        ).isEqualTo("1600 Amphitheatre Parkway, CA 94043")
    }

    @Test fun `city strip leaves a street name containing the city word alone`() {
        // "Mountain View" inside the street name is not a component.
        assertThat(
            stripCityFromAddress("1 Mountain View Road, Springfield, IL", "Mountain View"),
        ).isEqualTo("1 Mountain View Road, Springfield, IL")
    }

    @Test fun `city strip is a no-op without a city or for a city-only address`() {
        assertThat(stripCityFromAddress("1600 Amphitheatre Parkway, CA", null))
            .isEqualTo("1600 Amphitheatre Parkway, CA")
        assertThat(stripCityFromAddress("Mountain View", "Mountain View"))
            .isEqualTo("Mountain View")
    }

    @Test fun `nominatim city parses from the structured address`() {
        val json = """[{"lat":"37.422","lon":"-122.084","display_name":"1600 Amphitheatre """ +
            """Parkway, Mountain View, CA","address":{"city":"Mountain View","state":"California"}}]"""
        assertThat(parseNominatimFirstResult(json)?.city).isEqualTo("Mountain View")
    }

    @Test fun `nominatim town falls back when there is no city`() {
        val json = """[{"lat":"48.0","lon":"2.0","address":{"town":"Versailles"}}]"""
        assertThat(parseNominatimFirstResult(json)?.city).isEqualTo("Versailles")
    }
}
