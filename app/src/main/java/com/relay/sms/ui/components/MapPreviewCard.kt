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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.relay.sms.R
import com.relay.sms.data.smartactions.MapPreviewFetcher
import com.relay.sms.data.smartactions.staticMapUrl
import com.relay.sms.domain.smartactions.MapCoordinates
import com.relay.sms.domain.smartactions.SmartAction
import java.io.File

/**
 * Round 53 — a tap-to-load map preview for the first physical address in
 * a message body: the address is geocoded through Nominatim and the
 * static map image comes from staticmap.openstreetmap.de (see
 * [MapPreviewFetcher]), with the required OSM attribution. Renders
 * nothing when the body has no address.
 *
 * Tap-to-load ONLY: geocoding sends the address to OSM's server and
 * addresses can be home addresses, so nothing is fetched until the user
 * taps "Load map preview" (user-initiated, like the Klipy GIF search).
 * A geocode the process already cached renders immediately — the lookup
 * already happened, so no new request goes out.
 *
 * Fail-soft everywhere: a geocode or image failure shows "Map preview
 * unavailable — tap to retry" (the retry re-attempts; a network failure
 * is never cached, so a retry can succeed), and an address Nominatim
 * definitively has no result for renders nothing — no preview exists
 * for it, so there is nothing to retry. The address itself stays
 * tappable ("Open in Maps") whatever happens here.
 *
 * The caller ([MessageBubble]) skips this on FAILED messages: the card
 * tap would fight the tap-to-retry, the same reason linkification is off
 * there.
 */
@Composable
fun MapPreviewCard(
    body: String,
    messageId: Long,
    onSmartAction: (SmartAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val address = remember(body, messageId) { firstSmartAddress(body, messageId) }
    if (address == null) return
    val fetcher = remember { MapPreviewFetcher() }
    val isCached = remember(address) { fetcher.isGeocodeCached(address) }
    var requested by remember(address) { mutableStateOf(isCached) }
    var retry by remember(address) { mutableIntStateOf(0) }
    val state by produceState<MapPreviewState>(
        initialValue = if (isCached) {
            when (val cached = fetcher.cachedGeocode(address)) {
                null -> MapPreviewState.NoPreview
                else -> MapPreviewState.Loaded(cached)
            }
        } else {
            MapPreviewState.Idle
        },
        address,
        requested,
        retry,
    ) {
        if (!requested || isCached) return@produceState
        value = MapPreviewState.Loading
        value = fetcher.geocode(address)?.let { MapPreviewState.Loaded(it) }
            ?: MapPreviewState.Unavailable
    }
    when (val current = state) {
        MapPreviewState.Idle -> MapPreviewLoadButton(
            onLoad = { requested = true },
            modifier = modifier,
        )
        MapPreviewState.Loading -> MapPreviewPlaceholder(modifier = modifier)
        is MapPreviewState.Loaded -> MapPreviewLoaded(
            address = address,
            coords = current.coords,
            onSmartAction = onSmartAction,
            onRetry = { retry++ },
            modifier = modifier,
        )
        MapPreviewState.Unavailable -> MapPreviewUnavailable(
            onRetry = { retry++ },
            modifier = modifier,
        )
        MapPreviewState.NoPreview -> return
    }
}

/** The card's geocode state (see [MapPreviewCard]). */
private sealed interface MapPreviewState {
    /** Not requested yet: show the load button. */
    data object Idle : MapPreviewState

    /** A geocode is in flight: show the placeholder. */
    data object Loading : MapPreviewState

    /** Geocoded: show the map image. */
    data class Loaded(val coords: MapCoordinates) : MapPreviewState

    /** Geocode (or image) failed: show the retry row. */
    data object Unavailable : MapPreviewState

    /** Nominatim definitively has no result: render nothing. */
    data object NoPreview : MapPreviewState
}

/** The tap-to-load button: the label plus the hint that a map loads from OpenStreetMap. */
@Composable
private fun MapPreviewLoadButton(onLoad: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(MAP_PREVIEW_CORNER_DP.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = MAP_PREVIEW_BELOW_GAP_DP.dp)
            .clip(shape)
            .border(width = 1.dp, color = cs.outlineVariant, shape = shape)
            .background(cs.surface)
            .clickable { onLoad() }
            .padding(MAP_PREVIEW_TEXT_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.map_preview_load),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.primary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.map_preview_load_hint),
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
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

/** The retry row after a geocode or image failure. */
@Composable
private fun MapPreviewUnavailable(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(MAP_PREVIEW_CORNER_DP.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = MAP_PREVIEW_BELOW_GAP_DP.dp)
            .clip(shape)
            .border(width = 1.dp, color = cs.outlineVariant, shape = shape)
            .background(cs.surface)
            .clickable { onRetry() }
            .padding(MAP_PREVIEW_TEXT_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.map_preview_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The loaded card: the static map image (16:9, from the on-disk cache —
 * [LinkPreviewImageCache] with its own `map_previews` directory, so a
 * re-entered thread shows the image immediately) plus the OSM
 * attribution line. Tapping the card opens the address in the user's
 * maps app, like tapping the address span itself. A failed image
 * download shows the retry row instead (the geocode stays cached, so
 * the retry only re-attempts the image).
 */
@Composable
private fun MapPreviewLoaded(
    address: String,
    coords: MapCoordinates,
    onSmartAction: (SmartAction) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val imageUrl = remember(coords) { staticMapUrl(coords.latitude, coords.longitude) }
    val cache = remember(context) {
        LinkPreviewImageCache(File(context.cacheDir, MAP_PREVIEW_CACHE_DIR))
    }
    val initialFile = remember(imageUrl) { cache.cachedFile(imageUrl) }
    var imageLoading by remember(imageUrl) { mutableStateOf(initialFile == null) }
    val file by produceState<File?>(
        initialValue = initialFile,
        imageUrl,
    ) {
        if (value == null) {
            value = cache.fetch(imageUrl)
            imageLoading = false
        }
    }
    if (imageLoading) {
        MapPreviewPlaceholder(modifier = modifier)
        return
    }
    if (file == null) {
        MapPreviewUnavailable(onRetry = onRetry, modifier = modifier)
        return
    }
    val shape = RoundedCornerShape(MAP_PREVIEW_CORNER_DP.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = MAP_PREVIEW_BELOW_GAP_DP.dp)
            .clip(shape)
            .border(width = 1.dp, color = cs.outlineVariant, shape = shape)
            .background(cs.surface)
            .clickable { onSmartAction(SmartAction.Address(address)) },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(MAP_PREVIEW_IMAGE_RATIO),
        ) {
            AsyncImage(
                model = file,
                contentDescription = coords.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = stringResource(R.string.map_preview_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(MAP_PREVIEW_ATTRIBUTION_PADDING_DP.dp),
        )
    }
}

private const val MAP_PREVIEW_CORNER_DP = 12
private const val MAP_PREVIEW_BELOW_GAP_DP = 8
private const val MAP_PREVIEW_TEXT_PADDING_DP = 12
private const val MAP_PREVIEW_ATTRIBUTION_PADDING_DP = 8
private const val MAP_PREVIEW_IMAGE_RATIO = 16f / 9f
private const val MAP_PREVIEW_PLACEHOLDER_DP = 96

/** The map-image cache directory, under `cacheDir` (the system may clear it). */
private const val MAP_PREVIEW_CACHE_DIR = "map_previews"
