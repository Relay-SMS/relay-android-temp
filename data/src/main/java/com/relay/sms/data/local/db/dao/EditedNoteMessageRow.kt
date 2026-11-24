/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.local.db.dao

import androidx.room.ColumnInfo

/**
 * Phase 2 — one row of [NoteMessageDao.observeEditedMessages]: the
 * hub's edits collector watches edited entries and pushes
 * `note_message_updated` when a body changes after its first
 * sighting.
 */
data class EditedNoteMessageRow(
    val id: Long,
    @ColumnInfo(name = "note_id") val noteId: Long,
    val body: String,
)
