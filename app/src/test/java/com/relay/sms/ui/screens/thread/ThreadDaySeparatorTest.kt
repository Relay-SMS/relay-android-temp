/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens.thread

import com.google.common.truth.Truth.assertThat
import com.relay.sms.domain.model.Message
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * Round 54 — the day separator belongs to the FIRST message of its day, and
 * its label is computed from that message's own date.
 *
 * Kristin's report (2026-11-25, screenshot `today_label_is_off.jpg`): "Yay!"
 * 7:17 PM one evening, then a long prayer text 6:33 AM the next morning, and
 * the "Today" pill rendered BELOW the 6:33 AM message instead of above it.
 * The label text was right ("Today") and on the right message — the pill was
 * in the wrong place inside its item (the reversed list stacked the item's
 * roots bottom-up; fixed by the [Column] wrapper in [ThreadMessageItem]).
 * This test pins the half a plain-JVM test CAN see: the assignment — the
 * separator must attach to the 6:33 AM message, labelled from its own date,
 * not to the 7:17 PM message and not labelled "Yesterday". The layout half
 * (pill above the bubble) has no plain-JVM observable; the device check is
 * its proof.
 *
 * The label text itself comes from a stub (`"LABEL@<date>"`) so the test
 * asserts the label is derived from the message's own timestamp: a label
 * computed from the previous message's date would read `LABEL@<yesterday>`
 * on the morning message — the 2026-11-23 symptom (a message sent today
 * showing under "Yesterday").
 */
class ThreadDaySeparatorTest {

    private fun msg(
        id: Long,
        date: Long,
        outgoing: Boolean,
        address: String = if (outgoing) "me" else "+33612345678",
    ) = Message(
        id = id,
        conversationId = 1L,
        address = address,
        body = "msg $id",
        type = Message.Type.SMS,
        direction = if (outgoing) Message.Direction.OUTGOING else Message.Direction.INCOMING,
        date = date,
        dateSent = null,
        read = true,
        starred = false,
        status = Message.Status.SENT,
        errorCode = null,
        attachmentsCount = 0,
        subId = null,
        scheduledAt = null,
    )

    /** Yesterday 7:17 PM and today 6:33 AM, both derived from one `now` so a
     * midnight crossing mid-test cannot put them on the same day. */
    private fun eveningThenMorning(): Pair<Long, Long> {
        val morning = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 6)
            set(Calendar.MINUTE, 33)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val evening = Calendar.getInstance().apply {
            timeInMillis = morning
            add(Calendar.DATE, -1)
            set(Calendar.HOUR_OF_DAY, 19)
            set(Calendar.MINUTE, 17)
        }.timeInMillis
        return evening to morning
    }

    @Test
    fun `yesterday evening then this morning puts the separator on the morning message`() {
        val (evening, morning) = eveningThenMorning()
        val messages = listOf(
            msg(1, date = evening, outgoing = true), // "Yay!" 7:17 PM
            msg(2, date = morning, outgoing = false), // prayer 6:33 AM
        )
        val labels = computeDaySeparatorLabels(messages) { timestampMillis ->
            "LABEL@$timestampMillis"
        }
        // One separator per day, each labelled from its own message's date:
        // the morning message carries the new day's separator — it must
        // render BEFORE (above) that message, never after it.
        assertThat(labels).containsExactly("LABEL@$evening", "LABEL@$morning").inOrder()
    }

    @Test
    fun `second message on the same day gets no separator`() {
        val (_, morning) = eveningThenMorning()
        val laterSameDay = Calendar.getInstance().apply {
            timeInMillis = morning
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 0)
        }.timeInMillis
        val messages = listOf(
            msg(1, date = morning, outgoing = false),
            msg(2, date = laterSameDay, outgoing = false),
        )
        val labels = computeDaySeparatorLabels(messages) { timestampMillis ->
            "LABEL@$timestampMillis"
        }
        assertThat(labels).containsExactly("LABEL@$morning", null).inOrder()
    }

    @Test
    fun `first message of the thread always gets a separator`() {
        val (evening, _) = eveningThenMorning()
        val labels = computeDaySeparatorLabels(
            listOf(msg(1, date = evening, outgoing = true)),
        ) { timestampMillis -> "LABEL@$timestampMillis" }
        assertThat(labels).containsExactly("LABEL@$evening").inOrder()
    }
}
