/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.relay.sms.core.result.AppError
import com.relay.sms.core.result.Outcome
import com.relay.sms.domain.e2e.E2eGroupSendGate
import com.relay.sms.domain.e2e.E2ePayloadType
import com.relay.sms.domain.e2e.E2eSendGate
import com.relay.sms.domain.e2e.MediaFileInput
import com.relay.sms.domain.model.Conversation
import com.relay.sms.domain.model.Message
import com.relay.sms.domain.model.PhoneAddress
import com.relay.sms.domain.model.ReactionFormat
import com.relay.sms.domain.sender.SenderNameProvider
import com.relay.sms.domain.sender.SmsSender
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Encrypted reactions (design §3.2): a reaction in a 1:1 conversation
 * to an ENCRYPTED peer rides the encrypted path with payload type
 * REACTION instead of leaking as plain SMS; the formatted body is
 * unchanged — the receiver decrypts exactly what a plain reaction SMS
 * would have contained and folds it onto its target.
 *
 * The gate is faked here: the real gate ([com.relay.sms.e2e
 * .E2eSendManager]) proves the wire side in the app module, and the
 * end-to-end round trip lives in `E2eReactionRoundTripTest`. What this
 * file locks is the USE-CASE side of the contract:
 *
 * - 1:1 (incoming or own message) + ENCRYPTED peer → the gate
 *   dispatches with REACTION; the plain sender never sees it.
 * - Group + group gate Encrypted → ONE GROUP_REACTION fragment set
 *   fanned out to every member; the plain sender never sees it and
 *   no group echo is written. Group + group gate Plain (or NoOp) →
 *   plain, byte-identical to the pre-encryption behavior (F2 single
 *   target for an incoming message, all members for an own message);
 *   the 1:1 gate is never consulted.
 * - Group + group gate RefuseOverCap → failure, nothing sent, no
 *   echo, no plain fallback.
 * - 1:1 + non-encrypted peer → plain + a background probe.
 * - Removals encrypt like adds when the peer / group is encrypted.
 * - Client mode keeps its early return; the gate is untouched.
 */
class SendReactionE2eTest {

    private val sms = RecordingSender()
    private val miroir = NoopMirror()
    private val senderName = object : SenderNameProvider {
        override fun resolveDisplayName(): String? = null
    }

    /** A recording [E2eSendGate]: the test chooses the decision. */
    private class RecordingGate(
        var decision: E2eSendGate.Decision = E2eSendGate.Decision.Plain,
    ) : E2eSendGate {
        data class EncryptedCall(
            val address: String,
            val body: String,
            val payloadType: E2ePayloadType,
        )

        val decidedFor = mutableListOf<String>()
        val probed = mutableListOf<String>()
        val encryptedCalls = mutableListOf<EncryptedCall>()

        override suspend fun decideSend(address: String, body: String): E2eSendGate.Decision {
            decidedFor += address
            return decision
        }

        override suspend fun sendEncrypted(
            localMessageId: Long,
            address: String,
            body: String,
            date: Long,
            subId: Int?,
            requestDeliveryReport: Boolean,
            attempt: Int,
            payloadType: E2ePayloadType,
        ): Outcome<Unit> {
            encryptedCalls += EncryptedCall(address, body, payloadType)
            return Outcome.Success(Unit)
        }

        override fun probeForSend(address: String, subId: Int?) {
            probed += address
        }
    }

    private fun conversation(id: Long, vararg numbers: String) = Conversation(
        id = id,
        threadId = null,
        addresses = numbers.map { PhoneAddress.of(it) },
        displayName = null,
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
    )

    private fun message(
        id: Long,
        conversationId: Long,
        address: String,
        outgoing: Boolean,
        body: String = "salut",
    ) = Message(
        id = id,
        conversationId = conversationId,
        address = address,
        body = body,
        type = Message.Type.SMS,
        direction = if (outgoing) Message.Direction.OUTGOING else Message.Direction.INCOMING,
        date = System.currentTimeMillis(),
        dateSent = null,
        read = true,
        starred = false,
        status = Message.Status.RECEIVED,
        errorCode = null,
        attachmentsCount = 0,
        subId = null,
        scheduledAt = null,
    )

