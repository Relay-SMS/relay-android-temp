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
import com.relay.sms.domain.e2e.E2ePayloadType
import com.relay.sms.domain.e2e.E2eSendGate
import com.relay.sms.domain.model.MessageStatus
import com.relay.sms.domain.model.PhoneAddress
import com.relay.sms.domain.model.SendReport
import com.relay.sms.domain.repository.BlockedNumberRepository
import com.relay.sms.domain.repository.OutgoingMessageMirror
import com.relay.sms.domain.sender.DefaultSmsAppChecker
import com.relay.sms.domain.sender.SentSmsRecorder
import com.relay.sms.domain.sender.SmsSender
import com.relay.sms.domain.settings.AppSettings
import com.relay.sms.domain.settings.AppSettingsSource
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/**
 * Public API for sending a text SMS.
 *
 * Performs (in order):
 *  1. Default-SMS-app guard
 *  2. Blocked-number guard for each recipient
 *  3. Optional signature append from settings
 *  4. For each recipient: insert sent row in Android provider, mirror in Room, dispatch SmsManager
 *
 * For multi-recipient broadcasts each recipient becomes its own row (one private SMS each).
 *
 * E2E (design §3.4) — a 1:1 text-only send to an ENCRYPTED peer goes out
 * encrypted: the decision is taken at the top of [invoke] (before the
 * multi-recipient fan-out), the mirror row holds the plaintext with
 * `encrypted = 1`, and the fragments go out through the normal
 * [SmsSender] path with the plaintext never touching the system sent
 * box. A 1:1 send to a peer that is not ENCRYPTED triggers a probe in
 * the background and sends plain. A 1:1 reaction ([e2ePayloadType] is
 * [E2ePayloadType.REACTION]) encrypts as a reaction payload instead of
 * leaking as plain SMS.
 * A message over the 8,000-character encrypted cap is REFUSED — never
 * silently sent plain.
 *
 * E2E groups (design §13) — a group text encrypts when EVERY member is
 * ENCRYPTED + group-capable: the same fragment set fans out to each
 * member as individual SMS (regardless of the group-MMS setting — the
 * router routes encrypted groups to this path). Round 60 (r): each
 * member's mirror row is a HIDDEN tracking sentinel (empty mirror body,
 * `hidden = 1` — like the reaction sentinels), NOT a visible message:
 * a 1:1 row carrying the group plaintext rendered as an ordinary sent
 * 1:1 message in each member's thread, although only ciphertext
 * fragments of a group message go out to them. The group echo carries
 * the plaintext with `encrypted = 1` and is the only visible row. A
 * group that is not fully encrypted sends plain (its members get a
 * background probe); an over-cap or failed encryption fails the send —
 * the group thread gets a FAILED echo, never a plain send. Group MMS
 * and group media stay plain; group reactions encrypt when the group
 * is encrypted (round 54) — a reaction that fails to encrypt fails
 * visibly with NO failed echo (the tapback text must never paint as a
 * bubble).
 */
