/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.smartactions

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Round 53 — dates and times in a message body, so the bubble can offer
 * "Add to calendar" and the notification a matching action.
 *
 * **Pure function**, like [OtpCodeDetector][com.relay.sms.domain.otp.OtpCodeDetector]:
 * input = raw text (+ a reference instant, for "tomorrow" and year
 * rollover), output = matches with resolved instants. No Android
 * dependency, no external NLP library — hand-rolled patterns for the
 * common forms, in the app's five languages (EN, FR, DE, ES, IT).
 *
 * # What v1 detects
 *
 *  - **Explicit dates** — "Dec 25", "December 25, 2026", "25 December
 *    2026", "12/25/2026", "12/25", "2026-12-25", "12-25-2026" — with an
 *    optional time after them ("Dec 25 at 5pm", "12/25 17:30"). A bare
 *    date becomes an all-day event; a date with a time, a one-hour event.
 *    A date without a year rolls to next year when it already passed.
 *  - **Relative days, with a time** — "tomorrow at 5pm", "5pm tomorrow",
 *    "demain 17h30". A bare "today"/"tomorrow" is NOT detected: "today
 *    was great" would otherwise offer a calendar event for nothing, and
 *    an all-day event with no title is not worth the noise.
 *  - **Weekday names, with a time** — "Monday at 9:30am", "lundi 17h30",
 *    "next Friday 5pm". The weekday resolves to its next occurrence (a
 *    time already passed today pushes it a week out); "next" means the
 *    occurrence in the following week.
 *
 * A bare time ("at 5pm", no day) is NOT detected — it is not a calendar
 * event on its own.
 *
 * # False-positive guards (the OTP doctrine)
 *
 *  - **"March" / "May" must be capitalised** — "we march 5 miles" is a
 *    verb, not a date. The other month names have no verb collision and
 *    match in any case.
 *  - **A bare `MM/DD` followed by a unit word is a fraction**, not a date
 *    ("add 3/4 cup of flour") — see [FRACTION_WORDS].
 *  - **Times need a marker**: `am`/`pm`, a colon (`17:30`) or the French
 *    `17h30` form. A bare number is never a time, and `17h30min` (a
 *    duration) is refused by the trailing-letter guard.
 *  - **Numeric dates are read US-first** (`MM/DD`): "05/06/2026" is May
 *    6, and "25/12/2026" is rejected (month 25 does not exist) rather
 *    than misread. European day-first numeric dates are a known v1
 *    limitation; day-first MONTH names ("25 December") do work.
 *  - **Impossible dates are dropped** ("Feb 30" never matches — the
 *    [LocalDate] construction throws and the candidate is skipped).
 */
object DateTimeDetector {

    /** Cap on the inspected body, like the OTP detector's: bounds the regexes. */
    private const val MAX_BODY_LENGTH = 2000

    /** How far after / before a day word a time may sit ("tomorrow at 5pm"). */
    private const val TIME_WINDOW_CHARS = 20

    /** A timed event lasts one hour by default (the user edits it in the app). */
    private const val DEFAULT_EVENT_MINUTES: Long = 60

    /** Two-digit years resolve into this century ("12/25/26" → 2026). */
    private const val CENTURY_BASE = 2000

    /** Words after a bare `MM/DD` that mark it a fraction, not a date. */
    private val FRACTION_WORDS = setOf(
        "cup", "cups", "tbsp", "tsp", "oz", "lb", "lbs", "kg", "g", "mg",
        "ml", "l", "liter", "liters", "litre", "litres", "of",
    )

    // ──────────────── Month names (5 locales) ────────────────

