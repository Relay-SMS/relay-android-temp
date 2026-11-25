/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import com.relay.sms.core.result.Outcome
import com.relay.sms.domain.e2e.E2eMediaGate
import com.relay.sms.domain.e2e.MediaFileInput
import com.relay.sms.domain.mms.MmsAttachment
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.relay.sms.data.local.db.entity.E2ePeerState as StoredPeerState

/**
 * The [E2eMediaGate] implementation (round 48, design §3): decide +
 * encrypt + key-send for one 1:1 MMS, ahead of the PDU build.
 *
 * - **Decide:** ENCRYPTED + media-capable → encrypt; anything else →
 *   plain. A state-read failure fails OPEN to Plain (the mirror write
 *   that follows would fail on the same broken database anyway — the
 *   same rule as [E2eSendManager.decideSend]). A non-ENCRYPTED peer
 *   also triggers a fire-and-forget probe, like the SMS send path:
 *   the key exchange starts now, so the NEXT media send can encrypt.
 * - **Encrypt:** one fresh random 32-byte AES-256-GCM key per file,
 *   via [E2eMediaFilePreparer] (the notes attachment envelope —
 *   `[version:1][iv:12][ciphertext+tag]`, 29 bytes overhead, no new
 *   crypto). The ciphertext lands in a temp file under the app's
 *   private cache (`cacheDir/media_encrypted/`, inside the
 *   system-writeback sandbox), named `relay-media-<id>.bin`
 *   with MIME `application/octet-stream` — the id is the only link
 *   to the key message, so the two must agree exactly.
 * - **Key messages FIRST:** one MEDIA_KEY message per file, over
 *   the encrypted text channel ([E2eSendManager.sendMediaKey]),
 *   sent BEFORE the caller dispatches the MMS — the receiver may
 *   need the key before the part lands, and a key that goes out
 *   after its part is a race. Only after every key is accepted
 *   does the caller dispatch the parts.
 * - **Cap:** the CIPHERTEXT total must fit the caller's cap (the
 *   use case's 300 KB — the envelope adds 29 bytes per file, so
 *   the check runs on the encrypted sizes). Over the cap, the
 *   send is REFUSED loudly — never silently dispatched plain.
 * - **Never silently fail:** any encryption or key-send failure
 *   falls back to plain MMS ([E2eMediaGate.PrepareResult.FallbackToPlain])
 *   and records it in the error log (`e2e-media-fallback`) —
 *   the send is never dropped, and the fallback is never silent.
 *
 * Round 54 — the capability fix (design §8.4): the `!capable`
 * branch now re-declares our capability ([E2eMediaCapabilityManager.announceIfNeeded])
 * before returning Plain, mirroring the probe in the `!ENCRYPTED`
 * branch above it. The current send stays plain (never block the
 * user on a first media send); the peer's announce-back on
 * receipt makes the NEXT media send encrypt. This closes the
 * bundle-sender deferral hole, where a peer's declaration could
 * lag the text session and media went out plain with no log.
 *
 * Temp-file ownership: the caller deletes the returned ciphertext
 * files after dispatch (the PDU builder reads the bytes eagerly,
 * so the files are dead the moment the dispatcher returns). This
 * class deletes its own temp files on every failure path, and
 * prunes stale ones (older than 24h — a process death between
 * prepare and delete) on the next prepare.
 */
