/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.smartactions

/**
 * Round 53 — smart actions: the useful thing a message body contains, and
 * the one-tap action it unlocks. Produced by the pure detectors in this
 * package ([EmailDetector], [AddressDetector], [DateTimeDetector],
 * [TrackingDetector], aggregated by [SmartActionDetector]) and surfaced in
 * two places, like the OTP model: as a tappable span in the message bubble
 * and as an action button on the incoming-message notification.
 *
 * [Call] is the exception: phone numbers are detected with
 * `android.util.Patterns.PHONE`, which cannot run on plain JVM, so the
 * phone match is produced in the app layer (the bubble's linkify pass and
 * the notifier) and only *represented* here, so one picker and one intent
 * builder serve all five kinds.
 *
 * The action carries everything its intent needs — no re-parsing at tap
 * time. [DateTime] carries resolved instants (not the raw words), so the
 * calendar editor opens pre-filled even if the user taps hours later.
 */
sealed interface SmartAction {

    /** A phone number: tap opens the dialer pre-filled (never auto-dials). */
    data class Call(val number: String) : SmartAction

    /** An email address: tap opens a compose window (`mailto:`, sends nothing). */
    data class Email(val address: String) : SmartAction

    /** A street address: tap opens it in the user's maps app of choice. */
    data class Address(val text: String) : SmartAction

    /**
     * A date, with an optional time: tap opens the calendar editor
     * pre-filled. A bare date is [allDay] (midnight to midnight); a date
     * with a time is a one-hour event starting at [startMillis].
     * [text] is the matched substring, for the bubble span.
     */
    data class DateTime(
        val text: String,
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
    ) : SmartAction

    /** A parcel tracking number: tap opens the carrier's tracking page. */
    data class Tracking(val carrier: TrackingCarrier, val number: String) : SmartAction
}

/** The carriers [TrackingDetector] knows, each with its own check digit. */
enum class TrackingCarrier { UPS, USPS, FEDEX }

/**
 * One detection: the [SmartAction] plus where it sits in the body
 * ([range], inclusive — the bubble converts it to its half-open spans).
 * Detectors return these so the bubble can highlight exactly the matched
 * text and the notifier can pick the earliest action by position.
 */
data class SmartActionMatch(val range: IntRange, val action: SmartAction)
