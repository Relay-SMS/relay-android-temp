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
import com.relay.sms.domain.settings.MapsProvider
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
 * free keyless static image — so round 54 dropped the static map and
 * round 55 unfurls each provider's own page instead (see
 * [fetchMapPreview]). What the card shows follows the Settings
 * maps-provider choice (the same rule as desktop `mapPreview.ts`):
 * - Apple Maps: Relay unfurls the Apple Maps link for the address
 *   (`maps.apple.com/?q=ADDRESS&ll=LAT,LON`, [appleMapsPreviewUrl])
 *   through the link-preview Open Graph machinery — Apple's own map
 *   card (a snapshot.apple-mapkit.com image), free, no key, Relay
 *   doesn't own or host the image. Nominatim is still needed, for
 *   the `ll=` pin.
 * - Google Maps: the same unfurl of the Google Maps link
 *   (`maps.google.com/maps?q=ADDRESS&ll=LAT,LON`,
 *   [googleMapsPreviewUrl]) — Google's page carries an og:image that
 *   IS its own static map (maps.google.com/maps/api/staticmap, keyed
 *   by Google itself, so no billed key on our side). The `ll=` pin
 *   matters: without it Google serves a generic country view.
 * - OpenStreetMap: the same unfurl of the OSM search page
 *   (`openstreetmap.org/search?query=ADDRESS`, [osmSearchUrl]) — its
 *   og:image is the OSM logo, not a map snapshot (generic, accepted
 *   for now). The query drops the city/town ([stripCityFromAddress]):
 *   the city name breaks the map load on the OSM page; without it
 *   the map loads.
 * - System default: NO preview image — the card IS the "Get
 *   directions" button (see
 *   [com.relay.sms.smartactions.SmartActionIntents.mapsDirectionsUrl]).
 *
 * The OG previews are VERIFIED by live fetch (2026-11-25, round 55):
 * Apple serves og:image (snapshot.apple-mapkit.com, 450x300) on both
 * the `/?q=&ll=` link and a directions URL, under a Safari
 * User-Agent; Google serves og:image (its own staticmap, 900x900,
 * correctly centred when `ll=` is given — verified: the image itself
 * fetches as a 900x900 PNG) on maps.google.com/maps under a Chrome
 * User-Agent (google.com/maps/search 403s from a datacentre IP — the
 * `maps.google.com/maps?q=` form is the one to unfurl); OSM serves
 * og:image (the site logo) on its search page. The card still FAILS
 * SOFT: no og:image (or any fetch/geocode failure) → the card is
 * just the "Get directions" button, exactly like System default. A
 * failed OG fetch is cached as "no preview" by the link-preview
 * cache, like every other link, so it sticks for the session; the
 * button still works.
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
 * a provider page contacts that provider — and addresses can be home
 * addresses — so round 54 kept this tap-to-load (nothing sent until
 * the user tapped "Show map"). Round 55 drops the tap gate on
 * Kristin's call: the card fetches on render, like the link preview
 * card (which also fetches on render). System default fetches nothing
 * at all.
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
     * The address the card opens with (city-stripped for OSM when the
     * geocode is cached): the NoPreview button's address, where there
     * is no fetched preview to carry it.
     */
    fun openAddressFor(address: String, provider: MapsProvider): String =
        openAddress(address, provider, cachedGeocode(address))

    /**
     * The map preview for [address] under [provider]: geocode it
     * (Nominatim), then unfurl the provider's own page through the
     * link-preview Open Graph machinery. Returns the preview plus the
     * address the card opens with ([MapPreview.openAddress] — the
     * city-stripped query for OSM), or `null` when there is no preview
     * to show (geocode failure, page fetch failure, or a page with no
     * usable OG tags) — the caller falls back to the "Get directions"
     * button. Never throws. The preview is only usable as a card when
     * it carries an image (`imageUrl`); a title-only preview is also
     * `null` here, so the card never renders a text-only box.
     *
     * Per provider (see the class KDoc for the verification):
     * - Apple: geocode required (the `ll=` pin); Safari User-Agent.
     * - Google: geocode pins `ll=` (a centred map); without it the
     *   `q`-only page still unfurls, with a generic image.
     * - OpenStreetMap: geocode is best-effort (only for the city
     *   strip); the search page unfurls without it too.
     * - System default: no preview — `null`, the caller shows the
     *   button without fetching anything.
     */
    suspend fun fetchMapPreview(address: String, provider: MapsProvider): MapPreview? {
        if (provider == MapsProvider.SYSTEM_DEFAULT) return null
        val coords = geocode(address)
        // Apple needs the geocode for its ll= pin; Google/OSM unfurl without it.
        val url = if (provider == MapsProvider.APPLE_MAPS && coords == null) {
            null
        } else {
            mapPreviewUrl(address, provider, coords)
        } ?: return null
        val preview = if (provider == MapsProvider.APPLE_MAPS) {
            linkPreviews.preview(url, APPLE_PREVIEW_USER_AGENT)
        } else {
            linkPreviews.preview(url)
        }?.takeIf { it.imageUrl != null } ?: return null
        return MapPreview(preview, openAddress(address, provider, coords))
    }

    /**
     * The cached map preview for [address] under [provider], if this
     * process already resolved it — a preview with an image, `null`
     * for a cached "no preview" (or a cached preview with no image).
     * Also `null` when uncached: call [isMapPreviewCached] first to
     * tell the two apart. Lets a card render its cached preview on
     * first composition instead of popping in after mount (the pop-in
     * grows the thread and shifts the view up).
     */
    fun cachedMapPreview(address: String, provider: MapsProvider): MapPreview? {
        if (provider == MapsProvider.SYSTEM_DEFAULT) return null
        val coords = cachedGeocode(address)
        val url = mapPreviewUrl(address, provider, coords) ?: return null
        val cached = linkPreviews.cachedPreview(url)?.takeIf { it.imageUrl != null }
            ?: return null
        return MapPreview(cached, openAddress(address, provider, coords))
    }

    /**
     * Whether [address] has a cached map preview result (including
     * "no preview"): the provider page's preview is cached for the
     * URL [mapPreviewUrl] builds. A failed OG fetch counts as cached
     * "no preview" (the link-preview cache keeps the `null`), so the
     * card shows the button instead of re-fetching on scroll.
     */
    fun isMapPreviewCached(address: String, provider: MapsProvider): Boolean {
        if (provider == MapsProvider.SYSTEM_DEFAULT) return false
        val coords = cachedGeocode(address)
        val url = mapPreviewUrl(address, provider, coords) ?: return false
        return linkPreviews.isCached(url)
    }

    /**
     * The provider page to unfurl for [address]: Apple and Google
     * need the geocoded coordinates for the `ll=` pin (Google falls
     * back to a `q`-only page when the geocode failed — generic
     * image, still a card); OSM takes the city-stripped search query
     * and needs no coordinates at all.
     */
    internal fun mapPreviewUrl(
        address: String,
        provider: MapsProvider,
        coords: MapCoordinates?,
    ): String? = when (provider) {
        MapsProvider.APPLE_MAPS ->
            coords?.let { appleMapsPreviewUrl(address, it.latitude, it.longitude) }
        MapsProvider.GOOGLE_MAPS ->
            coords?.let { googleMapsPreviewUrl(address, it.latitude, it.longitude) }
                ?: googleMapsSearchUrl(address)
        MapsProvider.OPENSTREETMAP ->
            osmSearchUrl(openAddress(address, provider, coords))
        MapsProvider.SYSTEM_DEFAULT -> null
    }

    /**
     * The address the card opens with: the city-stripped query for
     * OSM (see [stripCityFromAddress]), the full address otherwise.
     */
    internal fun openAddress(
        address: String,
        provider: MapsProvider,
        coords: MapCoordinates?,
    ): String =
        if (provider == MapsProvider.OPENSTREETMAP) {
            stripCityFromAddress(address, coords?.city)
        } else {
            address
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
            "$NOMINATIM_URL?format=json&limit=1&addressdetails=1&q=$encoded",
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
    val encoded = encodeAddressQuery(address)
    val lat = String.format(Locale.US, "%.6f", latitude)
    val lon = String.format(Locale.US, "%.6f", longitude)
    return "https://maps.apple.com/?q=$encoded&ll=$lat,$lon"
}