class SendSmsUseCase @Inject constructor(
    private val defaultAppManager: DefaultSmsAppChecker,
    private val sentSmsRecorder: SentSmsRecorder,
    private val sender: SmsSender,
    private val mirror: OutgoingMessageMirror,
    private val blockedRepo: BlockedNumberRepository,
    private val settings: AppSettingsSource,
    /**
     * E2E send gate. Default [E2eSendGate.NoOp] keeps the existing
     * `:domain` tests compiling untouched; Hilt always provides the
     * real gate (bound in `E2eModule`).
     */
    private val e2eGate: E2eSendGate = E2eSendGate.NoOp,
    /**
     * E2E groups (design §13) — the encrypted-group send gate.
     * Default [E2eGroupSendGate.NoOp] keeps the existing `:domain`
     * tests compiling untouched (every group sends plain, exactly
     * as before); Hilt always provides the real gate (bound in
     * `E2eModule`).
     */
    private val groupGate: E2eGroupSendGate = E2eGroupSendGate.NoOp,
) {
    private val envoi = EnvoiParDestinataire(blockedRepo, mirror)

    /**
     * Les trois gardes qui precedent toute ecriture, regroupes en une seule decision.
     *
     * Factorises en v1.28.3 (F21) : l'ajout d'une issue distinguant « tous bloques » d'une panne
     * de telephonie portait `invoke` a cinq sorties. Les regrouper vaut mieux que d'excuser le
     * compte — ces trois refus repondent tous a la meme question, « a-t-on le droit d'envoyer ».
     */
    private fun refusPrealable(recipients: List<PhoneAddress>, body: String): AppError? = when {
        !defaultAppManager.isDefault() -> AppError.NotDefaultSmsApp
        recipients.isEmpty() -> AppError.Validation("no recipients")
        body.isBlank() -> AppError.Validation("body is blank")
        else -> null
    }

    suspend operator fun invoke(
        recipients: List<PhoneAddress>,
        body: String,
        subId: Int? = null,
        /**
         * Optional contextual-reply target (#8). When non-null, the persisted outgoing row is
         * tagged with this local message id so the UI can render the quoted excerpt above the
         * bubble. The replied-to row is **not** required to exist in the same conversation; we
         * tolerate dangling refs (deleted source) at the UI layer.
         */
        replyToMessageId: Long? = null,
        /**
         * v1.3.1 — quand `false`, on n'ajoute PAS la signature utilisateur au corps. Default
         * `true` pour la compatibilité ascendante (envois texte/médias standards). Mis à
         * `false` par [SendReactionUseCase] : un emoji de réaction doit rester un emoji seul,
         * sinon (a) le SMS bascule en multi-part = facturation ×2/×3, (b) le destinataire
         * reçoit "❤️\n--\nPat" qui pollue le fil et casse la sémantique "réaction".
         */
        appendSignature: Boolean = true,
        /**
         * v1.4.1 — when non-null, overrides what the Room mirror stores as the row's
         * `body` (the on-wire SMS body sent via `SmsManager` and mirrored into the
         * system inbox `content://sms` remains the regular [body] / `finalBody`,
         * untouched). Used by [SendReactionUseCase] to send a Tapback reaction to
         * the correspondent while NOT painting a redundant outgoing text bubble in
         * the reactor's own thread — the empty `""` row is filtered out at the DAO
         * `observeForConversation` query level. Default `null` = mirror the wire
         * body as-is (regular text SMS).
         */
        localMirrorBody: String? = null,
        /**
         * v1.28.3 (groupes) — `true` quand l'envoi est fait DEPUIS un fil de groupe : une copie
         * locale est alors écrite dans ce fil, cf. [OutgoingMessageMirror.upsertGroupEcho].
         * Volontairement opt-in : le Safety call et le mode urgence envoient aussi à plusieurs,
         * sans qu'un « groupe » doive apparaître dans la liste.
         */
        echoInGroup: Boolean = false,
        /**
         * Slice 3 — clé d'idempotence d'un envoi fait depuis un appareil lié. Une seule ligne
         * la porte : l'écho de groupe quand il y en a un, sinon la ligne du destinataire
         * unique. Jamais sur plusieurs lignes — la colonne porte un index UNIQUE, et deux
         * lignes avec la même clé feraient échouer l'insertion (IGNORE → id -1).
         */
        clientMessageId: String? = null,
        /**
         * E2E (design §3.2) — the payload kind when this send goes out
         * encrypted. [E2ePayloadType.TEXT] (the default) is an ordinary
         * message; [E2ePayloadType.REACTION] is a reaction, passed only
         * by [SendReactionUseCase] after it has established that the
         * reaction is single-target in a 1:1 conversation — or that
         * the reaction is in an encrypted group (all members, via
         * [groupSendPlan]). A reaction payload encrypts the formatted
         * tapback body; the receiver folds it onto its target instead
         * of showing a bubble.
         */
        e2ePayloadType: E2ePayloadType = E2ePayloadType.TEXT,
    ): Outcome<SendReport> {
        refusPrealable(recipients, body)?.let { return Outcome.Failure(it) }

        // Audit H3 (v1.14.8) — on évite `flow.first()` sur CHAQUE envoi (ouverture DataStore +
        // désérialisation, 5-15 ms).
        //
        // v1.27.2 — mais pas via `state.value`, qui rend les valeurs PAR DÉFAUT tant que le
        // processus n'est pas hydraté. Cet envoi n'est pas toujours déclenché depuis l'interface :
        // le Safety call et le mode urgence passent ici depuis un worker réveillé à froid, et la
        // réponse rapide depuis une notification aussi. On y lisait alors `defaultSubId = null`
        // — le SMS d'urgence partait de la SIM système au lieu de celle choisie, donc d'un autre
        // numéro que celui que les contacts reconnaissent — et une signature vide.
        //
        // [AppSettingsSource.hydratedOrNull] ne coûte rien quand le processus est chaud, ce qui
        // reste le cas de tous les envois faits depuis l'interface.
        //
        // Repli sur les valeurs par défaut si les réglages sont illisibles : ici elles ne
        // dégradent que du confort (pas de signature, SIM système, pas d'accusé de réception).
        // Refuser l'envoi coûterait le message lui-même — y compris un message d'urgence.
        val s = settings.hydratedOrNull() ?: AppSettings()
        val signature = s.conversations.signature?.takeIf { it.isNotBlank() }
        val finalBody = if (appendSignature && signature != null) "$body\n--\n$signature" else body
        val deliveryReports = s.sending.deliveryReports
        val effectiveSubId = subId ?: s.sending.defaultSubId

        // E2E (design §3.4 + §2.4) — the encryption decision for a 1:1
        // send, taken BEFORE the fan-out. A 1:1 text (`localMirrorBody
        // == null`) or a 1:1 reaction (`e2ePayloadType == REACTION`,
        // which only SendReactionUseCase passes, and only once it has
        // established single-target + 1:1 conversation) consults the
        // gate; multi-recipient sends consult the GROUP gate instead
        // (below — [groupSendPlan], which also admits group
        // reactions), and any other reaction never encrypts, but a
        // 1:1 reaction still triggers a probe (sending to the thread
        // is intent to talk). A peer that is not ENCRYPTED gets a
        // background probe and a plain send; an over-cap encrypted
        // send is refused here, before any row is written — never
        // silently sent plain.
        val singleRecipient = recipients.singleOrNull()
        val e2eDecision =
            if (singleRecipient != null &&
                (localMirrorBody == null || e2ePayloadType == E2ePayloadType.REACTION)
            ) {
                e2eGate.decideSend(singleRecipient.raw, finalBody)
            } else {
                null
            }
        val e2eEncrypted = when (e2eDecision) {
            E2eSendGate.Decision.Encrypted -> true
            E2eSendGate.Decision.RefuseOverCap -> return Outcome.Failure(
                AppError.Validation("message too long to send encrypted"),
            )
            E2eSendGate.Decision.Plain -> {
                // Plain implies a single recipient (the decision is only
                // consulted for one); the safe call is for the compiler.
                singleRecipient?.let { e2eGate.probeForSend(it.raw, effectiveSubId) }
                false
            }
            null -> {
                if (singleRecipient != null) {
                    e2eGate.probeForSend(singleRecipient.raw, effectiveSubId)
                }
                false
            }
        }

        val now = System.currentTimeMillis()
        // v1.28.3 (F21) — un destinataire bloqué laisse une TRACE, dans le miroir local
        // UNIQUEMENT : rien n'est parti, et écrire chez le fournisseur système une ligne
        // « envoyée » qui ne l'est pas mentirait aux autres applications SMS de l'appareil. Le
        // `telephonyUri` reste donc `null`. Statut posé en deux temps par l'aide :
        // `upsertOutgoingSms` n'écrit pas de code d'erreur, et la promotion monotone refuserait
        // ensuite d'en poser un sur une ligne déjà au sommet de l'échelle.
        // v1.28.4 — boucle, écho et verdict vivent dans [EnvoiParDestinataire].
        //
        // ⚠️ v1.28.12 (audit de cohérence, C1) — **le paramètre `respectBlocklistOnIncoming` a
        // été RETIRÉ de la signature.** Il commandait le `if` de blocage dans les trois copies de
        // la boucle d'envoi ; la factorisation de la v1.28.4 a réuni ces copies ici, où le
        // blocage est désormais INCONDITIONNEL — et elle a nettoyé la signature des deux jumeaux
        // MMS, mais pas celle-ci. Le paramètre survivait donc seul, sans KDoc, sans appelant
        // (une seule occurrence dans tout le dépôt : sa propre déclaration) et sans le moindre
        // effet.
        //
        // Aucun trou de sécurité : le comportement du défaut `true` — bloquer — est le seul qui
        // se soit jamais exécuté. Mais quelqu'un qui écrirait un jour
        // `invoke(..., respectBlocklistOnIncoming = false)` croirait tenir un levier, et
        // enverrait à un numéro bloqué sans que rien ne l'avertisse. Ce dépôt retire ces
        // drapeaux morts plutôt que de les laisser décorer une signature — quatre précédents,
        // dont `AdvancedSettings.isDefaultSmsApp` ce matin même.
        //
        // Si un vrai besoin de contournement apparaît, il se pose sur
        // [EnvoiParDestinataire.parDestinataire], pour que les TROIS voies d'envoi restent
        // alignées par construction — ce que la v1.28.4 cherchait précisément à obtenir.
        // Slice 3 — la clé ne va que sur UNE ligne : l'écho quand il y en a un, sinon la ligne
        // du destinataire unique. Un envoi multi-destinataires sans écho (Safety call, mode
        // urgence) ne porte jamais de clé — ces appelants n'en passent pas.
        val cleSurLigne = clientMessageId.takeIf { !echoInGroup && recipients.size == 1 }
        val cleSurEcho = clientMessageId.takeIf { echoInGroup && localMirrorBody != "" }

        // E2E groups (design §13) — the encryption decision for a
        // group text send, taken BEFORE the fan-out like the 1:1
        // decision above. A refusal (over the cap) or an encrypt
        // failure returns here, before any row is written or the
        // radio touched — the plaintext NEVER goes out instead (an
        // encrypt failure leaves a FAILED echo in the group thread,
        // written by the plan itself — see [groupSendPlan]).
        val groupFragments = when (
            val plan = groupSendPlan(
                recipients = recipients,
                finalBody = finalBody,
                effectiveSubId = effectiveSubId,
                now = now,
                echoInGroup = echoInGroup,
                localMirrorBody = localMirrorBody,
                clientMessageId = cleSurEcho,
                replyToMessageId = replyToMessageId,
                payloadType = e2ePayloadType,
            )
        ) {
            is Outcome.Success -> plan.value
            is Outcome.Failure -> return Outcome.Failure(plan.error)
        }
        val groupEncrypted = groupFragments != null
        val outcome = envoi.parDestinataire(
            recipients = recipients,
            sansRemise = "no message dispatched",
            miroir = { r, bloque ->
                // Round 60 (r) — encrypted group sends: the per-member
                // rows are delivery-tracking SENTINELS only, not
                // messages. A 1:1 row carrying the group plaintext
                // rendered as an ordinary sent 1:1 message in each
                // member's thread (sender-side only — receivers file
                // into the group only), although only ciphertext
                // fragments of a GROUP message ever go out to the
                // member. So the member rows mirror an empty body
                // (`""`), exactly like the reaction sentinels: the
                // real mirror sets `hidden = 1`, skips the
                // conversation touch (no preview, no sort change),
                // and the thread / list / search / sync queries all
                // exclude `hidden = 1` rows. The row id still exists
                // — the fragment sent/delivery intents track against
                // it, and a radio failure still fails it — but
                // nothing about it ever renders. The echo below keeps
                // the plaintext: it is the only visible row. Plain
                // group sends are untouched (a real SMS does go to
                // each member); 1:1 sends are untouched (the row IS
                // the message); group reactions already pass `""`.
                val memberMirrorBody = if (groupEncrypted) "" else localMirrorBody
                if ((e2eEncrypted || groupEncrypted) && !bloque) {
                    // E2E — the mirror row holds the PLAINTEXT with
                    // `encrypted = 1` — for a 1:1 send. For an
                    // encrypted GROUP send the row holds `""` instead
                    // (see above); the plaintext NEVER goes into the
                    // system sent box either way (the fragment
                    // ciphertext rows are written by the send step,
                    // next to the radio send, so the row has no
                    // `telephonyUri` — like a group echo).
                    mirror.upsertOutgoingSms(
                        address = r.raw,
                        body = finalBody,
                        date = now,
                        telephonyUri = null,
                        subId = effectiveSubId,
                        initialStatus = MessageStatus.PENDING,
                        replyToMessageId = replyToMessageId,
                        localMirrorBody = memberMirrorBody,
                        clientMessageId = cleSurLigne,
                        encrypted = true,
                    )
                } else {
                    mirror.upsertOutgoingSms(
                        address = r.raw,
                        body = finalBody,
                        date = now,
                        telephonyUri = if (bloque) {
                            null
                        } else {
                            sentSmsRecorder.insertSentSms(
                                address = r.raw,
                                body = finalBody,
                                date = now,
                                subId = effectiveSubId,
                            )
                        },
                        subId = effectiveSubId,
                        initialStatus = MessageStatus.PENDING,
                        replyToMessageId = replyToMessageId,
                        localMirrorBody = memberMirrorBody,
                        clientMessageId = cleSurLigne,
                    )
                }
            },
            envoi = { localId, r ->
                // The fragments are read into a local first: the
                // smart cast needs a direct null check — the
                // `groupEncrypted` flag alone does not narrow the
                // nullable fragment list.
                val fragments = groupFragments
                if (fragments != null) {
                    sendGroupFragments(
                        localMessageId = localId,
                        destination = r.raw,
                        fragments = fragments,
                        date = now,
                        subId = effectiveSubId,
                        requestDeliveryReport = deliveryReports,
                    )
                } else if (e2eEncrypted) {
                    e2eGate.sendEncrypted(
                        localMessageId = localId,
                        address = r.raw,
                        body = finalBody,
                        date = now,
                        subId = effectiveSubId,
                        requestDeliveryReport = deliveryReports,
                        payloadType = e2ePayloadType,
                    )
                } else {
                    sender.send(localId, r.raw, finalBody, effectiveSubId, deliveryReports)
                }
            },
            // v1.28.3 (groupes) — la copie dans le fil du groupe. Pas pour une réaction (corps
            // local vide, filtré à l'affichage).
            echoDeGroupe = if (echoInGroup && localMirrorBody != "") {
                { statut ->
                    mirror.upsertGroupEcho(
                        addresses = recipients,
                        body = localMirrorBody ?: finalBody,
                        date = now,
                        subId = effectiveSubId,
                        status = statut,
                        replyToMessageId = replyToMessageId,
                        clientMessageId = cleSurEcho,
                        encrypted = groupEncrypted,
                    )
                }
            } else {
                null
            },
        )
        // E2E groups — the first encrypted group message IS the
        // activation event (design §6.4): record the notice once per
        // conversation, on the send side (the receive side records it
        // on the first decrypted group message). Only when at least
        // one member's fragments dispatched — a wholly failed send
        // records nothing, and the next successful send records it.
        // Best-effort: a failure here must never fail a send that
        // already dispatched.
        if (groupEncrypted && outcome is Outcome.Success) {
            runCatching { groupGate.recordGroupActivated(recipients.map { it.raw }, now) }
                .onFailure { Timber.w(it, "E2E: group activation notice failed") }
        }
        return outcome
    }

    /**
     * E2E groups (design §13) — the fragment set for an encrypted
     * group text send, or null when this send is not one.
     *
     * Only a send FROM a group thread qualifies: `echoInGroup`
     * with at least two recipients and a real text body
     * (`localMirrorBody == null`) — or a group reaction
     * (`localMirrorBody` is `""` with [E2ePayloadType.REACTION],
     * admitted since round 54: [SendReactionUseCase] pre-decides
     * the group gate and passes the encrypted attempt here). A
     * multi-recipient broadcast (Safety call, emergency mode) is
     * not a group send — each recipient gets a private SMS, no
     * echo — and any other reaction stays plain.
     *
     * - [Outcome.Success] with a fragment list: the group is
     *   encrypted — the caller fans the SAME fragments out to
     *   every member as individual SMS (the group MMS path has no
     *   encrypted-text channel).
     * - [Outcome.Success] with null: plain — the members get a
     *   background probe (sending to the thread is intent to
     *   talk; the exchange that flips the group to encrypted
     *   starts here) and the existing plain path runs. The router
     *   probes too, for the group-MMS transport — the probe
     *   manager gates the duplicate (24-hour spacing), so no
     *   member is probed twice for one send.
     * - [Outcome.Failure]: a refusal (over the 8,000-character
     *   cap) or an encrypt failure — nothing is sent, and for an
     *   encrypt failure the group thread gets a FAILED echo row
     *   (written here, since the caller has not written any row
     *   yet) so the user sees the failure where they sent it.
     *   The plaintext NEVER goes out instead. A REACTION failure
     *   writes NO echo: the tapback text (`Liked "…"`) must never
     *   paint as a failed bubble in the group thread — the caller
     *   surfaces the failure (snack / SEND_FAILED) and the local
     *   badge stays set.
     */
    private suspend fun groupSendPlan(
        recipients: List<PhoneAddress>,
        finalBody: String,
        effectiveSubId: Int?,
        now: Long,
        echoInGroup: Boolean,
        localMirrorBody: String?,
        clientMessageId: String?,
        replyToMessageId: Long?,
        payloadType: E2ePayloadType,
    ): Outcome<List<String>?> {
        // A group reaction carries no mirror body (""), but its
        // REACTION payload still qualifies it as a group send.
        val qualifiesAsGroupSend = localMirrorBody == null ||
            payloadType == E2ePayloadType.REACTION
        if (!echoInGroup || recipients.size < 2 || !qualifiesAsGroupSend) {
            return Outcome.Success(null)
        }
        val memberAddresses = recipients.map { it.raw }
        return when (groupGate.decideGroupSend(memberAddresses, finalBody)) {
            E2eGroupSendGate.Decision.Encrypted -> {
                when (
                    val encrypted = groupGate.encryptGroupMessage(
                        memberAddresses,
                        finalBody,
                        effectiveSubId,
                        now,
                        payloadType,
                    )
                ) {
                    is Outcome.Success -> Outcome.Success(encrypted.value.fragments)
                    is Outcome.Failure -> {
                        // The encrypt failed (no own number, no key):
                        // the group thread gets a FAILED echo — never
                        // a plain send — and the caller reports the
                        // failure. No per-recipient rows: nothing went
                        // out to anyone, so there is nothing to track.
                        // A REACTION failure writes NO echo: the
                        // tapback text must never paint as a failed
                        // bubble in the group thread (see the KDoc
                        // above — the caller surfaces the failure and
                        // the local badge stays set).
                        if (payloadType != E2ePayloadType.REACTION) {
                            mirror.upsertGroupEcho(
                                addresses = recipients,
                                body = finalBody,
                                date = now,
                                subId = effectiveSubId,
                                status = MessageStatus.FAILED,
                                replyToMessageId = replyToMessageId,
                                clientMessageId = clientMessageId,
                                encrypted = true,
                            )
                        }
                        Outcome.Failure(encrypted.error)
                    }
                }
            }
            E2eGroupSendGate.Decision.RefuseOverCap -> Outcome.Failure(
                AppError.Validation("message too long to send encrypted"),
            )
            E2eGroupSendGate.Decision.Plain -> {
                recipients.forEach { e2eGate.probeForSend(it.raw, effectiveSubId) }
                Outcome.Success(null)
            }
        }
    }

    /**
     * E2E groups (design §13.4) — fans one encrypted fragment set
     * out to ONE member, tracked against that member's mirror row.
     *
     * **Per-member fragment tracking (the open integration
     * question, decided in round 49, REVERSED in round 60):**
     * round 49 kept the existing per-recipient 1:1 rows — the same
     * rows the plain group path already writes ("chaque ligne
     * d'envoi vit dans la conversation 1-à-1 du destinataire") —
     * as the tracking rows, each carrying the plaintext with
     * `encrypted = 1` (exactly like a 1:1 encrypted row), so the
     * per-fragment sent/delivery intents could update the row and
     * the delivery receiver could hold DELIVERED until ALL of
     * that member's fragments reported — independently per member,
     * since the tracker is keyed by (message id, attempt). The
     * echo row in the group thread kept its existing role: the
     * frozen whole-send status (SENT when every member's
     * fragments were accepted by the radio, FAILED otherwise),
     * never updated by async reports.
     *
     * Round 60 (r) reverses the VISIBLE half of that decision: a
     * 1:1 row carrying the group plaintext rendered as an ordinary
     * sent 1:1 message in each member's thread (sender-side only),
     * although only ciphertext fragments of a GROUP message go out
     * to the member — the rows misrepresented what happened. The
     * per-member rows are now HIDDEN tracking sentinels (empty
     * mirror body, `hidden = 1`, like the reaction sentinels):
     * the fragment intents still track against the row id, a radio
     * failure still fails it, and the echo still freezes the
     * whole-send status — but nothing about the row ever renders
     * (thread, conversation list, search, export, and sync all
     * exclude `hidden = 1`). The cost, accepted with the reversal:
     * per-member DELIVERED ticks are no longer surfaced anywhere
     * (they were only ever visible in the phantom 1:1 rows); the
     * echo's SENT/FAILED on radio accept is the delivery signal
     * the sender sees. The diagnostic-only alternative (no
     * per-member rows at all) stays rejected for the same reason
     * as in round 49: a member's radio failure would be invisible,
     * and the echo could never say who failed — the sentinel
     * keeps the failure attributable per member (the row status
     * and the `SendReport.failed` list name the member) without
     * painting a message.
     *
     * Each fragment is written to the system sent box first (the
     * ciphertext SMS is stored like any SMS, design §3.1). A
     * fragment the radio refuses fails the member's send at once;
     * later fragments are not attempted — a partial set is
     * undecryptable anyway, and the row's FAILED status is the
     * signal to retry the whole send.
     */
    private fun sendGroupFragments(
        localMessageId: Long,
        destination: String,
        fragments: List<String>,
        date: Long,
        subId: Int?,
        requestDeliveryReport: Boolean,
    ): Outcome<Unit> {
        for ((index, fragment) in fragments.withIndex()) {
            sentSmsRecorder.insertSentSms(destination, fragment, date, subId = subId)
            val result = sender.sendFragment(
                localMessageId = localMessageId,
                destination = destination,
                text = fragment,
                fragmentIndex = index,
                fragmentTotal = fragments.size,
                subId = subId,
                requestDeliveryReport = requestDeliveryReport,
            )
            if (result is Outcome.Failure) return result
        }
        return Outcome.Success(Unit)
    }
}
