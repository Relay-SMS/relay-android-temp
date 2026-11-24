/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.model

/**
 * Slice 2 — the live-mutable slice of one message: what the sync
 * states watch compares to decide whether a `message_updated` push
 * is due. No body, no attachments — just the fields that can change
 * after a message was first synced.
 */
data class MessageState(
    val id: Long,
    val conversationId: Long,
    val status: Message.Status,
    val reactionEmoji: String?,
    val read: Boolean,
    val starred: Boolean,
)
