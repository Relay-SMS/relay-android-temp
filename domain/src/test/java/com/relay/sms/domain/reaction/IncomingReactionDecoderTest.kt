/*
 * Copyright 2026 Relay Messaging (modifications). The original work is
 * copyright its respective holders and is licensed under the Apache
 * License 2.0. See LICENSE.
 */

package com.relay.sms.domain.reaction

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.4.1 — locks down the wire compatibility between
 * [com.relay.sms.domain.usecase.SendReactionUseCase.buildTapbackBody] (encoder) and
 * [IncomingReactionDecoder.decode] (decoder).
 *
 * Each scenario corresponds to a real shape the encoder is documented to emit, so any
 * future refactor of the Tapback wire format MUST keep the round-trip green.
 */
class IncomingReactionDecoderTest {

    // ──────────────── Tapback with preview (the common path) ────────────────

    @Test fun `decodes Tapback with simple ASCII preview`() {
        val result = IncomingReactionDecoder.decode("Reacted ❤️ to «Hello»")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("❤️")
        assertThat(result.previewPrefix).isEqualTo("Hello")
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    @Test fun `decodes Tapback with truncation marker (strips the ellipsis)`() {
        // Relay appends `…` when the original body was truncated to fit one
        // UCS-2 segment. The decoder must strip it so the caller can match
        // `body LIKE 'preview%'` against the stored outgoing message.
        val result = IncomingReactionDecoder.decode("Reacted 👍 to «Lorem ipsum dolor sit…»")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("👍")
        assertThat(result.previewPrefix).isEqualTo("Lorem ipsum dolor sit")
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    @Test fun `decodes Tapback with multi-codepoint emoji (ZWJ family)`() {
        // 👨‍👩‍👧 = man + ZWJ + woman + ZWJ + girl (4 code points, 7 UTF-16 units).
        val result = IncomingReactionDecoder.decode("Reacted 👨‍👩‍👧 to «Family pic»")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("👨‍👩‍👧")
        assertThat(result.previewPrefix).isEqualTo("Family pic")
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    @Test fun `decodes Tapback with Unicode preview characters`() {
        val result = IncomingReactionDecoder.decode("Reacted 🎉 to «Joyeux anniversaire !»")
        assertThat(result).isNotNull()
        assertThat(result!!.previewPrefix).isEqualTo("Joyeux anniversaire !")
    }

    // ──────────────── Tapback without preview (voice/image MMS) ────────────────

    @Test fun `decodes Tapback without preview as previewPrefix null`() {
        val result = IncomingReactionDecoder.decode("Reacted ❤️")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("❤️")
        assertThat(result.previewPrefix).isNull()
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    // ──────────────── v1.4.1 — emoji-only candidate (Kind.EmojiOnly) ────────────────

    @Test fun `decodes single emoji body as EmojiOnly candidate`() {
        // Relay's `reactionEmojiOnly` mode sends just the emoji. The decoder now
        // tags it [Kind.EmojiOnly] so the caller knows to apply the 5 min time
        // window check before folding it onto an outgoing message.
        val result = IncomingReactionDecoder.decode("❤️")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("❤️")
        assertThat(result.previewPrefix).isNull()
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.EmojiOnly)
    }

    @Test fun `decodes ZWJ family emoji body as EmojiOnly candidate`() {
        val result = IncomingReactionDecoder.decode("👨‍👩‍👧")
        assertThat(result).isNotNull()
        assertThat(result!!.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.EmojiOnly)
    }

    @Test fun `two-emoji body is NOT decoded (a message, not a reaction)`() {
        // Two emojis are two grapheme clusters: a plain expressive message.
        // Folding it would glue both emojis onto one badge.
        assertThat(IncomingReactionDecoder.decode("❤️❤️")).isNull()
    }

    @Test fun `three-emoji body is NOT decoded (the reported multi-emoji case)`() {
        assertThat(IncomingReactionDecoder.decode("👍😂😂")).isNull()
    }

    @Test fun `skin-tone emoji body folds as one cluster`() {
        val result = IncomingReactionDecoder.decode("👍🏽")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("👍🏽")
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.EmojiOnly)
    }

    @Test fun `flag body folds as one cluster (regional-indicator pair)`() {
        val result = IncomingReactionDecoder.decode("🇫🇷")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("🇫🇷")
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.EmojiOnly)
    }

    @Test fun `two-flag body is NOT decoded`() {
        assertThat(IncomingReactionDecoder.decode("🇫🇷🇩🇪")).isNull()
    }

    @Test fun `ZWJ heart-on-fire body folds as one cluster`() {
        val result = IncomingReactionDecoder.decode("❤️‍🔥")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("❤️‍🔥")
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.EmojiOnly)
    }

