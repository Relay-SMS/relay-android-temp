/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens.scheduled

import com.relay.sms.domain.model.Contact

/**
 * One resolved recipient of a scheduled message: the label the row shows
 * (the contact's display name, or the raw number when there is no contact),
 * whether the number is unknown (no contact name — the row renders the grey
 * person glyph, like a 1-to-1 conversation row), and the contact photo URI
 * when the contact has one.
 *
 * Relay-added (scheduled avatars): the scheduled screen used to render
 * digit initials from the raw addresses — it never resolved its recipients
 * to contacts. [ScheduledMessagesViewModel.recipients] resolves them
 * through the cached [com.relay.sms.domain.repository.ContactRepository]
 * lookup, the same mechanism as the conversation list's contact photos.
 */
data class ScheduledRecipient(
    val label: String,
    val isUnknownContact: Boolean,
    val photoUri: String?,
)

/**
 * Resolves one scheduled recipient from a contacts lookup result: the
 * display name when the lookup found a named contact, else the raw number
 * with the unknown flag (the row renders the grey person glyph, like the
 * conversation list does for a 1-to-1 with no display name). A contact
 * with no usable name counts as unknown — the list's rule, not a new one.
 * A throwing lookup never reaches here: the caller maps failures to null.
 */
fun scheduledRecipient(raw: String, contact: Contact?): ScheduledRecipient {
    val name = contact?.displayName?.takeIf { it.isNotBlank() }
    return ScheduledRecipient(
        label = name ?: raw,
        isUnknownContact = name == null,
        photoUri = contact?.photoUri,
    )
}
