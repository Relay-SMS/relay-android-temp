/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.components

import com.google.common.truth.Truth.assertThat
import com.relay.sms.domain.settings.MapsProvider
import org.junit.jupiter.api.Test

/**
 * Round 55 — the first-map-tap sheet gate ([shouldShowMapProviderSheet]):
 * the sheet shows once, on the first map tap, only while the user has
 * never explicitly chosen a provider; a provider picked in Settings
 * (non-default) skips it even before the flag existed.
 */
class MapProviderSheetTest {

    @Test fun `sheet shows on first map tap with the default provider`() {
        assertThat(
            shouldShowMapProviderSheet(MapsProvider.SYSTEM_DEFAULT, false),
        ).isTrue()
    }

    @Test fun `sheet never shows once asked`() {
        for (provider in MapsProvider.entries) {
            assertThat(shouldShowMapProviderSheet(provider, true)).isFalse()
        }
    }

    @Test fun `sheet never shows when a provider was already picked in settings`() {
        assertThat(
            shouldShowMapProviderSheet(MapsProvider.APPLE_MAPS, false),
        ).isFalse()
        assertThat(
            shouldShowMapProviderSheet(MapsProvider.GOOGLE_MAPS, false),
        ).isFalse()
        assertThat(
            shouldShowMapProviderSheet(MapsProvider.OPENSTREETMAP, false),
        ).isFalse()
    }
}
