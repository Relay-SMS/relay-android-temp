/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.local.db.dao

import androidx.room.ColumnInfo

/**
 * Slice 2 — one row of [MessageDao.countByConversation]: how many
 * visible messages a non-vault conversation holds. Conversations
 * with no visible message produce no row.
 */
data class ConversationMessageCount(
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "message_count") val messageCount: Int,
)
