/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.smartactions

/**
 * Round 53 — where a street address is, as resolved by the map preview's
 * geocoder (Nominatim): the coordinates the static map centres on, plus
 * the display name the geocoder returned for the address, when it gave
 * one. Produced by [com.relay.sms.data.smartactions.MapPreviewFetcher],
 * consumed by the thread's map preview card.
 */
data class MapCoordinates(
    val latitude: Double,
    val longitude: Double,
    val displayName: String? = null,
    /**
     * Round 55 — the city/town/village from Nominatim's structured
     * `address` object, when it gave one: the OSM city strip
     * ([stripCityFromAddress]) drops this component from the query.
     */
    val city: String? = null,
)