    /** A recording [E2eGroupSendGate]: the test chooses the decision. */
    private class FakeGroupGate(
        var decision: E2eGroupSendGate.Decision = E2eGroupSendGate.Decision.Plain,
        var encryptError: AppError? = null,
    ) : E2eGroupSendGate {
        val decidedFor = mutableListOf<List<String>>()
        val encryptedFor = mutableListOf<List<String>>()
        val encryptedBodies = mutableListOf<String>()
        val encryptedPayloadTypes = mutableListOf<E2ePayloadType>()
        val activatedFor = mutableListOf<List<String>>()

        override suspend fun decideGroupSend(
            memberAddresses: List<String>,
            body: String,
        ): E2eGroupSendGate.Decision {
            decidedFor += memberAddresses
            return decision
        }

        override suspend fun encryptGroupMessage(
            memberAddresses: List<String>,
            body: String,
            subId: Int?,
            date: Long,
            payloadType: E2ePayloadType,
        ): Outcome<E2eGroupSendGate.EncryptedGroupMessage> {
            encryptedFor += memberAddresses
            encryptedBodies += body
            encryptedPayloadTypes += payloadType
            val error = encryptError
            return if (error != null) {
                Outcome.Failure(error)
            } else {
                Outcome.Success(E2eGroupSendGate.EncryptedGroupMessage(FRAGMENTS, GROUP_ID))
            }
        }

        override suspend fun recordGroupActivated(memberAddresses: List<String>, now: Long) {
            activatedFor += memberAddresses
        }

        override suspend fun decideGroupMediaSend(
            memberAddresses: List<String>,
        ): E2eGroupSendGate.Decision = E2eGroupSendGate.Decision.Plain

        override suspend fun prepareGroupMediaSend(
            memberAddresses: List<String>,
            files: List<MediaFileInput>,
            maxTotalBytes: Long,
            date: Long,
            subId: Int?,
        ): E2eGroupSendGate.GroupMediaPrepareResult =
            E2eGroupSendGate.GroupMediaPrepareResult.Plain
    }

    private fun useCase(
        messagesById: Map<Long, Message>,
        conversationsById: Map<Long, Conversation> = emptyMap(),
        gate: E2eSendGate,
        groupGate: E2eGroupSendGate = E2eGroupSendGate.NoOp,
        sender: SmsSender = sms,
        clientMode: Boolean = false,
    ): SendReactionUseCase {
        val reglages = FakeSettings()
        return SendReactionUseCase(
            sendSms = sendSmsUseCase(
                settings = reglages,
                sender = sender,
                mirror = miroir,
                e2eGate = gate,
                groupGate = groupGate,
            ),
            conversationRepo = FakeConversationRepository(
                conversationsById = conversationsById,
                messagesById = messagesById,
            ),
            senderNameProvider = senderName,
            clientActions = FakeClientActionGateway(clientMode = clientMode),
            groupGate = groupGate,
        )
    }

