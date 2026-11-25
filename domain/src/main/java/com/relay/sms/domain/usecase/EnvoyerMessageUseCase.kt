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
import com.relay.sms.domain.clientmode.ClientSendGateway
import com.relay.sms.domain.clientmode.NoOpClientSendGateway
import com.relay.sms.domain.e2e.E2eGroupSendGate
import com.relay.sms.domain.e2e.E2eSendGate
import com.relay.sms.domain.model.PhoneAddress
import com.relay.sms.domain.model.SendReport
import com.relay.sms.domain.ownnumber.OwnNumberEnsurer
import com.relay.sms.domain.settings.AppSettings
import com.relay.sms.domain.settings.AppSettingsSource
import com.relay.sms.domain.settings.DeviceMode
import javax.inject.Inject

/**
 * v1.28.4 — **l'aiguillage d'un message sortant, écrit UNE fois.**
 *
 * Le fil de conversation et l'envoi programmé décidaient chacun, avec leurs propres mots, si un
 * message part en SMS, en MMS, ou en MMS de groupe. Ils ont divergé : l'envoi programmé depuis un
 * groupe ignorait le réglage « MMS de groupe » que l'envoi immédiat appliquait. La règle vit
 * désormais ici, et lit elle-même le réglage — un appelant ne peut plus l'oublier.
 *
 * La règle : plusieurs destinataires et réglage « MMS de groupe » actif → un seul MMS à tous,
 * texte seul compris ; sinon des pièces jointes → un MMS par destinataire ; sinon un SMS par
 * destinataire. Le message vocal garde son use case propre (charge utile différente).
 *
 * Group-send own-number fix — avant de router un MMS de groupe, le
 * numéro propre est vérifié juste à temps ([OwnNumberEnsurer]) : un
 * envoi de groupe avec un numéro vide est resté bloqué sur
 * « envoi… » sur l'appareil de l'utilisatrice, donc un numéro
 * toujours vide après détection échoue IMMÉDIATEMENT
 * ([AppError.OwnNumberMissing]) — avant toute ligne miroir, pour
 * que rien ne reste coincé en « envoi… ». La voie vocale, qui
 * contourne ce routeur, porte la même garde dans
 * [SendVoiceMmsUseCase].
 *
 * Round 49 (E2E groups, design §13) — an encrypted group TEXT
 * always travels as per-member SMS fragments: the group MMS path
 * has no encrypted-text channel, so the group-MMS setting never
 * applies to it. The router consults the group gate for group
 * texts and routes Encrypted to the SMS path; the decision is
 * re-taken in [SendSmsUseCase] with the final (signed) body, so
 * this pre-check only routes (and refuses an over-cap body before
 * any row is written). Media in groups stays plain — the gate is
 * never consulted when there are attachments.
 *
 * Round 54 (group media, design §13.7) — the gate IS consulted
 * for group media too ([E2eGroupSendGate.decideGroupMediaSend]):
 * an encrypted group media send rides the group-MMS path (the
 * encryption itself happens in [SendMediaMmsUseCase], which
 * re-takes the decision with the files), and a plain group media
 * send probes its members in the background, like a plain group
 * text. With the group-MMS setting OFF, group media stays plain
 * on the per-recipient echo path — the 1:1 gate is explicitly
 * skipped there, so no member gets a pairwise-encrypted part.
 */