    @Test fun `emoji with trailing text is NOT decoded as EmojiOnly`() {
        // A real one-emoji-plus-text message ("❤️ je t'aime") must NOT slip through ;
        // it's a regular message that just happens to start with an emoji.
        assertThat(IncomingReactionDecoder.decode("❤️ je t'aime")).isNull()
        assertThat(IncomingReactionDecoder.decode("👍 great job!")).isNull()
        assertThat(IncomingReactionDecoder.decode("ok 👍")).isNull()
    }

    @Test fun `emoji-only body too long is NOT decoded (likely a long emoji-soup message)`() {
        // 20 hearts = 40 UTF-16 units, well past the [EMOJI_ONLY_MAX_UNITS] cap.
        // Likely a real expressive message, not a reaction.
        val longBody = "❤️".repeat(20)
        assertThat(IncomingReactionDecoder.decode(longBody)).isNull()
    }

    // ──────────────── Negative cases ────────────────

    @Test fun `plain text is NOT decoded`() {
        assertThat(IncomingReactionDecoder.decode("Hello, how are you?")).isNull()
        assertThat(IncomingReactionDecoder.decode("OK")).isNull()
        assertThat(IncomingReactionDecoder.decode("")).isNull()
        assertThat(IncomingReactionDecoder.decode("   ")).isNull()
    }

    @Test fun `text containing the word Reacted without the wire format is NOT decoded`() {
        // Defensive: a casual sentence starting with "Reacted" must not match.
        assertThat(IncomingReactionDecoder.decode("Reacted to your post earlier today.")).isNull()
        // Empty emoji slot is rejected.
        assertThat(IncomingReactionDecoder.decode("Reacted  to «Hello»")).isNull()
        // Empty preview slot is rejected.
        assertThat(IncomingReactionDecoder.decode("Reacted ❤️ to «»")).isNull()
    }

    @Test fun `decodes Tapback with leading and trailing whitespace`() {
        // Mobile carriers occasionally pad SMS bodies — the decoder trims first.
        val result = IncomingReactionDecoder.decode("  Reacted ❤️ to «Hello»  ")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("❤️")
        assertThat(result.previewPrefix).isEqualTo("Hello")
    }

    // ──────────────── Apple Messages tapbacks (English) ────────────────

    @Test fun `decodes iPhone Loved tapback with curly quotes`() {
        val result = IncomingReactionDecoder.decode("Loved “Hello”")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("❤️")
        assertThat(result.previewPrefix).isEqualTo("Hello")
        assertThat(result.isRemoval).isFalse()
    }

    @Test fun `decodes iPhone tapbacks with straight quotes`() {
        assertThat(IncomingReactionDecoder.decode("Liked \"Hello\"")!!.emoji)
            .isEqualTo("👍")
        assertThat(IncomingReactionDecoder.decode("Disliked \"Hello\"")!!.emoji)
            .isEqualTo("👎")
        assertThat(IncomingReactionDecoder.decode("Laughed at \"Hello\"")!!.emoji)
            .isEqualTo("😂")
        assertThat(IncomingReactionDecoder.decode("Emphasized \"Hello\"")!!.emoji)
            .isEqualTo("‼️")
        assertThat(IncomingReactionDecoder.decode("Questioned \"Hello\"")!!.emoji)
            .isEqualTo("❓")
    }

