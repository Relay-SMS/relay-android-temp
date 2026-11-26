/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.components

import com.relay.sms.domain.smartactions.SmartAction

/**
 * Feedback round 5 — the bubble menu's per-message toggle action: pin
 * ([isPinned] + [onTogglePin]). One bundle parameter instead of two —
 * [MessageBubble] sits at the Detekt 20-parameter limit, and the pin
 * toggle alone would trip it. The pin state is read from the conversation
 * (one pinned message per thread).
 *
 * Round 23 — the star toggle is gone with the menu's "Star" entry: there
 * is no starred list, screen or marker, so starring only exempted a
 * message from auto-delete. The `starred` column still exists and still
 * protects a message from the purge; only the UI toggle is removed.
 *
 * Round 52 emoji fix — [onReactionBadgeTap] rides the same bundle for
 * the same reason: the badge tap opens the reaction picker, and the text
 * bubble has no parameter slot left for a fourth callback. (Round 21
 * wired it as a removal — `setReaction(id, null)` — but a tap must not
 * clear a reaction whose author the badge cannot tell.)
 *
 * Round 53 — [onSmartAction] rides it too: a tap on a smart-action span
 * (email, address, date/time, tracking number) in the bubble's text
 * forwards here.
 *
 * Round 56 — the map preview card is gone (Kristin tested
 * Apple/Google/OSM and none of them really work): [mapsProvider] and
 * [onMapOpen] went with it — the address pill tap now forwards
 * through [onSmartAction], and the thread gates the first map tap
 * behind the provider sheet itself.
 */
data class BubbleMenuToggles(
    val isPinned: Boolean,
    val onTogglePin: () -> Unit,
    /** Round 52 emoji fix — badge tap opens the reaction picker; never clears. */
    val onReactionBadgeTap: () -> Unit,
    /**
     * Round 53 — tap on a smart-action span.
     * `null` where the thread does not wire smart actions (previews,
     * standalone bubbles) — the tap then does nothing, like the default
     * phone callback.
     */
    val onSmartAction: ((SmartAction) -> Unit)? = null,
)
