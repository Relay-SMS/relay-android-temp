/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.invite

import android.content.Context
import android.content.Intent

/**
 * Round 36 — "Invite friends": the share text and the share
 * sheet, reached from the conversation-list 3-dot menu ("Invite
 * friends") and the bottom of the Settings landing ("Invite
 * your friends").
 *
 * The message is Kristin's wording, kept in English in every
 * locale: it is a personal message in her words, not UI copy,
 * so it lives here as a constant instead of a string resource.
 * The row labels ARE translated (see `invite_friends` /
 * `invite_friends_settings` in strings.xml).
 */
object InviteFriends {

    /** The invite message, exactly as worded — see above. */
    const val MESSAGE: String =
        "Join me on Relay for private SMS messaging across your devices. https://getrelaysms.com"

    /** The chooser intent for the invite message. */
    fun shareIntent(chooserTitle: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, MESSAGE)
            },
            chooserTitle,
        )

    /** Opens the Android share sheet with the invite message. Never throws. */
    fun share(context: Context, chooserTitle: String) {
        runCatching { context.startActivity(shareIntent(chooserTitle)) }
    }
}