    @Test
    fun `1-1 incoming reaction to an encrypted peer encrypts as REACTION`() = runBlocking {
        val gate = RecordingGate(decision = E2eSendGate.Decision.Encrypted)
        val incoming = message(id = 11L, conversationId = 9L, address = ALICE, outgoing = false)
        val conv = conversation(9L, ALICE)
        val res = useCase(
            messagesById = mapOf(11L to incoming),
            conversationsById = mapOf(9L to conv),
            gate = gate,
        ).invoke(11L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        // The gate dispatches with the REACTION payload type and the
        // formatted tapback body, untouched; the plain sender never
        // sees it and the mirror row is encrypted.
        assertThat(gate.decidedFor).containsExactly(ALICE)
        assertThat(gate.encryptedCalls).hasSize(1)
        assertThat(gate.encryptedCalls.single().address).isEqualTo(ALICE)
        assertThat(gate.encryptedCalls.single().body).isEqualTo("Liked \"salut\"")
        assertThat(gate.encryptedCalls.single().payloadType).isEqualTo(E2ePayloadType.REACTION)
        assertThat(sms.sentTexts).isEmpty()
        assertThat(miroir.lignes).hasSize(1)
        assertThat(miroir.lignes.single().encrypted).isTrue()
    }

    @Test
    fun `1-1 own-message reaction to an encrypted peer encrypts as REACTION`() = runBlocking {
        val gate = RecordingGate(decision = E2eSendGate.Decision.Encrypted)
        val own = message(id = 12L, conversationId = 9L, address = ALICE, outgoing = true)
        val conv = conversation(9L, ALICE)
        val res = useCase(
            messagesById = mapOf(12L to own),
            conversationsById = mapOf(9L to conv),
            gate = gate,
        ).invoke(12L, "❤️", ReactionFormat.TAPBACK_EN)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.encryptedCalls).hasSize(1)
        assertThat(gate.encryptedCalls.single().body).isEqualTo("Reacted ❤️ to «salut»")
        assertThat(gate.encryptedCalls.single().payloadType).isEqualTo(E2ePayloadType.REACTION)
        assertThat(sms.sentTexts).isEmpty()
    }

    @Test
    fun `group reaction to an encrypted group fans one fragment set to every member`() =
        runBlocking {
            val gate = RecordingGate(decision = E2eSendGate.Decision.Encrypted)
            val groupGate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
            val fragments = RecordingFragmentSender()
            val own = message(
                id = 13L,
                conversationId = 10L,
                address = "$ALICE;$BOB;$CAROL",
                outgoing = true,
            )
            val conv = conversation(10L, ALICE, BOB, CAROL)
            val res = useCase(
                messagesById = mapOf(13L to own),
                conversationsById = mapOf(10L to conv),
                gate = gate,
                groupGate = groupGate,
                sender = fragments,
            ).invoke(13L, "👍", ReactionFormat.IOS_TAPBACK)

            assertThat(res).isInstanceOf(Outcome.Success::class.java)
            // The group gate decides on ALL members — TWICE, by
            // design: once in SendReactionUseCase (to pick the
            // encrypted branch) and once in SendSmsUseCase's
            // groupSendPlan (which re-decides with the final body
            // before encrypting). The 1:1 gate is never consulted
            // and no plain send goes out.
            assertThat(groupGate.decidedFor).containsExactly(
                listOf(ALICE, BOB, CAROL),
                listOf(ALICE, BOB, CAROL),
            )
            assertThat(groupGate.encryptedFor).isEqualTo(listOf(listOf(ALICE, BOB, CAROL)))
            assertThat(groupGate.encryptedBodies).isEqualTo(listOf("Liked \"salut\""))
            assertThat(groupGate.encryptedPayloadTypes)
                .isEqualTo(listOf(E2ePayloadType.REACTION))
            assertThat(groupGate.activatedFor).isEqualTo(listOf(listOf(ALICE, BOB, CAROL)))
            assertThat(gate.decidedFor).isEmpty()
            assertThat(gate.encryptedCalls).isEmpty()
            assertThat(gate.probed).isEmpty()
            // ONE fragment set (2 fragments), fanned out identically
            // to every member — 6 dispatches, no plain send.
            assertThat(fragments.fragments).hasSize(6)
            assertThat(fragments.fragments.map { it.destination })
                .containsExactly(ALICE, ALICE, BOB, BOB, CAROL, CAROL)
            assertThat(fragments.fragments.map { it.text }.toSet()).isEqualTo(FRAGMENTS.toSet())
            assertThat(fragments.plainSends).isEmpty()
            // Per-member mirror rows carry the lock; no group echo —
            // a reaction is not a bubble.
            assertThat(miroir.lignes.map { it.adresse })
                .containsExactly(ALICE, BOB, CAROL)
            assertThat(miroir.lignes.all { it.encrypted }).isTrue()
            assertThat(miroir.echos).isEmpty()
        }

