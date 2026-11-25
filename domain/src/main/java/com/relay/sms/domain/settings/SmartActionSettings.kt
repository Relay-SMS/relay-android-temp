/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.settings

/**
 * Round 53 — the smart-actions settings: which maps app opens when the
 * user taps an address in a message (or the "Open Maps" notification
 * action). The choice affects "Open in" only; the tap-to-load map
 * preview image is always OpenStreetMap, whatever the choice.
 */
data class SmartActionSettings(
    /**
     * Which maps app opens for an address. Default
     * [MapsProvider.SYSTEM_DEFAULT] — Android resolves the `geo:` intent.
     * Persisted by enum NAME (`smart.mapsProvider`); an unknown name
     * reads back as [MapsProvider.SYSTEM_DEFAULT].
     */
    val mapsProvider: MapsProvider = MapsProvider.SYSTEM_DEFAULT,
)

/**
 * The maps provider for "Open in Maps".
 *
 * ⚠️ Persisted BY NAME (`smart.mapsProvider`): add a provider, never
 * rename or remove one. An unknown name — an older version reading a
 * newer value — falls back to [SYSTEM_DEFAULT]
 * (`SettingsRepository.enumOr`).
 */
enum class MapsProvider {
    /** A plain `geo:` intent — Android resolves it to the user's maps app. */
    SYSTEM_DEFAULT,

    /** The Google Maps app when installed, otherwise the web version. */
    GOOGLE_MAPS,

    /** Apple Maps on the web — there is no Apple Maps app for Android. */
    APPLE_MAPS,

    /** An OpenStreetMap app when installed, otherwise openstreetmap.org. */
    OPENSTREETMAP,
}
