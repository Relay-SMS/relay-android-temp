/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.smartactions

import com.relay.sms.data.linkpreview.LinkPreviewFetcher
import com.relay.sms.data.linkpreview.extractJsonStringField
import com.relay.sms.di.IoDispatcher
import com.relay.sms.domain.linkpreview.LinkPreview
import com.relay.sms.domain.smartactions.MapCoordinates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 53 — the geocoder behind the tap-to-load map previews in message
 * bubbles; reworked round 54 (see below for the provider matrix).
 *
 * Round 53 geocoded an address through Nominatim
 * (nominatim.openstreetmap.org, free, no key) and centred a static map
 * image from staticmap.openstreetmap.de on the result. That static
 * service is DEAD (DNS NXDOMAIN, verified twice) and no provider has a
 * free keyless static image — Google needs a billed API key, Apple
 * static images need a paid Developer account — so round 54 drops the
 * static map entirely. What the card shows now follows the Settings
 * maps-provider choice (the same rule as desktop `mapPreview.ts`):
 * - Apple Maps: a tap-to-load PREVIEW — Relay unfurls the Apple Maps
 *   link for the address (`maps.apple.com/?q=ADDRESS&ll=LAT,LON`,
 *   [appleMapsPreviewUrl]) through the link-preview Open Graph
 *   machinery ([fetchAppleMapsPreview]) — Apple's own map card, free,
 *   no key, Relay doesn't own or host the image. Nominatim is still
 *   needed, for the `ll=` pin.
 * - Google Maps, OpenStreetMap, System default: NO preview image — the
 *   card IS the "Get directions" button (see
 *   [com.relay.sms.smartactions.SmartActionIntents.mapsDirectionsUrl]).
 *
 * The Apple OG preview is HONESTLY UNCONFIRMED: the live fetch of a
 * maps.apple.com place page serves the page, but the fetch returned
 * rendered text, not head meta tags, so og:image/og:title were never
 * directly observed. The balance of evidence says they exist and are
 * UA-gated (Clerk.AI's docs: Apple Maps URLs render a map-snapshot
 * card, "pull Open Graph data for title and image", "scrape with a
 * Safari user agent — a Firefox user agent is rejected"; Apple's TN3156
 * documents the OG image rules — 900px minimum width, 1 MB page limit —
 * and iMessage unfurls maps.apple.com links into map cards in the real
 * world). One counterpoint: a SwiftLinkPreview issue reports Apple Maps
 * previews failing (title came back as "Google maps") — possibly the
 * same UA issue. So the fetch uses a Safari User-Agent
 * ([APPLE_PREVIEW_USER_AGENT]), and the card FAILS SOFT: no og:image
 * (or any fetch/geocode failure) → the Apple card is just the "Get
 * directions" button, exactly like OSM. Do NOT present the Apple preview
 * as working until Kristin sees the card on a device — the device test
 * is the final confirmation. A failed OG fetch is cached as "no preview"
 * by the link-preview cache, like every other link, so it sticks for
 * the session; the button still works.
 *
 * Nominatim's usage policy is binding and is honoured here:
 * - at most one request per second per app — [throttle] serialises
 *   requests across instances through a shared reservation timestamp;
 * - an identifying User-Agent naming Relay with a contact address;
 * - results are cached ([cache], keyed by the normalised address), so an
 *   address the user already looked at is never re-geocoded.
 *
 * Fail-soft everywhere, like [LinkPreviewFetcher]: any failure — no
 * network, non-200, timeout, unparseable body — is a `null` result, never
 * a throw, and the address stays tappable ("Open in Maps") without a
 * preview. Network and parse failures are NOT cached, so a retry can
 * succeed; a successful geocode and a definitive "no results" (`[]`)
 * are cached.
 *
 * Privacy: geocoding sends the address to OSM's server, and fetching
 * the Apple page contacts Apple — and addresses can be home addresses —
 * so [fetchAppleMapsPreview] is only ever called from the map-preview
 * card after the user taps "Show map" (tap-to-load, user-initiated like
 * the Klipy GIF search). Nothing in this fetcher triggers a request on
 * its own, and the non-Apple providers fetch nothing at all.
 */
