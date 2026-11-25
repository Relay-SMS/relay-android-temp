/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 23 — the quick-reaction row's contents and toggle, shared by the
 * tap bar ([QuickReactionBar]) and the long-press menu's top row
 * ([QuickReactionRow]): [quickReactionEmojis] decides which emojis the row
 * shows, [toggleReaction] decides what a tap stores. The row itself is a
 * composable (no Compose test harness in this repo), so these pure
 * functions are the testable surface.
 */
class QuickReactionRowTest {

    @Test fun `empty recents fall back to the defaults`() {
        assertThat(quickReactionEmojis(emptyList())).isEqualTo(DEFAULT_QUICK_REACTIONS)
    }

    @Test fun `recents come first, defaults top up`() {
        assertThat(quickReactionEmojis(listOf("🎉", "🔥"))).isEqualTo(
            listOf("🎉", "🔥", "❤️", "👍", "😂", "😮"),
        )
    }

    @Test fun `a recent already in the defaults is not duplicated`() {
        assertThat(quickReactionEmojis(listOf("👍"))).isEqualTo(
            listOf("👍", "❤️", "😂", "😮", "😢", "🙏"),
        )
    }

    @Test fun `the row is capped at six emojis`() {
        // Six is RecentReactionsStore.MAX_RECENT_REACTIONS: the row never
        // grows past it, however many recents there are.
        assertThat(
            quickReactionEmojis(listOf("🎉", "🔥", "💯", "🌟", "🎊", "🚀", "🍕")),
        ).isEqualTo(listOf("🎉", "🔥", "💯", "🌟", "🎊", "🚀"))
    }

    @Test fun `tapping the applied emoji removes the reaction`() {
        assertThat(toggleReaction("❤️", "❤️")).isNull()
    }

    @Test fun `tapping a different emoji swaps the reaction`() {
        assertThat(toggleReaction("❤️", "👍")).isEqualTo("👍")
    }

    @Test fun `tapping with no current reaction applies it`() {
        assertThat(toggleReaction(null, "👍")).isEqualTo("👍")
    }

    @Test fun `tapping one emoji of a multi-emoji reaction swaps it`() {
        // Only the whole string matches for removal; a single emoji from
        // a multi-emoji reaction replaces it instead.
        assertThat(toggleReaction("❤️👍", "❤️")).isEqualTo("❤️")
    }

    @Test fun `tapping any other emoji never clears the stored reaction`() {
        // Round 52 emoji fix — the badge tap opens the picker and never
        // clears: removal happens ONLY through this toggle, and only on
        // an explicit tap of the already-applied emoji. A tap on any
        // other emoji must store that emoji, never null — so a stray tap
        // on someone else's reaction can never wipe the badge.
        for (emoji in DEFAULT_QUICK_REACTIONS) {
            if (emoji != "👍") {
                assertThat(toggleReaction("👍", emoji)).isEqualTo(emoji)
            }
        }
    }
}
