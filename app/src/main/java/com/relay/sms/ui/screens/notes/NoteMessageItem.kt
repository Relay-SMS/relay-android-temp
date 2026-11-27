/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.relay.sms.R
import com.relay.sms.data.voice.VoicePlaybackController
import com.relay.sms.domain.model.Attachment
import com.relay.sms.domain.model.Message
import com.relay.sms.domain.model.NoteMessage
import com.relay.sms.domain.model.toDisplayMessage
import com.relay.sms.domain.notes.NoteAttachmentResolver
import com.relay.sms.sync.client.ClientDownloadState
import com.relay.sms.ui.components.AudioMessageBubble
import com.relay.sms.ui.components.BubbleMenuToggles
import com.relay.sms.ui.components.BurstPosition
import com.relay.sms.ui.components.MessageBubble
import com.relay.sms.ui.screens.thread.isLastOfSenderRun
import com.relay.sms.ui.theme.FixedTypeSize
import com.relay.sms.ui.theme.RelayText

/**
 * Notes — one entry of the note thread.
 *
 * The entry maps to a display [Message] at the render boundary
 * ([NoteMessage.toDisplayMessage]: OUTGOING, RECEIVED, no status)
 * and renders through the thread's dumb bubbles:
 *
 *   - a lone audio attachment → [AudioMessageBubble] (delete only);
 *   - any other attachment → [NoteMediaAttachmentBubble] (delete +
 *     copy when there is a caption, forward + pin since round 60);
 *   - text only → [MessageBubble] (delete + copy + translate,
 *     forward + pin since round 60).
 *
 * No reply and no reactions — the note thread has none of those
 * (no peer to reply to, no reaction store for entries). The mapped
 * message never
 * leaves this renderer: it is never passed into an SMS path.
 *
 * Timestamps follow the thread's rule ([isLastOfSenderRun]: one per
 * same-sender run, on its last message — every entry here is
 * outgoing, so the run is the whole day's run) and the burst shape
 * follows the thread's ([computeNoteBurstPosition]: same-day, ≤60 s
 * apart). The day separator renders above the entry when
 * [daySeparatorLabel] is non-null.
 *
 * Round 36 phase 2 — an edited entry shows a small "Edited" marker
 * under the bubble (right-aligned, secondary text); the marker is
 * rendered here, not inside the bubbles, so it covers all three
 * branches. Editing itself is text-only entries only (the
 * [MessageBubble] branch): a media entry's caption is not editable
 * this round.
 */
