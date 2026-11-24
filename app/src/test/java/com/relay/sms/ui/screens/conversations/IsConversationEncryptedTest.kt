/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens.conversations

import com.google.common.truth.Truth.assertThat
import com.relay.sms.domain.model.Conversation
import com.relay.sms.domain.model.PhoneAddress
import org.junit.jupiter.api.Test

/**
 * E2E list badges — [isConversationEncrypted] canonicalizes the
 * conversation's address before comparing it with the ENCRYPTED
 * set (round 47): the set holds canonical keys (round 46), while
 * a conversation's stored number may still carry `+1`.
 */
class IsConversationEncryptedTest {

    @Test
    fun `a plus-one stored conversation shows the badge when its peer is encrypted`() {
        val conv = conversation("+16108423232")

        assertThat(isConversationEncrypted(conv, setOf("6108423232"))).isTrue()
    }

    @Test
    fun `a national stored conversation still shows the badge`() {
        val conv = conversation("6108423232")

        assertThat(isConversationEncrypted(conv, setOf("6108423232"))).isTrue()
    }

    @Test
    fun `a formatted stored number shows the badge`() {
        val conv = conversation("(610) 842-3232")

        assertThat(isConversationEncrypted(conv, setOf("6108423232"))).isTrue()
    }

    @Test
    fun `a non-NANP number shows the badge`() {
        val conv = conversation("+33612345678")

        assertThat(isConversationEncrypted(conv, setOf("+33612345678"))).isTrue()
    }

    @Test
    fun `a non-encrypted peer shows no badge`() {
        val conv = conversation("+16108423232")

        assertThat(isConversationEncrypted(conv, emptySet())).isFalse()
        assertThat(isConversationEncrypted(conv, setOf("6109999999"))).isFalse()
    }

    @Test
    fun `a group never shows the badge`() {
        val conv = conversation("+16108423232", "+15551234567")

        assertThat(
            isConversationEncrypted(conv, setOf("6108423232", "5551234567")),
        ).isFalse()
    }

    @Test
    fun `a legacy non-canonical set member shows no badge`() {
        // Round 46 left pre-round-46 peer rows (keyed by
        // normalizePhone alone, `+1…`) unreachable by design —
        // their sessions are unreachable too, so the thread reads
        // UNKNOWN and messages go out plain. The badge must agree
        // with the thread, not with the stale row.
        val conv = conversation("+16108423232")

        assertThat(isConversationEncrypted(conv, setOf("+16108423232"))).isFalse()
    }

    private fun conversation(vararg addresses: String): Conversation = Conversation(
        id = 1L,
        threadId = null,
        addresses = addresses.map { PhoneAddress.of(it) },
        displayName = "Marie",
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
    )
}
