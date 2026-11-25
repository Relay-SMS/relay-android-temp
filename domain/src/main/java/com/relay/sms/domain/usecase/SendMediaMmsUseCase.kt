/*
 * Copyright 2026 Relay Messaging (modifications). Relay's modifications
 * to this file are proprietary to Relay Messaging and are not licensed
 * under the Apache License 2.0. See LICENSE-RELAY for the terms. The
 * original work is copyright its respective holders and is licensed
 * under the Apache License 2.0. See LICENSE.
 */

package com.relay.sms.domain.usecase

import com.relay.sms.core.result.AppError
import com.relay.sms.core.result.Outcome
import com.relay.sms.domain.e2e.E2eGroupSendGate
import com.relay.sms.domain.e2e.E2eMediaGate
import com.relay.sms.domain.e2e.MediaFileInput
import com.relay.sms.domain.mms.MediaAttachmentSpec
import com.relay.sms.domain.mms.MmsAttachment
import com.relay.sms.domain.mms.MmsDispatcher
import com.relay.sms.domain.mms.OutgoingAttachmentStore
import com.relay.sms.domain.model.PhoneAddress
import com.relay.sms.domain.model.SendReport
import com.relay.sms.domain.repository.BlockedNumberRepository
import com.relay.sms.domain.repository.OutgoingMessageMirror
import com.relay.sms.domain.sender.DefaultSmsAppChecker
import com.relay.sms.domain.settings.AppSettings
import com.relay.sms.domain.settings.AppSettingsSource
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject

/**
 * v1.2.1 — Sends a non-voice MMS (photo / video / file / contact card) to one or more
 * recipients. Mirrors the broadcast semantics of [SendVoiceMmsUseCase]: one MMS row per
 * non-blocked recipient, each independently dispatched via [MmsDispatcher.sendMediaMms].
 *
 * Carrier-friendly cap of **300 KB total payload** per message — matches what most French
 * MMSCs accept (Free is the tightest at ~300 KB; Orange / SFR / Sosh / Bouygues handle up to
 * ~1 MB). The cap is conservative on purpose so the user always gets a usable error before
 * the MMSC rejects the upload.
 *
 * Round 48 — media encryption (1:1 only in the per-recipient
 * path): before any mirror row exists, each non-blocked recipient
 * goes through [E2eMediaGate.prepareMediaSend], which decides
 * (ENCRYPTED + media-capable → encrypt) and, when it encrypts,
 * sends the key messages FIRST and returns the ciphertext parts.
 * The mirror row keeps the PLAINTEXT sender copy (the durable
 * files) and carries the `encrypted` flag, so the bubble shows
 * the lock + blue. A Refuse (ciphertext over the cap) fails the
 * WHOLE send before anything is mirrored or dispatched; a
 * fallback (encryption failure) dispatches the originals plain —
 * the gate records it in the error log, so the fallback is never
 * silent.
 *
 * Round 54 — group media (design §13.7): the group-MMS path
 * ([envoiDeGroupe]) prepares through [E2eGroupSendGate.prepareGroupMediaSend]
 * instead — one per-file key wrapped in the sender's sender key,
 * fanned out as one identical `GROUP_MEDIA_KEY` fragment set per
 * file, then ONE group PDU with the ciphertext parts and ONE
 * mirror row with `encrypted = true`. The group discipline
 * applies: a group that cannot encrypt its media FAILS visibly,
 * never plain. The per-recipient loop NEVER consults the 1:1 gate
 * for a multi-recipient send (the echo path): a group send must
 * go through the group gate only, never the pairwise gate.
 */
