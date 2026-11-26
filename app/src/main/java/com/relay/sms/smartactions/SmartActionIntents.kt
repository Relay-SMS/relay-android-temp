/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.smartactions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import com.relay.sms.domain.settings.MapsProvider
import com.relay.sms.domain.smartactions.SmartAction
import com.relay.sms.domain.smartactions.TrackingCarrier
import timber.log.Timber

/**
 * Round 53 — the intents behind smart actions. Every action opens an app
 * the user already has (or the browser, or the calendar editor) and
 * nothing sends itself: a mailto: opens the compose screen, a calendar
 * insert opens the editor pre-filled, a tracking link opens the carrier's
 * page in the browser. Relay never queries a carrier or a maps service
 * itself.
 *
 * The intent builders are pure ([emailIntent], [dialIntent],
 * [calendarIntent], [trackingIntent]) so the URL and URI shaping is
 * unit-testable; [perform] is the one place that actually starts an
 * activity, and it returns `false` (with a log) instead of throwing
 * when no app can handle the intent.
 *
 * Maps: an address tap opens directions in the user's provider choice
 * ([MapsProvider]) — [directionsIntent] / [performDirections]: Google,
 * Apple and OSM go straight to their keyless directions web URL (the
 * `geo:` URI has no directions mode), while SYSTEM_DEFAULT tries `geo:`
 * first and falls back to OSM directions on the web when nothing
 * handles it — the same rule as the desktop's `openMapsDirections`.
 * Round 56 — the inline map preview card is gone, so the directions
 * pill (and the notification's address action) are the only map
 * actions left; the OSM directions query drops the locality
 * component ([stripLocalityForOsm]) because the city name breaks the
 * map load on the OSM page.
 */
object SmartActionIntents {

