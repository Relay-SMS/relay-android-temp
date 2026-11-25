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
import com.relay.sms.domain.e2e.E2eMediaGate
import com.relay.sms.domain.e2e.E2ePayloadType
import com.relay.sms.domain.e2e.MediaFileInput
import com.relay.sms.domain.mms.MmsAttachment
import com.relay.sms.domain.model.PhoneAddress
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Round 48 — the MMS use cases' media-encryption wiring: the
 * pre-pass decides per recipient BEFORE any mirror row exists, an
 * Encrypted preparation dispatches the ciphertext parts and
 * mirrors the row encrypted, a Refuse fails the whole send, and
 * groups + blocked recipients never reach the gate.
 *
 * The gate itself is faked (its decision matrix lives in
 * `E2eMediaGateImplTest`, `:app`); what is measured here is what
 * the USE CASES do with each preparation outcome.
 */
class SendMediaMmsE2eTest {

    private companion object {
        const val ALICE = "+33611111111"
        const val BOB = "+33622222222"
    }

    /** A gate whose answer is scripted per address; records every call. */
    private class FakeMediaGate(
        private val results: Map<String, E2eMediaGate.PrepareResult> = emptyMap(),
    ) : E2eMediaGate {
        val calls = mutableListOf<String>()

        override suspend fun prepareMediaSend(
            address: String,
            files: List<MediaFileInput>,
            maxTotalBytes: Long,
            date: Long,
            subId: Int?,
        ): E2eMediaGate.PrepareResult {
            calls += address
            return results[address] ?: E2eMediaGate.PrepareResult.Plain
        }
    }

    /** A group gate whose media answer is scripted; records every media call. */
    private class FakeGroupMediaGate(
        var mediaResult: E2eGroupSendGate.GroupMediaPrepareResult =
            E2eGroupSendGate.GroupMediaPrepareResult.Plain,
    ) : E2eGroupSendGate {
        val mediaCalls = mutableListOf<List<String>>()

        override suspend fun decideGroupSend(
            memberAddresses: List<String>,
            body: String,
        ): E2eGroupSendGate.Decision = E2eGroupSendGate.Decision.Plain

        override suspend fun encryptGroupMessage(
            memberAddresses: List<String>,
            body: String,
            subId: Int?,
            date: Long,
            payloadType: E2ePayloadType,
        ): Outcome<E2eGroupSendGate.EncryptedGroupMessage> =
            Outcome.Failure(AppError.Validation("unused in media tests"))

        override suspend fun recordGroupActivated(memberAddresses: List<String>, now: Long) = Unit

        override suspend fun decideGroupMediaSend(
            memberAddresses: List<String>,
        ): E2eGroupSendGate.Decision = E2eGroupSendGate.Decision.Plain

        override suspend fun prepareGroupMediaSend(
            memberAddresses: List<String>,
            files: List<MediaFileInput>,
            maxTotalBytes: Long,
            date: Long,
            subId: Int?,
        ): E2eGroupSendGate.GroupMediaPrepareResult {
            mediaCalls += memberAddresses
            return mediaResult
        }
    }

    private fun fichier(dir: File, nom: String): File =
        File(dir, nom).apply { writeBytes(ByteArray(64) { 1 }) }

    /** A ciphertext part exactly as the real gate builds one. */
    private fun chiffre(dir: File, id: String): MmsAttachment {
        val file = File(dir, "relay-media-$id.bin").apply { writeBytes(ByteArray(93) { 2 }) }
        return MmsAttachment(file, "application/octet-stream", MmsAttachment.Kind.OTHER)
    }

    private fun mediaUseCase(
        mirror: NoopMirror,
        dispatcher: RecordingMmsDispatcher,
        gate: E2eMediaGate,
        blocked: Set<String> = emptySet(),
        groupGate: E2eGroupSendGate = E2eGroupSendGate.NoOp,
    ) = SendMediaMmsUseCase(
        defaultAppManager = alwaysDefaultSmsApp(),
        blockedRepo = BlockedList(blocked),
        mirror = mirror,
        sender = dispatcher,
        settings = DefaultSettingsSource(),
        attachmentStore = PassthroughAttachmentStore(),
        mediaGate = gate,
        groupGate = groupGate,
    )

