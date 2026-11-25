/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.smartactions

/**
 * Round 53 — parcel tracking numbers in a message body, so the bubble can
 * offer "Track package" and the notification a matching action.
 *
 * **Pure function**, like [OtpCodeDetector][com.relay.sms.domain.otp.OtpCodeDetector]:
 * input = raw text, output = matches. No Android dependency. Relay never
 * queries a carrier itself — the tap only opens the carrier's tracking
 * page in the browser, user-initiated.
 *
 * # The check digit is the point
 *
 * The doctrine is the OTP detector's: a missed number costs one manual
 * copy, a false positive teaches the user to ignore the feature. A bare
 * run of digits is usually an order number, a phone number or an ID — so
 * every shape below is gated on the carrier's own check digit, which a
 * random digit run passes only ~1 time in 10 (and the shape gates cut
 * that further). A number that fails its check digit is not detected at
 * all, even when the shape matches.
 *
 * # Shapes (all require a non-alphanumeric boundary on both sides)
 *
 *  - **UPS** `1Z` + 16 alphanumerics (18 total). Check digit over the 15
 *    characters after the `1Z`: letters map to digits (`A`=2 … `H`=9,
 *    `I`=0, `J`=1 … `Z`=7, i.e. `(code - 63) mod 10`), even offsets count
 *    once, odd offsets twice, and the check digit brings the total to the
 *    next multiple of 10. Verified against UPS's own example
 *    `1Z999AA10123456784` (check 4).
 *  - **USPS 22-digit** IMpb, starting 92/93/94/95. Mod-10: from the
 *    right (check digit excluded), weights alternate 3, 1. Verified
 *    against `9400111206206406260787` (check 7).
 *  - **USPS 20-digit** (legacy form), starting 92/93/94/95. Same mod-10,
 *    but the sum is computed over the number with `91` prefixed — the
 *    legacy form's rule, per the community reconstruction the
 *    open-source tracking libraries converged on. No published 20-digit
 *    test vector was available; the test uses a number constructed under
 *    this rule, so this shape is the least certain of the five.
 *  - **USPS international (UPU S10)**: 2 letters + 9 digits + 2 letters
 *    ("EE123456785US"). Mod-11 over the first 8 serial digits with
 *    weights 8, 6, 4, 2, 3, 5, 9, 7; check = 11 − (sum mod 11), with 10
 *    → 0 and 11 → 5. Verified against `EE123456785US` (check 5).
 *  - **FedEx** 12, 15 or 20 digits. Mod-10, weights 3, 1 from the right
 *    — the same scheme as USPS. Verified against the 15-digit
 *    `449044304137821` (check 1). The 34-digit FedEx form is NOT
 *    detected in v1 (its check-digit scheme could not be verified).
 *
 * A 20-digit run is tried as USPS first when it starts with 92/93/94/95
 * (FedEx 20-digit starts with 96); other prefixes go to FedEx. A
 * 22-digit run starting 96 is FedEx SmartPost, not USPS — the prefix
 * decides, so the two never collide.
 */
object TrackingDetector {

    /** Cap on the inspected body, like the OTP detector's: bounds the regexes. */
    private const val MAX_BODY_LENGTH = 2000

    /** UPS: `1Z` + 16 alphanumerics. */
    private val UPS_REGEX = Regex("""(?<![A-Za-z0-9])1Z[0-9A-Z]{16}(?![A-Za-z0-9])""")

    /** USPS/FedEx long digit runs: 20 or 22 digits. */
    private val LONG_DIGITS_REGEX = Regex("""(?<![A-Za-z0-9])\d{20,22}(?![A-Za-z0-9])""")

    /** FedEx short digit runs: 12 or 15 digits. */
    private val SHORT_DIGITS_REGEX = Regex(
        """(?<![A-Za-z0-9])\d{12}(?![A-Za-z0-9])|(?<![A-Za-z0-9])\d{15}(?![A-Za-z0-9])""",
    )

    /** UPU S10: 2 letters + 9 digits + 2 letters. */
    private val S10_REGEX = Regex("""(?<![A-Za-z0-9])[A-Z]{2}\d{9}[A-Z]{2}(?![A-Za-z0-9])""")

    /** S10 check-digit weights, left to right over the first 8 serial digits. */
    private val S10_WEIGHTS = intArrayOf(8, 6, 4, 2, 3, 5, 9, 7)

    /** USPS domestic prefixes (IMpb application identifiers). */
    private val USPS_PREFIXES = setOf("92", "93", "94", "95")

