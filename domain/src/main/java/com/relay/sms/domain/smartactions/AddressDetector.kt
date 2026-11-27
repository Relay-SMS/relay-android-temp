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
 * "5 minutes walk down Main Street" — so a match whose FIRST street-name
 * word starts lowercase only counts when it extends over a unit
 * ("Apt 4") or a city / state / ZIP tail (", Kinzers, PA 17535"):
 * real addresses carry the tail, "5 minutes…" does not, and a bare
 * lowercase street ("meet at 123 main st") is still missed — that is
 * the accepted cost. A capitalised first word (or an ordinal,
 * "5th Avenue") needs no tail. The suffix list is longest-first
 * ("street" before "st") so "123 Main Street" never matches as
 * "123 Main St". Case is otherwise ignored throughout — street name,
 * suffix, city and state code — and the detected text keeps the case
 * the user wrote, so the directions query is verbatim.
 */
object AddressDetector {

    /** Cap on the inspected body, like the OTP detector's: bounds the regex. */
    private const val MAX_BODY_LENGTH = 2000

    /**
     * Street suffixes, longest first within each pair (see the class KDoc).
     * Matched case-insensitively via an inline flag, like the city and
     * state in the tail — the whole detector is case-insensitive now,
     * and the false-positive gate lives in [findAll] (the tail
     * requirement for a lowercase first word), not in the pattern's case.
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
     * first word may start with any case; [findAll] applies the
     * lowercase gate. Its first letter is captured (group 1) for that
     * gate — empty when the first word is an ordinal ("5th"). The
     * lookbehind refuses a number glued to a digit, a letter, a dot or a
     * dash ("12.5 Main St" must not match as "5 Main St"); the lookahead
     * refuses a suffix glued to a letter ("123 Main Streets" is not
     * "123 Main Street" plus an "s").
     */
    private val CORE_REGEX = Regex(
        """(?<![\dA-Za-z.\-])\d{1,6}[A-Za-z]?\s+(?:([A-Za-z])[A-Za-z'.-]*|(?i:\d+(?:st|nd|rd|th)))""" +
            """(?:\s+[A-Za-z][A-Za-z'.-]*){0,3}\s+(?i:${STREET_SUFFIXES.joinToString("|")})\.?(?![A-Za-z])""",
    )

    /** An apartment / suite / unit after the street ("Apt 4", ", Suite 200"). */
    private val UNIT_REGEX = Regex(
        """,?\s+(?i:Apt|Apartment|Suite|Ste|Unit|#)\.?\s*[A-Za-z0-9\-]+""",
    )

    /**
     * A city / state / ZIP tail (", Springfield, IL 62701", ", IL 62701")
     * or a bare ZIP (", 62701"). The city name is lazy — it stops at the
     * first comma that a state + ZIP follows. City and state match in
     * any case ("kinzers, pa 17535" extends like "Kinzers, PA 17535").
     *
     * Round 60 — the commas are OPTIONAL (desktop parity): the
     * desktop tail regex (`ADDRESS_TAIL_REGEX`) takes the city and
     * the state with or without commas, so "5466 elam rd kinzers
     * pa 17535" detects there — while this regex demanded both
     * commas, the tail never extended, and the lowercase gate
     * dropped the address, so mobile showed no "Get directions"
     * pill for a comma-less lowercase address. A capitalised
     * address still detected (just without its tail); only the
     * lowercase form vanished outright. The bare-ZIP alternative
     * keeps its comma (the desktop has no bare-ZIP tail at all).
     */
    private val ZIP_TAIL_REGEX = Regex(
        """\s*,?\s*(?:[A-Za-z][A-Za-z .'\-]{1,40}?\s*,?\s+)?[A-Za-z]{2}\s+\d{5}(?:-\d{4})?|,\s*\d{5}(?:-\d{4})?""",
    )

    /** Every street address in [text], in order, or an empty list. */
    fun findAll(text: String): List<SmartActionMatch> {
        if (text.isBlank()) return emptyList()
        val capped = if (text.length > MAX_BODY_LENGTH) text.substring(0, MAX_BODY_LENGTH) else text
        val found = ArrayList<SmartActionMatch>()
        for (match in CORE_REGEX.findAll(capped)) {
            val end = extendEnd(capped, match.range.last + 1)
            // The lowercase gate (see the class KDoc): a first
            // street-name word starting lowercase only counts when the
            // match extended over a unit or a tail.
            if (startsLowercase(match) && end == match.range.last + 1) continue
            val range = match.range.first until end
            found += SmartActionMatch(range, SmartAction.Address(capped.substring(range)))
        }
        return found
    }

    /**
     * True when the core match's first street-name word starts with a
     * lowercase letter. Ordinals ("5th") capture no first letter and
     * never trigger the gate.
     */
    private fun startsLowercase(match: MatchResult): Boolean {
        val firstLetter = match.groupValues[1].firstOrNull() ?: return false
        return firstLetter in 'a'..'z'
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
