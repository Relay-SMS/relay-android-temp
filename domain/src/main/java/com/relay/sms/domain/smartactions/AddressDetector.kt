/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.smartactions

/**
 * Round 53 — street addresses in a message body, so the bubble can offer
 * "Get directions" and the notification a matching action.
 *
 * **Pure function**, like [OtpCodeDetector][com.relay.sms.domain.otp.OtpCodeDetector]:
 * input = raw text, output = matches. No Android dependency.
 *
 * # v1 scope: US-style street addresses
 *
 * A house number, a street name and a known suffix — "123 Main Street",
 * "1600 Amphitheatre Parkway", "45 5th Avenue" — optionally followed by a
 * unit ("Apt 4") and a city / state / ZIP tail (", Springfield, IL
 * 62701"), which the match extends to cover so the maps query is complete.
 * International formats (no house number first, no suffix, postal code
 * first) are a known v1 limitation: they are not detected.
 *
 * # The false-positive gate
 *
 * The doctrine is the OTP detector's: a missed address costs one manual
 * copy, a false positive teaches the user to ignore the feature. The
 * shape "number + words + suffix" also matches ordinary sentences —
 * "5 minutes walk down Main Street" — so the FIRST street-name word must
 * start with an uppercase letter (or be an ordinal, "5th Avenue"): real
 * addresses are capitalised, and "5 minutes…" is not. A lowercase address
 * ("meet at 123 main st") is missed in v1; that is the accepted cost.
 * The suffix list is longest-first ("street" before "st") so "123 Main
 * Street" never matches as "123 Main St".
 */
object AddressDetector {

    /** Cap on the inspected body, like the OTP detector's: bounds the regex. */
    private const val MAX_BODY_LENGTH = 2000

    /**
     * Street suffixes, longest first within each pair (see the class KDoc).
     * Matched case-insensitively via an inline flag — the rest of the
     * pattern stays case-sensitive, because the capitalised-first-word
     * gate depends on it.
     */
    private val STREET_SUFFIXES = listOf(
        "street", "avenue", "boulevard", "terrace", "highway", "parkway",
        "circle", "square", "trail", "alley", "road", "lane", "drive",
        "court", "place",
        "st", "ave", "blvd", "rd", "ln", "dr", "ct", "pl", "way", "ter",
        "hwy", "cir", "trl", "pkwy", "aly", "sq",
    )

    /**
     * The core address: house number (optional letter suffix, "221B"),
     * 1–4 street-name words, a suffix with an optional period. The
     * lookbehind refuses a number glued to a digit, a letter, a dot or a
     * dash ("12.5 Main St" must not match as "5 Main St"); the lookahead
     * refuses a suffix glued to a letter ("123 Main Streets" is not
     * "123 Main Street" plus an "s").
     */
    private val CORE_REGEX = Regex(
        """(?<![\dA-Za-z.\-])\d{1,6}[A-Za-z]?\s+(?:[A-Z][A-Za-z'.-]*|(?i:\d+(?:st|nd|rd|th)))""" +
            """(?:\s+[A-Za-z][A-Za-z'.-]*){0,3}\s+(?i:${STREET_SUFFIXES.joinToString("|")})\.?(?![A-Za-z])""",
    )

    /** An apartment / suite / unit after the street ("Apt 4", ", Suite 200"). */
    private val UNIT_REGEX = Regex(
        """,?\s+(?i:Apt|Apartment|Suite|Ste|Unit|#)\.?\s*[A-Za-z0-9\-]+""",
    )

    /**
     * A city / state / ZIP tail (", Springfield, IL 62701", ", IL 62701")
     * or a bare ZIP (", 62701"). The city name is lazy — it stops at the
     * first comma that a state + ZIP follows.
     */
    private val ZIP_TAIL_REGEX = Regex(
        """,\s*(?:[A-Z][A-Za-z .'\-]{1,40}?,\s*)?[A-Z]{2}\s+\d{5}(?:-\d{4})?|,\s*\d{5}(?:-\d{4})?""",
    )

    /** Every street address in [text], in order, or an empty list. */
    fun findAll(text: String): List<SmartActionMatch> {
        if (text.isBlank()) return emptyList()
        val capped = if (text.length > MAX_BODY_LENGTH) text.substring(0, MAX_BODY_LENGTH) else text
        return CORE_REGEX.findAll(capped).map { match ->
            val end = extendEnd(capped, match.range.last + 1)
            val range = match.range.first until end
            SmartActionMatch(range, SmartAction.Address(capped.substring(range)))
        }.toList()
    }

    /** The first street address in [text], or `null`. */
    fun findFirst(text: String): SmartActionMatch? = findAll(text).firstOrNull()

    /**
     * Extends a core match at [end] over a unit and/or a city / state /
     * ZIP tail when one follows immediately. Each step is anchored
     * ([Regex.matchAt]), so nothing past the address is swallowed.
     */
    private fun extendEnd(text: String, end: Int): Int {
        var extended = end
        UNIT_REGEX.matchAt(text, extended)?.let { extended = it.range.last + 1 }
        ZIP_TAIL_REGEX.matchAt(text, extended)?.let { extended = it.range.last + 1 }
        return extended
    }
}
