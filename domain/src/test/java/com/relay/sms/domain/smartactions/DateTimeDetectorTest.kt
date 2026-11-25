/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.smartactions

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Positive and negative cases for [DateTimeDetector].
 *
 * The reference instant is fixed — Tuesday 2026-11-24, noon UTC — so
 * "tomorrow", weekday resolution and year rollover are deterministic.
 * Expected instants are built from the same [LocalDate]/[LocalDateTime]
 * the detector resolves to: the tests pin the parsing and the
 * resolution, not the `java.time` arithmetic underneath.
 */
class DateTimeDetectorTest {

    private val zone: ZoneId = ZoneOffset.UTC

    /** Tuesday 2026-11-24, 12:00 UTC. */
    private val nowMillis: Long = Instant.parse("2026-11-24T12:00:00Z").toEpochMilli()

    private fun first(text: String): SmartAction.DateTime? =
        DateTimeDetector.findFirst(text, nowMillis, zone)?.action as? SmartAction.DateTime

    private fun allDay(year: Int, month: Int, day: Int): Pair<Long, Long> {
        val date = LocalDate.of(year, month, day)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    private fun timed(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): Pair<Long, Long> {
        val start = LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone).toInstant().toEpochMilli()
        val end = LocalDateTime.of(year, month, day, hour, minute).plusHours(1)
            .atZone(zone).toInstant().toEpochMilli()
        return start to end
    }

    private fun assertAllDay(found: SmartAction.DateTime?, year: Int, month: Int, day: Int) {
        assertThat(found).isNotNull()
        val (start, end) = allDay(year, month, day)
        assertThat(found!!.startMillis).isEqualTo(start)
        assertThat(found.endMillis).isEqualTo(end)
        assertThat(found.allDay).isTrue()
    }

    private fun assertTimed(
        found: SmartAction.DateTime?,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
    ) {
        assertThat(found).isNotNull()
        val (start, end) = timed(year, month, day, hour, minute)
        assertThat(found!!.startMillis).isEqualTo(start)
        assertThat(found.endMillis).isEqualTo(end)
        assertThat(found.allDay).isFalse()
    }

    // ──────────────── Explicit dates ────────────────

    @Test fun `month name without year, this year`() {
        assertAllDay(first("Party on Dec 25!"), 2026, 12, 25)
    }

    @Test fun `month name with year`() {
        assertAllDay(first("December 25, 2026"), 2026, 12, 25)
    }

    @Test fun `month name with ordinal day`() {
        assertAllDay(first("December 25th, 2026"), 2026, 12, 25)
    }

    @Test fun `day first month name`() {
        assertAllDay(first("25 December 2026"), 2026, 12, 25)
    }

    @Test fun `past month day rolls to next year`() {
        assertAllDay(first("Jan 5"), 2027, 1, 5)
    }

    @Test fun `slash date with year`() {
        assertAllDay(first("12/25/2026"), 2026, 12, 25)
    }

    @Test fun `slash date without year`() {
        assertAllDay(first("see you 12/25"), 2026, 12, 25)
    }

    @Test fun `slash date with two digit year`() {
        assertAllDay(first("12/25/26"), 2026, 12, 25)
    }

    @Test fun `iso date`() {
        assertAllDay(first("2026-12-25"), 2026, 12, 25)
    }

    @Test fun `dash date with year`() {
        assertAllDay(first("12-25-2026"), 2026, 12, 25)
    }

    // ──────────────── Dates with times ────────────────

    @Test fun `date with pm time`() {
        assertTimed(first("Dec 25 at 5pm"), 2026, 12, 25, 17)
    }

    @Test fun `date with am time and minutes`() {
        assertTimed(first("Dec 25, 9:30am"), 2026, 12, 25, 9, 30)
    }

    @Test fun `date with 24 hour time`() {
        assertTimed(first("12/25 17:30"), 2026, 12, 25, 17, 30)
    }

    @Test fun `noon and midnight`() {
        assertTimed(first("Dec 25 at 12pm"), 2026, 12, 25, 12)
        assertTimed(first("Dec 25 at 12am"), 2026, 12, 25, 0)
    }

    @Test fun `past date with a future time today stays this year`() {
        // Nov 24 at 5pm is still ahead at noon.
        assertTimed(first("Nov 24 at 5pm"), 2026, 11, 24, 17)
    }

    @Test fun `past date with a past time today rolls to next year`() {
        // Nov 24 at 9am already passed at noon.
        assertTimed(first("Nov 24 at 9am"), 2027, 11, 24, 9)
    }

