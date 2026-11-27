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
import com.relay.sms.domain.model.MessageStatus
import com.relay.sms.domain.model.PhoneAddress
import com.relay.sms.domain.model.SendReport
import com.relay.sms.domain.sender.SmsSender
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The encrypted-group send path in [SendSmsUseCase] (design §13):
 * a group text encrypts when EVERY member is ENCRYPTED +
 * group-capable — the same fragment set fans out to each member as
 * individual SMS, each member's 1:1 mirror row is a HIDDEN tracking
 * sentinel (empty mirror body — round 60 (r): a row carrying the
 * group plaintext rendered as an ordinary sent 1:1 message in each
 * member's thread, although only ciphertext fragments of a group
 * message go out to them), and the group echo carries the plaintext
 * with `encrypted = 1`.
 *
 * The gate is faked here: the real gate ([com.relay.sms.e2e
 * .E2eGroupSessionManager]) is covered by its own tests in the app
 * module. What this file locks is the USE-CASE side of the
 * contract:
 *
 * - Encrypted → the same fragments go to every member (each tied
 *   to that member's row id, so delivery tracks per member), the
 *   plain sender is never called, the fragment ciphertexts — not
 *   the plaintext — land in the system sent box, the per-member
 *   rows mirror an empty body (hidden sentinels), and the
 *   activation notice is recorded.
 * - Plain → the members are probed and the send goes out exactly
 *   as it did before encrypted groups existed.
 * - RefuseOverCap → the send is refused BEFORE any row is written.
 * - Encrypt failure → the group thread gets a FAILED echo, never
 *   a plain send, and nothing goes out on the radio — except for a
 *   group REACTION, which writes NO echo (the tapback text must
 *   never paint as a failed bubble).
 * - A group reaction (REACTION payload, empty local mirror body)
 *   consults the group gate like a group text: Encrypted → the
 *   same fragments to every member, per-member rows encrypted, no
 *   group echo. A multi-recipient send WITHOUT an echo (Safety
 *   call, emergency mode) never consults the group gate.
 */
class SendSmsGroupE2eTest {

    private companion object {
        const val ALICE = "+33611111111"
        const val BOB = "+33622222222"
        const val BODY = "hello, encrypted group"
        val FRAGMENTS = listOf("FRAG-1", "FRAG-2")
        const val GROUP_ID = "0123456789abcdef0123456789abcdef"
    }

    /** A recording [E2eGroupSendGate]: the test chooses the decision and the encrypt outcome. */
    private class FakeGroupGate(
        var decision: E2eGroupSendGate.Decision = E2eGroupSendGate.Decision.Plain,
        var encryptError: AppError? = null,
    ) : E2eGroupSendGate {
        val decidedFor = mutableListOf<List<String>>()
        val encryptedFor = mutableListOf<List<String>>()
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
            files: List<com.relay.sms.domain.e2e.MediaFileInput>,
            maxTotalBytes: Long,
            date: Long,
            subId: Int?,
        ): E2eGroupSendGate.GroupMediaPrepareResult =
            E2eGroupSendGate.GroupMediaPrepareResult.Plain
    }

    /** Records the background probes (the 1:1 gate's only role on the group path). */
    private class ProbingGate : E2eSendGate {
        val probed = mutableListOf<String>()

        override suspend fun decideSend(address: String, body: String): E2eSendGate.Decision =
            E2eSendGate.Decision.Plain

        override suspend fun sendEncrypted(
            localMessageId: Long,
            address: String,
            body: String,
            date: Long,
            subId: Int?,
            requestDeliveryReport: Boolean,
            attempt: Int,
            payloadType: E2ePayloadType,
        ): Outcome<Unit> = Outcome.Success(Unit)

        override fun probeForSend(address: String, subId: Int?) {
            probed += address
        }
    }

    private fun sendGroup(
        recipients: List<String> = listOf(ALICE, BOB),
        body: String = BODY,
        groupGate: E2eGroupSendGate,
        sender: SmsSender = RecordingFragmentSender(),
        mirror: NoopMirror = NoopMirror(),
        recorder: RecordingRecorder = RecordingRecorder(),
        echoInGroup: Boolean = true,
        localMirrorBody: String? = null,
        e2eGate: E2eSendGate = E2eSendGate.NoOp,
        e2ePayloadType: E2ePayloadType = E2ePayloadType.TEXT,
    ): Outcome<SendReport> = runBlocking {
        sendSmsUseCase(
            settings = DefaultSettingsSource(),
            sender = sender,
            recorder = recorder,
            mirror = mirror,
            groupGate = groupGate,
            e2eGate = e2eGate,
        ).invoke(
            recipients = recipients.map { PhoneAddress.of(it) },
            body = body,
            echoInGroup = echoInGroup,
            localMirrorBody = localMirrorBody,
            e2ePayloadType = e2ePayloadType,
        )
    }

    // ──────────────────────────── The encrypted path ────────────────────────────

    @Test
    fun `encrypted group fans the same fragments out to every member`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = sendGroup(groupGate = gate, sender = sender, mirror = mirror, recorder = recorder)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).containsExactly(listOf(ALICE, BOB))
        assertThat(gate.encryptedFor).containsExactly(listOf(ALICE, BOB))
        // The SAME fragment set to each member, in order, each tied
        // to that member's mirror row — so delivery tracks per
        // member (the tracker is keyed by message id + attempt).
        assertThat(sender.fragments).hasSize(4)
        val aliceFragments = sender.fragments.filter { it.destination == ALICE }
        val bobFragments = sender.fragments.filter { it.destination == BOB }
        assertThat(aliceFragments.map { it.text }).containsExactly("FRAG-1", "FRAG-2").inOrder()
        assertThat(bobFragments.map { it.text }).containsExactly("FRAG-1", "FRAG-2").inOrder()
        assertThat(aliceFragments.map { it.fragmentIndex }).containsExactly(0, 1).inOrder()
        assertThat(bobFragments.map { it.fragmentIndex }).containsExactly(0, 1).inOrder()
        assertThat(aliceFragments.map { it.fragmentTotal }.toSet()).containsExactly(2)
        assertThat(bobFragments.map { it.fragmentTotal }.toSet()).containsExactly(2)
        assertThat(aliceFragments.map { it.localMessageId }.toSet()).hasSize(1)
        assertThat(bobFragments.map { it.localMessageId }.toSet()).hasSize(1)
        assertThat(aliceFragments.first().localMessageId)
            .isNotEqualTo(bobFragments.first().localMessageId)
        // The plain sender never sees the message.
        assertThat(sender.plainSends).isEmpty()
        // The fragment ciphertexts — not the plaintext — land in the
        // system sent box, two per member.
        assertThat(recorder.adressesEcrites).containsExactly(ALICE, ALICE, BOB, BOB)
    }

    @Test
    fun `encrypted group mirrors hidden tracking rows and an encrypted echo`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
        val mirror = NoopMirror()

        val outcome = sendGroup(groupGate = gate, mirror = mirror)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        // One 1:1 row per member — but a HIDDEN tracking sentinel,
        // not a message: the mirror body is empty, so the real
        // mirror stores `hidden = 1` and the row never renders in
        // the member's 1:1 thread (round 60 (r) — the plaintext
        // row painted the group text as a sent 1:1 message). The
        // row still carries the encrypted flag and no system row
        // (the plaintext never enters the system sent box), and
        // its id is what the fragment delivery intents track.
        assertThat(mirror.lignes).hasSize(2)
        assertThat(mirror.lignes.map { it.adresse }).containsExactly(ALICE, BOB)
        assertThat(mirror.lignes.all { it.encrypted }).isTrue()
        assertThat(mirror.lignes.all { it.telephonyUri == null }).isTrue()
        assertThat(mirror.lignes.all { it.corpsMiroir == "" }).isTrue()
        // The group echo carries the flag too, with the frozen
        // whole-send status — and it is the ONLY row carrying the
        // plaintext.
        assertThat(mirror.echos).hasSize(1)
        assertThat(mirror.echos.single().encrypted).isTrue()
        assertThat(mirror.echos.single().statut).isEqualTo(MessageStatus.SENT)
        assertThat(mirror.echos.single().corps).isEqualTo(BODY)
    }

    @Test
    fun `encrypted group records the activation notice on dispatch`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)

        val outcome = sendGroup(groupGate = gate)

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.activatedFor).containsExactly(listOf(ALICE, BOB))
    }

    @Test
    fun `encrypted group radio failure fails the member row and records no activation`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
        val sender = RecordingFragmentSender(outcome = radioFailure())
        val mirror = NoopMirror()

        val outcome = sendGroup(groupGate = gate, sender = sender, mirror = mirror)

        // Nothing dispatched, so the use case reports the failure.
        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(mirror.lignes).hasSize(2)
        assertThat(mirror.lignes.all { it.encrypted }).isTrue()
        // The failed member rows are hidden sentinels too — a
        // radio failure must not paint the group text into the
        // 1:1 threads either.
        assertThat(mirror.lignes.all { it.corpsMiroir == "" }).isTrue()
        assertThat(mirror.statuts.map { it.second })
            .containsExactly(MessageStatus.FAILED, MessageStatus.FAILED)
        // The echo still carries the flag, with the frozen FAILED
        // status — the user sees the failure where they sent it.
        assertThat(mirror.echos).hasSize(1)
        assertThat(mirror.echos.single().encrypted).isTrue()
        assertThat(mirror.echos.single().statut).isEqualTo(MessageStatus.FAILED)
        // No member's fragments dispatched, so no activation notice.
        assertThat(gate.activatedFor).isEmpty()
    }

    // ──────────────────────────── The plain path ────────────────────────────

    @Test
    fun `plain group sends exactly as before and probes its members`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Plain)
        val probeGate = ProbingGate()
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = sendGroup(
            groupGate = gate,
            sender = sender,
            mirror = mirror,
            recorder = recorder,
            e2eGate = probeGate,
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).containsExactly(listOf(ALICE, BOB))
        assertThat(gate.encryptedFor).isEmpty()
        assertThat(gate.activatedFor).isEmpty()
        // Every member gets a background probe: sending to the
        // thread is intent to talk, and the exchange that flips
        // the group to encrypted starts here.
        assertThat(probeGate.probed).containsExactly(ALICE, BOB)
        // Byte-identical plain behavior: system rows written, plain
        // sender called once per member, mirror rows unencrypted,
        // echo unencrypted — and the member rows mirror the
        // plaintext, as before: a real SMS does go to each member,
        // so the rows are honest (round 60 (r) scopes the hidden
        // sentinels to encrypted groups only).
        assertThat(recorder.adressesEcrites).containsExactly(ALICE, BOB)
        assertThat(sender.plainSends).containsExactly(BODY, BODY)
        assertThat(sender.fragments).isEmpty()
        assertThat(mirror.lignes).hasSize(2)
        assertThat(mirror.lignes.all { !it.encrypted }).isTrue()
        assertThat(mirror.lignes.all { it.corpsMiroir == null }).isTrue()
        assertThat(mirror.lignes.all { it.telephonyUri != null }).isTrue()
        assertThat(mirror.echos).hasSize(1)
        assertThat(mirror.echos.single().encrypted).isFalse()
        assertThat(mirror.echos.single().statut).isEqualTo(MessageStatus.SENT)
    }

    @Test
    fun `noop group gate behaves byte-identically to the pre-e2e group send`() {
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = sendGroup(
            groupGate = E2eGroupSendGate.NoOp,
            sender = sender,
            mirror = mirror,
            recorder = recorder,
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(recorder.adressesEcrites).containsExactly(ALICE, BOB)
        assertThat(sender.plainSends).containsExactly(BODY, BODY)
        assertThat(sender.fragments).isEmpty()
        assertThat(mirror.lignes).hasSize(2)
        assertThat(mirror.lignes.all { !it.encrypted }).isTrue()
        assertThat(mirror.echos).hasSize(1)
        assertThat(mirror.echos.single().encrypted).isFalse()
    }

    // ──────────────────────────── Refusal and encrypt failure ────────────────────────────

    @Test
    fun `over-cap encrypted group is refused before any row is written`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.RefuseOverCap)
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = sendGroup(groupGate = gate, sender = sender, mirror = mirror, recorder = recorder)

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        // Nothing was written, nothing was sent: the refusal happens
        // before the fan-out.
        assertThat(mirror.lignes).isEmpty()
        assertThat(mirror.echos).isEmpty()
        assertThat(mirror.statuts).isEmpty()
        assertThat(recorder.adressesEcrites).isEmpty()
        assertThat(sender.plainSends).isEmpty()
        assertThat(sender.fragments).isEmpty()
        assertThat(gate.encryptedFor).isEmpty()
        assertThat(gate.activatedFor).isEmpty()
    }

    @Test
    fun `encrypt failure writes a failed echo and never sends plain`() {
        val gate = FakeGroupGate(
            decision = E2eGroupSendGate.Decision.Encrypted,
            encryptError = AppError.Validation("no own number"),
        )
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = sendGroup(groupGate = gate, sender = sender, mirror = mirror, recorder = recorder)

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(gate.encryptedFor).containsExactly(listOf(ALICE, BOB))
        // Nothing went out to anyone: no radio, no system rows, no
        // per-recipient rows — there is nothing to track.
        assertThat(sender.plainSends).isEmpty()
        assertThat(sender.fragments).isEmpty()
        assertThat(recorder.adressesEcrites).isEmpty()
        assertThat(mirror.lignes).isEmpty()
        assertThat(mirror.statuts).isEmpty()
        // The group thread gets a FAILED echo, carrying the flag, so
        // the user sees the failure where they sent it.
        assertThat(mirror.echos).hasSize(1)
        assertThat(mirror.echos.single().encrypted).isTrue()
        assertThat(mirror.echos.single().statut).isEqualTo(MessageStatus.FAILED)
        assertThat(mirror.echos.single().corps).isEqualTo(BODY)
        assertThat(gate.activatedFor).isEmpty()
    }

    // ──────────────────────────── Non-group sends skip the gate ────────────────────────────

    @Test
    fun `multi-recipient send without an echo never consults the group gate`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()

        // A Safety call / emergency broadcast: each recipient gets a
        // private SMS, no echo — it is not a group send.
        val outcome = sendGroup(
            groupGate = gate,
            sender = sender,
            mirror = mirror,
            echoInGroup = false,
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).isEmpty()
        assertThat(gate.encryptedFor).isEmpty()
        assertThat(sender.plainSends).containsExactly(BODY, BODY)
        assertThat(sender.fragments).isEmpty()
        assertThat(mirror.lignes.all { !it.encrypted }).isTrue()
        assertThat(mirror.echos).isEmpty()
    }

    @Test
    fun `group reaction fans the same fragments to every member, with no group echo`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        // A group reaction (empty local mirror body, REACTION payload,
        // as SendReactionUseCase passes for an encrypted group).
        val outcome = sendGroup(
            groupGate = gate,
            sender = sender,
            mirror = mirror,
            recorder = recorder,
            localMirrorBody = "",
            e2ePayloadType = E2ePayloadType.REACTION,
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).containsExactly(listOf(ALICE, BOB))
        assertThat(gate.encryptedFor).containsExactly(listOf(ALICE, BOB))
        assertThat(gate.encryptedPayloadTypes)
            .containsExactly(E2ePayloadType.REACTION)
        // The SAME fragment set to each member; no plain send.
        assertThat(sender.fragments).hasSize(4)
        assertThat(sender.fragments.map { it.destination })
            .containsExactly(ALICE, ALICE, BOB, BOB)
        assertThat(sender.plainSends).isEmpty()
        // Per-member rows carry the lock — but NO group echo: a
        // reaction is not a bubble, and the tapback text must never
        // paint in the group thread. The member rows were already
        // hidden sentinels (empty mirror body) before round 60 (r)
        // and stay that way.
        assertThat(mirror.lignes).hasSize(2)
        assertThat(mirror.lignes.all { it.encrypted }).isTrue()
        assertThat(mirror.lignes.all { it.corpsMiroir == "" }).isTrue()
        assertThat(mirror.echos).isEmpty()
        assertThat(gate.activatedFor).containsExactly(listOf(ALICE, BOB))
    }

    @Test
    fun `group reaction encrypt failure writes no failed echo and never sends plain`() {
        val gate = FakeGroupGate(
            decision = E2eGroupSendGate.Decision.Encrypted,
            encryptError = AppError.Validation("no own number"),
        )
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()
        val recorder = RecordingRecorder()

        val outcome = sendGroup(
            groupGate = gate,
            sender = sender,
            mirror = mirror,
            recorder = recorder,
            localMirrorBody = "",
            e2ePayloadType = E2ePayloadType.REACTION,
        )

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(gate.encryptedFor).containsExactly(listOf(ALICE, BOB))
        assertThat(sender.plainSends).isEmpty()
        assertThat(sender.fragments).isEmpty()
        assertThat(recorder.adressesEcrites).isEmpty()
        assertThat(mirror.lignes).isEmpty()
        assertThat(mirror.statuts).isEmpty()
        // Unlike a group TEXT failure, a reaction failure writes NO
        // FAILED echo — the tapback text must never paint as a failed
        // bubble in the group thread.
        assertThat(mirror.echos).isEmpty()
        assertThat(gate.activatedFor).isEmpty()
    }

    @Test
    fun `group reaction refused over cap fails with no echo`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.RefuseOverCap)
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()

        val outcome = sendGroup(
            groupGate = gate,
            sender = sender,
            mirror = mirror,
            localMirrorBody = "",
            e2ePayloadType = E2ePayloadType.REACTION,
        )

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(gate.encryptedFor).isEmpty()
        assertThat(sender.plainSends).isEmpty()
        assertThat(sender.fragments).isEmpty()
        assertThat(mirror.lignes).isEmpty()
        assertThat(mirror.echos).isEmpty()
        assertThat(gate.activatedFor).isEmpty()
    }

    @Test
    fun `one-to-one send never consults the group gate`() {
        val gate = FakeGroupGate(decision = E2eGroupSendGate.Decision.Encrypted)
        val sender = RecordingFragmentSender()
        val mirror = NoopMirror()

        val outcome = sendGroup(
            recipients = listOf(ALICE),
            groupGate = gate,
            sender = sender,
            mirror = mirror,
            echoInGroup = false,
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.decidedFor).isEmpty()
        assertThat(gate.encryptedFor).isEmpty()
        assertThat(sender.plainSends).containsExactly(BODY)
        assertThat(mirror.lignes).hasSize(1)
        assertThat(mirror.lignes.single().encrypted).isFalse()
        assertThat(mirror.echos).isEmpty()
    }
}