    /** The "Send email" intent: a `mailto:` compose, nothing sent. */
    fun emailIntent(address: String): Intent =
        Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$address")
        }

    /** The "Call" intent: the dialer, pre-filled — the user taps to call. */
    fun dialIntent(number: String): Intent =
        Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${number.filterNot { it.isWhitespace() }}")
        }

    /**
     * The "Add to calendar" intent: the calendar editor, pre-filled with
     * the detected date/time. All-day events pass the all-day flag and a
     * whole-day span; timed events pass a one-hour span. Nothing is
     * created until the user confirms.
     */
    fun calendarIntent(action: SmartAction.DateTime): Intent =
        Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, action.startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, action.endMillis)
            if (action.allDay) {
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            }
        }

    /**
     * The carrier's tracking page for [action], as a URL string.
     * UPS needs the full deep-link form (round 56, Kristin's device
     * test on 1Z7A377E0392030514): the bare `track?tracknum=` URL
     * redirects to a broken page; the license-agreement + `loc` +
     * `requester=ST/trackdetails` form lands on the details page.
     */
    fun trackingUrl(action: SmartAction.Tracking): String = when (action.carrier) {
        TrackingCarrier.UPS ->
            "https://www.ups.com/track?tracknum=${action.number}" +
                "&accept_UPS_license_agreement=yes&loc=en_US&requester=ST/trackdetails"
        TrackingCarrier.USPS ->
            "https://tools.usps.com/go/TrackConfirmAction?tLabels=${action.number}"
        TrackingCarrier.FEDEX -> "https://www.fedex.com/fedextrack/?trknbr=${action.number}"
    }

    /** The "Track package" intent: the carrier's page in the browser. */
    fun trackingIntent(action: SmartAction.Tracking): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(trackingUrl(action))
        }

    /**
     * Round 54 — the directions URL for [address] under [provider]: the
     * "Get directions" pill's target (see `mapPreview.ts` on desktop —
     * the same provider matrix). Google uses the keyless Maps URLs
     * directions form (`dir/?api=1&destination=`), Apple the `daddr`
     * destination parameter (no `saddr`, so the route starts from the
     * user's location), OpenStreetMap the `?to=` destination form; the
     * `geo:` URI has no directions mode, so System default is the address
     * search — the opener falls back to OSM directions on the web when
     * nothing handles `geo:` (see [performDirections]).
     *
     * Pure on purpose (URL-encoded with [java.net.URLEncoder], `+` turned
     * back into `%20` so the shape matches `encodeURIComponent`): the
     * URL shaping is unit-tested on plain JVM, where `android.net.Uri`
     * throws `Stub!` — the same reason [trackingUrl] is the tested shape
     * for tracking.
     */
    fun mapsDirectionsUrl(address: String, provider: MapsProvider): String {
        // Round 56 — OSM only: the directions query drops the locality
        // ([stripLocalityForOsm]); the other providers take the full
        // address. SYSTEM_DEFAULT ignores `encoded` (see below).
        val queryAddress = if (provider == MapsProvider.OPENSTREETMAP) {
            stripLocalityForOsm(address)
        } else {
            address
        }
        val encoded = runCatching {
            java.net.URLEncoder.encode(queryAddress, Charsets.UTF_8.name())
        }.getOrNull()?.replace("+", "%20") ?: ""
        return when (provider) {
            MapsProvider.GOOGLE_MAPS ->
                "https://www.google.com/maps/dir/?api=1&destination=$encoded"
            MapsProvider.APPLE_MAPS -> "https://maps.apple.com/?daddr=$encoded"
            MapsProvider.OPENSTREETMAP ->
                "https://www.openstreetmap.org/directions?to=$encoded"
            MapsProvider.SYSTEM_DEFAULT -> geoUriStringForDirections(address)
        }
    }

    /**
     * The `geo:` search URI for [address], as the System-default
     * directions target (the `geo:` URI has no directions mode — see
     * [mapsDirectionsUrl]). Pure on purpose (URL-encoded with
     * `java.net.URLEncoder`) so the directions path stays unit-testable
     * on plain JVM, where `android.net.Uri` throws `Stub!`.
     */
    private fun geoUriStringForDirections(address: String): String =
        "geo:0,0?q=" + runCatching {
            java.net.URLEncoder.encode(address, Charsets.UTF_8.name())
        }.getOrNull()?.replace("+", "%20") ?: ""

    /**
     * Round 54 — the "Get directions" intent for [address] under
     * [provider]: the provider's directions web URL, except
     * SYSTEM_DEFAULT, which is a plain `geo:` intent (Android resolves
     * it to any maps app). No install check — every directions URL is a
     * plain `ACTION_VIEW`, and the System-default fallback to OSM
     * directions on the web lives in [performDirections], which is the
     * only place that knows whether the `geo:` start failed.
     */
    fun directionsIntent(address: String, provider: MapsProvider): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(mapsDirectionsUrl(address, provider))
        }

    /** Starts [intent], `false` (with a log) when no app can take it. */
    private fun tryStart(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(intent)
            true
        }.onFailure { Timber.d(it, "Smart action had no handler") }.getOrDefault(false)

    /**
     * Round 54 — starts the directions intent for [address] under
     * [provider]. SYSTEM_DEFAULT tries `geo:` first and falls back to
     * OSM directions on the web when nothing handles it (the desktop's
     * `openMapsDirections` rule); every other provider goes straight to
     * its directions web URL. Returns `true` when an app took an intent,
     * `false` (with a log) when none could — the caller shows the "no
     * app" note.
     */
    fun performDirections(context: Context, address: String, provider: MapsProvider): Boolean {
        if (provider == MapsProvider.SYSTEM_DEFAULT) {
            if (tryStart(context, directionsIntent(address, provider))) return true
            return tryStart(
                context,
                directionsIntent(address, MapsProvider.OPENSTREETMAP),
            )
        }
        return tryStart(context, directionsIntent(address, provider))
    }

    /**
     * The intent for [action] under [provider] (the provider only matters
     * for addresses, which open directions under it).
     */
    fun intentFor(action: SmartAction, provider: MapsProvider): Intent =
        when (action) {
            is SmartAction.Call -> dialIntent(action.number)
            is SmartAction.Email -> emailIntent(action.address)
            is SmartAction.Address -> directionsIntent(action.text, provider)
            is SmartAction.DateTime -> calendarIntent(action)
            is SmartAction.Tracking -> trackingIntent(action)
        }

    /**
     * Starts the intent for [action] — the one place a smart action
     * leaves Relay. Returns `true` when an app took the intent, `false`
     * (with a log) when none could — the caller shows the "no app" toast.
     * Callers outside an activity pass a context that can start one (the
     * notifier's pending intent is built separately).
     */
    fun perform(context: Context, action: SmartAction, provider: MapsProvider): Boolean =
        runCatching {
            context.startActivity(intentFor(action, provider))
            true
        }.onFailure { Timber.d(it, "Smart action had no handler") }.getOrDefault(false)
}

/**
 * Round 56 — the OSM directions query for [address] with the locality
 * (city/town) component dropped: the city name breaks the map load on
 * the openstreetmap.org page, and without it the map loads (Kristin's
 * device finding, round 55). The old geocode-based strip (Nominatim
 * city in {city, town, village, municipality}) never fired for small
 * places — "5466 Elam Rd, Kinzers, PA 17535" is a hamlet, so Nominatim
 * returned no city and even the preview card's open left the city in. This strip needs no
 * network: US addresses are "street, locality, ST ZIP", so the
 * locality is the second-to-last comma component when the last one
 * looks like a state + ZIP ("PA 17535"). No locality, or an address
 * that IS only the locality, comes back unchanged — same guards as
 * the old strip.
 */
internal fun stripLocalityForOsm(address: String): String {
    val parts = address.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size < 3) return address
    if (!STATE_ZIP_REGEX.matches(parts.last())) return address
    return (parts.dropLast(2) + parts.last()).joinToString(", ")
}

/** A US state + ZIP tail ("PA 17535", "CA 90210-1234"). */
private val STATE_ZIP_REGEX = Regex("^[A-Za-z]{2}\\s+\\d{5}(-\\d{4})?$")
