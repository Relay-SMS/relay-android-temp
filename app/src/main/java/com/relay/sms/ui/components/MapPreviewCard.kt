/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relay.sms.R
import com.relay.sms.data.smartactions.MapPreviewFetcher
import com.relay.sms.domain.linkpreview.LinkPreview
import com.relay.sms.domain.settings.MapsProvider
import com.relay.sms.smartactions.SmartActionIntents

/**
 * Round 53 — a tap-to-load map preview for the first physical address in
 * a message body; reworked round 54 (see below). Renders nothing when
 * the body has no address.
 *
 * What the card shows follows the Settings maps-provider choice (the
 * same rule as desktop `MapPreviewCard.tsx`):
 * - Apple Maps: a tap-to-load PREVIEW — Relay unfurls the Apple Maps
 *   link for the address through the link-preview Open Graph machinery
 *   (og:image, og:title — Apple's own map card, fetched with a Safari
 *   User-Agent; see [MapPreviewFetcher]). The tap geocodes (Nominatim)
 *   and fetches the Apple page, so nothing is sent to anyone until the
 *   user taps "Show map". A cached preview renders on first paint,
 *   like the link preview card. The card's image opens the Apple Maps
 *   page; the "Get directions" button under it opens directions in
 *   Apple Maps.
 * - Every other provider (Google, OpenStreetMap, System default): no
 *   preview image exists (Google needs a billed API key, the OSM static
 *   service is dead, System has no provider), so the card IS the "Get
 *   directions" button — no tap-to-load step, no fetch of any kind
 *   before the tap.
 *
 * Fail-soft, Apple: a geocode failure, a page with no usable OG tags
 * (og:image is UNCONFIRMED — see [MapPreviewFetcher], the device test
 * is the final confirmation), or a failed image all land on the "Get
 * directions" button, exactly like the other providers. A failed open
 * shows a note under the button; the address itself stays tappable
 * ("Open in Maps") whatever happens here.
 *
 * The caller ([MessageBubble]) skips this on FAILED messages: the card
 * tap would fight the tap-to-retry, the same reason linkification is off
 * there.
 */
@Composable
fun MapPreviewCard(
    body: String,
    messageId: Long,
    mapsProvider: MapsProvider = MapsProvider.SYSTEM_DEFAULT,
    modifier: Modifier = Modifier,
) {
    val address = remember(body, messageId) { firstSmartAddress(body, messageId) }
    if (address == null) return
    // Every provider but Apple: the card is the button, no fetch.
    if (mapsProvider != MapsProvider.APPLE_MAPS) {
        MapDirectionsButton(
            address = address,
            mapsProvider = mapsProvider,
            modifier = modifier,
        )
        return
    }
    val fetcher = remember { MapPreviewFetcher() }
    val isCached = remember(address) { fetcher.isAppleMapsPreviewCached(address) }
    var requested by remember(address) { mutableStateOf(isCached) }
    val state by produceState<MapPreviewState>(
        initialValue = if (isCached) {
            when (val cached = fetcher.cachedAppleMapsPreview(address)) {
                null -> MapPreviewState.NoPreview
                else -> MapPreviewState.Loaded(cached)
            }
        } else {
            MapPreviewState.Idle
        },
        address,
        requested,
    ) {
        if (!requested || isCached) return@produceState
        value = MapPreviewState.Loading
        value = fetcher.fetchAppleMapsPreview(address)?.let { MapPreviewState.Loaded(it) }
            ?: MapPreviewState.NoPreview
    }
    when (val current = state) {
        MapPreviewState.Idle -> MapPreviewShowMapButton(
            onLoad = { requested = true },
            modifier = modifier,
        )
        MapPreviewState.Loading -> MapPreviewPlaceholder(modifier = modifier)
        is MapPreviewState.Loaded -> MapPreviewAppleCard(
            preview = current.preview,
            address = address,
            modifier = modifier,
        )
        MapPreviewState.NoPreview -> MapDirectionsButton(
            address = address,
            mapsProvider = mapsProvider,
            modifier = modifier,
        )
    }
}

/** The Apple card's load state (see [MapPreviewCard]). */
private sealed interface MapPreviewState {
    /** Not requested yet: show the "Show map" button. */
    data object Idle : MapPreviewState

    /** A geocode + Apple page fetch is in flight: show the placeholder. */
    data object Loading : MapPreviewState

