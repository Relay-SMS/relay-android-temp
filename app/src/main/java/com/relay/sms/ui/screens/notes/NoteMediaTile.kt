/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.relay.sms.core.result.Outcome
import com.relay.sms.domain.model.Attachment
import com.relay.sms.domain.notes.NoteAttachmentResolver
import com.relay.sms.ui.screens.media.MediaTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Notes — one tile of the details panel's Photos / Videos tabs:
 * [MediaTile] with the decrypted model.
 *
 * Same resolution as the thread bubble ([NoteMediaAttachmentBubble]):
 * an image decrypts to memory (plaintext bytes, never on disk); a
 * video decrypts to a playback file, its first frame is extracted,
 * and the file is released immediately (the frame bitmap is the
 * model). A failed decrypt leaves a null model — the neutral tile.
 */
@Composable
fun NoteMediaTile(
    attachment: Attachment,
    resolver: NoteAttachmentResolver,
    onClick: () -> Unit,
    onSaveCopy: ((Attachment) -> Unit)? = null,
) {
    var model by remember(attachment.id) { mutableStateOf<Any?>(null) }
    LaunchedEffect(attachment.id) {
        model = when {
            attachment.isImage -> when (val res = resolver.decryptToMemory(attachment)) {
                is Outcome.Success -> res.value
                is Outcome.Failure -> null
            }

            attachment.mimeType.startsWith("video/", ignoreCase = true) ->
                when (val res = resolver.decryptToPlaybackFile(attachment)) {
                    is Outcome.Success -> {
                        // The frame extraction is blocking I/O
                        // (MediaMetadataRetriever) — off the
                        // composition thread.
                        val frame = withContext(Dispatchers.IO) {
                            extractVideoFrame(res.value)
                        }
                        resolver.releasePlaybackFile(res.value)
                        frame
                    }

                    is Outcome.Failure -> null
                }

            else -> null
        }
    }
    // The override is ALWAYS passed — even while the model is still
    // null (decrypting): omitting it would make the tile load the
    // attachment's own URI, which is ciphertext for a note. A null
    // model renders the neutral tile (image) or the plain play icon
    // (video) until the bytes resolve.
    MediaTile(
        attachment = attachment,
        onClick = onClick,
        onSaveCopy = onSaveCopy,
        imageModel = { model },
    )
}
