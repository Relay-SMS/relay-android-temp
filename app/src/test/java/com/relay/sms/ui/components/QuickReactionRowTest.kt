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
        assertThat(toggleReaction("❤️", "❤️", isOwnReaction = true)).isNull()
    }

    @Test fun `tapping a different emoji swaps the reaction`() {
        assertThat(toggleReaction("❤️", "👍", isOwnReaction = true)).isEqualTo("👍")
    }

    @Test fun `tapping with no current reaction applies it`() {
        assertThat(toggleReaction(null, "👍", isOwnReaction = true)).isEqualTo("👍")
    }

    @Test fun `tapping one emoji of a multi-emoji reaction swaps it`() {
        // Only the whole string matches for removal; a single emoji from
        // a multi-emoji reaction replaces it instead.
        assertThat(toggleReaction("❤️👍", "❤️", isOwnReaction = true)).isEqualTo("❤️")
    }

    @Test fun `tapping any other emoji never clears the stored reaction`() {
        // Round 52 emoji fix — the badge tap opens the picker and never
        // clears: removal happens ONLY through this toggle, and only on
        // an explicit tap of the already-applied emoji. A tap on any
        // other emoji must store that emoji, never null — so a stray tap
        // on someone else's reaction can never wipe the badge.
        for (emoji in DEFAULT_QUICK_REACTIONS) {
            if (emoji != "👍") {
                assertThat(toggleReaction("👍", emoji, isOwnReaction = true)).isEqualTo(emoji)
            }
        }
    }

    // Round 54 — ownership: only the user's OWN reaction toggles off.
    // Tapping someone else's (or unknown-owner) emoji must leave the
    // badge exactly as it is — the repository refuses the removal too,
    // but the toggle is the first line of defence.

    @Test fun `tapping someone else's emoji does not remove it`() {
        // Unknown ownership reads as not-mine at the call site (the
        // stored row predates the author columns): same no-op.
        assertThat(toggleReaction("❤️", "❤️", isOwnReaction = false)).isEqualTo("❤️")
    }

    @Test fun `tapping a different emoji replaces someone else's reaction`() {
        // The single-emoji slot holds only the latest reactor, so
        // reacting differently always replaces — the no-op is only for
        // tapping the SAME emoji as someone else's reaction.
        assertThat(toggleReaction("❤️", "👍", isOwnReaction = false)).isEqualTo("👍")
    }
}
