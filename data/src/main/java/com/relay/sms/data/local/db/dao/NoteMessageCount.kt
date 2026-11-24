/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.local.db.dao

import androidx.room.ColumnInfo

/**
 * Phase 2 — one row of [NoteMessageDao.countsByNote]: how many
 * entries a note holds. Notes with no entry produce no row.
 */
data class NoteMessageCount(
    @ColumnInfo(name = "note_id") val noteId: Long,
    @ColumnInfo(name = "message_count") val messageCount: Int,
)