    @Test fun `decodes iPhone tapback with truncation marker`() {
        val result = IncomingReactionDecoder.decode("Loved “Lorem ipsum dolor sit…”")
        assertThat(result).isNotNull()
        assertThat(result!!.previewPrefix).isEqualTo("Lorem ipsum dolor sit")
        assertThat(result.wasTruncated).isTrue()
    }

    @Test fun `decodes iPhone tapback removals`() {
        val heart = IncomingReactionDecoder.decode("Removed a heart from “Hello”")
        assertThat(heart).isNotNull()
        assertThat(heart!!.emoji).isEqualTo("❤️")
        assertThat(heart.previewPrefix).isEqualTo("Hello")
        assertThat(heart.isRemoval).isTrue()
        assertThat(IncomingReactionDecoder.decode("Removed a like from “Hello”")!!.emoji)
            .isEqualTo("👍")
        assertThat(
            IncomingReactionDecoder.decode("Removed a laugh from “Hello”")!!.isRemoval,
        ).isTrue()
        assertThat(
            IncomingReactionDecoder.decode("Removed ❤️ from “Hello”")!!.isRemoval,
        ).isTrue()
    }

    @Test fun `iPhone tapback wording without quotes is NOT decoded`() {
        assertThat(IncomingReactionDecoder.decode("Loved the movie last night")).isNull()
        assertThat(IncomingReactionDecoder.decode("I loved \"that\"")).isNull()
        assertThat(IncomingReactionDecoder.decode("Loved \"\"")).isNull()
        // Generic removal needs a real emoji, not a word.
        assertThat(
            IncomingReactionDecoder.decode("Removed the meeting from \"Friday\""),
        ).isNull()
    }

    @Test fun `decodes emoji-to-quote tapback with connector word and truncated preview`() {
        // Feedback round 9 — Kristin's exact message: the carrier renders the
        // iPhone tapback as `👍 to "…"` with the connector word, and the
        // preview is cut off mid-sentence with no closing quote.
        val result = IncomingReactionDecoder.decode(
            "👍 to \"Hey Curt, this is the translation video I was talking about:",
        )
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("👍")
        assertThat(result.previewPrefix)
            .isEqualTo("Hey Curt, this is the translation video I was talking about:")
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
        assertThat(result.wasTruncated).isTrue()
    }

    @Test fun `decodes emoji-to-quote tapback with closing quote`() {
        val result = IncomingReactionDecoder.decode("👍 to \"Hello\"")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("👍")
        assertThat(result.previewPrefix).isEqualTo("Hello")
        assertThat(result.wasTruncated).isFalse()
    }

    @Test fun `decodes emoji-to-quote tapback with guillemets`() {
        val result = IncomingReactionDecoder.decode("❤️ to «Hello»")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("❤️")
        assertThat(result.previewPrefix).isEqualTo("Hello")
    }

    @Test fun `decodes emoji-to-quote tapback with curly quotes and a multiline preview`() {
        // Round 52 — Kristin's exact message from device testing: curly
        // quotes `“”` (not straight `"`), a space after the opening
        // quote and before the closing one, a NEWLINE inside the
        // preview, and a truncated URL. The v4 regex only knew `«»`
        // and `"` — this stayed a plain bubble.
        val result = IncomingReactionDecoder.decode(
            "👍 to “ Hey Curt, this is the video I was talking about:\n" +
                "https://www.youtube.com/watch?v=8v2 ”",
        )
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("👍")
        assertThat(result.previewPrefix).isEqualTo(
            "Hey Curt, this is the video I was talking about:\n" +
                "https://www.youtube.com/watch?v=8v2",
        )
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
        assertThat(result.wasTruncated).isFalse()
    }

    @Test fun `decodes emoji-to-quote tapback with curly quotes and no connector word`() {
        val result = IncomingReactionDecoder.decode("👍 “Hello”")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("👍")
        assertThat(result.previewPrefix).isEqualTo("Hello")
        assertThat(result.wasTruncated).isFalse()
    }

