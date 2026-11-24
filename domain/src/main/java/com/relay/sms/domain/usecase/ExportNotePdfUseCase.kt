/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.usecase

import com.relay.sms.core.result.AppError
import com.relay.sms.core.result.Outcome
import com.relay.sms.domain.model.toDisplayMessage
import com.relay.sms.domain.pdf.PdfExportResult
import com.relay.sms.domain.pdf.PdfExporter
import com.relay.sms.domain.repository.NoteRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Notes — exports a whole note to a shareable PDF, the thread PDF
 * export's shape with note content: the note title as the header,
 * every entry as an outgoing bubble, date dividers between days.
 * Attachments are not rendered (the conversation export is text
 * only too); an attachment-only entry renders as the `[…]`
 * placeholder, like an empty body there.
 */
class ExportNotePdfUseCase @Inject constructor(
    private val notes: NoteRepository,
    private val exporter: PdfExporter,
) {
    suspend operator fun invoke(noteId: Long): Outcome<PdfExportResult> {
        val note = notes.findNoteById(noteId)
            ?: return Outcome.Failure(AppError.NotFound("note"))
        val messages = notes.observeMessages(noteId).first()
        if (messages.isEmpty()) return Outcome.Failure(AppError.Validation("no messages to export"))
        return exporter.exportNote(note.title, messages.map { it.toDisplayMessage() })
    }
}
