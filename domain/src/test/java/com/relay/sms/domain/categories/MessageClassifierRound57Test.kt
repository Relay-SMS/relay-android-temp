/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.categories

import com.google.common.truth.Truth.assertThat
import com.relay.sms.domain.model.PhoneAddress
import org.junit.jupiter.api.Test

/**
 * Round 57 — the Medicare-advantage sample Kristin reported
 * (2026-11-26): "Washington is threatening Medicare Advantage. 35
 * million seniors need you to fight back. Speak up now.
 * facts26.net/b/xl4xZk / StopToEnd", from +1 (833) 330-2134. It
 * filed Personal on a fresh import: the body carries no
 * vote/election keyword, the "StopToEnd" footer is one word (the
 * classifier knew only "stop to stop" / "stop to quit" and the
 * "Stop=End" regex), and the 10-digit toll-free sender counted as
 * a regular number. The fix is two narrow signals — the one-word
 * "stoptoend" footer keyword and the toll-free sender rule — and
 * deliberately NO broad standalone advocacy keywords ("fight
 * back", "speak up" stay out: real chats say them).
 */
class MessageClassifierRound57Test {

    private val regularNumber = PhoneAddress.of("+15551234567")
    private val tollFreeNumber = PhoneAddress.of("+1 (833) 330-2134")

    private val sampleBody = "Washington is threatening Medicare Advantage. " +
        "35 million seniors need you to fight back. Speak up now. " +
        "facts26.net/b/xl4xZk / StopToEnd"

    // ──────── The reported sample ────────

    @Test fun `medicare advantage sample from toll free sender is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = sampleBody,
                sender = tollFreeNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `medicare advantage sample from a regular number is promotional`() {
        // The one-word footer alone files it — the sender rule is
        // belt and braces, not the only signal.
        assertThat(
            MessageClassifier.classify(
                body = sampleBody,
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `sample body without the footer from toll free sender is promotional`() {
        // And the sender alone files it — the footer is belt and
        // braces, not the only signal.
        assertThat(
            MessageClassifier.classify(
                body = "Washington is threatening Medicare Advantage. " +
                    "35 million seniors need you to fight back. " +
                    "Speak up now. facts26.net/b/xl4xZk",
                sender = tollFreeNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    // ──────── The toll-free sender rule ────────

    @Test fun `a plain personal body from a toll free sender is promotional`() {
        // Ungated, like the short-code rule: a toll-free line is a
        // business / campaign line, not a person — even saved.
        assertThat(
            MessageClassifier.classify(
                body = "Hi, just checking in, how are you?",
                sender = tollFreeNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `every toll free area code files promotional`() {
        for (area in listOf("800", "833", "844", "855", "866", "877", "888")) {
            assertThat(
                MessageClassifier.classify(
                    body = "Hi, just checking in, how are you?",
                    sender = PhoneAddress.of("+1 ($area) 555-1234"),
                    isContact = false,
                    isGroup = false,
                ),
            ).isEqualTo(MessageCategory.PROMOTIONAL)
        }
    }

    @Test fun `a toll free number without the country code files promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, just checking in, how are you?",
                sender = PhoneAddress.of("8333302134"),
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    // ──────── Guards: the rule must not swallow real people ────────

    @Test fun `a personal body from a regular ten digit number stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, just checking in, how are you?",
                sender = PhoneAddress.of("+1 (212) 555-1234"),
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `a personal body from a regular eleven digit number stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, just checking in, how are you?",
                sender = PhoneAddress.of("+14155551234"),
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `a personal body from a seven digit number stays personal`() {
        // No area code to read — a short local number is a person.
        assertThat(
            MessageClassifier.classify(
                body = "Hi, just checking in, how are you?",
                sender = PhoneAddress.of("5551234"),
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `a personal body from an international number stays personal`() {
        // Longer than NANP — no area code is read.
        assertThat(
            MessageClassifier.classify(
                body = "Hi, just checking in, how are you?",
                sender = PhoneAddress.of("+33612345678"),
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `advocacy wording alone from a regular number stays personal`() {
        // Deliberately NOT added: "fight back" / "speak up" are
        // everyday language — only the footer + the sender file
        // this sample, never the advocacy phrasing on its own.
        assertThat(
            MessageClassifier.classify(
                body = "We need you to fight back. Speak up now, they are " +
                    "threatening our benefits.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }
}
