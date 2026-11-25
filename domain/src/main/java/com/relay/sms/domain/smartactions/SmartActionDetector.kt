/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.smartactions

import java.time.ZoneId

/**
 * Round 53 — the one detector run per message: runs every smart-action
 * detector ([EmailDetector], [AddressDetector], [DateTimeDetector],
 * [TrackingDetector]) over the body once and merges the matches.
 *
 * The bubble and the notifier both call this — one run, shared — so a
 * message never gets a different action in the shade than in the thread.
 * Phone numbers are NOT in this run: they come from
 * `android.util.Patterns.PHONE`, which cannot run on plain JVM, so the
 * app layer finds them and merges them with these matches by position
 * (see the notifier's picker and the bubble's linkify pass).
 *
 * # Overlap rules
 *
 *  - **A match inside a URL is dropped.** A tracking number or an email
 *    address in a query string (`…?q=1Z999…`, `…?email=a@b.com`) is part
 *    of the link, not an action — the bubble's URL priority does the
 *    same for display, and dropping here keeps the notifier honest too.
 *    Only explicit `http(s)://` URLs are recognised here (the domain
 *    layer has no `Patterns.WEB_URL`); a tracking number inside a bare
 *    domain URL is a known edge the bubble still covers via priority.
 *  - **Overlapping matches across detectors resolve by position**: the
 *    earliest start wins, and on a tie the longest match. In practice
 *    the shapes barely overlap (an email cannot contain a date, a
 *    tracking number cannot contain an address), so the rule is a
 *    safety net, not a ranking.
 */
object SmartActionDetector {

    /** Cap on the inspected body, like the OTP detector's: bounds the regexes. */
    private const val MAX_BODY_LENGTH = 2000

    /** Explicit URLs, whose spans swallow any detector match inside them. */
    private val URL_REGEX = Regex("""https?://[^\s]+""")

    /**
     * Every smart action in [text], in position order, or an empty list.
     * [nowMillis] / [zone] are the date detector's reference instant and
     * zone (see [DateTimeDetector.findAll]); both default to the device's
     * now.
     */
    fun detectAll(
        text: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SmartActionMatch> {
        if (text.isBlank()) return emptyList()
        val capped = if (text.length > MAX_BODY_LENGTH) text.substring(0, MAX_BODY_LENGTH) else text
        val urlSpans = URL_REGEX.findAll(capped).map { it.range }.toList()
        val candidates = ArrayList<SmartActionMatch>()
        candidates += EmailDetector.findAll(capped)
        candidates += AddressDetector.findAll(capped)
        candidates += DateTimeDetector.findAll(capped, nowMillis, zone)
        candidates += TrackingDetector.findAll(capped)
        if (candidates.isEmpty()) return emptyList()
        candidates.sortWith(compareBy({ it.range.first }, { -(it.range.last - it.range.first) }))
        val accepted = ArrayList<SmartActionMatch>(candidates.size)
        var lastEnd = -1
        for (candidate in candidates) {
            val overlapsPrevious = candidate.range.first <= lastEnd
            val insideUrl = urlSpans.any { candidate.range.first in it }
            if (!overlapsPrevious && !insideUrl) {
                accepted += candidate
                lastEnd = candidate.range.last
            }
        }
        return accepted
    }

    /**
     * The first smart action in [text] — the one the notification offers —
     * or `null`.
     */
    fun firstAction(
        text: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): SmartActionMatch? = detectAll(text, nowMillis, zone).firstOrNull()
}