    /** Every tracking number in [text], in order, or an empty list. */
    fun findAll(text: String): List<SmartActionMatch> {
        if (text.isBlank()) return emptyList()
        val capped = if (text.length > MAX_BODY_LENGTH) text.substring(0, MAX_BODY_LENGTH) else text
        val candidates = ArrayList<SmartActionMatch>()
        for (match in UPS_REGEX.findAll(capped)) {
            if (isValidUps(match.value)) {
                candidates += SmartActionMatch(match.range, SmartAction.Tracking(TrackingCarrier.UPS, match.value))
            }
        }
        for (match in LONG_DIGITS_REGEX.findAll(capped)) {
            longDigitCarrier(match.value)?.let { carrier ->
                candidates += SmartActionMatch(match.range, SmartAction.Tracking(carrier, match.value))
            }
        }
        for (match in SHORT_DIGITS_REGEX.findAll(capped)) {
            if (isValidMod10ThreeOne(match.value)) {
                candidates += SmartActionMatch(match.range, SmartAction.Tracking(TrackingCarrier.FEDEX, match.value))
            }
        }
        for (match in S10_REGEX.findAll(capped)) {
            if (isValidS10(match.value)) {
                candidates += SmartActionMatch(match.range, SmartAction.Tracking(TrackingCarrier.USPS, match.value))
            }
        }
        if (candidates.isEmpty()) return emptyList()
        // The shapes cannot overlap (a match is either letters-led, a
        // digit run of one length, or S10) — only the order needs fixing.
        return candidates.sortedBy { it.range.first }
    }

    /** The first tracking number in [text], or `null`. */
    fun findFirst(text: String): SmartActionMatch? = findAll(text).firstOrNull()

    /**
     * The carrier for a 20- or 22-digit run, or `null` when it passes
     * neither check: USPS when the prefix says USPS (22-digit plain
     * mod-10, 20-digit with the `91`-prefixed legacy sum), FedEx
     * otherwise (plain mod-10).
     */
    private fun longDigitCarrier(number: String): TrackingCarrier? {
        val isUspsPrefix = number.take(2) in USPS_PREFIXES
        return when {
            isUspsPrefix && number.length == USPS_IMPB_LENGTH && isValidMod10ThreeOne(number) ->
                TrackingCarrier.USPS
            isUspsPrefix && number.length == USPS_LEGACY_LENGTH && isValidUspsLegacy(number) ->
                TrackingCarrier.USPS
            !isUspsPrefix && isValidMod10ThreeOne(number) -> TrackingCarrier.FEDEX
            else -> null
        }
    }

    /**
     * The UPS 1Z check digit: over the 15 characters after the `1Z`,
     * letters mapped to digits (`(code - 63) mod 10`), even offsets once,
     * odd offsets twice; the check digit brings the total to the next
     * multiple of 10.
     */
    internal fun isValidUps(number: String): Boolean {
        if (!UPS_SHAPE.matches(number)) return false
        val body = number.substring(2, number.length - 1)
        val check = number.last().digitToIntOrNull() ?: return false
        var total = 0
        for ((index, c) in body.withIndex()) {
            val value = if (c.isDigit()) c.digitToInt() else (c.code - UPS_LETTER_OFFSET) % 10
            total += if (index % 2 == 0) value else value * UPS_ODD_WEIGHT
        }
        return (10 - total % 10) % 10 == check
    }

    /** The UPS shape, for [isValidUps] (the regex already matched it). */
    private val UPS_SHAPE = Regex("""1Z[0-9A-Z]{16}""")

    /**
     * The shared USPS / FedEx mod-10: from the rightmost digit before the
     * check digit, weights alternate 3, 1; the check digit brings the
     * total to the next multiple of 10.
     */
    internal fun isValidMod10ThreeOne(number: String): Boolean {
        if (number.length < 2 || !number.all { it.isDigit() }) return false
        val body = number.dropLast(1)
        val check = number.last().digitToInt()
        var total = 0
        for ((index, c) in body.reversed().withIndex()) {
            val weight = if (index % 2 == 0) MOD10_ODD_WEIGHT else 1
            total += c.digitToInt() * weight
        }
        return (10 - total % 10) % 10 == check
    }

    /**
     * The USPS 20-digit legacy check: the same mod-10, computed over the
     * number with `91` prefixed (see the class KDoc).
     */
    private fun isValidUspsLegacy(number: String): Boolean =
        isValidMod10ThreeOne(USPS_LEGACY_PREFIX + number)

    /**
     * The UPU S10 check digit: weights 8, 6, 4, 2, 3, 5, 9, 7 over the
     * first 8 serial digits, mod 11; 10 → 0, 11 → 5.
     */
    internal fun isValidS10(number: String): Boolean {
        if (!S10_SHAPE.matches(number)) return false
        val serial = number.substring(2, 11)
        val check = serial.last().digitToInt()
        var total = 0
        for (i in S10_WEIGHTS.indices) {
            total += serial[i].digitToInt() * S10_WEIGHTS[i]
        }
        val computed = S10_MODULUS - total % S10_MODULUS
        val expected = when (computed) {
            10 -> 0
            S10_MODULUS -> 5
            else -> computed
        }
        return expected == check
    }

    /** The S10 shape, for [isValidS10] (the regex already matched it). */
    private val S10_SHAPE = Regex("""[A-Z]{2}\d{9}[A-Z]{2}""")

    private const val USPS_IMPB_LENGTH = 22
    private const val USPS_LEGACY_LENGTH = 20
    private const val USPS_LEGACY_PREFIX = "91"
    private const val UPS_LETTER_OFFSET = 63
    private const val UPS_ODD_WEIGHT = 2
    private const val MOD10_ODD_WEIGHT = 3
    private const val S10_MODULUS = 11
}
