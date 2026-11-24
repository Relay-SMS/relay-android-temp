package com.relay.sms.domain.usecase

import com.relay.sms.core.result.AppError
import com.relay.sms.core.result.Outcome
import com.relay.sms.domain.pdf.PdfExportResult
import com.relay.sms.domain.pdf.PdfExporter
import com.relay.sms.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ExportConversationPdfUseCase @Inject constructor(
    private val repo: ConversationRepository,
    private val exporter: PdfExporter,
) {
    suspend operator fun invoke(conversationId: Long): Outcome<PdfExportResult> {
        val conv = repo.observeOne(conversationId).first() ?: return Outcome.Failure(AppError.NotFound("conversation"))
        val messages = repo.observeMessages(conversationId).first()
        if (messages.isEmpty()) return Outcome.Failure(AppError.Validation("no messages to export"))
        return exporter.export(conv, messages)
    }
}
