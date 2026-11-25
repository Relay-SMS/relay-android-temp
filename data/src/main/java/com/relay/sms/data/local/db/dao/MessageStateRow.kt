/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.local.db.dao

import androidx.room.ColumnInfo
import com.relay.sms.domain.model.MessageStatus

/**
 * Slice 2 — one row of [MessageDao.observeStates]: the live-mutable
 * slice of a message, without its body. Projected straight out of
 * SQL so the states watch never loads message content to learn that
 * a status changed.
 */
data class MessageStateRow(
    val id: Long,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    val status: MessageStatus,
    @ColumnInfo(name = "reaction_emoji") val reactionEmoji: String?,
    @ColumnInfo(name = "reaction_by_address") val reactionByAddress: String?,
    @ColumnInfo(name = "reaction_is_own") val reactionIsOwn: Boolean,
    val read: Boolean,
    val starred: Boolean,
)
