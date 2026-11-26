/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.UriHandler
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
 * Round 53 — a map preview for the first physical address in a message
 * body; reworked round 54 (provider matrix); reworked round 55 (see
 * below). Renders nothing when the body has no address.
 *
 * Round 55 — the preview is INLINE and AUTO-LOADED: the card fetches
 * on render, no tap gate, no "Show map" button. Per provider
 * ([MapPreviewFetcher.fetchMapPreview], all verified by live fetch
 * 2026-11-25):
 * - Apple Maps: the Apple Maps link unfurled (og:image = Apple's own
 *   map snapshot, 450x300) — the prettiest of the three.
 * - Google Maps: the Google Maps link unfurled (og:image = Google's
 *   own static map, 900x900, centred on the place via the `ll=` pin).
 * - OpenStreetMap: the OSM search page unfurled (og:image = the OSM
 *   logo — generic, accepted for now); the search query drops the
 *   city name, which breaks the map load on the OSM page.
 * - System default: no preview exists, so the card IS the "Get
 *   directions" button — no fetch of any kind before the tap.
 *
 * Fail-soft: a geocode failure, a page with no usable OG tags, or a
 * failed image all land on the "Get directions" button, exactly like
 * System default. A failed open shows a note under the button; the
 * address itself stays tappable ("Open in Maps") whatever happens here.
 *
 * Round 55 diagnosis of the round-54 "Show map does nothing" report:
 * the button WAS wired (tap → fetch → card or button) and the Apple
 * og:image IS fetchable — the dead feeling was the Loading state: a
 * bare empty box with no spinner while the geocode + page fetch ran
 * (up to ~50s of network on a slow connection), so the tap looked
 * like nothing happened. The placeholder now shows a spinner, and
 * the tap gate is gone, so the card loads on render instead.
 *
 * The caller ([MessageBubble]) skips this on FAILED messages: the card
 * tap would fight the tap-to-retry, the same reason linkification is off
 * there. The card sits BELOW the message text (round 55): the action
 * buttons live under the text, next to the OTP pill and the
 * smart-action pills.
 *
 * Map opens go through [onMapOpen] where the thread wires it: the
 * thread gates the FIRST map tap behind the "Set your map provider"
 * sheet (see [MapProviderSheet]). Where nothing is wired (previews,
 * standalone cards) the tap opens directly. The handler returns
 * whether the tap was consumed, so the directions button can show
 * its "couldn't open" note when the open itself failed.
 */
@Composable
fun MapPreviewCard(
    body: String,
    messageId: Long,
    mapsProvider: MapsProvider = MapsProvider.SYSTEM_DEFAULT,
    onMapOpen: ((MapOpenRequest) -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val address = remember(body, messageId) { firstSmartAddress(body, messageId) }
    if (address == null) return
    // System default: no preview exists — the card is the button, no fetch.
    if (mapsProvider == MapsProvider.SYSTEM_DEFAULT) {
        MapDirectionsButton(
            address = address,
            mapsProvider = mapsProvider,
            onMapOpen = onMapOpen,
            modifier = modifier,
        )
        return
    }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val open: (MapOpenRequest) -> Boolean = onMapOpen ?: { request ->
        defaultOpenMap(request, mapsProvider, context, uriHandler)
    }
    val fetcher = remember { MapPreviewFetcher() }
    val isCached = remember(address, mapsProvider) {
        fetcher.isMapPreviewCached(address, mapsProvider)
    }
    val state by produceState<MapPreviewState>(
        initialValue = if (isCached) {
            when (val cached = fetcher.cachedMapPreview(address, mapsProvider)) {
                null -> MapPreviewState.NoPreview
                else -> MapPreviewState.Loaded(cached.preview, cached.openAddress)
            }
        } else {
            MapPreviewState.Loading
        },
        address,
        mapsProvider,
    ) {
        if (isCached) return@produceState
        value = MapPreviewState.Loading
        value = fetcher.fetchMapPreview(address, mapsProvider)?.let {
            MapPreviewState.Loaded(it.preview, it.openAddress)
        } ?: MapPreviewState.NoPreview
    }
    when (val current = state) {
        MapPreviewState.Loading -> MapPreviewPlaceholder(modifier = modifier)
        is MapPreviewState.Loaded -> MapPreviewLoadedCard(
            preview = current.preview,
            openAddress = current.openAddress,
            mapsProvider = mapsProvider,
            onMapOpen = open,
            modifier = modifier,
        )
        MapPreviewState.NoPreview -> MapDirectionsButton(
            address = fetcher.openAddressFor(address, mapsProvider),
            mapsProvider = mapsProvider,
            onMapOpen = open,
            modifier = modifier,
        )
    }
}

/** The map card's load state (see [MapPreviewCard]). */
private sealed interface MapPreviewState {
    /** The geocode + provider page fetch is in flight: show the spinner. */
    data object Loading : MapPreviewState

    /** Unfurled: show the provider's map card. */
    data class Loaded(val preview: LinkPreview, val openAddress: String) : MapPreviewState

    /** No preview for this address: show the directions button instead. */
    data object NoPreview : MapPreviewState
}

/**
 * The direct open used where the thread does not wire a gate
 * (previews, standalone cards): the page opens in the browser, the
 * directions open in the user's maps provider. Returns whether the
 * open was taken (the page open cannot fail visibly, so it reports
 * taken unless the handler itself throws).
 */
private fun defaultOpenMap(
    request: MapOpenRequest,
    mapsProvider: MapsProvider,
    context: Context,
    uriHandler: UriHandler,
): Boolean = when (request) {
    is MapOpenRequest.OpenPage ->
        runCatching { uriHandler.openUri(request.url) }.isSuccess
    is MapOpenRequest.OpenDirections ->
        SmartActionIntents.performDirections(context, request.address, mapsProvider)
}

/**
 * Loading skeleton: the same box as the loaded card, with a spinner in
 * the middle — the round-54 box was bare, so a slow geocode + fetch
 * looked like the tap did nothing. The thread barely grows either way.
 */
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
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = cs.primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The "Get directions" button (+ failed-open note) — the whole card for
 * System default and for a failed preview, the footer for a loaded
 * card. Opens directions to the address in the user's maps provider
 * (through [onMapOpen] where the thread gates the first tap).
 */
@Composable
private fun MapDirectionsButton(
    address: String,
    mapsProvider: MapsProvider,
    onMapOpen: ((MapOpenRequest) -> Boolean)?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
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
                val open = onMapOpen ?: { request ->
                    defaultOpenMap(request, mapsProvider, context, uriHandler)
                }
                if (!open(MapOpenRequest.OpenDirections(address))) {
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
 * The loaded card: the provider's Open Graph image on top (16:9 crop,
 * from the shared link-preview image cache), then the title (or the
 * description when there is no title) and the site name (or the URL
 * host), with the "Get directions" button as the footer. Tapping the
 * card opens the provider's page; the button opens directions in the
 * user's maps provider. Its own surface background plus a hairline
 * outline, so it reads on both the blue outgoing bubble and the grey
 * incoming one, in either theme.
 */
@Composable
private fun MapPreviewLoadedCard(
    preview: LinkPreview,
    openAddress: String,
    mapsProvider: MapsProvider,
    onMapOpen: (MapOpenRequest) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
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
            .clickable {
                onMapOpen(MapOpenRequest.OpenPage(preview.url, openAddress))
            },
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
            address = openAddress,
            mapsProvider = mapsProvider,
            onMapOpen = onMapOpen,
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