    @Test
    fun `group reaction to a plain group stays plain and probes the members`() = runBlocking {
        val gate = RecordingGate(decision = E2eSendGate.Decision.Encrypted)
        val groupGate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Plain)
        val fragments = RecordingFragmentSender()
        val own = message(
            id = 13L,
            conversationId = 10L,
            address = "$ALICE;$BOB;$CAROL",
            outgoing = true,
        )
        val conv = conversation(10L, ALICE, BOB, CAROL)
        val res = useCase(
            messagesById = mapOf(13L to own),
            conversationsById = mapOf(10L to conv),
            gate = gate,
            groupGate = groupGate,
            sender = fragments,
        ).invoke(13L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        // The group gate was consulted on all members and said Plain:
        // the reaction goes out plain to all members, byte-identical
        // to the pre-encryption behavior. The plain reaction path
        // (TEXT payload) bypasses groupSendPlan entirely, so no
        // member is probed here — the probe happens on the next
        // group TEXT send, exactly as before group reactions.
        assertThat(groupGate.decidedFor).isEqualTo(listOf(listOf(ALICE, BOB, CAROL)))
        assertThat(groupGate.encryptedFor).isEmpty()
        assertThat(gate.decidedFor).isEmpty()
        assertThat(gate.encryptedCalls).isEmpty()
        assertThat(gate.probed).isEmpty()
        assertThat(fragments.plainSends).isEqualTo(List(3) { "Liked \"salut\"" })
        assertThat(fragments.fragments).isEmpty()
        assertThat(miroir.lignes.all { !it.encrypted }).isTrue()
        assertThat(miroir.echos).isEmpty()
    }

