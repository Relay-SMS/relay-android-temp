/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.relay.sms.R
import com.relay.sms.domain.settings.MapsProvider
import com.relay.sms.ui.theme.RelayText
import com.relay.sms.ui.theme.brandBlue

/**
 * Round 55 — whether the first-map-tap sheet shows: only while the
 * user has never explicitly chosen a provider AND the current provider
 * is still the default. A non-default provider necessarily came from a
 * Settings pick (the default is [MapsProvider.SYSTEM_DEFAULT]), so a
 * pre-existing pick skips the sheet even before the flag existed.
 */
internal fun shouldShowMapProviderSheet(
    provider: MapsProvider,
    mapsProviderAsked: Boolean,
): Boolean = !mapsProviderAsked && provider == MapsProvider.SYSTEM_DEFAULT

/**
 * Round 55 — the first-map-tap bottom sheet: "Set your map provider",
 * sliding up from the bottom the first time the user taps a map (an
 * address pill or span) while no explicit provider choice exists.
 * Choosing a provider saves it and opens the pending map with it;
 * dismissing (swipe, scrim, back) opens the pending map with the
 * current provider and never re-prompts. The sheet shows once — the
 * caller gates it on [shouldShowMapProviderSheet] and persists the
 * choice/dismissal — and never when the user already picked a provider
 * in Settings. The provider setting drives all map links, so the sheet
 * is for map taps generally, not Apple-only.
 */
@Composable
fun MapProviderSheet(
    current: MapsProvider,
    onSelect: (MapsProvider) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RelaySheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            // Round 56 — the 12dp spacer above the title, matching the
            // forward sheet: the title was jammed against the top of
            // the sheet, right under the drag handle (the shell carries
            // no gap of its own — the content owns it, see
            // [RelaySheet]).
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.map_provider_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.map_provider_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Column(modifier = Modifier.padding(top = 8.dp)) {
                for (provider in MapsProvider.entries) {
                    MapProviderSheetRow(
                        provider = provider,
                        selected = provider == current,
                        onClick = { onSelect(provider) },
                    )
                }
            }
        }
    }
}

/** One provider row: radio + label + hint, mirroring [RelayPickerDialog]'s rows. */
@Composable
private fun MapProviderSheetRow(
    provider: MapsProvider,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = brandBlue()),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(providerLabelRes(provider)),
                style = RelayText.MessageBody,
                color = cs.onSurface,
            )
            Text(
                text = stringResource(providerHintRes(provider)),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

/** The Settings picker's labels, reused so the sheet and Settings agree. */
private fun providerLabelRes(provider: MapsProvider): Int = when (provider) {
    MapsProvider.SYSTEM_DEFAULT -> R.string.settings_maps_app_system
    MapsProvider.GOOGLE_MAPS -> R.string.settings_maps_app_google
    MapsProvider.APPLE_MAPS -> R.string.settings_maps_app_apple
    MapsProvider.OPENSTREETMAP -> R.string.settings_maps_app_osm
}

private fun providerHintRes(provider: MapsProvider): Int = when (provider) {
    MapsProvider.SYSTEM_DEFAULT -> R.string.settings_maps_app_system_hint
    MapsProvider.GOOGLE_MAPS -> R.string.settings_maps_app_google_hint
    MapsProvider.APPLE_MAPS -> R.string.settings_maps_app_apple_hint
    MapsProvider.OPENSTREETMAP -> R.string.settings_maps_app_osm_hint
}