/**
 * Round 55 — the Google Maps link Relay unfurls for the preview: the
 * address as the search query plus the geocoded coordinates as the pin
 * (`ll`). The `ll=` matters: without it Google's page carries a
 * generic country view as its og:image; with it the static map is
 * centred on the queried place (verified by live fetch). Same
 * encoding + 6-decimal rule as [appleMapsPreviewUrl].
 */
fun googleMapsPreviewUrl(address: String, latitude: Double, longitude: Double): String {
    val encoded = encodeAddressQuery(address)
    val lat = String.format(Locale.US, "%.6f", latitude)
    val lon = String.format(Locale.US, "%.6f", longitude)
    return "https://maps.google.com/maps?q=$encoded&ll=$lat,$lon"
}

/**
 * Round 55 — the Google Maps search link without coordinates: the
 * fallback when the geocode failed. It still unfurls (Google's page
 * carries an og:image), but the static map is a generic country view,
 * not the queried place.
 */
fun googleMapsSearchUrl(address: String): String {
    val encoded = encodeAddressQuery(address)
    return "https://maps.google.com/maps?q=$encoded"
}

/**
 * Round 55 — the OpenStreetMap search link Relay unfurls for the
 * preview (and opens on tap): the query is the address with the city
 * stripped (see [stripCityFromAddress] — the city name breaks the map
 * load on the OSM page). Same encoding as the other builders.
 */
