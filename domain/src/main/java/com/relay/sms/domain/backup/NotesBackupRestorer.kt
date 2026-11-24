/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.backup

import com.relay.sms.core.result.Outcome

/**
 * Port domaine : restauration d'une sauvegarde de notes chiffrée `.notebk`.
 *
 * [com.relay.sms.domain.usecase.RestoreNotesBackupUseCase] en dépend. L'implémentation
 * [com.relay.sms.data.backup.NotesBackupService] gère le déchiffrement, l'import Room et
 * la transaction atomique. La source est passée sous forme d'URI **chaîne** (l'UI fournit
 * `uri.toString()`), de sorte que `domain/` reste sans import `android.net.Uri`.
 */
interface NotesBackupRestorer {

    /**
     * Lit, déchiffre et importe le fichier `.notebk` pointé par [uriString] avec [password].
     * Renvoie le bilan d'import, ou une [Outcome.Failure] (URI illisible, mot de passe erroné,
     * format invalide).
     */
    suspend fun restore(uriString: String, password: CharArray): Outcome<RestoreNotesResult>
}
