/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.components

/**
 * Round 13 — the two schedule actions the long-press menu on the send button
 * needs, bundled into one parameter so [ComposerBar] stays under the
 * 20-parameter Detekt limit. [onPresetSelected] schedules the draft for a preset
 * epoch through the existing `scheduleSend` path; [onPickDateTime] opens the
 * existing DatePicker → TimePicker scheduling flow.
 */
data class ScheduleMenuActions(
    val onPresetSelected: (Long) -> Unit,
    val onPickDateTime: () -> Unit,
)