@Singleton
open class MapPreviewFetcher @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
    private val linkPreviews: LinkPreviewFetcher,
) {

    /** Manual construction (Compose bubbles, tests): the same fetcher on [Dispatchers.IO]. */
    constructor() : this(Dispatchers.IO, LinkPreviewFetcher())

    /**
     * The coordinates for [address], or `null` when Nominatim has no
     * result or the request fails. Cached per normalised address (see
     * the class KDoc for what is cached). Throttled to one request per
     * second across the app.
     */
    suspend fun geocode(address: String): MapCoordinates? = withContext(io) {
        val key = normalizeAddressForCache(address)
        if (key.isEmpty()) return@withContext null
        synchronized(cacheLock) {
            if (cache.containsKey(key)) return@withContext cache[key]
        }
        throttle()
        val body = runCatching { fetchGeocodeJson(key) }
            .onFailure { Timber.d(it, "Map preview geocode failed") }
            .getOrNull() ?: return@withContext null
        val coords = parseNominatimFirstResult(body)
        if (coords != null || body.trim() == EMPTY_ARRAY_BODY) {
            synchronized(cacheLock) { cache[key] = coords }
        }
        coords
    }

    /**
     * The cached coordinates for [address], if this process already
     * geocoded it — coordinates, or `null` for a cached "no results".
     * Also `null` when uncached: call [isGeocodeCached] first to tell
     * the two apart. Lets a card render its cached preview on first
     * composition instead of re-fetching on every scroll. Same key
     * cleaning as [geocode].
     */
    fun cachedGeocode(address: String): MapCoordinates? =
        synchronized(cacheLock) { cache[normalizeAddressForCache(address)] }

    /** Whether [address] has a cached geocode result (including "no results"). */
    fun isGeocodeCached(address: String): Boolean =
        synchronized(cacheLock) { cache.containsKey(normalizeAddressForCache(address)) }

    /**
     * The Apple Maps preview for [address]: geocode it (Nominatim),
     * then unfurl the Apple Maps link through the link-preview Open
     * Graph machinery with a Safari User-Agent. Returns the preview, or
     * `null` when there is no preview to show (geocode failure, page
     * fetch failure, or a page with no usable OG tags) — the caller
     * falls back to the "Get directions" button. Never throws. The
     * preview is only usable as a card when it carries an image
     * (`imageUrl`); a title-only preview is also `null` here, so the
     * card never renders a text-only box for Apple.
     */
    suspend fun fetchAppleMapsPreview(address: String): LinkPreview? {
        val found = geocode(address) ?: return null
        val preview = linkPreviews.preview(
            appleMapsPreviewUrl(address, found.latitude, found.longitude),
            APPLE_PREVIEW_USER_AGENT,
        )
        return preview?.takeIf { it.imageUrl != null }
    }

    /**
     * The cached Apple preview for [address], if this process already
     * resolved it — a preview with an image, `null` for a cached "no
     * preview" (or a cached preview with no image). Also `null` when
     * uncached: call [isAppleMapsPreviewCached] first to tell the two
     * apart. Lets a card render its cached preview on first composition
     * instead of popping in after mount (the pop-in grows the thread
     * and shifts the view up).
     */
    fun cachedAppleMapsPreview(address: String): LinkPreview? {
        val found = cachedGeocode(address) ?: return null
        val cached = linkPreviews.cachedPreview(
            appleMapsPreviewUrl(address, found.latitude, found.longitude),
        ) ?: return null
        return cached.takeIf { it.imageUrl != null }
    }

    /**
     * Whether [address] has a cached Apple preview result (including
     * "no preview"): the geocode is cached AND the Apple link's preview
     * is cached for those coordinates. A failed OG fetch counts as
     * cached "no preview" (the link-preview cache keeps the `null`),
     * so the card shows the button instead of re-fetching on scroll.
     */
    fun isAppleMapsPreviewCached(address: String): Boolean {
        val found = cachedGeocode(address) ?: return false
        return linkPreviews.isCached(
            appleMapsPreviewUrl(address, found.latitude, found.longitude),
        )
    }

    /**
     * Nominatim's one-request-per-second policy: each caller reserves the
     * next free slot (the reservation is made up front, so concurrent
     * callers queue instead of bursting) and waits for it.
     */
    private suspend fun throttle() {
        val waitMs = synchronized(throttleLock) {
            val now = System.currentTimeMillis()
            val nextSlot = maxOf(now, lastRequestMillis + MIN_REQUEST_INTERVAL_MS)
            lastRequestMillis = nextSlot
            nextSlot - now
        }
        if (waitMs > 0) delay(waitMs)
    }

    /**
     * Nominatim's JSON body for [address] (already normalised), capped at
     * [MAX_JSON_CHARS] — a `limit=1` answer is small — or `null` on any
     * failure. Non-200 is a plain miss, not an error.
     */
    private fun fetchGeocodeJson(address: String): String? {
        val encoded = runCatching { URLEncoder.encode(address, Charsets.UTF_8.name()) }
            .getOrNull() ?: return null
        val connection = openGetConnection(
            "$NOMINATIM_URL?format=json&limit=1&q=$encoded",
        ) ?: return null
        try {
            return runCatching {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    null
                } else {
                    readUpTo(connection, MAX_JSON_CHARS)
                }
            }.getOrNull()
        } finally {
            connection.disconnect()
        }
    }

    /** A GET connection with the fetcher's timeouts and identifying UA. */
    private fun openGetConnection(pageUrl: String): HttpURLConnection? {
        val connection = runCatching { URL(pageUrl).openConnection() as HttpURLConnection }
            .getOrNull() ?: return null
        connection.apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            instanceFollowRedirects = true
        }
        return connection
    }

    /** The response body as UTF-8 text, up to [maxChars]. */
    private fun readUpTo(connection: HttpURLConnection, maxChars: Int): String {
        val builder = StringBuilder()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(READ_BUFFER_CHARS)
            while (builder.length < maxChars) {
                val read = reader.read(buffer)
                if (read < 0) break
                builder.append(buffer, 0, read)
            }
        }
        return builder.toString()
    }

    companion object {
        // In-memory geocode cache, shared across fetcher instances (see
        // the class KDoc): `null` values are cached too, so an address
        // with no result is not re-geocoded on every scroll. Bounded
        // (256 entries, eldest evicted) like the link preview cache.
        private const val MAX_CACHE_ENTRIES: Int = 256
        private val cacheLock = Any()
        private val cache = object : LinkedHashMap<String, MapCoordinates?>(MAX_CACHE_ENTRIES) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, MapCoordinates?>,
            ): Boolean = size > MAX_CACHE_ENTRIES
        }

        // The shared throttle reservation (see [throttle]).
        private val throttleLock = Any()
        private var lastRequestMillis: Long = 0L
    }
}

