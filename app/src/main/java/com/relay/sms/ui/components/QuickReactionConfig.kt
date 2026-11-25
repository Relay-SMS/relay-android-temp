/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.components

/**
 * Round 12 — the quick-reaction bar's input, bundled in one parameter.
 * One bundle parameter instead of two — [MessageBubble] sits at the
 * Detekt 20-parameter limit (see [BubbleMenuToggles]).
 */
data class QuickReactionConfig(
    /** Most recently used reaction emojis, most recent first. */
    val recentEmojis: List<String>,
    /**
     * Called with the tapped emoji (always non-null — the row only emits
     * real emojis); the caller sends the reaction. Round 23 — the caller
     * applies [toggleReaction] to decide: tapping the emoji already
     * applied stores `null` (removes the reaction), any other tap stores
     * the tapped emoji.
     */
    val onSelect: (String) -> Unit,
)

/**
 * Round 23 — what a tap on a quick-reaction emoji stores: tapping the
 * emoji already applied to the message removes the reaction (toggle), so
 * the caller passes `null`; any other tap stores the tapped emoji. A
 * multi-emoji reaction only matches when the whole string is tapped — a
 * single emoji from it swaps the reaction instead, as before. Shared by
 * the tap bar and the long-press menu's reaction row, which both feed
 * the same [QuickReactionConfig.onSelect].
 *
 * Round 54 — ownership-aware: the toggle only removes the USER'S OWN
 * reaction. [isOwnReaction] is `true` only when the stored reaction
 * was set by this user (see `Message.reactionIsOwn`); a peer's
 * reaction — or one of unknown ownership, stored before the author
 * columns existed — is never removed by tapping it:
 *   - same emoji + own → `null` (remove, as before);
 *   - same emoji + not own → the stored reaction, unchanged (no-op —
 *     the caller must not re-send it; `ConversationRepository`
 *     refuses the removal too, as the choke point);
 *   - different emoji → the tapped emoji (replace — the single-emoji
 *     slot holds only the latest reactor, so reacting differently
 *     always replaces whoever reacted before).
 * Callers MUST pass the message's real ownership: the old two-arg
 * shape defaulted to "removable", which is exactly the bug this
 * fixes, so there is no default here.
 */
fun toggleReaction(
    currentReaction: String?,
    tappedEmoji: String,
    isOwnReaction: Boolean,
): String? =
    if (tappedEmoji == currentReaction) {
        if (isOwnReaction) null else currentReaction
    } else {
        tappedEmoji
    }
