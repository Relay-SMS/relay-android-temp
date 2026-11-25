/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import android.content.Context
import com.relay.sms.core.crypto.AeadCipher
import com.relay.sms.core.result.Outcome
import com.relay.sms.domain.e2e.MediaFileInput
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One prepared file: the ciphertext temp file + its key-message
 * JSON. The JSON is sent (all files) before the caller dispatches
 * (all parts) — keys first, parts second.
 */
data class PreparedMediaFile(val file: File, val keyJson: String)

/**
 * The per-file encryption half of media sending (round 54,
 * extracted from [E2eMediaGateImpl.prepareOne] — no behaviour
 * change): one fresh random 32-byte AES-256-GCM key per file, via
 * [AeadCipher] (the notes attachment envelope — `[version:1]
 * [iv:12][ciphertext+tag]`, 29 bytes overhead), staged as a temp
 * file under the app's private cache (`cacheDir/media_encrypted/`,
 * inside the system-writeback sandbox), named
 * `relay-media-<id>.bin` with MIME `application/octet-stream` —
 * the id is the only link to the key message, so the two must
 * agree exactly. The [E2eMediaKeyMessage.MediaKey] JSON is built
 * here too (same shape for 1:1 and group sends — the receiver
 * never learns which path the key took).
 *
 * Shared by the 1:1 gate ([E2eMediaGateImpl]) and the group gate
 * ([E2eGroupSessionManager.prepareGroupMediaSend]): the pipeline
 * is MIME-agnostic (bytes in, envelope out), which is what makes
 * images, video and voice ride the same path. The caller owns the
 * temp files (deletes after dispatch; this class deletes on its
 * own failure paths) and prunes stale ones (older than 24h — a
 * process death between prepare and delete) on the next prepare.
 */
@Singleton
class E2eMediaFilePreparer @Inject constructor(
    private val cipher: AeadCipher,
    @ApplicationContext private val context: Context,
) {

    /**
     * One file's encryption + staging, as a value: the per-file
     * failure points (unreadable, empty, encrypt, stage) all
     * report a reason instead of returning early, so the callers
     * stay under the return-count limit.
     */
    sealed interface PrepareOneResult {
        data class Ok(val file: PreparedMediaFile) : PrepareOneResult
        data class Failed(val reason: String) : PrepareOneResult
    }

    /** The ciphertext temp-file directory (inside the private cache sandbox). */
    fun cipherDir(): File = File(context.cacheDir, CIPHER_DIR).apply { mkdirs() }

    fun prepareOne(input: MediaFileInput, dir: File): PrepareOneResult {
        val plaintext = runCatching { input.file.readBytes() }.getOrNull()
        return when {
            plaintext == null ->
                PrepareOneResult.Failed("could not read the attachment")
            plaintext.isEmpty() -> PrepareOneResult.Failed("attachment is empty")
            else -> {
                val id = E2eMediaKeyMessage.generateFileId()
                val key = E2eMediaKeyMessage.generateKey()
                when (val result = cipher.encryptRaw(key, plaintext)) {
                    is Outcome.Success -> {
                        val outFile = File(dir, E2eMediaKeyMessage.partFileName(id))
                        val staged = runCatching {
                            outFile.writeBytes(result.value)
                        }.isSuccess
                        if (!staged) {
                            PrepareOneResult.Failed("could not stage the encrypted file")
                        } else {
                            val keyJson = E2eMediaKeyMessage.encode(
                                E2eMediaKeyMessage.MediaKey(
                                    id = id,
                                    key = key,
                                    mime = input.mimeType,
                                    sizeBytes = plaintext.size.toLong(),
                                    sha256Hex = sha256Hex(plaintext),
                                    fileName = input.file.name,
                                ),
                            )
                            PrepareOneResult.Ok(PreparedMediaFile(outFile, keyJson))
                        }
                    }
                    is Outcome.Failure -> PrepareOneResult.Failed("encryption failed")
                }
            }
        }
    }

    /** Deletes staged temp files (best-effort — delete on an already-deleted file is a no-op). */
    fun deleteAll(prepared: List<PreparedMediaFile>) {
        for (item in prepared) runCatching { item.file.delete() }
    }

    /**
     * Best-effort prune of ciphertext temp files older than 24h —
     * the only leak path is a process death between prepare and
     * the caller's post-dispatch delete. Fresh files (an in-flight
     * send's parts) are never touched.
     */
    fun pruneStaleCiphertext() {
        runCatching {
            val dir = File(context.cacheDir, CIPHER_DIR)
            val cutoff = System.currentTimeMillis() - STALE_CIPHER_MS
            dir.listFiles()
                ?.filter { it.isFile && it.lastModified() < cutoff }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        /** Ciphertext temp files live here (inside the private cache sandbox). */
        const val CIPHER_DIR = "media_encrypted"

        /** A ciphertext temp file older than this is a leaked one (pruned on prepare). */
        const val STALE_CIPHER_MS = 24 * 60 * 60 * 1000L
    }
}
