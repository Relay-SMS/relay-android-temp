/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.smartactions

import com.relay.sms.data.linkpreview.extractJsonStringField
import com.relay.sms.di.IoDispatcher
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
 * bubbles: an address from a message is resolved through Nominatim
 * (nominatim.openstreetmap.org, free, no key) and the coordinates feed a
 * static map image from staticmap.openstreetmap.de (see [staticMapUrl]).
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
 * Privacy: geocoding sends the address to OSM's server, and addresses
 * can be home addresses — so [geocode] is only ever called from the
 * map-preview card after the user taps "Load map preview" (tap-to-load,
 * user-initiated like the Klipy GIF search). Nothing in this fetcher
 * triggers a request on its own.
 *
 * Note (verified live 2026-11-24): Nominatim answers, but
 * staticmap.openstreetmap.de currently returns an empty reply — the
 * preview image may be unavailable until the service returns. The
 * geocode path and "Open in Maps" are unaffected, and the card fails
 * soft either way.
 */
@Singleton
open class MapPreviewFetcher @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Manual construction (Compose bubbles, tests): the same fetcher on [Dispatchers.IO]. */
    constructor() : this(Dispatchers.IO)

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
private const val STATICMAP_URL = "https://staticmap.openstreetmap.de/staticmap.php"
private const val STATICMAP_ZOOM = 15
private const val STATICMAP_WIDTH = 640
private const val STATICMAP_HEIGHT = 360
private const val MIN_REQUEST_INTERVAL_MS = 1_000L
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val READ_BUFFER_CHARS = 8 * 1024
private const val MAX_JSON_CHARS = 64 * 1024
private const val MAX_DISPLAY_NAME_CHARS = 200
private const val EMPTY_ARRAY_BODY = "[]"

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
 * The static map image URL for [latitude]/[longitude]: an OSM mapnik tile
 * at zoom 15 with a red pushpin on the spot. Coordinates are formatted
 * with [Locale.US] so the decimal separator is always a dot. The preview
 * image is always OSM — Google/Apple static maps need paid API keys.
 */
fun staticMapUrl(latitude: Double, longitude: Double): String {
    val lat = String.format(Locale.US, "%.6f", latitude)
    val lon = String.format(Locale.US, "%.6f", longitude)
    return "$STATICMAP_URL?center=$lat,$lon&zoom=$STATICMAP_ZOOM" +
        "&size=${STATICMAP_WIDTH}x$STATICMAP_HEIGHT&maptype=mapnik" +
        "&markers=$lat,$lon,red-pushpin"
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