@Composable
fun NoteMessageItem(
    noteMessage: NoteMessage,
    prev: NoteMessage?,
    next: NoteMessage?,
    daySeparatorLabel: String?,
    resolver: NoteAttachmentResolver,
    playbackProvider: () -> VoicePlaybackController.PlaybackState,
    onTogglePlayback: (Attachment) -> Unit,
    onSeekPlaybackTo: (Int) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    customBubbleColorArgb: Int?,
    onDelete: () -> Unit,
    /** Null when the entry has no text (no inert Copy entry). */
    onCopy: (() -> Unit)?,
    /** Null when the entry has no text (no inert Translate entry). */
    onTranslate: (() -> Unit)?,
    /** Round 36 phase 2 — null when the entry is not a text-only entry with text. */
    onEdit: (() -> Unit)? = null,
    /**
     * Round 60 (h)/(i) — null when the entry has no text (forward
     * is text-only: a note attachment points at an encrypted file,
     * so staging it would forward ciphertext).
     */
    onForward: (() -> Unit)? = null,
    /**
     * Round 60 (h)/(i)/(k) — the bubble menu toggles: the note's
     * pin state + handler, and the smart-action handler that makes
     * the pills and spans in the entry work. Null = no pin entry
     * and no smart actions (the pre-round-60 behaviour).
     */
    toggles: BubbleMenuToggles? = null,
    /** Round 60 (k) — a tapped phone number opens the call dialog. */
    onPhoneClick: (String) -> Unit = {},
    /** Round 36 phase 2 — client mode: the note download states, keyed by local attachment id. */
    downloadStates: Map<Long, ClientDownloadState> = emptyMap(),
    /** Round 36 phase 2 — client mode: requests one note attachment's bytes, by local id. */
    onRequestDownload: ((Long) -> Unit)? = null,
) {
    val display = remember(noteMessage) { noteMessage.toDisplayMessage() }
    val displayPrev = remember(prev) { prev?.toDisplayMessage() }
    val displayNext = remember(next) { next?.toDisplayMessage() }
    val showTimestamp = isLastOfSenderRun(display, displayNext)
    val burst = computeNoteBurstPosition(displayPrev, display, displayNext)
    Column {
        if (daySeparatorLabel != null) {
            NoteDateSeparator(label = daySeparatorLabel)
        }
        val audio = noteMessage.attachments.singleOrNull()?.takeIf { it.isAudio }
        when {
            audio != null -> AudioMessageBubble(
                message = display,
                audio = audio,
                playbackProvider = playbackProvider,
                onTogglePlay = { onTogglePlayback(audio) },
                onSeekTo = onSeekPlaybackTo,
                onDelete = onDelete,
                showTimestamp = showTimestamp,
                showStatus = false,
                customBubbleColorArgb = customBubbleColorArgb,
                onRequestDownload = onRequestDownload?.let { request -> { request(audio.id) } },
            )

            noteMessage.attachments.isNotEmpty() -> NoteMediaAttachmentBubble(
                displayMessage = display,
                noteMessage = noteMessage,
                resolver = resolver,
                onOpenAttachment = onOpenAttachment,
                showTimestamp = showTimestamp,
                customBubbleColorArgb = customBubbleColorArgb,
                onDelete = onDelete,
                onCopy = onCopy,
                onForward = onForward,
                toggles = toggles,
                downloadStates = downloadStates,
                onRequestDownload = onRequestDownload,
            )

            else -> MessageBubble(
                message = display,
                showTimestamp = showTimestamp,
                showStatus = false,
                burstPosition = burst,
                onDelete = onDelete,
                onTranslate = onTranslate,
                onCopy = onCopy,
                onEdit = onEdit,
                onForward = onForward,
                toggles = toggles,
                onPhoneClick = onPhoneClick,
                customBubbleColorArgb = customBubbleColorArgb,
            )
        }
        // Round 36 phase 2 — the "Edited" marker: small,
        // right-aligned secondary text under the bubble, rendered
        // here so it covers every branch (edits are text-only, but
        // the marker belongs to the entry, not the bubble type).
        if (noteMessage.edited) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, end = 8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = stringResource(R.string.note_message_edited),
                    style = RelayText.Timestamp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Max gap (ms) between two entries to count as one burst — the thread's [BURST_GAP_MS]. */
private const val NOTE_BURST_GAP_MS: Long = 60_000L

/**
 * The thread's `computeBurstPosition`, for notes: every entry is
 * outgoing, so the direction check drops out — a burst is a run of
 * entries on the same day, ≤60 s apart.
 */
private fun computeNoteBurstPosition(
    prev: Message?,
    current: Message,
    next: Message?,
): BurstPosition {
    val joinsBefore = prev != null &&
        sameDay(prev.date, current.date) &&
        current.date - prev.date <= NOTE_BURST_GAP_MS
    val joinsAfter = next != null &&
        sameDay(current.date, next.date) &&
        next.date - current.date <= NOTE_BURST_GAP_MS
    return when {
        !joinsBefore && !joinsAfter -> BurstPosition.Solo
        !joinsBefore && joinsAfter -> BurstPosition.First
        joinsBefore && joinsAfter -> BurstPosition.Middle
        else -> BurstPosition.Last
    }
}

/** True when [a] and [b] fall on the same calendar day (the thread's `sameDay`). */
internal fun sameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

/**
 * The thread's `DateSeparator` + `DatePill`, for notes (both are
 * private there): a quiet pill on the surface fill, 13sp secondary
 * text, 16dp of vertical air — no dividers above or below.
 */
@Composable
private fun NoteDateSeparator(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cs = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(cs.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            FixedTypeSize {
                Text(
                    text = label,
                    style = RelayText.SystemNote,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}