class EnvoyerMessageUseCase @Inject constructor(
    private val sendSms: SendSmsUseCase,
    private val sendMediaMms: SendMediaMmsUseCase,
    private val settings: AppSettingsSource,
    private val ownNumberEnsurer: OwnNumberEnsurer,
    /**
     * Client mode C3 — the wire send gateway. Default NoOp keeps the
     * hub tests compiling untouched; Hilt always provides the real one
     * (a missing binding fails the build).
     */
    private val clientSend: ClientSendGateway = NoOpClientSendGateway,
    /**
     * Round 49 — the encrypted-group send gate. Default
     * [E2eGroupSendGate.NoOp] keeps the existing tests compiling
     * untouched (every group routes as before); Hilt always
     * provides the real gate (bound in `E2eModule`).
     */
    private val groupGate: E2eGroupSendGate = E2eGroupSendGate.NoOp,
    /**
     * Round 49 — the 1:1 send gate, for the background probe on a
     * plain group send: the members' exchanges start here (the
     * group-MMS transport never reaches the send hook, so the
     * router probes — [SendSmsUseCase] probes too, for its own
     * path, and the probe manager gates the duplicate). Default
     * [E2eSendGate.NoOp].
     */
    private val e2eGate: E2eSendGate = E2eSendGate.NoOp,
) {
    suspend operator fun invoke(
        recipients: List<PhoneAddress>,
        body: String,
        attachments: List<SendMediaMmsUseCase.AttachmentPayload> = emptyList(),
        subId: Int? = null,
        /** Cible d'une réponse citée — portée par le SMS seulement, comme avant. */
        replyToMessageId: Long? = null,
        /**
         * Slice 3 — clé d'idempotence d'un envoi fait depuis un appareil lié. Transmise telle
         * quelle aux deux branches : une seule ligne la porte — celle que l'appareil affiche
         * (la ligne 1-à-1, la ligne de MMS de groupe, ou l'écho de groupe).
         */
        clientMessageId: String? = null,
        /**
         * Client mode C3 — the local conversation id, when the caller
         * has one. The wire branch resolves the hub conversation from
         * it (falling back to the recipients' canonical CSV); hub mode
         * ignores it.
         */
        conversationId: Long? = null,
    ): Outcome<SendReport> {
        // Client mode C3 — the wire branch sits ABOVE the hub router AND
        // above the own-number guard: a linked device sends via the hub
        // phone, never via telephony, and the guard (a hub-only concern
        // — the ensurer returns true in client mode) must not be
        // reachable from here even by accident. Group text sends take
        // the same branch: the hub owns group MMS, the client just
        // sends to the conversation.
        if ((settings.hydratedOrNull() ?: AppSettings()).mode == DeviceMode.CLIENT) {
            // Client mode C5 — media sends take the wire branch too:
            // the gateway prepares, uploads, then sends with the
            // `attachments` array (the C3 fail-fast is gone).
            return if (attachments.isNotEmpty()) {
                clientSend.sendMedia(conversationId, recipients, body, attachments, replyToMessageId, clientMessageId)
            } else {
                clientSend.sendText(conversationId, recipients, body, replyToMessageId, clientMessageId)
            }
        }
        val groupe = recipients.size > 1
        // Round 49 — the encrypted-group decision for a group TEXT
        // send (the text gate is never consulted when there are
        // attachments — group media has its own decision below).
        // An encrypted group routes to the SMS fan-out regardless of
        // the group-MMS setting; a plain group probes its members
        // in the background (sending to the thread is intent to
        // talk — the exchange that flips the group starts here).
        // The decision is re-taken in [SendSmsUseCase] with the
        // final (signed) body, so this pre-check only routes — but
        // an over-cap body is refused here, before any row is
        // written: routing it to group MMS instead would silently
        // send an encrypted group's message in the clear.
        val groupDecision = if (groupe && attachments.isEmpty()) {
            groupGate.decideGroupSend(recipients.map { it.raw }, body)
        } else {
            null
        }
        // Round 54 — the encrypted-group decision for a group
        // MEDIA send: consulted so a plain group probes its
        // members in the background (same intent-to-talk rule as
        // text) and so the routing tests can pin that the gate is
        // consulted. The encryption itself is re-decided with the
        // files in [SendMediaMmsUseCase] — this pre-check only
        // probes, it never encrypts or refuses here.
        val groupMediaDecision = if (groupe && attachments.isNotEmpty()) {
            groupGate.decideGroupMediaSend(recipients.map { it.raw })
        } else {
            null
        }
        if (groupDecision == E2eGroupSendGate.Decision.Plain) {
            recipients.forEach { e2eGate.probeForSend(it.raw, subId) }
        }
        if (groupMediaDecision == E2eGroupSendGate.Decision.Plain) {
            recipients.forEach { e2eGate.probeForSend(it.raw, subId) }
        }
        val mmsDeGroupe = groupe &&
            groupDecision != E2eGroupSendGate.Decision.Encrypted &&
            (settings.hydratedOrNull() ?: AppSettings()).sending.groupMms
        // Group-send own-number fix — just-in-time detection, then
        // fail-fast: the check sits BEFORE the dispatch, so a
        // still-blank number creates no PENDING mirror row and the
        // user is told to set their number instead of watching a
        // message hang on "sending". 1:1 sends and group SMS (setting
        // off) never reach this guard.
        if (mmsDeGroupe && !ownNumberEnsurer.ensureForGroupSend()) {
            return Outcome.Failure(AppError.OwnNumberMissing)
        }
        return when {
            groupDecision == E2eGroupSendGate.Decision.RefuseOverCap -> Outcome.Failure(
                AppError.Validation("message too long to send encrypted"),
            )
            mmsDeGroupe -> sendMediaMms.invoke(
                recipients,
                attachments,
                body,
                subId,
                groupMms = true,
                clientMessageId = clientMessageId,
            )
            attachments.isNotEmpty() -> sendMediaMms.invoke(
                recipients,
                attachments,
                body,
                subId,
                echoInGroup = groupe,
                clientMessageId = clientMessageId,
            )
            else -> sendSms.invoke(
                recipients,
                body,
                subId,
                replyToMessageId = replyToMessageId,
                echoInGroup = groupe,
                clientMessageId = clientMessageId,
            )
        }
    }
}