    // ──────────────── Relative days ────────────────

    @Test fun `tomorrow with time`() {
        assertTimed(first("tomorrow at 5pm"), 2026, 11, 25, 17)
    }

    @Test fun `time before tomorrow`() {
        assertTimed(first("5pm tomorrow"), 2026, 11, 25, 17)
    }

    @Test fun `today with a future time`() {
        assertTimed(first("today at 5pm"), 2026, 11, 24, 17)
    }

    @Test fun `french tomorrow with h-form time`() {
        assertTimed(first("demain à 17h30"), 2026, 11, 25, 17, 30)
    }

    @Test fun `bare today and tomorrow are not detected`() {
        assertThat(first("today was great")).isNull()
        assertThat(first("See you tomorrow")).isNull()
    }

    // ──────────────── Weekdays ────────────────

    @Test fun `coming weekday with time`() {
        // Today is Tuesday; the next Monday is Nov 30.
        assertTimed(first("Monday at 9am"), 2026, 11, 30, 9)
    }

    @Test fun `today with a future time stays today`() {
        assertTimed(first("Tuesday at 5pm"), 2026, 11, 24, 17)
    }

    @Test fun `today with a past time moves a week out`() {
        assertTimed(first("Tuesday at 9am"), 2026, 12, 1, 9)
    }

    @Test fun `next weekday means next week`() {
        // Friday Nov 27 is this week, so "next Friday" is Dec 4.
        assertTimed(first("next Friday at 5pm"), 2026, 12, 4, 17)
        // Monday Nov 30 is already next week, so "next Monday" is Nov 30.
        assertTimed(first("next Monday at 9am"), 2026, 11, 30, 9)
    }

    @Test fun `french weekday with postposed prochain`() {
        assertTimed(first("lundi prochain à 9h00"), 2026, 11, 30, 9)
    }

    @Test fun `french weekday with h-form time`() {
        assertTimed(first("vendredi 17h30"), 2026, 11, 27, 17, 30)
    }

    @Test fun `bare weekday is not detected`() {
        assertThat(first("See you Monday")).isNull()
    }

    // ──────────────── Other languages ────────────────

    @Test fun `french day first date`() {
        assertAllDay(first("25 décembre 2026"), 2026, 12, 25)
    }

    @Test fun `german month name`() {
        assertAllDay(first("25. Dezember 2026"), 2026, 12, 25)
    }

    // ──────────────── Negatives ────────────────

    @Test fun `impossible date is not detected`() {
        assertThat(first("Feb 30")).isNull()
        assertThat(first("13/25/2026")).isNull()
    }

    @Test fun `european day first numeric is rejected not misread`() {
        assertThat(first("25/12/2026")).isNull()
    }

    @Test fun `lowercase march is a verb not a month`() {
        assertThat(first("we march 5 miles")).isNull()
    }

    @Test fun `capitalised march and may are months`() {
        assertAllDay(first("March 5"), 2027, 3, 5)
        assertAllDay(first("May 5"), 2027, 5, 5)
    }

    @Test fun `fraction is not a date`() {
        assertThat(first("add 3/4 cup of flour")).isNull()
        assertThat(first("3/4 of the cake is gone")).isNull()
    }

    @Test fun `bare time is not detected`() {
        assertThat(first("call me at 5pm")).isNull()
        assertThat(first("17:30")).isNull()
    }

    @Test fun `duration is not a time - the date stays all-day`() {
        // "17h30min" is a duration, so no time attaches — but Dec 25
        // itself is still a date, offered as an all-day event.
        assertAllDay(first("Dec 25 in 17h30min"), 2026, 12, 25)
    }

    @Test fun `invalid times are ignored - the date stays all-day`() {
        assertAllDay(first("Dec 25 at 13pm"), 2026, 12, 25)
        assertAllDay(first("Dec 25 at 25:00"), 2026, 12, 25)
    }

    @Test fun `plain text has no date`() {
        assertThat(first("hello, how are you?")).isNull()
        assertThat(first("")).isNull()
    }

    @Test fun `two dates in order`() {
        val found = DateTimeDetector.findAll("Dec 25 and Jan 1", nowMillis, zone)
        assertThat(found).hasSize(2)
        assertThat((found[0].action as SmartAction.DateTime).text).isEqualTo("Dec 25")
        assertThat((found[1].action as SmartAction.DateTime).text).isEqualTo("Jan 1")
    }
}