    @Test fun `decodes emoji-to-quote tapback with truncated curly quote`() {
        val result = IncomingReactionDecoder.decode("👍 to “Hey Curt, about the trip")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("👍")
        assertThat(result.previewPrefix).isEqualTo("Hey Curt, about the trip")
        assertThat(result.wasTruncated).isTrue()
    }

    // ──────────────── Google Messages invisible-char format ────────────────

    private fun googleBody(
        emoji: String,
        original: String,
        separator: Char = '\u200B',
    ): String = "\u200A" + "sender" + separator + emoji + separator +
        "tag" + "\u200A" + original + "\u200A" + "tail" + "\u200A"

    @Test fun `decodes Google Messages reaction from raw body`() {
        val result = IncomingReactionDecoder.decodeGoogleFormat(
            googleBody("❤️", "Hello world"),
        )
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("❤️")
        assertThat(result.previewPrefix).isEqualTo("Hello world")
        assertThat(result.isRemoval).isFalse()
    }

    @Test fun `decodes Google Messages removal (U+200C separators)`() {
        val result = IncomingReactionDecoder.decodeGoogleFormat(
            googleBody("👍", "Hello world", separator = '\u200C'),
        )
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("👍")
        assertThat(result.previewPrefix).isEqualTo("Hello world")
        assertThat(result.isRemoval).isTrue()
    }

    @Test fun `decodes Google Messages reaction with truncated preview`() {
        val result = IncomingReactionDecoder.decodeGoogleFormat(
            googleBody("😂", "Lorem ipsum dolor sit…"),
        )
        assertThat(result).isNotNull()
        assertThat(result!!.previewPrefix).isEqualTo("Lorem ipsum dolor sit")
        assertThat(result.wasTruncated).isTrue()
    }

    @Test fun `Google format without framing is NOT decoded`() {
        assertThat(IncomingReactionDecoder.decodeGoogleFormat("❤️ Hello")).isNull()
        assertThat(
            IncomingReactionDecoder.decodeGoogleFormat(
                "\u200B❤️\u200BHello\u200A",
            ),
        ).isNull()
        // Missing the closing frame.
        assertThat(
            IncomingReactionDecoder.decodeGoogleFormat(
                "\u200Asender\u200B❤️\u200Btag\u200AHello\u200Atail",
            ),
        ).isNull()
        // Empty emoji slot / empty preview.
        assertThat(
            IncomingReactionDecoder.decodeGoogleFormat(
                googleBody("", "Hello"),
            ),
        ).isNull()
        assertThat(
            IncomingReactionDecoder.decodeGoogleFormat(
                googleBody("❤️", ""),
            ),
        ).isNull()
    }

    // ──────────────── v1.6.0 audit Q4 — ReDoS regression ────────────────

    @Test fun `decode rejects oversized body fast (ReDoS guard)`() {
        // A malicious sender could craft a multi-part SMS like `Reacted ❤️ to «aaa…`
        // with no closing guillemet to force the non-greedy regex into catastrophic
        // backtracking. The MAX_DECODE_INPUT_LENGTH guard short-circuits before the
        // regex sees the input.
        val malicious = "Reacted ❤️ to «" + "a".repeat(3000)
        val start = System.currentTimeMillis()
        val result = IncomingReactionDecoder.decode(malicious)
        val elapsed = System.currentTimeMillis() - start
        assertThat(result).isNull()
        // Allow generous CI slack ; on a real device the guard returns in microseconds.
        assertThat(elapsed).isLessThan(200L)
    }

    @Test fun `decode rejects body just above the cap`() {
        val justOver = "Reacted ❤️ to «" + "a".repeat(400) + "»"
        assertThat(IncomingReactionDecoder.decode(justOver)).isNull()
    }

    @Test fun `decode accepts a realistic Tapback close to the cap`() {
        // A real Tapback with the longest preview Relay ever emits stays under
        // 100 chars — confirm the cap doesn't reject legitimate inputs.
        val realistic = "Reacted ❤️ to «" + "x".repeat(50) + "»"
        val result = IncomingReactionDecoder.decode(realistic)
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("❤️")
    }