class SendMediaMmsUseCase @Inject constructor(
    private val defaultAppManager: DefaultSmsAppChecker,
    private val blockedRepo: BlockedNumberRepository,
    private val mirror: OutgoingMessageMirror,
    private val sender: MmsDispatcher,
    private val settings: AppSettingsSource,
    private val attachmentStore: OutgoingAttachmentStore,
    private val mediaGate: E2eMediaGate = E2eMediaGate.NoOp,
    /**
     * Round 54 — the encrypted-group media gate. Default
     * [E2eGroupSendGate.NoOp] keeps the existing tests compiling
     * untouched (every group MMS routes as before); Hilt always
     * provides the real gate (bound in `E2eModule`).
     */
    private val groupGate: E2eGroupSendGate = E2eGroupSendGate.NoOp,
) {
    private val envoi = EnvoiParDestinataire(blockedRepo, mirror)

    suspend operator fun invoke(
        recipients: List<PhoneAddress>,
        attachments: List<AttachmentPayload>,
        textBody: String = "",
        subId: Int? = null,
        /** v1.28.3 (groupes) — cf. [SendSmsUseCase.invoke]. */
        echoInGroup: Boolean = false,
        /**
         * v1.28.4 — MMS de groupe : un seul PDU à tous, une seule ligne dans le groupe. Le réglage
         * est lu par l'appelant, qui sait aussi si la conversation est un groupe.
         */
        groupMms: Boolean = false,
        /**
         * Slice 3 — clé d'idempotence d'un envoi fait depuis un appareil lié, cf.
         * [SendSmsUseCase.invoke]. Une seule ligne la porte : la ligne de MMS de groupe, l'écho
         * de groupe, ou la ligne du destinataire unique.
         */
        clientMessageId: String? = null,
    ): Outcome<SendReport> {
        refusPrealable(recipients, attachments, textBody)?.let { return Outcome.Failure(it) }

        // Promote the UI-staged cache files into durable storage BEFORE mirroring/dispatch so the
        // `AttachmentEntity.localUri` we persist survives the outbound-cache pruners (otherwise the
        // sent image shows an empty tile when the thread is reopened days later). Done once, up
        // front, so every recipient row references the same durable file. Cf. [OutgoingAttachmentStore].
        val durableAttachments = attachments.map { it.copy(file = attachmentStore.promoteToDurable(it.file)) }

        // Audit H3 (v1.14.8) — on évite `flow.first()` (ouverture DataStore) sur chaque envoi.
        //
        // v1.27.2 — mais pas via `state.value` : il rend les valeurs PAR DÉFAUT tant que le
        // processus n'est pas hydraté, et cet envoi n'est pas toujours déclenché depuis
        // l'interface — [com.relay.sms.system.scheduler.ScheduledSendAttempt] le rejoue depuis
        // un worker réveillé à froid. `defaultSubId` y valait alors `null` : le renvoi partait de
        // la SIM système au lieu de celle choisie. Même correctif que [SendSmsUseCase].
        val s = settings.hydratedOrNull() ?: AppSettings()
        val effectiveSubId = subId ?: s.sending.defaultSubId
        val deliveryReports = s.sending.deliveryReports
        val now = System.currentTimeMillis()

        // Hisses hors de la boucle : ni l'un ni l'autre ne depend du destinataire, et la voie
        // du destinataire bloque a besoin des specs avant le reste.
        val mirrorSpecs = durableAttachments.map {
            MediaAttachmentSpec(
                file = it.file,
                mimeType = it.mimeType,
                width = it.width,
                height = it.height,
                durationMs = it.durationMs,
            )
        }
        val pduAttachments = durableAttachments.map {
            MmsAttachment(
                file = it.file,
                mimeType = it.mimeType,
                kind = when {
                    it.mimeType.startsWith("image/", ignoreCase = true) -> MmsAttachment.Kind.IMAGE
                    it.mimeType.startsWith("video/", ignoreCase = true) -> MmsAttachment.Kind.VIDEO
                    it.mimeType.startsWith("audio/", ignoreCase = true) -> MmsAttachment.Kind.AUDIO
                    else -> MmsAttachment.Kind.OTHER
                },
            )
        }

        if (groupMms && recipients.size > 1) {
            return envoiDeGroupe(
                recipients,
                mirrorSpecs,
                pduAttachments,
                textBody,
                effectiveSubId,
                deliveryReports,
                clientMessageId,
            )
        }

        // Slice 3 — même règle que côté SMS : la clé ne va que sur UNE ligne.
        val cleSurLigne = clientMessageId.takeIf { !echoInGroup && recipients.size == 1 }
        val cleSurEcho = clientMessageId.takeIf { echoInGroup }

        // Round 48 — préparation du chiffrement AVANT toute ligne
        // miroir : pour chaque destinataire non bloqué, le gate
        // décide (chiffré / clair) et, le cas échéant, chiffre les
        // pièces jointes ET envoie les messages-clés. Un Refuse
        // (chiffré au-dessus du plafond) fait échouer l'envoi
        // ENTIER avant que quoi que ce soit ne soit miroité ou
        // remis — jamais de demi-envoi. Les bloqués ne passent
        // jamais par le gate : leur ligne est marquée en échec sans
        // remise, un message-clé pour eux serait perdu. Les groupes
        // ne passent jamais ici (envoiDeGroupe, plus haut).
        //
        // Round 54 — le gate 1:1 n'est consulté que pour un
        // destinataire UNIQUE : un envoi multi-destinataires
        // (chemin écho de groupe, réglage MMS de groupe
        // désactivé) ne doit jamais passer par le gate par
        // paire — sinon il partirait chiffré par destinataire
        // alors que l'écho de groupe prétend le contraire. Les
        // groupes chiffrés passent par le gate de groupe, dans
        // envoiDeGroupe uniquement.
        val mediaInputs = durableAttachments.map { MediaFileInput(it.file, it.mimeType) }
        val prepared = mutableMapOf<String, E2eMediaGate.PrepareResult.Encrypted>()
        try {
            // Round 54 — the 1:1 gate is consulted only for a
            // UNIQUE recipient: the loop below runs for one
            // recipient at most, so a multi-recipient send (the
            // group echo path, group-MMS setting OFF) never gets a
            // pairwise-encrypted part.
            if (recipients.size == 1) {
                for (r in recipients) {
                    if (blockedRepo.isBlocked(r.raw)) continue
                    when (
                        val result = mediaGate.prepareMediaSend(
                            address = r.raw,
                            files = mediaInputs,
                            maxTotalBytes = MAX_PAYLOAD_BYTES,
                            date = now,
                            subId = effectiveSubId,
                        )
                    ) {
                        is E2eMediaGate.PrepareResult.Encrypted -> prepared[r.raw] = result
                        is E2eMediaGate.PrepareResult.Plain,
                        E2eMediaGate.PrepareResult.FallbackToPlain,
                        -> Unit
                        is E2eMediaGate.PrepareResult.Refuse ->
                            return Outcome.Failure(AppError.Validation(result.message))
                    }
                }
            }
            // v1.28.3 (F21, second passage) — le `continue` muet était ici AUSSI : le correctif
            // n'avait été posé que sur `SendSmsUseCase`. v1.28.4 — la boucle n'existe plus qu'une
            // fois, dans [EnvoiParDestinataire] ; ce chemin ne peut plus diverger du SMS.
            return envoi.parDestinataire(
                recipients = recipients,
                sansRemise = "no MMS dispatched",
                miroir = { r, _ ->
                    mirror.upsertOutgoingMediaMms(
                        address = r.raw,
                        attachments = mirrorSpecs,
                        textBody = textBody,
                        date = now,
                        subId = effectiveSubId,
                        clientMessageId = cleSurLigne,
                        encrypted = prepared.containsKey(r.raw),
                    )
                },
                envoi = { localId, r ->
                    val pieces = prepared[r.raw]?.attachments ?: pduAttachments
                    try {
                        sender.sendMediaMms(
                            localMessageId = localId,
                            recipients = listOf(r.raw),
                            attachments = pieces,
                            textBody = textBody.ifBlank { null },
                            subId = effectiveSubId,
                            requestDeliveryReport = deliveryReports,
                        )
                    } finally {
                        // Les fichiers chiffrés sont lus par le
                        // builder pendant la remise ; après, ils
                        // sont morts — on les supprime.
                        prepared[r.raw]?.attachments?.forEach {
                            runCatching { it.file.delete() }
                        }
                    }
                },
                echoDeGroupe = if (echoInGroup) {
                    { statut ->
                        mirror.upsertGroupEcho(
                            addresses = recipients,
                            body = textBody,
                            date = now,
                            subId = effectiveSubId,
                            status = statut,
                            attachments = mirrorSpecs,
                            clientMessageId = cleSurEcho,
                        )
                    }
                } else {
                    null
                },
            )
        } finally {
            // Les restes (destinataire dont la remise n'a jamais eu
            // lieu, exception dans la boucle) : delete() sur un
            // fichier déjà supprimé est sans effet.
            deletePrepared(prepared)
        }
    }

    /** Round 48 — supprime les fichiers chiffrés temporaires préparés (best-effort). */
    private fun deletePrepared(prepared: Map<String, E2eMediaGate.PrepareResult.Encrypted>) {
        for (result in prepared.values) {
            for (attachment in result.attachments) {
                runCatching { attachment.file.delete() }
            }
        }
    }

    /**
     * v1.28.3 (F21, second passage) — les cinq gardes prealables en une seule decision.
     *
     * Meme raison que dans [SendSmsUseCase] : elles repondent toutes a « a-t-on le droit
     * d'envoyer », et les regrouper vaut mieux que d'excuser leur nombre dans la baseline.
     */
    /**
     * v1.28.4 — **le MMS de groupe** : les membres bloqués sortent du PDU (et sont comptés), les
     * autres reçoivent UN seul MMS, et le miroir n'a qu'UNE ligne — dans le groupe — que le radio
     * suit par son id. Tous bloqués : refus de blocage, pas panne de téléphonie.
     *
     * Round 54 — la préparation du chiffrement de groupe a lieu AVANT toute ligne miroir
     * (clés d'abord, ligne ensuite) : [E2eGroupSendGate.prepareGroupMediaSend] chiffre les
     * fichiers et envoie les `GROUP_MEDIA_KEY` avant que le PDU ne parte. Un Refuse fait
     * échouer l'envoi ENTIER avant toute ligne — jamais de demi-envoi, jamais de clair
     * silencieux. Chiffré : le PDU porte les parties chiffrées et la ligne miroir
     * `encrypted = true` (cadenas + bleu) ; la ligne garde la copie PLAINTEXTE.
     */
    @Suppress("LongParameterList")
    private suspend fun envoiDeGroupe(
        recipients: List<PhoneAddress>,
        mirrorSpecs: List<MediaAttachmentSpec>,
        pduAttachments: List<MmsAttachment>,
        textBody: String,
        subId: Int?,
        deliveryReports: Boolean,
        clientMessageId: String? = null,
    ): Outcome<SendReport> {
        val prepared = groupGate.prepareGroupMediaSend(
            memberAddresses = recipients.map { it.raw },
            files = mirrorSpecs.map { MediaFileInput(it.file, it.mimeType) },
            maxTotalBytes = MAX_PAYLOAD_BYTES,
            date = System.currentTimeMillis(),
            subId = subId,
        )
        if (prepared is E2eGroupSendGate.GroupMediaPrepareResult.Refuse) {
            return Outcome.Failure(AppError.Validation(prepared.message))
        }
        val encrypted = prepared as? E2eGroupSendGate.GroupMediaPrepareResult.Encrypted
        val dispatchAttachments = encrypted?.attachments ?: pduAttachments
        val result = envoi.enGroupe(
            recipients = recipients,
            sansRemise = "no MMS dispatched",
            // v1.28.5 — le miroir porte les CIBLES, pas la liste d'origine : cf. `EnvoiParDestinataire.enGroupe`.
            miroir = { cibles ->
                mirror.upsertOutgoingGroupMms(
                    addresses = cibles,
                    attachments = mirrorSpecs,
                    textBody = textBody,
                    date = System.currentTimeMillis(),
                    subId = subId,
                    clientMessageId = clientMessageId,
                    encrypted = encrypted != null,
                )
            },
            envoi = { localId, cibles ->
                sender.sendMediaMms(
                    localMessageId = localId,
                    recipients = cibles.map { it.raw },
                    attachments = dispatchAttachments,
                    textBody = textBody.ifBlank { null },
                    subId = subId,
                    requestDeliveryReport = deliveryReports,
                )
            },
        )
        // Les fichiers chiffrés sont lus par le builder pendant la
        // remise ; après, ils sont morts — on les supprime.
        encrypted?.attachments?.forEach { runCatching { it.file.delete() } }
        return result
    }

    private fun refusPrealable(
        recipients: List<PhoneAddress>,
        attachments: List<AttachmentPayload>,
        textBody: String,
    ): AppError? {
        val totalBytes = attachments.sumOf { it.file.length() }
        return when {
            !defaultAppManager.isDefault() -> AppError.NotDefaultSmsApp
            recipients.isEmpty() -> AppError.Validation("no recipients")
            attachments.isEmpty() && textBody.isBlank() -> AppError.Validation("no payload")
            attachments.any { !it.file.exists() || it.file.length() == 0L } ->
                AppError.Validation("attachment file missing or empty")
            totalBytes > MAX_PAYLOAD_BYTES ->
                AppError.Validation("payload exceeds 300 KB cap ($totalBytes B)")
            else -> null
        }
    }

    /** Payload describing one attachment, normalised + cached to disk by the UI before send. */
    data class AttachmentPayload(
        val file: File,
        val mimeType: String,
        val width: Int? = null,
        val height: Int? = null,
        val durationMs: Long? = null,
    )

    companion object {
        /**
         * Conservative cap aligned with French MMSC ceilings (Free is the tightest at ~300 KB).
         *
         * v1.26.0 — n'est plus prive : [ScheduleMessageUseCase] applique la MEME limite au moment
         * de programmer, pour que l'utilisateur l'apprenne pendant qu'il peut encore retirer une
         * image, et non des heures plus tard. Une constante partagee plutot que deux valeurs
         * jumelles vouees a diverger.
         */
        /**
         * ⚠️ v1.27.2 — divergence CONNUE et ASSUMÉE avec
         * [com.relay.sms.core.mms.MmsConstants.CARRIER_PAYLOAD_CAP_BYTES] (280 Ko), qui est
         * le seuil de recompression appliqué par l'interface.
         *
         * Sans effet aujourd'hui : l'UI recompresse toujours sous 280 Ko avant qu'un fichier
         * n'atteigne cet use case, les 20 Ko d'écart ne sont donc jamais exercés.
         *
         * Arbitrage de Patrice, 2026-08-04 : NE PAS unifier. Descendre ce plafond à 280
         * rejetterait à l'envoi un message DÉJÀ PROGRAMMÉ dont la pièce jointe tombe entre les
         * deux valeurs — accepté à la programmation sous l'ancienne limite. Casser un envoi
         * programmé pour une cohérence sans effet observable n'en vaut pas le prix.
         */
        const val MAX_PAYLOAD_BYTES = 300L * 1024L
    }
}
