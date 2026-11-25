/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.relay.sms.R

/**
 * Round 24 — the "+" reaction picker: the FULL emoji grid — all 487 emojis
 * in titled sections with a search at the top, like the desktop picker —
 * replacing the old 24-emoji quick-pick sheet (deleted) with its "Other
 * emoji" button and validate step.
 *
 * Tap an emoji to react IMMEDIATELY (no validate step); tapping the emoji
 * already applied to the message removes the reaction instead
 * ([toggleReaction] — the same toggle as the quick-reaction row and the
 * desktop picker). The grid is the shared [EmojiGrid], so search
 * ([emojiMatchesQuery]) and the long-press skin-tone picker behave exactly
 * like the composer's emoji tab, and the set stays the curated
 * [EMOJI_SECTIONS] (487 emojis — the round-15 exclusions stay out).
 *
 * Round 54 — the toggle is ownership-aware ([isOwnReaction]): tapping
 * someone else's emoji does NOT remove it (the tap is a no-op, and the
 * repository refuses the removal too). [reactedByLabel] names the
 * reactor at the top of the sheet when a reaction is set (e.g. "❤️ by
 * Curt", "❤️ by you") — `null` when no reaction is set or its owner
 * is unknown.
 *
 * Round 54 — the search at the top is the composer's search bar
 * ([EmojiPickerSearchBar]): the same 48dp pill, close button,
 * placeholder ("Search emoji") and live per-keystroke filtering as the
 * composer panel's emoji tab — it previously used [RelaySearchField]
 * (36dp, 10dp corners, magnifier, no close button), which read as a
 * different search. The only deliberate difference from the composer
 * bar: this picker is emoji-only, so the composer's tab icons on the
 * right are replaced by a clear button, shown only while the query is
 * non-empty (the clear affordance this sheet already had).
 *
 * [onPicked] receives the reaction to store, or `null` when the tap
 * removed the reaction. Back / drag down / outside tap → [onDismiss]
 * with no reaction change.
 */
@Composable
fun ReactionEmojiPickerSheet(
    currentReaction: String?,
    isOwnReaction: Boolean = false,
    reactedByLabel: String? = null,
    onPicked: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    // Round 54 — enter on the search hides the keyboard, like the
    // composer's submit (there is no GIF search to submit here — the
    // emoji grid filters live on every keystroke).
    val keyboardController = LocalSoftwareKeyboardController.current
    // Round 24 — fully-rounded (48dp) top corners, like the old picker:
    // the emoji popup family reads as a pill. The search field manages
    // the IME itself (`imePadding` below), so the sheet only takes the
    // navigation-bars insets — the same setup as [ForwardMessageSheet].
    RelaySheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Round 54 — who reacted, at the top of the sheet (above
            // the search): the reactor's name or number, so a tap on
            // their emoji reads as theirs before it is tapped. Shown
            // only when the reaction's owner is known.
            if (reactedByLabel != null) {
                Text(
                    text = reactedByLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )
            }
            // Round 54 — the composer's search bar ([EmojiPickerSearchBar]):
            // same pill, close button and placeholder as the composer
            // panel's emoji tab. The right end is a clear button (shown
            // only while typing) instead of the composer's tab icons —
            // this picker is emoji-only.
            // Hoisted: stringResource is @Composable and cannot run
            // inside the semantics {} block below.
            val clearDescription = stringResource(R.string.action_close)
            EmojiPickerSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = stringResource(R.string.emoji_picker_search_emoji),
                onSearchSubmit = { keyboardController?.hide() },
                modifier = Modifier.fillMaxWidth(),
                leadingContent = { PickerSearchCloseButton(onClick = onDismiss) },
                trailingContent = {
                    if (searchQuery.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { searchQuery = "" }
                                .semantics {
                                    contentDescription = clearDescription
                                    role = Role.Button
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            EmojiGrid(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = REACTION_PICKER_GRID_MAX_HEIGHT_DP.dp),
                query = searchQuery,
                onEmojiSelected = { emoji ->
                    onPicked(toggleReaction(currentReaction, emoji, isOwnReaction))
                    onDismiss()
                },
            )
        }
    }
}

/** Round 24 — the grid's max height inside the sheet: ten 48dp rows. */
private const val REACTION_PICKER_GRID_MAX_HEIGHT_DP = 480