    // ──────────────── v3 reparse — firstGraphemeCluster ────────────────

    @Test fun `first cluster of a stacked reaction is the first emoji`() {
        assertThat(IncomingReactionDecoder.firstGraphemeCluster("👍😂😅")).isEqualTo("👍")
    }

    @Test fun `first cluster keeps a skin tone with its base`() {
        assertThat(IncomingReactionDecoder.firstGraphemeCluster("👍🏽😂")).isEqualTo("👍🏽")
    }

    @Test fun `first cluster keeps a flag whole`() {
        assertThat(IncomingReactionDecoder.firstGraphemeCluster("🇫🇷😂")).isEqualTo("🇫🇷")
    }

    @Test fun `first cluster keeps a ZWJ family whole`() {
        assertThat(IncomingReactionDecoder.firstGraphemeCluster("👨‍👩‍👧😂")).isEqualTo("👨‍👩‍👧")
    }

    @Test fun `first cluster of a single emoji is the emoji itself`() {
        assertThat(IncomingReactionDecoder.firstGraphemeCluster("❤️")).isEqualTo("❤️")
    }

    @Test fun `first cluster of an empty string is empty`() {
        assertThat(IncomingReactionDecoder.firstGraphemeCluster("")).isEqualTo("")
    }

    @Test fun `first cluster skips a leading BOM`() {
        // A stored "‹BOM›👍" (folded before the decoder stripped format
        // chars) normalizes to the visible thumbs-up, not to the BOM.
        assertThat(IncomingReactionDecoder.firstGraphemeCluster("\uFEFF👍")).isEqualTo("👍")
        assertThat(IncomingReactionDecoder.firstGraphemeCluster("\u200B👍")).isEqualTo("👍")
        assertThat(IncomingReactionDecoder.firstGraphemeCluster("\u200C👍")).isEqualTo("👍")
    }

    @Test fun `first cluster of only format chars is empty`() {
        assertThat(IncomingReactionDecoder.firstGraphemeCluster("\uFEFF")).isEqualTo("")
        assertThat(IncomingReactionDecoder.firstGraphemeCluster("\u200B\u200C")).isEqualTo("")
    }

    // ──────────────── Round 52 emoji fix — invisible-only tokens never decode ────────────────

    @Test fun `lone VS16 token is NOT decoded as a reaction`() {
        // A lone variation selector is invisible: storing it would paint an empty pill.
        assertThat(IncomingReactionDecoder.decode("\uFE0F to \u201CHello\u201D")).isNull()
        assertThat(IncomingReactionDecoder.decode("Reacted \uFE0F to \u201CHello\u201D")).isNull()
        assertThat(IncomingReactionDecoder.decode("Reacted \uFE0F")).isNull()
        assertThat(IncomingReactionDecoder.decode("\uFE0F")).isNull()
    }

    @Test fun `lone skin-tone token is NOT decoded as a reaction`() {
        assertThat(IncomingReactionDecoder.decode("\uD83C\uDFFB to \u201CHello\u201D")).isNull()
        assertThat(IncomingReactionDecoder.decode("Reacted \uD83C\uDFFB to \u201CHello\u201D")).isNull()
        assertThat(IncomingReactionDecoder.decode("Reacted \uD83C\uDFFB")).isNull()
        assertThat(IncomingReactionDecoder.decode("\uD83C\uDFFB")).isNull()
    }

    @Test fun `lone ZWJ token is NOT decoded as a reaction`() {
        assertThat(IncomingReactionDecoder.decode("\u200D to \u201CHello\u201D")).isNull()
        assertThat(IncomingReactionDecoder.decode("\u200D")).isNull()
    }