@Singleton
class E2eMediaGateImpl @Inject constructor(
    private val peers: E2ePeerRepository,
    private val sendManager: E2eSendManager,
    private val probeManager: E2eProbeManager,
    private val preparer: E2eMediaFilePreparer,
    private val mediaCapability: E2eMediaCapabilityManager,
    private val failureRecorder: E2eFailureRecorder,
) : E2eMediaGate {

    override suspend fun prepareMediaSend(
        address: String,
        files: List<MediaFileInput>,
        maxTotalBytes: Long,
        date: Long,
        subId: Int?,
    ): E2eMediaGate.PrepareResult {
        if (files.isEmpty()) return E2eMediaGate.PrepareResult.Plain
        val state = runCatching { peers.state(address) }.getOrNull()
        val capable = state == StoredPeerState.ENCRYPTED &&
            runCatching { peers.isMediaCapable(address) }.getOrDefault(false)
        return when {
            state == null -> E2eMediaGate.PrepareResult.Plain
            state != StoredPeerState.ENCRYPTED -> {
                // Like the SMS send path: a 1:1 send to a peer that
                // is not ENCRYPTED yet starts the key exchange in
                // the background, so the next media send can
                // encrypt.
                probeManager.probeForSend(address, subId)
                E2eMediaGate.PrepareResult.Plain
            }
            !capable -> {
                // Round 54 — the media send itself is a
                // capability-declaration trigger (design §8.4):
                // re-declare OUR capability now, mirroring the
                // probe in the !ENCRYPTED branch above. The peer's
                // announce-back on receipt sends THEIR declaration
                // back, so the NEXT media send encrypts. The
                // current send stays plain — never block the user
                // on a first media send.
                runCatching { mediaCapability.announceIfNeeded(address, subId) }
                E2eMediaGate.PrepareResult.Plain
            }
            else -> encryptAll(address, files, maxTotalBytes, date, subId)
        }
    }

    private suspend fun encryptAll(
        address: String,
        files: List<MediaFileInput>,
        maxTotalBytes: Long,
        date: Long,
        subId: Int?,
    ): E2eMediaGate.PrepareResult {
        preparer.pruneStaleCiphertext()
        val dir = preparer.cipherDir()
        val prepared = mutableListOf<PreparedMediaFile>()
        return try {
            for (input in files) {
                when (val one = preparer.prepareOne(input, dir)) {
                    is E2eMediaFilePreparer.PrepareOneResult.Ok -> prepared += one.file
                    is E2eMediaFilePreparer.PrepareOneResult.Failed -> return fallback(prepared, one.reason)
                }
            }
            // The cap runs on the CIPHERTEXT total (the envelope
            // adds 29 bytes per file) — and BEFORE any key message
            // goes out, so a refused send leaves no orphan keys.
            val total = prepared.sumOf { it.file.length() }
            if (total > maxTotalBytes) {
                preparer.deleteAll(prepared)
                return E2eMediaGate.PrepareResult.Refuse(
                    "encrypted attachments exceed the MMS size cap ($total bytes)",
                )
            }
            // Keys FIRST: every key message accepted before the
            // caller dispatches any part.
            for (item in prepared) {
                val sent = sendManager.sendMediaKey(address, item.keyJson, date, subId)
                if (sent is Outcome.Failure) {
                    return fallback(prepared, "the media key could not be sent")
                }
            }
            E2eMediaGate.PrepareResult.Encrypted(
                prepared.map {
                    MmsAttachment(
                        file = it.file,
                        mimeType = E2eMediaKeyMessage.MEDIA_PART_MIME,
                        kind = MmsAttachment.Kind.OTHER,
                    )
                },
            )
        } catch (ce: CancellationException) {
            preparer.deleteAll(prepared)
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "E2E: unexpected media encryption failure")
            fallback(prepared, "unexpected media encryption failure")
        }
    }

    /**
     * Fall back to plain MMS: delete the temp files staged so far,
     * record the failure in the error log (fixed message — no
     * addresses, no filenames), and tell the caller to dispatch
     * the originals plain. Never throws.
     */
    private fun fallback(prepared: List<PreparedMediaFile>, reason: String): E2eMediaGate.PrepareResult {
        preparer.deleteAll(prepared)
        failureRecorder.recordEvent(
            MEDIA_FALLBACK_TAG,
            "Encrypted media failed ($reason); sent plain instead.",
        )
        return E2eMediaGate.PrepareResult.FallbackToPlain
    }

    companion object {
        /** Error-log tag for plain fallbacks (round 48). */
        const val MEDIA_FALLBACK_TAG = "e2e-media-fallback"
    }
}