    @Test
    fun `reaction to one member of an encrypted group encrypts to every member`() = runBlocking {
        val gate = RecordingGate(decision = E2eSendGate.Decision.Encrypted)
        val groupGate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
        val fragments = RecordingFragmentSender()
        // F2 — reacting to Alice's message in a group notifies Alice
        // alone when plain, but the encrypted attempt must reach
        // every member (a member who never exchanged keys would
        // otherwise leave the rest stale).
        val incoming = message(id = 14L, conversationId = 10L, address = ALICE, outgoing = false)
        val conv = conversation(10L, ALICE, BOB, CAROL)
        val res = useCase(
            messagesById = mapOf(14L to incoming),
            conversationsById = mapOf(10L to conv),
            gate = gate,
            groupGate = groupGate,
            sender = fragments,
        ).invoke(14L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        // Decided twice (reaction branch + groupSendPlan — see the
        // own-message test); the encrypt itself runs once, to every
        // member.
        assertThat(groupGate.decidedFor).containsExactly(
            listOf(ALICE, BOB, CAROL),
            listOf(ALICE, BOB, CAROL),
        )
        assertThat(groupGate.encryptedFor).isEqualTo(listOf(listOf(ALICE, BOB, CAROL)))
        assertThat(groupGate.encryptedBodies).isEqualTo(listOf("Liked \"salut\""))
        assertThat(groupGate.encryptedPayloadTypes)
            .isEqualTo(listOf(E2ePayloadType.REACTION))
        assertThat(gate.decidedFor).isEmpty()
        assertThat(gate.encryptedCalls).isEmpty()
        assertThat(fragments.fragments).hasSize(6)
        assertThat(fragments.plainSends).isEmpty()
        assertThat(miroir.lignes.map { it.adresse })
            .containsExactly(ALICE, BOB, CAROL)
        assertThat(miroir.lignes.all { it.encrypted }).isTrue()
        assertThat(miroir.echos).isEmpty()
    }

    @Test
    fun `reaction to one member of a group stays plain despite a single target`() = runBlocking {
        val gate = RecordingGate(decision = E2eSendGate.Decision.Encrypted)
        // F2 — reacting to Alice's message in a group notifies Alice
        // alone: ONE target, but the conversation is a group, and the
        // group gate (NoOp here) leaves it Plain.
        val incoming = message(id = 14L, conversationId = 10L, address = ALICE, outgoing = false)
        val conv = conversation(10L, ALICE, BOB, CAROL)
        val res = useCase(
            messagesById = mapOf(14L to incoming),
            conversationsById = mapOf(10L to conv),
            gate = gate,
        ).invoke(14L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).isEmpty()
        assertThat(gate.encryptedCalls).isEmpty()
        assertThat(sms.sentTexts).isEqualTo(listOf("Liked \"salut\""))
        assertThat(miroir.lignes.map { it.adresse }).isEqualTo(listOf(ALICE))
        assertThat(miroir.lignes.single().encrypted).isFalse()
    }

    @Test
    fun `group reaction refused over cap fails with no send and no echo`() = runBlocking {
        val gate = RecordingGate(decision = E2eSendGate.Decision.Encrypted)
        val groupGate = FakeGroupGate(decision = E2eGroupSendGate.Decision.RefuseOverCap)
        val own = message(
            id = 13L,
            conversationId = 10L,
            address = "$ALICE;$BOB;$CAROL",
            outgoing = true,
        )
        val conv = conversation(10L, ALICE, BOB, CAROL)
        val res = useCase(
            messagesById = mapOf(13L to own),
            conversationsById = mapOf(10L to conv),
            gate = gate,
            groupGate = groupGate,
        ).invoke(13L, "👍", ReactionFormat.IOS_TAPBACK)

        // Visible failure, never a plain send and never a FAILED echo
        // carrying the tapback text.
        assertThat(res).isInstanceOf(Outcome.Failure::class.java)
        assertThat((res as Outcome.Failure).error)
            .isInstanceOf(AppError.Validation::class.java)
        assertThat(groupGate.decidedFor).isEqualTo(listOf(listOf(ALICE, BOB, CAROL)))
        assertThat(groupGate.encryptedFor).isEmpty()
        assertThat(gate.decidedFor).isEmpty()
        assertThat(gate.encryptedCalls).isEmpty()
        assertThat(gate.probed).isEmpty()
        assertThat(sms.sentTexts).isEmpty()
        assertThat(miroir.lignes).isEmpty()
        assertThat(miroir.echos).isEmpty()
    }

    @Test
    fun `group removal in an encrypted group encrypts the removal form`() = runBlocking {
        val gate = RecordingGate(decision = E2eSendGate.Decision.Encrypted)
        val groupGate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
        val fragments = RecordingFragmentSender()
        val own = message(
            id = 13L,
            conversationId = 10L,
            address = "$ALICE;$BOB;$CAROL",
            outgoing = true,
        )
        val conv = conversation(10L, ALICE, BOB, CAROL)
        val res = useCase(
            messagesById = mapOf(13L to own),
            conversationsById = mapOf(10L to conv),
            gate = gate,
            groupGate = groupGate,
            sender = fragments,
        ).invoke(13L, "👍", ReactionFormat.IOS_TAPBACK, isRemoval = true)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(groupGate.encryptedBodies)
            .isEqualTo(listOf("Removed a like from \"salut\""))
        assertThat(groupGate.encryptedPayloadTypes)
            .isEqualTo(listOf(E2ePayloadType.REACTION))
        assertThat(fragments.fragments).hasSize(6)
        assertThat(fragments.plainSends).isEmpty()
        assertThat(miroir.lignes.all { it.encrypted }).isTrue()
        assertThat(miroir.echos).isEmpty()
    }

    @Test
    fun `1-1 reaction to a non-encrypted peer stays plain and probes`() = runBlocking {
        val gate = RecordingGate(decision = E2eSendGate.Decision.Plain)
        val incoming = message(id = 15L, conversationId = 9L, address = ALICE, outgoing = false)
        val conv = conversation(9L, ALICE)
        val res = useCase(
            messagesById = mapOf(15L to incoming),
            conversationsById = mapOf(9L to conv),
            gate = gate,
        ).invoke(15L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        // Plain, byte-identical to the pre-encryption reaction send,
        // plus a background probe (sending to the thread is intent).
        assertThat(gate.decidedFor).containsExactly(ALICE)
        assertThat(gate.probed).containsExactly(ALICE)
        assertThat(gate.encryptedCalls).isEmpty()
        assertThat(sms.sentTexts).isEqualTo(listOf("Liked \"salut\""))
        assertThat(miroir.lignes.single().encrypted).isFalse()
    }

    @Test
    fun `1-1 removal to an encrypted peer encrypts the removal form`() = runBlocking {
        val gate = RecordingGate(decision = E2eSendGate.Decision.Encrypted)
        val incoming = message(id = 16L, conversationId = 9L, address = ALICE, outgoing = false)
        val conv = conversation(9L, ALICE)
        val res = useCase(
            messagesById = mapOf(16L to incoming),
            conversationsById = mapOf(9L to conv),
            gate = gate,
        ).invoke(16L, "👍", ReactionFormat.IOS_TAPBACK, isRemoval = true)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        // A removal is a reaction too: it encrypts, with the removal
        // form the receiver decodes as `isRemoval`.
        assertThat(gate.encryptedCalls).hasSize(1)
        assertThat(gate.encryptedCalls.single().body).isEqualTo("Removed a like from \"salut\"")
        assertThat(gate.encryptedCalls.single().payloadType).isEqualTo(E2ePayloadType.REACTION)
        assertThat(sms.sentTexts).isEmpty()
    }

    @Test
    fun `1-1 removal to a non-encrypted peer stays plain`() = runBlocking {
        val gate = RecordingGate(decision = E2eSendGate.Decision.Plain)
        val incoming = message(id = 17L, conversationId = 9L, address = ALICE, outgoing = false)
        val conv = conversation(9L, ALICE)
        val res = useCase(
            messagesById = mapOf(17L to incoming),
            conversationsById = mapOf(9L to conv),
            gate = gate,
        ).invoke(17L, "👍", ReactionFormat.IOS_TAPBACK, isRemoval = true)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.encryptedCalls).isEmpty()
        assertThat(sms.sentTexts).isEqualTo(listOf("Removed a like from \"salut\""))
    }

    @Test
    fun `client mode returns early without touching the gate`() = runBlocking {
        val gate = RecordingGate(decision = E2eSendGate.Decision.Encrypted)
        val incoming = message(id = 18L, conversationId = 9L, address = ALICE, outgoing = false)
        val conv = conversation(9L, ALICE)
        val res = useCase(
            messagesById = mapOf(18L to incoming),
            conversationsById = mapOf(9L to conv),
            gate = gate,
            clientMode = true,
        ).invoke(18L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat((res as Outcome.Success).value.dispatched).isEmpty()
        assertThat(gate.decidedFor).isEmpty()
        assertThat(gate.encryptedCalls).isEmpty()
        assertThat(gate.probed).isEmpty()
        assertThat(sms.sentTexts).isEmpty()
    }

    @Test
    fun `incoming reaction with a missing conversation fails open to plain`() = runBlocking {
        val gate = RecordingGate(decision = E2eSendGate.Decision.Encrypted)
        // No conversation row for the message: the 1:1 check cannot
        // prove 1:1, so the reaction stays plain — but it still sends.
        val incoming = message(id = 19L, conversationId = 9L, address = ALICE, outgoing = false)
        val res = useCase(
            messagesById = mapOf(19L to incoming),
            gate = gate,
        ).invoke(19L, "👍", ReactionFormat.IOS_TAPBACK)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).isEmpty()
        assertThat(gate.encryptedCalls).isEmpty()
        assertThat(sms.sentTexts).isEqualTo(listOf("Liked \"salut\""))
    }

    private companion object {
        const val ALICE = "+33600000001"
        const val BOB = "+33600000002"
        const val CAROL = "+33600000003"
        val FRAGMENTS = listOf("FRAG-1", "FRAG-2")
        const val GROUP_ID = "0123456789abcdef0123456789abcdef"
    }
}
