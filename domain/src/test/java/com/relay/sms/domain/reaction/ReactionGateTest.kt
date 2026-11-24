/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.reaction

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 50 — locks down [IncomingReactionDecoder.looksLikeReactionText],
 * the cheap pre-filter the import fold and the retroactive reparse use
 * to decide whether a body is worth decoding.
 *
 * The gate is a SUPERSET of what `decode()` matches: a false negative
 * loses a reaction forever (the round-50 bug — an emoji-form tapback
 * like `👍 to "…"` matched no word prefix and no `»`, so the SMS import
 * filed it as a plain bubble and `decode` never ran), while a false
 * positive only costs a wasted decode. Every test below pins one arm
 * of the audit in the gate's KDoc; each positive case also asserts
 * `decode()` (or `decodeGoogleFormat()`) actually matches, so the gate
 * and the decoder cannot drift apart again.
 */
class ReactionGateTest {

    // ──────────────────── The round-50 gap: emoji + straight quote ────────────────────

    @Test
    fun `emoji tapback with straight quotes passes the gate and decodes`() {
        val body = "👍 to \"The family is but I am not sure yet, I hope to!\""

        assertThat(IncomingReactionDecoder.looksLikeReactionText(body)).isTrue()
        val decoded = IncomingReactionDecoder.decode(body)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.emoji).isEqualTo("👍")
        assertThat(decoded.previewPrefix).isEqualTo("The family is but I am not sure yet, I hope to!")
    }

    @Test
    fun `emoji tapback without to passes the gate and decodes`() {
        val body = "❤️ \"See you at six\""

        assertThat(IncomingReactionDecoder.looksLikeReactionText(body)).isTrue()
        assertThat(IncomingReactionDecoder.decode(body)).isNotNull()
    }

    @Test
    fun `emoji tapback truncated mid-quote passes the gate and decodes`() {
        // The carrier cut the preview short: no closing quote at all.
        val body = "👍 to \"Hey Curt, about the trip"

        assertThat(IncomingReactionDecoder.looksLikeReactionText(body)).isTrue()
        val decoded = IncomingReactionDecoder.decode(body)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.wasTruncated).isTrue()
    }

    @Test
    fun `emoji tapback with guillemets passes the gate and decodes`() {
        val body = "👍 «See you at six»"

        assertThat(IncomingReactionDecoder.looksLikeReactionText(body)).isTrue()
        assertThat(IncomingReactionDecoder.decode(body)).isNotNull()
    }

    @Test
    fun `emoji tapback with truncated guillemet passes the gate and decodes`() {
        // No closing `»`: the old gate (which only knew `»`) missed this too.
        val body = "👍 «See you at"

        assertThat(IncomingReactionDecoder.looksLikeReactionText(body)).isTrue()
        assertThat(IncomingReactionDecoder.decode(body)).isNotNull()
    }

    // ──────────────────── The arms the old gate already had ────────────────────

    @Test
    fun `tapback verb prefix passes the gate and decodes`() {
        val body = "Reacted ❤️ to «Hello»"

        assertThat(IncomingReactionDecoder.looksLikeReactionText(body)).isTrue()
        assertThat(IncomingReactionDecoder.decode(body)).isNotNull()
    }

    @Test
    fun `ios tapback verb passes the gate and decodes`() {
        val body = "Loved \"great news\""

        assertThat(IncomingReactionDecoder.looksLikeReactionText(body)).isTrue()
        assertThat(IncomingReactionDecoder.decode(body)).isNotNull()
    }

    @Test
    fun `ios removal verb passes the gate and decodes`() {
        val body = "Removed a heart from \"great news\""

        assertThat(IncomingReactionDecoder.looksLikeReactionText(body)).isTrue()
        val decoded = IncomingReactionDecoder.decode(body)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.isRemoval).isTrue()
    }

    @Test
    fun `generic removal passes the gate and decodes`() {
        val body = "Removed ❤️ from \"great news\""

        assertThat(IncomingReactionDecoder.looksLikeReactionText(body)).isTrue()
        assertThat(IncomingReactionDecoder.decode(body)).isNotNull()
    }

    @Test
    fun `named french form passes the gate and decodes`() {
        val body = "Marie a réagi par ❤️ à votre message : «Bonjour»"

        assertThat(IncomingReactionDecoder.looksLikeReactionText(body)).isTrue()
        assertThat(IncomingReactionDecoder.decode(body)).isNotNull()
    }

    @Test
    fun `french no-name form passes the gate and decodes`() {
        val body = "Réagi par ❤️ à votre message : «Bonjour»"

        assertThat(IncomingReactionDecoder.looksLikeReactionText(body)).isTrue()
        assertThat(IncomingReactionDecoder.decode(body)).isNotNull()
    }

    @Test
    fun `legacy french form passes the gate and decodes`() {
        val body = "J'ai réagi par ❤️ à : «Bonjour»"

        assertThat(IncomingReactionDecoder.looksLikeReactionText(body)).isTrue()
        assertThat(IncomingReactionDecoder.decode(body)).isNotNull()
    }

    // ──────────────────── Google framing ────────────────────

    @Test
    fun `google framed reaction passes the gate and decodes`() {
        val body = "\u200AReactions\u200B❤️\u200Bby Marie\u200AHello\u200Aend\u200A"

        assertThat(IncomingReactionDecoder.looksLikeReactionText(body)).isTrue()
        assertThat(IncomingReactionDecoder.decodeGoogleFormat(body)).isNotNull()
    }

    // ──────────────────── Deliberate negatives ────────────────────

    @Test
    fun `plain text does not pass the gate`() {
        assertThat(IncomingReactionDecoder.looksLikeReactionText("hello, how are you?")).isFalse()
    }

    @Test
    fun `empty body does not pass the gate`() {
        assertThat(IncomingReactionDecoder.looksLikeReactionText("")).isFalse()
    }

    @Test
    fun `quoted sentence without a leading emoji does not pass the gate`() {
        assertThat(IncomingReactionDecoder.looksLikeReactionText("He said \"hello\" to me")).isFalse()
    }

    @Test
    fun `bare emoji does not pass the gate`() {
        // EmojiOnly is deliberately NOT covered: both the import fold
        // and the reparse skip it (its 5-minute window is anchored at
        // "now"), so admitting it would only waste decodes.
        assertThat(IncomingReactionDecoder.looksLikeReactionText("👍")).isFalse()
    }

    @Test
    fun `emoji-led sentence without a quote does not pass the gate`() {
        assertThat(IncomingReactionDecoder.looksLikeReactionText("👍 sounds good, see you there")).isFalse()
    }
}
