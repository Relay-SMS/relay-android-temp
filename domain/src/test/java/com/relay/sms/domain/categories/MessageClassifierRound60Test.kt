/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.categories

import com.google.common.truth.Truth.assertThat
import com.relay.sms.domain.model.Conversation
import com.relay.sms.domain.model.PhoneAddress
import org.junit.jupiter.api.Test

/**
 * Round 60 — the Medicare-advantage spam regression (item (u)).
 *
 * Same campaign as the round-57 833 sample. The Monday (Nov 23) message
 * filed Promotional fine; the new one (Nov 27 ~11:55 EST) filed
 * Personal: "URGENT: Washington is still eyeing Medicare Advantage
 * cuts. Kristin, take 60 seconds to speak up! facts26.net/b/mOoD6S /
 * StopToEnd". The sender is the same +1 (833) 330-2134 as the round-57
 * sample, so the body-alone test below is the decisive one: the
 * one-word "StopToEnd" footer is an ungated promotional keyword
 * (round 57), so this body must file Promotional from ANY sender —
 * regular number, toll-free number, even a contact. If it does, the
 * keyword path is not broken and the misfiling means the classifier
 * never saw this body at the decision point (see the receive-path
 * notes in the round-60 report).
 *
 * The round-57 sample is re-asserted here as the regression pair
 * (its own file, [MessageClassifierRound57Test], covers it in depth).
 */
class MessageClassifierRound60Test {

    private val regularNumber = PhoneAddress.of("+15551234567")
    private val tollFreeNumber = PhoneAddress.of("+1 (833) 330-2134")

    private val newSampleBody = "URGENT: Washington is still eyeing Medicare Advantage cuts. " +
        "Kristin, take 60 seconds to speak up! facts26.net/b/mOoD6S / StopToEnd"

    private val round57SampleBody = "Washington is threatening Medicare Advantage. " +
        "35 million seniors need you to fight back. Speak up now. " +
        "facts26.net/b/xl4xZk / StopToEnd"

    // ──────── The Nov 27 sample ────────

    @Test fun `nov 27 medicare advantage sample from a regular number is promotional`() {
        // The footer alone files it, whatever the sender turns out
        // to be — so this body-alone test stays decisive even though
        // the sender is now confirmed as the campaign toll-free one.
        assertThat(
            MessageClassifier.classify(
                body = newSampleBody,
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `nov 27 medicare advantage sample from the campaign toll free sender is promotional`() {
        // The campaign's known sender (round 57), confirmed as the
        // Nov 27 sender too — same +1 (833) 330-2134.
        assertThat(
            MessageClassifier.classify(
                body = newSampleBody,
                sender = tollFreeNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `nov 27 medicare advantage sample is promotional even for a contact`() {
        // The "stoptoend" keyword is ungated: contact status cannot
        // explain the misfiling either.
        assertThat(
            MessageClassifier.classify(
                body = newSampleBody,
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `nov 27 medicare advantage sample preview files promotional`() {
        // The sample is 130 chars, so the 240-char preview IS the
        // full body and carries the footer — the preview path cannot
        // lose this signal either.
        val conversation = conversation(
            preview = newSampleBody,
            addresses = listOf(regularNumber),
        )
        assertThat(MessageClassifier.classifyConversation(conversation))
            .isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `nov 27 sample without the footer from a regular number stays personal`() {
        // Guard: the advocacy wording alone ("speak up", "eyeing
        // cuts") is NOT a signal — only the footer files this sample.
        assertThat(
            MessageClassifier.classify(
                body = "URGENT: Washington is still eyeing Medicare Advantage cuts. " +
                    "Kristin, take 60 seconds to speak up! facts26.net/b/mOoD6S",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    // ──────── The round-57 sample, re-asserted as the regression pair ────────

    @Test fun `round 57 medicare advantage sample still files promotional from a regular number`() {
        assertThat(
            MessageClassifier.classify(
                body = round57SampleBody,
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `round 57 medicare advantage sample still files promotional from its toll free sender`() {
        assertThat(
            MessageClassifier.classify(
                body = round57SampleBody,
                sender = tollFreeNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    // ──────── Sticky: a same-thread flip cannot come from the store ────────

    @Test fun `nov 27 sample fresh promotional sticks`() {
        assertThat(
            MessageClassifier.nextStickyCategory(
                null,
                MessageClassifier.classify(
                    body = newSampleBody,
                    sender = regularNumber,
                    isContact = false,
                    isGroup = false,
                ),
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `stored promotional survives a plain follow up`() {
        // So a thread the Nov 27 sample filed cannot flip back to
        // Personal on a plain follow-up — the flip, if the thread is
        // the same one, came from somewhere else (see the report).
        assertThat(
            MessageClassifier.nextStickyCategory(
                MessageCategory.PROMOTIONAL,
                MessageCategory.PERSONAL,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    private fun conversation(
        preview: String?,
        addresses: List<PhoneAddress>,
        displayName: String? = null,
        stickyCategory: MessageCategory? = null,
    ): Conversation = Conversation(
        id = 1L,
        threadId = null,
        addresses = addresses,
        displayName = displayName,
        lastMessageAt = 0L,
        lastMessagePreview = preview,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
        stickyCategory = stickyCategory,
    )
}
