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
 * [calendarIntent], [trackingIntent], [mapsIntent]) so the URL and URI
 * shaping is unit-testable; [perform] is the one place that actually
 * starts an activity, and it returns `false` (with a log) instead of
 * throwing when no app can handle the intent.
 *
 * Maps: [mapsIntent] honours the user's provider choice
 * ([MapsProvider]). A `geo:` intent is offered to the chosen app only
 * when it is installed (checked with [Context.getLaunchIntentForPackage],
 * which needs the package in the manifest's `<queries>` block on API
 * 30+) — otherwise the provider's web version opens. Round 54 — the map
 * preview card's "Get directions" button ([directionsIntent] /
 * [performDirections]) is a second maps path: Google, Apple and OSM go
 * straight to their keyless directions web URL (the `geo:` URI has no
 * directions mode), while SYSTEM_DEFAULT tries `geo:` first and falls
 * back to OSM directions on the web when nothing handles it — the same
 * rule as the desktop's `openMapsDirections`.
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

    /** The carrier's tracking page for [action], as a URL string. */
    fun trackingUrl(action: SmartAction.Tracking): String = when (action.carrier) {
        TrackingCarrier.UPS -> "https://www.ups.com/track?tracknum=${action.number}"
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
     * The `geo:` URI for [address] — the form handed to a maps app (the
     * system default, Google Maps, or an OSM app). The address is
     * URI-encoded; `geo:0,0?q=` is the query form every maps app
     * understands.
     */
    fun geoUriString(address: String): String =
        "geo:0,0?q=${Uri.encode(address)}"

    /** The provider's web search URL for [address] (Google/Apple/OSM web fallbacks). */
    fun mapsWebUrl(address: String, provider: MapsProvider): String {
        val encoded = Uri.encode(address)
        return when (provider) {
            MapsProvider.GOOGLE_MAPS ->
                "https://www.google.com/maps/search/?api=1&query=$encoded"
            MapsProvider.APPLE_MAPS -> "https://maps.apple.com/?q=$encoded"
            MapsProvider.OPENSTREETMAP ->
                "https://www.openstreetmap.org/search?query=$encoded"
            MapsProvider.SYSTEM_DEFAULT -> "https://www.openstreetmap.org/search?query=$encoded"
        }
    }

    /**
     * The "Open in Maps" intent for [address] under [provider], pure
     * except for the install check: [isInstalled] answers whether a
     * package is on the device (the context overload passes
     * `getLaunchIntentForPackage(package) != null`).
     *
     * - SYSTEM_DEFAULT: a plain `geo:` intent — Android resolves it.
     * - GOOGLE_MAPS: `geo:` pinned to the Maps app when installed, else
     *   the Google Maps web search.
     * - APPLE_MAPS: Apple Maps on the web — there is no Apple Maps app
     *   for Android.
     * - OPENSTREETMAP: `geo:` pinned to the first installed known OSM
     *   app (OsmAnd, Organic Maps), else openstreetmap.org.
     */
    fun mapsIntent(
        address: String,
        provider: MapsProvider,
        isInstalled: (String) -> Boolean,
    ): Intent = when (provider) {
        MapsProvider.SYSTEM_DEFAULT -> Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(geoUriString(address))
        }
        MapsProvider.GOOGLE_MAPS -> {
            if (isInstalled(GOOGLE_MAPS_PACKAGE)) {
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(geoUriString(address))
                    setPackage(GOOGLE_MAPS_PACKAGE)
                }
            } else {
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(mapsWebUrl(address, provider))
                }
            }
        }
        MapsProvider.APPLE_MAPS -> Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(mapsWebUrl(address, provider))
        }
        MapsProvider.OPENSTREETMAP -> {
            val installed = OSM_PACKAGES.firstOrNull { isInstalled(it) }
            if (installed != null) {
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(geoUriString(address))
                    setPackage(installed)
                }
            } else {
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(mapsWebUrl(address, provider))
                }
            }
        }
    }

    /** [mapsIntent] with the install check against the device's packages. */
    fun mapsIntent(
        address: String,
        provider: MapsProvider,
        context: Context,
    ): Intent = mapsIntent(address, provider) { packageName ->
        context.packageManager.getLaunchIntentForPackage(packageName) != null
    }

    /**
     * Round 54 — the directions URL for [address] under [provider]: the
     * "Get directions" button's target (see `mapPreview.ts` on desktop —
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
     * for tracking. Only [mapsWebUrl] keeps `Uri.encode`, for the search
     * URLs it already builds.
     */
    fun mapsDirectionsUrl(address: String, provider: MapsProvider): String {
        val encoded = runCatching {
            java.net.URLEncoder.encode(address, Charsets.UTF_8.name())
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
     * [mapsDirectionsUrl]). Kept separate from [geoUriString] so the
     * directions path stays pure and unit-testable: [geoUriString] needs
     * `android.net.Uri` and throws `Stub!` on plain JVM.
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
     * for addresses, where the install check runs against the device).
     */
    fun intentFor(action: SmartAction, provider: MapsProvider, context: Context): Intent =
        when (action) {
            is SmartAction.Call -> dialIntent(action.number)
            is SmartAction.Email -> emailIntent(action.address)
            is SmartAction.Address -> mapsIntent(action.text, provider, context)
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
            context.startActivity(intentFor(action, provider, context))
            true
        }.onFailure { Timber.d(it, "Smart action had no handler") }.getOrDefault(false)

    /** Google Maps' package, for the installed check and the pin. */
    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

    /** Known OpenStreetMap apps, in preference order, for the installed check. */
    val OSM_PACKAGES = listOf("net.osmand.plus", "net.osmand", "app.organicmaps")
}
