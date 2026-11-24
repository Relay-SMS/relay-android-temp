/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.backup

/**
 * Bilan d'une restauration de sauvegarde de notes `.notebk` (domaine). Type de données pur —
 * aucune dépendance Android/data. Miroir de [RestoreResult] pour les notes.
 *
 * Les notes, messages et pièces jointes sont rapprochés par UUID : un objet déjà présent
 * est conservé tel quel et compté comme réutilisé/ignoré, jamais dupliqué — réimporter le
 * même fichier est un no-op.
 */
data class RestoreNotesResult(
    val notesReused: Int,
    val notesCreated: Int,
    val messagesImported: Int,
    val messagesSkipped: Int,
    val attachmentsImported: Int = 0,
    val attachmentsSkipped: Int = 0,
) {
    val totalNotesInBackup: Int get() = notesReused + notesCreated
    val totalMessagesInBackup: Int get() = messagesImported + messagesSkipped
}
