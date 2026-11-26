/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.settings

/**
 * Round 53 — the smart-actions settings: which maps app opens when the
 * user taps an address in a message (or the "Get directions"
 * notification action). The choice drives every directions open — the
 * pill, the address span tap, and the notification action.
 */
data class SmartActionSettings(
    /**
     * Which maps app opens for an address. Default
     * [MapsProvider.SYSTEM_DEFAULT] — Android resolves the `geo:` intent.
     * Persisted by enum NAME (`smart.mapsProvider`); an unknown name
     * reads back as [MapsProvider.SYSTEM_DEFAULT].
     */
    val mapsProvider: MapsProvider = MapsProvider.SYSTEM_DEFAULT,
    /**
     * Round 55 — whether the user has ever explicitly chosen a maps
     * provider (in Settings, or through the first-map-tap sheet): the
     * sheet shows once, on the first map tap, only while this is
     * false. Persisted (`smart.mapsProviderAsked`); a provider picked
     * in Settings before this flag existed still skips the sheet,
     * because the thread also checks `mapsProvider != SYSTEM_DEFAULT`.
     */
    val mapsProviderAsked: Boolean = false,
    /**
     * Round 57 — when the maps provider was last written, epoch
     * millis: the sync write marker for the maps-provider sync
     * (SYNC_PROTOCOL.md, "Maps provider sync"). Every local write
     * of [mapsProvider] stamps `System.currentTimeMillis()` here;
     * a synced write stamps the marker the frame carried, never
     * "now" — the marker is what last-write-wins compares across
     * devices. Persisted (`smart.mapsProviderUpdatedAt`); 0 means
     * "never written" (the provider is still the default).
     */
    val mapsProviderUpdatedAt: Long = 0L,
)

/**
 * The maps provider for "Get directions".
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