    /**
     * Month names → month number. Abbreviations included; "mai" is both
     * French and German May, "marzo"/"agosto" both Spanish and Italian —
     * the map dedupes them. "march" and "may" are here for LOOKUP only
     * ("MAY" lowercases to "may"): the pattern below excludes them from
     * the case-insensitive alternation, so they only match capitalised
     * (see the class KDoc).
     */
    private val MONTHS = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2,
        "march" to 3, "april" to 4, "apr" to 4, "may" to 5,
        "june" to 6, "jun" to 6,
        "july" to 7, "jul" to 7, "august" to 8, "aug" to 8,
        "september" to 9, "sep" to 9, "sept" to 9, "october" to 10,
        "oct" to 10, "november" to 11, "nov" to 11, "december" to 12,
        "dec" to 12,
        // FR
        "janvier" to 1, "janv" to 1, "février" to 2, "févr" to 2,
        "mars" to 3, "avril" to 4, "avr" to 4, "mai" to 5, "juin" to 6,
        "juillet" to 7, "juil" to 7, "août" to 8, "septembre" to 9,
        "sept" to 9, "octobre" to 10, "oct" to 10, "novembre" to 11,
        "nov" to 11, "décembre" to 12, "déc" to 12,
        // DE
        "januar" to 1, "jan" to 1, "februar" to 2, "feb" to 2,
        "märz" to 3, "mär" to 3, "april" to 4, "apr" to 4, "mai" to 5,
        "juni" to 6, "jun" to 6, "juli" to 7, "jul" to 7, "august" to 8,
        "aug" to 8, "september" to 9, "sept" to 9, "oktober" to 10,
        "okt" to 10, "november" to 11, "nov" to 11, "dezember" to 12,
        "dez" to 12,
        // ES
        "enero" to 1, "ene" to 1, "febrero" to 2, "feb" to 2,
        "marzo" to 3, "mar" to 3, "abril" to 4, "abr" to 4, "mayo" to 5,
        "may" to 5, "junio" to 6, "jun" to 6, "julio" to 7, "jul" to 7,
        "agosto" to 8, "ago" to 8, "septiembre" to 9, "sept" to 9,
        "octubre" to 10, "oct" to 10, "noviembre" to 11, "nov" to 11,
        "diciembre" to 12, "dic" to 12,
        // IT
        "gennaio" to 1, "gen" to 1, "febbraio" to 2, "feb" to 2,
        "marzo" to 3, "mar" to 3, "aprile" to 4, "apr" to 4,
        "maggio" to 5, "mag" to 5, "giugno" to 6, "giu" to 6,
        "luglio" to 7, "lug" to 7, "agosto" to 8, "ago" to 8,
        "settembre" to 9, "set" to 9, "ottobre" to 10, "ott" to 10,
        "novembre" to 11, "nov" to 11, "dicembre" to 12, "dic" to 12,
    )

    /**
     * The English month names that collide with verbs — capitalised only
     * ("March", "May", and their all-caps forms). Every other name in
     * [MONTHS] matches in any case.
     */
    private const val CAPITALISED_MONTHS = "March|MARCH|May|MAY"

    /** Month words that only match capitalised (see [MONTH_PATTERN]). */
    private val CAPITALISED_MONTH_KEYS = setOf("march", "may")

    /**
     * The month alternation: capitalised-only names first, then every
     * other name case-insensitively, longest first (so "sept" wins over
     * "sep" and "juillet" over "juil"). Abbreviations (3–4 letters) take
     * an optional trailing period ("Dec.", "janv."). "march" and "may"
     * stay out of the insensitive half — they only match capitalised.
     */
    private val MONTH_PATTERN: String = run {
        val insensitive = (MONTHS.keys - CAPITALISED_MONTH_KEYS)
            .sortedByDescending { it.length }
            .joinToString("|") { name ->
                if (name.length <= 4) "$name\\.?" else name
            }
        "(?:$CAPITALISED_MONTHS|(?i:$insensitive))"
    }

    /** "Dec 25", "December 25th, 2026" — month first, optional year. */
    private val MONTH_FIRST_REGEX = Regex(
        """(?<!\p{L})($MONTH_PATTERN)\s+(\d{1,2})(?:st|nd|rd|th)?(?:\s*,?\s*(\d{4}))?""",
    )

    /** "25 December", "25 décembre 2026", "25. Dezember 2026" — day first, optional year. */
    private val DAY_FIRST_REGEX = Regex(
        """(?<![\d/])(\d{1,2})\.?\s+($MONTH_PATTERN)(?:\s*,?\s*(\d{4}))?(?!\p{L})""",
    )

    // ──────────────── Numeric dates ────────────────

    /** "2026-12-25" (ISO). */
    private val ISO_REGEX = Regex("""(?<![\d\-/])(\d{4})-(\d{1,2})-(\d{1,2})(?![\d/])""")

    /** "12/25/2026" / "12/25" — US order (see the class KDoc). */
    private val SLASH_REGEX = Regex("""(?<![\d/])(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?(?![\d/])""")

    /** "12-25-2026" — the year is required (a bare "12-25" is too ambiguous). */
    private val DASH_REGEX = Regex("""(?<![\d\-/])(\d{1,2})-(\d{1,2})-(\d{4})(?![\d/])""")

    // ──────────────── Relative days and weekdays (5 locales) ────────────────

    /** "today" / "tomorrow" → days from now. */
    private val RELATIVE_DAYS = mapOf(
        "today" to 0, "tomorrow" to 1,
        "aujourd'hui" to 0, "demain" to 1,
        "heute" to 0, "morgen" to 1,
        "hoy" to 0, "mañana" to 1,
        "oggi" to 0, "domani" to 1,
    )

    private val RELATIVE_REGEX = Regex(
        """(?<!\p{L})(?:${RELATIVE_DAYS.keys.sortedByDescending { it.length }.joinToString("|")})(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )

    /** Weekday names → [DayOfWeek]. Abbreviations included. */
    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY,
        "tues" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "wed" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
        "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY,
        "thurs" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY,
        "fri" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sat" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY,
        "sun" to DayOfWeek.SUNDAY,
        // FR
        "lundi" to DayOfWeek.MONDAY, "lun" to DayOfWeek.MONDAY,
        "mardi" to DayOfWeek.TUESDAY, "mar" to DayOfWeek.TUESDAY,
        "mercredi" to DayOfWeek.WEDNESDAY, "mer" to DayOfWeek.WEDNESDAY,
        "jeudi" to DayOfWeek.THURSDAY, "jeu" to DayOfWeek.THURSDAY,
        "vendredi" to DayOfWeek.FRIDAY, "ven" to DayOfWeek.FRIDAY,
        "samedi" to DayOfWeek.SATURDAY, "sam" to DayOfWeek.SATURDAY,
        "dimanche" to DayOfWeek.SUNDAY, "dim" to DayOfWeek.SUNDAY,
        // DE — the two-letter abbreviations take a MANDATORY period
        // ("Mo."), because bare "so" / "mi" / "fr" are ordinary words.
        "montag" to DayOfWeek.MONDAY, "mo" to DayOfWeek.MONDAY,
        "dienstag" to DayOfWeek.TUESDAY, "di" to DayOfWeek.TUESDAY,
        "mittwoch" to DayOfWeek.WEDNESDAY, "mi" to DayOfWeek.WEDNESDAY,
        "donnerstag" to DayOfWeek.THURSDAY, "do" to DayOfWeek.THURSDAY,
        "freitag" to DayOfWeek.FRIDAY, "fr" to DayOfWeek.FRIDAY,
        "samstag" to DayOfWeek.SATURDAY, "sa" to DayOfWeek.SATURDAY,
        "sonntag" to DayOfWeek.SUNDAY, "so" to DayOfWeek.SUNDAY,
        // ES
        "lunes" to DayOfWeek.MONDAY, "lun" to DayOfWeek.MONDAY,
        "martes" to DayOfWeek.TUESDAY, "mar" to DayOfWeek.TUESDAY,
        "miércoles" to DayOfWeek.WEDNESDAY, "mié" to DayOfWeek.WEDNESDAY,
        "jueves" to DayOfWeek.THURSDAY, "jue" to DayOfWeek.THURSDAY,
        "viernes" to DayOfWeek.FRIDAY, "vie" to DayOfWeek.FRIDAY,
        "sábado" to DayOfWeek.SATURDAY, "sáb" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY, "dom" to DayOfWeek.SUNDAY,
        // IT
        "lunedì" to DayOfWeek.MONDAY, "lun" to DayOfWeek.MONDAY,
        "martedì" to DayOfWeek.TUESDAY, "mar" to DayOfWeek.TUESDAY,
        "mercoledì" to DayOfWeek.WEDNESDAY, "mer" to DayOfWeek.WEDNESDAY,
        "giovedì" to DayOfWeek.THURSDAY, "gio" to DayOfWeek.THURSDAY,
        "venerdì" to DayOfWeek.FRIDAY, "ven" to DayOfWeek.FRIDAY,
        "sabato" to DayOfWeek.SATURDAY, "sab" to DayOfWeek.SATURDAY,
        "domenica" to DayOfWeek.SUNDAY, "dom" to DayOfWeek.SUNDAY,
    )

    /** The two-letter German abbreviations, which need their period. */
    private val SHORT_GERMAN_DAYS = setOf("mo", "di", "mi", "do", "fr", "sa", "so")

    private val WEEKDAY_PATTERN: String = WEEKDAYS.keys
        .sortedByDescending { it.length }
        .joinToString("|") { name ->
            when {
                name in SHORT_GERMAN_DAYS -> "$name\\."
                name.length <= 4 -> "$name\\.?"
                else -> name
            }
        }

    /** "next" in the five languages, before the weekday ("next Monday"). */
    private const val NEXT_PREFIX = "(?:next|prochain|prochaine|nächste|nächsten|nächstem|próximo|próxima|prossimo|prossima)"

    /**
     * A weekday, with an optional "next" before it ("next Monday") or the
     * French postposed "prochain" after it ("lundi prochain"). The time
     * itself is found separately ([findTimeAfter]/[findTimeBefore]) — it
     * is required, so a bare weekday never matches.
     */
    private val WEEKDAY_REGEX = Regex(
        """(?<!\p{L})(?:($NEXT_PREFIX)\s+)?($WEEKDAY_PATTERN)(?:\s+(prochain|prochaine))?(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )

    // ──────────────── Times ────────────────

    /**
     * A time of day: 12-hour with am/pm ("5pm", "5:30 pm"), 24-hour
     * ("17:30") or the French "17h30" form. The guards refuse a time
     * glued to a digit, a letter or a colon on either side — "17h30min"
     * is a duration, not a time, and "12345pm" is not "5pm".
     */
    private val TIME_REGEX = Regex(
        """(?<![\dA-Za-z:])(?:(?<h12>\d{1,2})(?::(?<m12>\d{2}))?\s*(?<ampm>am|pm|a\.m\.|p\.m\.)""" +
            """|(?<h24>\d{1,2}):(?<m24>\d{2})|(?<hh>\d{1,2})h(?<mh>\d{2}))(?![\dA-Za-z])""",
        RegexOption.IGNORE_CASE,
    )

    /** What may sit between a date and its time (", ", " at ", " à "). */
    private val TIME_PREFIX_REGEX = Regex("""^[, ]*(?:at\s+|à\s+)?""")

    /** What may sit between a time and the day word after it (", ", " "). */
    private val TIME_GAP_REGEX = Regex("""^[\s,]*$""")

    /**
     * Every date/time in [text], in order, or an empty list.
     * [nowMillis] is the reference instant ("tomorrow", year rollover);
     * [zone] the zone the instants resolve in. Both default to the
     * device's now — tests pass fixed values.
     */
    fun findAll(
        text: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SmartActionMatch> {
        if (text.isBlank()) return emptyList()
        val capped = if (text.length > MAX_BODY_LENGTH) text.substring(0, MAX_BODY_LENGTH) else text
        val candidates = ArrayList<SmartActionMatch>()
        candidates += datedCandidates(capped, nowMillis, zone)
        candidates += relativeCandidates(capped, nowMillis, zone)
        candidates += weekdayCandidates(capped, nowMillis, zone)
        if (candidates.isEmpty()) return emptyList()
        // Earliest start wins; on a tie the longest match wins ("December
        // 25, 2026" over "December 25"). Overlapping losers are dropped.
        candidates.sortWith(compareBy({ it.range.first }, { -(it.range.last - it.range.first) }))
        val accepted = ArrayList<SmartActionMatch>(candidates.size)
        var lastEnd = -1
        for (candidate in candidates) {
            if (candidate.range.first > lastEnd) {
                accepted += candidate
                lastEnd = candidate.range.last
            }
        }
        return accepted
    }

    /** The first date/time in [text], or `null`. */
    fun findFirst(
        text: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): SmartActionMatch? = findAll(text, nowMillis, zone).firstOrNull()

    // ──────────────── Explicit dates ────────────────

    /**
     * The explicit-date candidates: month names (month-first and
     * day-first) and numeric forms (ISO, slash, dash). A time next to
     * the date attaches via [buildDatedMatch]; without one the match is
     * all-day.
     */
    private fun datedCandidates(
        text: String,
        nowMillis: Long,
        zone: ZoneId,
    ): List<SmartActionMatch> {
        val monthFirst = MONTH_FIRST_REGEX.findAll(text).mapNotNull { match ->
            val month = monthOf(match.groupValues[1])
            val day = match.groupValues[2].toIntOrNull()
            if (month == null || day == null) {
                null
            } else {
                val year = match.groupValues[3].toIntOrNull()
                buildDatedMatch(text, match.range, month, day, year, nowMillis, zone)
            }
        }
        val dayFirst = DAY_FIRST_REGEX.findAll(text).mapNotNull { match ->
            val day = match.groupValues[1].toIntOrNull()
            val month = monthOf(match.groupValues[2])
            if (month == null || day == null) {
                null
            } else {
                val year = match.groupValues[3].toIntOrNull()
                buildDatedMatch(text, match.range, month, day, year, nowMillis, zone)
            }
        }
        val iso = ISO_REGEX.findAll(text).mapNotNull { match ->
            val year = match.groupValues[1].toIntOrNull()
            val month = match.groupValues[2].toIntOrNull()
            val day = match.groupValues[3].toIntOrNull()
            if (year == null || month == null || day == null) {
                null
            } else {
                buildDatedMatch(text, match.range, month, day, year, nowMillis, zone)
            }
        }
        val slash = SLASH_REGEX.findAll(text).mapNotNull { match ->
            val month = match.groupValues[1].toIntOrNull()
            val day = match.groupValues[2].toIntOrNull()
            // A 2-digit year resolves into this century; 4-digit years
            // pass through.
            val year = match.groupValues[3].toIntOrNull()
                ?.let { if (it < 100) CENTURY_BASE + it else it }
            if (month == null || day == null) {
                null
            } else if (year == null && isFraction(text, match.range.last + 1)) {
                null
            } else {
                buildDatedMatch(text, match.range, month, day, year, nowMillis, zone)
            }
        }
        val dash = DASH_REGEX.findAll(text).mapNotNull { match ->
            val month = match.groupValues[1].toIntOrNull()
            val day = match.groupValues[2].toIntOrNull()
            val year = match.groupValues[3].toIntOrNull()
            if (month == null || day == null || year == null) {
                null
            } else {
                buildDatedMatch(text, match.range, month, day, year, nowMillis, zone)
            }
        }
        return (monthFirst + dayFirst + iso + slash + dash).toList()
    }

    /**
     * The month number for a matched month word (period stripped,
     * lowercased), or `null` when the word is not a month — which cannot
     * happen for a regex match, but keeps the lookup total.
     */
    private fun monthOf(word: String): Int? {
        val key = word.trimEnd('.').lowercase(java.util.Locale.ROOT)
        return MONTHS[key]
    }

    /**
     * True when the text right after a bare `MM/DD` starts with a unit
     * word — "3/4 cup", "3/4 of the cake" — i.e. a fraction, not a date.
     */
    private fun isFraction(text: String, after: Int): Boolean {
        if (after >= text.length) return false
        val rest = text.substring(after).trimStart()
        val word = rest.takeWhile { it.isLetter() }.lowercase(java.util.Locale.ROOT)
        return word in FRACTION_WORDS
    }

    // ──────────────── Relative days and weekdays ────────────────

    private fun relativeCandidates(
        text: String,
        nowMillis: Long,
        zone: ZoneId,
    ): List<SmartActionMatch> {
        val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return RELATIVE_REGEX.findAll(text).mapNotNull { match ->
            val offset = RELATIVE_DAYS[match.value.lowercase(java.util.Locale.ROOT)]
            offset?.let { timedMatch(text, match.range, today.plusDays(it.toLong()), zone) }
        }.toList()
    }

    private fun weekdayCandidates(
        text: String,
        nowMillis: Long,
        zone: ZoneId,
    ): List<SmartActionMatch> {
        val now = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
        return WEEKDAY_REGEX.findAll(text).mapNotNull { match ->
            val key = match.groupValues[2].trimEnd('.').lowercase(java.util.Locale.ROOT)
            val target = WEEKDAYS[key]
            if (target == null) {
                null
            } else {
                val isNext = match.groupValues[1].isNotEmpty() ||
                    match.groupValues[3].isNotEmpty()
                val date = resolveWeekday(
                    now.toLocalDate(),
                    target,
                    isNext,
                    text,
                    match.range,
                    now,
                )
                date?.let { timedMatch(text, match.range, it, zone) }
            }
        }.toList()
    }

    /**
     * The date of the next [target] weekday from [today]: the coming one,
     * a week out when the time already passed today. With "next", the
     * occurrence in the NEXT week: the coming one when it already falls
     * next week ("next Monday" from a Tuesday is the Monday six days
     * out), a week further when it falls this week ("next Friday" from a
     * Tuesday is nine days out, not two). `null` when no usable time sits
     * next to the weekday at all — the time decides whether "today" still
     * counts, so the resolution and the time lookup happen together.
     */
    private fun resolveWeekday(
        today: LocalDate,
        target: DayOfWeek,
        isNext: Boolean,
        text: String,
        range: IntRange,
        now: java.time.ZonedDateTime,
    ): LocalDate? {
        val time = findTimeNear(text, range)?.time ?: return null
        var days = (target.value - today.dayOfWeek.value + DAYS_IN_WEEK) % DAYS_IN_WEEK
        if (isNext) {
            val daysLeftInWeek = DAYS_IN_WEEK - today.dayOfWeek.value
            if (days <= daysLeftInWeek) days += DAYS_IN_WEEK
        } else if (days == 0 && today.atTime(time).isBefore(now.toLocalDateTime())) {
            days = DAYS_IN_WEEK
        }
        return today.plusDays(days.toLong())
    }

    // ──────────────── Shared construction ────────────────

    /**
     * A dated match (explicit date, optional time after it): resolves the
     * year when absent, attaches a following time when one sits right
     * after the date, and builds the [SmartAction.DateTime]. `null` for
     * an impossible date ("Feb 30").
     */
    private fun buildDatedMatch(
        text: String,
        range: IntRange,
        month: Int,
        day: Int,
        year: Int?,
        nowMillis: Long,
        zone: ZoneId,
    ): SmartActionMatch? {
        val timed = findTimeAfter(text, range.last + 1)
        val now = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
        val date = resolveDate(month, day, year, timed?.time, now) ?: return null
        val end = timed?.range?.last ?: range.last
        val fullRange = range.first..end
        return SmartActionMatch(
            fullRange,
            dateTimeAction(text.substring(fullRange), date, timed?.time, zone),
        )
    }

    /**
     * A timed match (relative day / weekday): the time is REQUIRED, so
     * `null` when none sits next to the day word, on either side. The
     * range extends to cover the time, whichever side it is on.
     */
    private fun timedMatch(
        text: String,
        range: IntRange,
        date: LocalDate,
        zone: ZoneId,
    ): SmartActionMatch? {
        val timed = findTimeNear(text, range) ?: return null
        val fullRange = minOf(range.first, timed.range.first)..maxOf(range.last, timed.range.last)
        return SmartActionMatch(
            fullRange,
            dateTimeAction(text.substring(fullRange), date, timed.time, zone),
        )
    }

    /**
     * The calendar action for [date] with an optional [time]: all-day
     * (midnight to midnight) without one, a one-hour event with one.
     */
    private fun dateTimeAction(
        text: String,
        date: LocalDate,
        time: LocalTime?,
        zone: ZoneId,
    ): SmartAction.DateTime {
        return if (time == null) {
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            SmartAction.DateTime(text, start, end, allDay = true)
        } else {
            val start = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            val end = date.atTime(time).plusMinutes(DEFAULT_EVENT_MINUTES).atZone(zone)
                .toInstant().toEpochMilli()
            SmartAction.DateTime(text, start, end, allDay = false)
        }
    }

    /**
     * The [LocalDate] for a month/day with an optional [year]: an
     * explicit year is used as-is; without one, this year — or next year
     * when the date (with its time, when there is one) already passed.
     * `null` for an impossible date.
     */
    private fun resolveDate(
        month: Int,
        day: Int,
        year: Int?,
        time: LocalTime?,
        now: java.time.ZonedDateTime,
    ): LocalDate? {
        if (year != null) return safeDate(year, month, day)
        val today = now.toLocalDate()
        val date = safeDate(today.year, month, day) ?: return null
        val passed = if (time == null) date.isBefore(today) else date.atTime(time).isBefore(now.toLocalDateTime())
        return if (passed) date.plusYears(1) else date
    }

    /** [LocalDate.of] that returns `null` instead of throwing ("Feb 30"). */
    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? {
        return try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            null
        }
    }

    // ──────────────── Time lookup ────────────────

    /** A time match: its clock time plus where it sits in the body. */
    private data class Timed(val time: LocalTime, val range: IntRange)

    /**
     * The time right after [after] ("Dec 25 at 5pm"): the window after
     * the date, past an optional ", " / "at " / "à " prefix. `null` when
     * no valid time starts there.
     */
    private fun findTimeAfter(text: String, after: Int): Timed? {
        if (after >= text.length) return null
        val window = text.substring(after, minOf(text.length, after + TIME_WINDOW_CHARS))
        val prefix = TIME_PREFIX_REGEX.find(window)?.value?.length ?: 0
        val match = TIME_REGEX.matchAt(window, prefix) ?: return null
        val time = parseTime(match) ?: return null
        return Timed(time, (after + match.range.first)..(after + match.range.last))
    }

    /**
     * The time next to [range], after it first ("tomorrow at 5pm"), then
     * before it ("5pm tomorrow"). The before-side time must end right
     * before the day word — only a comma or spaces may sit between them.
     */
    private fun findTimeNear(text: String, range: IntRange): Timed? {
        findTimeAfter(text, range.last + 1)?.let { return it }
        return findTimeBefore(text, range.first)
    }

    private fun findTimeBefore(text: String, before: Int): Timed? {
        val start = maxOf(0, before - TIME_WINDOW_CHARS)
        val window = text.substring(start, before)
        val match = TIME_REGEX.findAll(window).lastOrNull() ?: return null
        val gap = window.substring(match.range.last + 1)
        if (!TIME_GAP_REGEX.matches(gap)) return null
        val time = parseTime(match) ?: return null
        return Timed(time, (start + match.range.first)..(start + match.range.last))
    }

    /**
     * The clock time for a [TIME_REGEX] match, or `null` when the numbers
     * are out of range ("13pm", "25:00"). 12am is midnight, 12pm noon.
     * Dispatches on which branch matched: 12-hour, 24-hour or French
     * "17h30".
     */
    private fun parseTime(match: MatchResult): LocalTime? {
        val h12 = match.groups["h12"]?.value?.toIntOrNull()
        if (h12 != null) return parse12HourTime(match, h12)
        val h24 = match.groups["h24"]?.value?.toIntOrNull()
        if (h24 != null) return parseHourMinuteTime(match, "h24", "m24")
        return parseHourMinuteTime(match, "hh", "mh")
    }

    /** The 12-hour branch ("5pm", "5:30 pm") as a clock time, or `null`. */
    private fun parse12HourTime(match: MatchResult, h12: Int): LocalTime? {
        val ampm = match.groups["ampm"]?.value?.lowercase(java.util.Locale.ROOT)
        val minutes = match.groups["m12"]?.value?.toIntOrNull() ?: 0
        if (ampm == null || minutes > MAX_MINUTE) return null
        if (h12 < 1 || h12 > MAX_12H_HOUR) return null
        val hour = when {
            ampm.startsWith("a") && h12 == MAX_12H_HOUR -> 0
            ampm.startsWith("p") && h12 != MAX_12H_HOUR -> h12 + HALF_DAY_HOURS
            else -> h12
        }
        return LocalTime.of(hour, minutes)
    }

    /**
     * An hour:minute branch as a clock time, or `null`: the 24-hour
     * form ("17:30", groups `h24`/`m24`) and the French form ("17h30",
     * groups `hh`/`mh`) differ only in their group names.
     */
    private fun parseHourMinuteTime(
        match: MatchResult,
        hourGroup: String,
        minuteGroup: String,
    ): LocalTime? {
        val hour = match.groups[hourGroup]?.value?.toIntOrNull()
        val minutes = match.groups[minuteGroup]?.value?.toIntOrNull()
        if (hour == null || minutes == null) return null
        if (hour > MAX_24H_HOUR || minutes > MAX_MINUTE) return null
        return LocalTime.of(hour, minutes)
    }

    private const val DAYS_IN_WEEK = 7
    private const val MAX_12H_HOUR = 12
    private const val MAX_24H_HOUR = 23
    private const val MAX_MINUTE = 59
    private const val HALF_DAY_HOURS = 12
}
