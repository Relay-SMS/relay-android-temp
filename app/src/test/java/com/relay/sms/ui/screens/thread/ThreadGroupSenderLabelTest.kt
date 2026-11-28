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

/**
 * Round 61 — the group sender label keys on the SENDER, not the direction:
 * consecutive incoming messages from two different people must each start a
 * sender run (and so each carry their name above the bubble), or the thread
 * reads as if one person wrote everything. Covers Kristin's round-60 device
 * test: her "So its not going to three groups anymore right?", her partner's
 * "Right", her "Yay!" — on the receiving phone all three are incoming, so
 * the old direction-burst rule labelled only the first.
 *
 * Covers: sender change inside one incoming burst; same-sender run labels
 * only the first; the burst gap and the day boundary still split a run;
 * direction change; outgoing runs; the same number in +1 / no-+1 form is
 * one sender.
 */
class ThreadGroupSenderLabelTest {

    private fun msg(
        id: Long,
        date: Long,
        outgoing: Boolean,
        address: String = if (outgoing) "me" else "+15551234567",
        encrypted: Boolean = false,
        type: Message.Type = Message.Type.SMS,
    ) = Message(
        id = id,
        conversationId = 1L,
        address = address,
        body = "msg $id",
        type = type,
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
        encrypted = encrypted,
    )

    @Test
    fun `three consecutive incoming group messages from two senders each start a run`() {
        // Kristin's round-60 scenario: she writes, her partner answers, she
        // writes again — all incoming on the third phone, seconds apart.
        // Every message needs its sender's name above it.
        val hers = msg(1, date = 1_000L, outgoing = false, address = "+15551111111")
        val partners = msg(2, date = 2_000L, outgoing = false, address = "+15552222222")
        val hersAgain = msg(3, date = 3_000L, outgoing = false, address = "+15551111111")
        assertThat(isFirstOfSenderRun(null, hers)).isTrue()
        assertThat(isFirstOfSenderRun(hers, partners)).isTrue()
        assertThat(isFirstOfSenderRun(partners, hersAgain)).isTrue()
    }

    @Test
    fun `same sender consecutive messages label only the first`() {
        val m1 = msg(1, date = 1_000L, outgoing = false)
        val m2 = msg(2, date = 21_000L, outgoing = false)
        val m3 = msg(3, date = 41_000L, outgoing = false)
        assertThat(isFirstOfSenderRun(null, m1)).isTrue()
        assertThat(isFirstOfSenderRun(m1, m2)).isFalse()
        assertThat(isFirstOfSenderRun(m2, m3)).isFalse()
    }

    @Test
    fun `same sender after the burst gap starts a new run`() {
        val m1 = msg(1, date = 1_000L, outgoing = false)
        val m2 = msg(2, date = 62_000L, outgoing = false)
        assertThat(isFirstOfSenderRun(m1, m2)).isTrue()
    }

    @Test
    fun `same sender on a different day starts a new run`() {
        // 1_000 ms is day 1 everywhere; 200_000_000 ms is day 3 everywhere,
        // so the day boundary holds in any device timezone.
        val m1 = msg(1, date = 1_000L, outgoing = false)
        val m2 = msg(2, date = 200_000_000L, outgoing = false)
        assertThat(isFirstOfSenderRun(m1, m2)).isTrue()
    }

    @Test
    fun `direction change starts a new run`() {
        val theirs = msg(1, date = 1_000L, outgoing = false)
        val mine = msg(2, date = 2_000L, outgoing = true)
        assertThat(isFirstOfSenderRun(theirs, mine)).isTrue()
    }

    @Test
    fun `outgoing run labels only the first`() {
        val m1 = msg(1, date = 1_000L, outgoing = true)
        val m2 = msg(2, date = 21_000L, outgoing = true)
        assertThat(isFirstOfSenderRun(null, m1)).isTrue()
        assertThat(isFirstOfSenderRun(m1, m2)).isFalse()
    }

    @Test
    fun `same sender in plus-one and no-plus-one form is one run`() {
        val m1 = msg(1, date = 1_000L, outgoing = false, address = "+15551234567")
        val m2 = msg(2, date = 2_000L, outgoing = false, address = "5551234567")
        assertThat(isFirstOfSenderRun(m1, m2)).isFalse()
    }

    @Test
    fun `plain non-encrypted group messages label each sender too`() {
        // Round-61 scope widening: the same missing-name issue was
        // reported in PLAIN group texts, not just encrypted groups.
        // The label rule is the shared group-thread path and is never
        // gated on encryption — plain SMS and plain MMS alike start a
        // sender run when the sender changes.
        val alice = msg(1, date = 1_000L, outgoing = false, address = "+15551111111", encrypted = false)
        val bob = msg(2, date = 2_000L, outgoing = false, address = "+15552222222", encrypted = false)
        val aliceMms = msg(
            3,
            date = 3_000L,
            outgoing = false,
            address = "+15551111111",
            encrypted = false,
            type = Message.Type.MMS,
        )
        assertThat(isFirstOfSenderRun(null, alice)).isTrue()
        assertThat(isFirstOfSenderRun(alice, bob)).isTrue()
        assertThat(isFirstOfSenderRun(bob, aliceMms)).isTrue()
    }
}