fun osmSearchUrl(queryAddress: String): String {
    val encoded = encodeAddressQuery(queryAddress)
    return "https://www.openstreetmap.org/search?query=$encoded"
}

/** The address as a URL query value (`+` back to `%20`, the `encodeURIComponent` shape). */
internal fun encodeAddressQuery(address: String): String =
    runCatching { URLEncoder.encode(address, Charsets.UTF_8.name()) }
        .getOrNull()?.replace("+", "%20") ?: ""

/**
 * Round 55 — a fetched map preview: the unfurled provider page
 * ([preview]) plus the address the card opens with ([openAddress] —
 * the city-stripped query for OSM, the full address otherwise).
 */
data class MapPreview(
    val preview: LinkPreview,
    val openAddress: String,
)

/**
 * Round 55 — the OSM search query for [address] with the city/town
 * stripped: Kristin's device finding is that the city name breaks the
 * map load on the openstreetmap.org page, and without it the map
 * loads. The strip drops the comma-separated component that IS the
 * city (case-insensitive, whole-component match — a street name that
 * contains the city word is left alone). No city, or an address that
 * IS only the city, comes back unchanged.
 */
internal fun stripCityFromAddress(address: String, city: String?): String {
    val cityName = city?.trim().takeUnless { it.isNullOrBlank() } ?: return address
    val parts = address.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size < 2) return address
    val stripped = parts.filterNot { it.equals(cityName, ignoreCase = true) }
    if (stripped.isEmpty() || stripped.size == parts.size) return address
    return stripped.joinToString(", ")
}

/**
 * The first result of a Nominatim `search` answer as coordinates, or
 * `null` when the answer is empty (`[]`), malformed, or its first result
 * carries no parseable `lat`/`lon`. The first result is the most relevant
 * one (the request asks for `limit=1` anyway). `display_name` is kept
 * for the card's content description; it is optional. Round 55 — the
 * structured `address` object (`addressdetails=1`) also yields the
 * city/town/village, for the OSM city strip; it is optional too.
 */
internal fun parseNominatimFirstResult(json: String): MapCoordinates? {
    if (json.trim() == EMPTY_ARRAY_BODY) return null
    val lat = extractJsonStringField(json, "lat")
        ?.toDoubleOrNull() ?: return null
    val lon = extractJsonStringField(json, "lon")
        ?.toDoubleOrNull() ?: return null
    val displayName = extractJsonStringField(json, "display_name")
        ?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_DISPLAY_NAME_CHARS)
    val city = listOf("city", "town", "village", "municipality")
        .firstNotNullOfOrNull { extractJsonStringField(json, it) }
        ?.trim()?.takeIf { it.isNotBlank() }
    return MapCoordinates(lat, lon, displayName, city)
}