    @Test fun `leading format char on the emoji token decodes to the clean emoji`() {
        // Root cause of the empty pills: a BOM / ZWSP / ZWNJ prepended
        // to the token used to be stored with the emoji, and the reparse
        // normalize then reduced it to the invisible char alone. The
        // token is stripped, so the fold stores a clean thumbs-up.
        assertDecodesTo("\uFEFF👍 to \u201CHello\u201D", "👍")
        assertDecodesTo("\u200B👍 to \u201CHello\u201D", "👍")
        assertDecodesTo("\u200C👍 to \u201CHello\u201D", "👍")
        assertDecodesTo("Reacted \uFEFF👍 to \u201CHello\u201D", "👍")
        assertDecodesTo("👍\uFEFF to \u201CHello\u201D", "👍")
        val google = IncomingReactionDecoder.decodeGoogleFormat(googleBody("\uFEFF👍", "Hello"))
        assertThat(google).isNotNull()
        assertThat(google!!.emoji).isEqualTo("👍")
    }

    @Test fun `lone format char token is NOT decoded as a reaction`() {
        assertThat(IncomingReactionDecoder.decode("\uFEFF to \u201CHello\u201D")).isNull()
        assertThat(IncomingReactionDecoder.decode("\uFEFF")).isNull()
        assertThat(IncomingReactionDecoder.decode("\u200B")).isNull()
    }

    @Test fun `Google format with invisible-only emoji is NOT decoded`() {
        assertThat(
            IncomingReactionDecoder.decodeGoogleFormat(googleBody("\uFE0F", "Hello")),
        ).isNull()
        assertThat(
            IncomingReactionDecoder.decodeGoogleFormat(googleBody("\uD83C\uDFFB", "Hello")),
        ).isNull()
    }

    @Test fun `isVisibleEmoji rejects blank and invisible-only strings`() {
        assertThat(IncomingReactionDecoder.isVisibleEmoji("")).isFalse()
        assertThat(IncomingReactionDecoder.isVisibleEmoji("   ")).isFalse()
        assertThat(IncomingReactionDecoder.isVisibleEmoji("\uFE0F")).isFalse()
        assertThat(IncomingReactionDecoder.isVisibleEmoji("\uD83C\uDFFB")).isFalse()
        assertThat(IncomingReactionDecoder.isVisibleEmoji("\u200D")).isFalse()
        assertThat(IncomingReactionDecoder.isVisibleEmoji("\uFE0F\uD83C\uDFFB\u200D")).isFalse()
    }

    @Test fun `isVisibleEmoji accepts real emojis with modifiers`() {
        assertThat(IncomingReactionDecoder.isVisibleEmoji("👍")).isTrue()
        assertThat(IncomingReactionDecoder.isVisibleEmoji("👍\uFE0F")).isTrue()
        assertThat(IncomingReactionDecoder.isVisibleEmoji("👍\uD83C\uDFFB")).isTrue()
        assertThat(IncomingReactionDecoder.isVisibleEmoji("❤️‍🔥")).isTrue()
        assertThat(IncomingReactionDecoder.isVisibleEmoji("🇫🇷")).isTrue()
        assertThat(IncomingReactionDecoder.isVisibleEmoji("‼️")).isTrue()
        // BMP emoji bases below U+2300 (must not be excluded with the modifiers).
        assertThat(IncomingReactionDecoder.isVisibleEmoji("⭐")).isTrue()
        assertThat(IncomingReactionDecoder.isVisibleEmoji("©️")).isTrue()
        assertThat(IncomingReactionDecoder.isVisibleEmoji("⁉️")).isTrue()
        assertThat(IncomingReactionDecoder.isVisibleEmoji("™️")).isTrue()
    }

    @Test fun `decodes BMP emoji bases below U+2300 in emoji-quote form`() {
        assertDecodesTo("‼️ to \u201CHello\u201D", "‼️")
        assertDecodesTo("⭐ to \u201CHello\u201D", "⭐")
        assertDecodesTo("©️ to \u201CHello\u201D", "©️")
    }

    // ──────────────── Round 52 emoji fix — Reacted … to … with curly quotes ────────────────