    private fun voiceUseCase(
        mirror: NoopMirror,
        dispatcher: RecordingMmsDispatcher,
        gate: E2eMediaGate,
        blocked: Set<String> = emptySet(),
        groupGate: E2eGroupSendGate = E2eGroupSendGate.NoOp,
    ) = SendVoiceMmsUseCase(
        defaultAppManager = alwaysDefaultSmsApp(),
        blockedRepo = BlockedList(blocked),
        mirror = mirror,
        sender = dispatcher,
        settings = DefaultSettingsSource(),
        attachmentStore = PassthroughAttachmentStore(),
        ownNumberEnsurer = FakeOwnNumberEnsurer(),
        mediaGate = gate,
        groupGate = groupGate,
    )

    // ───────────────────────────── Voie MÉDIA ─────────────────────────────

    @Test
    fun `encrypted preparation dispatches the ciphertext parts and mirrors encrypted`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val part = chiffre(dir, "0123456789abcdef")
        val gate = FakeMediaGate(
            mapOf(ALICE to E2eMediaGate.PrepareResult.Encrypted(listOf(part))),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
                textBody = "regarde",
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(dispatcher.dernierPiecesJointes).containsExactly(part)
        assertThat(dispatcher.dernierPiecesJointes!![0].mimeType).isEqualTo("application/octet-stream")
        assertThat(dispatcher.dernierPiecesJointes!![0].file.name).isEqualTo("relay-media-0123456789abcdef.bin")
        // The mirror row keeps the PLAINTEXT sender copy, flagged encrypted.
        assertThat(mirror.lignes).hasSize(1)
        assertThat(mirror.lignes[0].encrypted).isTrue()
        assertThat(mirror.lignesMms[0].encrypted).isTrue()
    }

    @Test
    fun `the ciphertext temp file is deleted after dispatch`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val part = chiffre(dir, "0123456789abcdef")
        val gate = FakeMediaGate(
            mapOf(ALICE to E2eMediaGate.PrepareResult.Encrypted(listOf(part))),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate)

        runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
            )
        }

        assertThat(part.file.exists()).isFalse()
    }

    @Test
    fun `plain preparation dispatches the originals and mirrors plain`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate()
        val uc = mediaUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(dispatcher.dernierPiecesJointes).hasSize(1)
        assertThat(dispatcher.dernierPiecesJointes!![0].mimeType).isEqualTo("image/jpeg")
        assertThat(dispatcher.dernierPiecesJointes!![0].file.name).isEqualTo("photo.jpg")
        assertThat(mirror.lignes[0].encrypted).isFalse()
    }

    @Test
    fun `a fallback preparation dispatches the originals plain`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate(
            mapOf(ALICE to E2eMediaGate.PrepareResult.FallbackToPlain),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(dispatcher.dernierPiecesJointes!![0].mimeType).isEqualTo("image/jpeg")
        assertThat(mirror.lignes[0].encrypted).isFalse()
    }

    @Test
    fun `a refusal fails the whole send before anything is mirrored or dispatched`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate(
            mapOf(ALICE to E2eMediaGate.PrepareResult.Refuse("encrypted attachments exceed the MMS size cap")),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
            )
        }

        assertThat(out).isInstanceOf(Outcome.Failure::class.java)
        assertThat((out as Outcome.Failure).error).isInstanceOf(AppError.Validation::class.java)
        assertThat(dispatcher.appels).isEqualTo(0)
        assertThat(mirror.lignes).isEmpty()
    }

    @Test
    fun `a group MMS never reaches the gate and goes plain`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate(
            mapOf(
                ALICE to E2eMediaGate.PrepareResult.Encrypted(
                    listOf(chiffre(dir, "0123456789abcdef")),
                ),
                BOB to E2eMediaGate.PrepareResult.Encrypted(
                    listOf(chiffre(dir, "fedcba9876543210")),
                ),
            ),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
                groupMms = true,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.calls).isEmpty()
        assertThat(dispatcher.dernierPiecesJointes!![0].mimeType).isEqualTo("image/jpeg")
    }

    @Test
    fun `a multi-recipient send never reaches the pairwise gate`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate()
        val uc = mediaUseCase(mirror, dispatcher, gate, blocked = setOf(BOB))

        runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
            )
        }

        // Round 54 (echo-path fix) — the 1:1 gate is consulted only
        // for a UNIQUE recipient: a multi-recipient send (the group
        // echo path, group-MMS setting OFF) never gets a pairwise
        // decision, for ANY recipient — otherwise it would go out
        // encrypted per recipient while the group echo claims the
        // opposite. The blocked recipient's row exists, in failure —
        // and plain, like the unblocked recipient's.
        assertThat(gate.calls).isEmpty()
        assertThat(mirror.lignes.map { it.adresse }).containsExactly(ALICE, BOB)
        assertThat(mirror.lignes.all { !it.encrypted }).isTrue()
        assertThat(mirror.lignes.first { it.adresse == BOB }.encrypted).isFalse()
    }

    @Test
    fun `a broadcast sends plain to every recipient, never pairwise-encrypted`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val part = chiffre(dir, "0123456789abcdef")
        val gate = FakeMediaGate(
            mapOf(ALICE to E2eMediaGate.PrepareResult.Encrypted(listOf(part))),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        // Round 54 (echo-path fix) — there is no per-recipient 1:1
        // decision in a multi-recipient send anymore: the gate is
        // never consulted, so even a recipient the gate WOULD have
        // encrypted goes out plain. (A single-recipient send still
        // encrypts — covered by the 1:1 tests below.)
        assertThat(gate.calls).isEmpty()
        assertThat(mirror.lignes.first { it.adresse == ALICE }.encrypted).isFalse()
        assertThat(mirror.lignes.first { it.adresse == BOB }.encrypted).isFalse()
    }

    // ───────────────────────────── Voie VOCALE ─────────────────────────────

    @Test
    fun `encrypted voice dispatches through sendMediaMms with the ciphertext part`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val part = chiffre(dir, "0123456789abcdef")
        val gate = FakeMediaGate(
            mapOf(ALICE to E2eMediaGate.PrepareResult.Encrypted(listOf(part))),
        )
        val uc = voiceUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                audioFile = fichier(dir, "voice.m4a"),
                mimeType = "audio/mp4",
                durationMs = 5_000L,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        // Encrypted voice goes through sendMediaMms (sendVoiceMms
        // forces the AUDIO kind in the SMIL; a ciphertext part is
        // an octet-stream OTHER).
        assertThat(dispatcher.appelsMedia).isEqualTo(1)
        assertThat(dispatcher.appelsVoix).isEqualTo(0)
        assertThat(dispatcher.dernierPiecesJointes).containsExactly(part)
        assertThat(mirror.lignesMms).hasSize(1)
        assertThat(mirror.lignesMms[0].encrypted).isTrue()
        assertThat(part.file.exists()).isFalse()
    }

    @Test
    fun `plain voice dispatches through sendVoiceMms and mirrors plain`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate()
        val uc = voiceUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE)),
                audioFile = fichier(dir, "voice.m4a"),
                mimeType = "audio/mp4",
                durationMs = 5_000L,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(dispatcher.appelsVoix).isEqualTo(1)
        assertThat(dispatcher.appelsMedia).isEqualTo(0)
        assertThat(mirror.lignesMms[0].encrypted).isFalse()
    }

    @Test
    fun `a group voice MMS never reaches the gate`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate(
            mapOf(
                ALICE to E2eMediaGate.PrepareResult.Encrypted(
                    listOf(chiffre(dir, "0123456789abcdef")),
                ),
            ),
        )
        val uc = voiceUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                audioFile = fichier(dir, "voice.m4a"),
                mimeType = "audio/mp4",
                durationMs = 5_000L,
                groupMms = true,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.calls).isEmpty()
        assertThat(dispatcher.appelsVoix).isEqualTo(1)
    }

    // ───────────────────────────── Voie GROUPE chiffrée (round 54) ─────────────────────────────

    @Test
    fun `group media encrypted dispatches one ciphertext PDU and mirrors encrypted`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate()
        val groupGate = FakeGroupMediaGate(
            E2eGroupSendGate.GroupMediaPrepareResult.Encrypted(
                listOf(chiffre(dir, "0123456789abcdef")),
            ),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate, groupGate = groupGate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
                groupMms = true,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(groupGate.mediaCalls).containsExactly(listOf(ALICE, BOB))
        // One PDU to everyone, carrying the ciphertext part —
        // never the 1:1 gate, never one PDU per member.
        assertThat(gate.calls).isEmpty()
        assertThat(dispatcher.appels).isEqualTo(1)
        assertThat(dispatcher.destinatairesTentes).containsExactly(ALICE, BOB)
        assertThat(dispatcher.dernierPiecesJointes).hasSize(1)
        assertThat(dispatcher.dernierPiecesJointes!![0].file.name)
            .isEqualTo("relay-media-0123456789abcdef.bin")
        assertThat(dispatcher.dernierPiecesJointes!![0].mimeType)
            .isEqualTo("application/octet-stream")
        assertThat(mirror.lignes).hasSize(1)
        assertThat(mirror.lignes.single().encrypted).isTrue()
    }

    @Test
    fun `group media over cap refuses and dispatches nothing`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate()
        val groupGate = FakeGroupMediaGate(
            E2eGroupSendGate.GroupMediaPrepareResult.Refuse("media too large to send encrypted"),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate, groupGate = groupGate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
                groupMms = true,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Failure::class.java)
        assertThat((out as Outcome.Failure).error).isInstanceOf(AppError.Validation::class.java)
        assertThat(dispatcher.appels).isEqualTo(0)
        assertThat(dispatcher.dernierPiecesJointes).isNull()
        assertThat(mirror.lignes).isEmpty()
    }

    @Test
    fun `group media key-send failure refuses instead of falling back to plain`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate()
        val groupGate = FakeGroupMediaGate(
            E2eGroupSendGate.GroupMediaPrepareResult.Refuse("could not send the media key"),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate, groupGate = groupGate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
                groupMms = true,
            )
        }

        // No silent plain fallback: the send fails visibly, like
        // the group text gate — the plaintext photo never goes out.
        assertThat(out).isInstanceOf(Outcome.Failure::class.java)
        assertThat(dispatcher.appels).isEqualTo(0)
        assertThat(dispatcher.dernierPiecesJointes).isNull()
        assertThat(mirror.lignes).isEmpty()
    }

    @Test
    fun `group media plain dispatches the originals and mirrors plain`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate()
        val groupGate = FakeGroupMediaGate(
            E2eGroupSendGate.GroupMediaPrepareResult.Plain,
        )
        val uc = mediaUseCase(mirror, dispatcher, gate, groupGate = groupGate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
                groupMms = true,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(dispatcher.appels).isEqualTo(1)
        assertThat(dispatcher.dernierPiecesJointes!![0].mimeType).isEqualTo("image/jpeg")
        assertThat(mirror.lignes).hasSize(1)
        assertThat(mirror.lignes.single().encrypted).isFalse()
    }

    @Test
    fun `group voice encrypted rides the media path with ciphertext parts`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate()
        val groupGate = FakeGroupMediaGate(
            E2eGroupSendGate.GroupMediaPrepareResult.Encrypted(
                listOf(chiffre(dir, "0123456789abcdef")),
            ),
        )
        val uc = voiceUseCase(mirror, dispatcher, gate, groupGate = groupGate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                audioFile = fichier(dir, "voice.m4a"),
                mimeType = "audio/mp4",
                durationMs = 5_000L,
                groupMms = true,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        assertThat(gate.calls).isEmpty()
        // Ciphertext is an octet-stream part: it rides sendMediaMms,
        // not the voice path.
        assertThat(dispatcher.appelsMedia).isEqualTo(1)
        assertThat(dispatcher.appelsVoix).isEqualTo(0)
        assertThat(dispatcher.dernierPiecesJointes!![0].mimeType)
            .isEqualTo("application/octet-stream")
        assertThat(mirror.lignes).hasSize(1)
        assertThat(mirror.lignes.single().encrypted).isTrue()
    }

    @Test
    fun `echo path never consults the 1 to 1 gate for a multi-recipient send`(@TempDir dir: File) {
        val mirror = NoopMirror()
        val dispatcher = RecordingMmsDispatcher()
        val gate = FakeMediaGate(
            mapOf(
                ALICE to E2eMediaGate.PrepareResult.Encrypted(
                    listOf(chiffre(dir, "0123456789abcdef")),
                ),
                BOB to E2eMediaGate.PrepareResult.Encrypted(
                    listOf(chiffre(dir, "fedcba9876543210")),
                ),
            ),
        )
        val uc = mediaUseCase(mirror, dispatcher, gate)

        val out = runBlocking {
            uc.invoke(
                recipients = listOf(PhoneAddress.of(ALICE), PhoneAddress.of(BOB)),
                attachments = listOf(
                    SendMediaMmsUseCase.AttachmentPayload(fichier(dir, "photo.jpg"), "image/jpeg"),
                ),
                echoInGroup = true,
            )
        }

        assertThat(out).isInstanceOf(Outcome.Success::class.java)
        // Group MMS is OFF in DefaultSettingsSource, so this rides
        // the per-recipient echo path — but the 1:1 gate is skipped
        // for multi-recipient sends: no member gets a pairwise-
        // encrypted part, and the echo stays plain.
        assertThat(gate.calls).isEmpty()
        assertThat(dispatcher.appels).isEqualTo(2)
        assertThat(mirror.echos).hasSize(1)
        assertThat(mirror.echos.single().encrypted).isFalse()
    }
}