// Nominatim's policy requires a valid User-Agent identifying the
// application, with a contact — Relay's site and support address.
private const val USER_AGENT = "Relay SMS (https://getrelaysms.com; support@getrelaysms.com)"
private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
private const val MIN_REQUEST_INTERVAL_MS = 1_000L
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val READ_BUFFER_CHARS = 8 * 1024
private const val MAX_JSON_CHARS = 64 * 1024
private const val MAX_DISPLAY_NAME_CHARS = 200
private const val EMPTY_ARRAY_BODY = "[]"

/**
 * Apple serves its maps pages' Open Graph tags to a Safari UA and
 * rejects other UAs (see the class KDoc) — the Apple preview fetch
 * must use this, not the link preview's default Chrome UA.
 */
const val APPLE_PREVIEW_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/18.0 Safari/605.1.15"

/** Whitespace runs, for collapsing an address to its cache key. */
private val WHITESPACE_REGEX = Regex("""\s+""")

/**
 * The cache key for [address]: trimmed, whitespace collapsed, lowercased
 * — "1600  Amphitheatre  Parkway" and "1600 amphitheatre parkway" are
 * the same lookup.
 */
internal fun normalizeAddressForCache(address: String): String =
    address.trim().replace(WHITESPACE_REGEX, " ").lowercase(Locale.ROOT)

/**
 * The Apple Maps link Relay unfurls for the preview: the address as
 * the search query plus the geocoded coordinates as the pin (`ll`).
 * Coordinates are fixed to 6 decimals so the URL is deterministic for
 * the cache key. The address is encoded with [URLEncoder] (`+` turned
 * back into `%20`, the `encodeURIComponent` shape) so the URL is pure
 * and unit-testable on plain JVM, where `android.net.Uri` throws
 * `Stub!`.
 */
fun appleMapsPreviewUrl(address: String, latitude: Double, longitude: Double): String {
    val encoded = runCatching {
        URLEncoder.encode(address, Charsets.UTF_8.name())
    }.getOrNull()?.replace("+", "%20") ?: ""
    val lat = String.format(Locale.US, "%.6f", latitude)
    val lon = String.format(Locale.US, "%.6f", longitude)
    return "https://maps.apple.com/?q=$encoded&ll=$lat,$lon"
}

/**
 * The first result of a Nominatim `search` answer as coordinates, or
 * `null` when the answer is empty (`[]`), malformed, or its first result
 * carries no parseable `lat`/`lon`. The first result is the most relevant
 * one (the request asks for `limit=1` anyway). `display_name` is kept
 * for the card's content description; it is optional.
 */
internal fun parseNominatimFirstResult(json: String): MapCoordinates? {
    if (json.trim() == EMPTY_ARRAY_BODY) return null
    val lat = extractJsonStringField(json, "lat")
        ?.toDoubleOrNull() ?: return null
    val lon = extractJsonStringField(json, "lon")
        ?.toDoubleOrNull() ?: return null
    val displayName = extractJsonStringField(json, "display_name")
        ?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_DISPLAY_NAME_CHARS)
    return MapCoordinates(lat, lon, displayName)
}