    @Test fun `decodes Relay Tapback with curly quotes`() {
        // Pre-existing gap: TAPBACK_WITH_PREVIEW_REGEX only knew «» and ".
        val result = IncomingReactionDecoder.decode("Reacted 👍 to \u201CHello\u201D")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("👍")
        assertThat(result.previewPrefix).isEqualTo("Hello")
        assertThat(result.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.Tapback)
    }

    @Test fun `decodes Relay Tapback with straight quotes`() {
        val result = IncomingReactionDecoder.decode("Reacted 👍 to \"Hello\"")
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo("👍")
        assertThat(result.previewPrefix).isEqualTo("Hello")
    }

    // ──────────────── Round 52 emoji fix — Curt regression (16 wire forms) ────────────────

    // Kristin's exact message: Curt's 👍 on it rendered as an empty oval in
    // the round-52 build (it rendered fine in round 50). The decoder is
    // proven correct for every form below — this locks the behaviour in so
    // the hardening above can never regress it.
    private val curtMessage = "Sounds good, cyah then! Oh no worries :)"

    private fun assertDecodesTo(body: String, expectedEmoji: String) {
        val result = IncomingReactionDecoder.decode(body)
        assertThat(result).isNotNull()
        assertThat(result!!.emoji).isEqualTo(expectedEmoji)
    }

    @Test fun `Curt thumbs-up decodes in every word and emoji-quote form`() {
        assertDecodesTo("Liked \"$curtMessage\"", "👍")
        assertDecodesTo("Liked \u201C$curtMessage\u201D", "👍")
        assertDecodesTo("👍 to \"$curtMessage\"", "👍")
        assertDecodesTo("👍 to \u201C$curtMessage\u201D", "👍")
        assertDecodesTo("👍 \u201C$curtMessage\u201D", "👍")
        assertDecodesTo("👍 to \u201C $curtMessage\u201D", "👍")
        assertDecodesTo("👍 to \" $curtMessage\"", "👍")
        assertDecodesTo("👍 to \u201C$curtMessage\u201D ", "👍")
    }

    @Test fun `Curt thumbs-up decodes in Relay Reacted form with curly and straight quotes`() {
        assertDecodesTo("Reacted 👍 to \u201C$curtMessage\u201D", "👍")
        assertDecodesTo("Reacted 👍 to \"$curtMessage\"", "👍")
    }

    @Test fun `Curt thumbs-up decodes with VS16 and skin tone`() {
        assertDecodesTo("👍\uFE0F to \u201C$curtMessage\u201D", "👍\uFE0F")
        assertDecodesTo("👍\uD83C\uDFFB to \u201C$curtMessage\u201D", "👍\uD83C\uDFFB")
    }

    @Test fun `Curt thumbs-up removals decode`() {
        val word = IncomingReactionDecoder.decode(
            "Removed a like from \u201C$curtMessage\u201D",
        )
        assertThat(word).isNotNull()
        assertThat(word!!.emoji).isEqualTo("👍")
        assertThat(word.isRemoval).isTrue()
        val generic = IncomingReactionDecoder.decode(
            "Removed 👍 from \u201C$curtMessage\u201D",
        )
        assertThat(generic).isNotNull()
        assertThat(generic!!.emoji).isEqualTo("👍")
        assertThat(generic.isRemoval).isTrue()
    }

    @Test fun `bare thumbs-up decodes as EmojiOnly and the YouTube control still decodes`() {
        val bare = IncomingReactionDecoder.decode("👍")
        assertThat(bare).isNotNull()
        assertThat(bare!!.emoji).isEqualTo("👍")
        assertThat(bare.kind).isEqualTo(IncomingReactionDecoder.DecodedReaction.Kind.EmojiOnly)
        val control = IncomingReactionDecoder.decode(
            "👍 to \u201C Hey Curt, this is the video I was talking about:\n" +
                "https://www.youtube.com/watch?v=8v2 \u201D",
        )
        assertThat(control).isNotNull()
        assertThat(control!!.emoji).isEqualTo("👍")
    }
}
