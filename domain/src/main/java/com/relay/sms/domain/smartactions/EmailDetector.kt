/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.smartactions

/**
 * Round 53 — email addresses in a message body, so the bubble can offer
 * "Send email" and the notification a matching action.
 *
 * **Pure function**, like [OtpCodeDetector][com.relay.sms.domain.otp.OtpCodeDetector]:
 * input = raw text, output = matches. No Android dependency — this is why
 * the pattern is hand-rolled here instead of `android.util.Patterns
 * .EMAIL_ADDRESS`, which is a stub on plain JVM and untestable there. The
 * shape mirrors it (local part + `@` + dotted domain) with two
 * tightenings:
 *  - the final label must be at least two LETTERS (`user@host.123` is not
 *    an address — a version number with an `@` in front of it);
 *  - the match must not be glued to a word character on either side, so
 *    `xuser@host.com` never matches as its tail `user@host.com`.
 *
 * Trailing punctuation is not part of the match: the domain labels end in
 * a letter, so `mail me at bob@example.com.` links the address and leaves
 * the period outside it.
 *
 * The doctrine is the OTP detector's: a missed address costs one manual
 * copy, a false positive teaches the user to ignore the feature.
 */
object EmailDetector {

    /** Cap on the inspected body, like the OTP detector's: bounds the regex. */
    private const val MAX_BODY_LENGTH = 2000

    /**
     * The address pattern: local part, `@`, dotted domain. The lookbehind
     * refuses a match glued to a word character or an `@` (a fragment of a
     * longer token); the lookahead refuses one glued to a letter, a digit
     * or a dot followed by a letter (a fragment of a longer domain,
     * `bob@example.com.uk` matching as `bob@example.com`) — but a bare
     * trailing period is sentence punctuation, not a longer domain, so it
     * stays outside the match instead of killing it.
     */
    private val EMAIL_REGEX = Regex(
        """(?<![A-Za-z0-9._%+@-])[A-Za-z0-9+._%\-]{1,256}@[A-Za-z0-9][A-Za-z0-9\-]{0,64}""" +
            """(?:\.[A-Za-z0-9][A-Za-z0-9\-]{0,25})*\.[A-Za-z]{2,}(?![A-Za-z0-9]|\.[A-Za-z])""",
    )

    /** Every email address in [text], in order, or an empty list. */
    fun findAll(text: String): List<SmartActionMatch> {
        if (text.isBlank()) return emptyList()
        val capped = if (text.length > MAX_BODY_LENGTH) text.substring(0, MAX_BODY_LENGTH) else text
        return EMAIL_REGEX.findAll(capped).map { match ->
            SmartActionMatch(match.range, SmartAction.Email(match.value))
        }.toList()
    }

    /** The first email address in [text], or `null`. */
    fun findFirst(text: String): SmartActionMatch? = findAll(text).firstOrNull()
}