    /** Unfurled: show the Apple map card. */
    data class Loaded(val preview: LinkPreview) : MapPreviewState

    /** No preview for this address: show the directions button instead. */
    data object NoPreview : MapPreviewState
}

/** The tap-to-load button: "Show map" — nothing is fetched until the tap. */
@Composable
private fun MapPreviewShowMapButton(onLoad: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(MAP_PREVIEW_CORNER_DP.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = MAP_PREVIEW_BELOW_GAP_DP.dp)
            .clip(shape)
            .border(width = 1.dp, color = cs.outlineVariant, shape = shape)
            .background(cs.surface)
            .clickable { onLoad() }
            .padding(MAP_PREVIEW_TEXT_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.map_show_map),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.primary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Loading skeleton: the same box as the loaded card, so the thread barely grows. */
@Composable
private fun MapPreviewPlaceholder(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(MAP_PREVIEW_CORNER_DP.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = MAP_PREVIEW_BELOW_GAP_DP.dp)
            .height(MAP_PREVIEW_PLACEHOLDER_DP.dp)
            .clip(shape)
            .border(width = 1.dp, color = cs.outlineVariant, shape = shape)
            .background(cs.surface),
    )
}

/**
 * The "Get directions" button (+ failed-open note) — the whole card for
 * non-Apple providers, the footer for the Apple preview card. Opens
 * directions to the address in the user's maps provider.
 */
@Composable
private fun MapDirectionsButton(
    address: String,
    mapsProvider: MapsProvider,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var openFailed by remember(address, mapsProvider) { mutableStateOf(false) }
    val shape = RoundedCornerShape(MAP_PREVIEW_CORNER_DP.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = MAP_PREVIEW_BELOW_GAP_DP.dp)
            .clip(shape)
            .border(width = 1.dp, color = cs.outlineVariant, shape = shape)
            .background(cs.surface)
            .clickable {
                openFailed = false
                if (!SmartActionIntents.performDirections(context, address, mapsProvider)) {
                    openFailed = true
                }
            }
            .padding(MAP_PREVIEW_TEXT_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.map_get_directions),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.primary,
            textAlign = TextAlign.Center,
        )
        if (openFailed) {
            Text(
                text = stringResource(R.string.map_directions_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The loaded Apple card: the Open Graph image on top (16:9 crop, from
 * the shared link-preview image cache), then the title (or the
 * description when there is no title) and the site name (or the URL
 * host), with the "Get directions" button as the footer. Tapping the
 * card opens the Apple Maps page; the button opens directions in Apple
 * Maps. Its own surface background plus a hairline outline, so it reads
 * on both the blue outgoing bubble and the grey incoming one, in either
 * theme.
 */
@Composable
private fun MapPreviewAppleCard(
    preview: LinkPreview,
    address: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val host = remember(preview.url) { preview.url.hostOrNull() }
    val headline = preview.title ?: preview.description
    val shape = RoundedCornerShape(MAP_PREVIEW_CORNER_DP.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = MAP_PREVIEW_BELOW_GAP_DP.dp)
            .clip(shape)
            .border(width = 1.dp, color = cs.outlineVariant, shape = shape)
            .background(cs.surface)
            .clickable { uriHandler.openUri(preview.url) },
    ) {
        val imageUrl = preview.imageUrl
        if (imageUrl != null) {
            LinkPreviewCachedImage(imageUrl = imageUrl)
        }
        Column(modifier = Modifier.padding(MAP_PREVIEW_TEXT_PADDING_DP.dp)) {
            if (headline != null) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = MAP_PREVIEW_HEADLINE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val domain = preview.siteName ?: host
            if (domain != null) {
                Text(
                    text = domain,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        MapDirectionsButton(
            address = address,
            mapsProvider = MapsProvider.APPLE_MAPS,
            modifier = Modifier.padding(
                start = MAP_PREVIEW_TEXT_PADDING_DP.dp,
                end = MAP_PREVIEW_TEXT_PADDING_DP.dp,
            ),
        )
    }
}

private const val MAP_PREVIEW_CORNER_DP = 12
private const val MAP_PREVIEW_BELOW_GAP_DP = 8
private const val MAP_PREVIEW_TEXT_PADDING_DP = 12
private const val MAP_PREVIEW_HEADLINE_LINES = 2
private const val MAP_PREVIEW_PLACEHOLDER_DP = 96
