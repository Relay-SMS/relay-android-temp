/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.system.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.relay.sms.di.IoDispatcher
import com.relay.sms.domain.repository.ContactPhotoWriteResult
import com.relay.sms.domain.repository.ContactRepository
import com.relay.sms.domain.repository.ConversationRepository
import com.relay.sms.sync.SyncProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 57 — contact-photo write-back, the one helper both photo
 * directions share (the desktop's `set_contact_photo` frame already
 * writes through [ContactRepository.setContactPhoto]; a photo picked
 * locally on the phone never did — it only became the conversation's
 * `avatarUri` in Relay's own database, so deleting the app lost it).
 *
 * Two entry points, one rule each:
 * - [writeBackPhoto]: write one picked photo to the matching system
 *   contact, right after the Relay-local save. The caller (the
 *   thread ViewModel) asks for WRITE_CONTACTS when this answers
 *   [ContactPhotoWriteResult.NoPermission].
 * - [writeBackPendingPhotos]: the scan the contacts ContentObserver
 *   triggers — an unsaved number that later becomes a saved system
 *   contact gets Relay's stored avatar then. NO-CLOBBER: Relay's
 *   photo is written ONLY when the system contact has NO photo yet;
 *   a contact that already has one is left untouched, and a number
 *   with no system contact is skipped (Relay never creates a system
 *   contact for a photo set).
 *
 * In client mode the repository refuses the write
 * ([ContactPhotoWriteResult.Failed] — deferred to the V2
 * `set_contact_photo` frame, like the sync path), so both entries
 * are safe no-ops on a linked device. Neither entry ever throws
 * except on coroutine cancellation.
 */
@Singleton
class ContactPhotoWriteBack @Inject constructor(
    private val conversations: ConversationRepository,
    private val contacts: ContactRepository,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * The picked-photo bytes, as a seam: the real loader decodes
     * the `content://` URI (BitmapFactory throws Stub! on plain
     * JVM, so tests pass a fake that returns fixed bytes instead).
     */
    private var photoBytesLoader: (String) -> ByteArray? = ::loadPhotoBytes

    internal constructor(
        conversations: ConversationRepository,
        contacts: ContactRepository,
        context: Context,
        io: CoroutineDispatcher,
        photoBytesLoader: (String) -> ByteArray?,
    ) : this(conversations, contacts, context, io) {
        this.photoBytesLoader = photoBytesLoader
    }

    /**
     * Scan Relay's stored avatars (1:1 conversations with an
     * `avatarUri`, archived and vault included) and write each one
     * to its system contact — but only when that contact exists
     * now AND has no photo yet. A contact saved after Relay's
     * photo was picked gets the photo then; anything already
     * covered (no system contact, no Relay photo, system photo
     * present) is a no-op. Best-effort per number: one failure
     * never stops the scan, and a missing WRITE_CONTACTS grant
     * skips the whole scan (a background check cannot ask).
     */
    suspend fun writeBackPendingPhotos() {
        if (!hasWritePermission()) return
        val candidates = try {
            pendingCandidates()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("ContactPhotoWriteBack: pending scan failed (%s)", e.javaClass.simpleName)
            return
        }
        for ((address, avatarUri) in candidates) {
            writeBackIfPhotoMissing(address, avatarUri)
        }
    }

    /**
     * Write one picked photo to the matching system contact:
     * [ContactPhotoWriteResult.NoPermission] when WRITE_CONTACTS
     * is not granted, [ContactPhotoWriteResult.Failed] when the
     * picked image cannot be read, otherwise the repository's own
     * verdict ([ContactPhotoWriteResult.Ok] /
     * [ContactPhotoWriteResult.NotFound] — no system contact for
     * the number, Relay-local only). The Relay-local copy is the
     * caller's business and is untouched here.
     */
    suspend fun writeBackPhoto(address: String, avatarUri: String): ContactPhotoWriteResult {
        if (!hasWritePermission()) return ContactPhotoWriteResult.NoPermission
        val bytes = withContext(io) {
            runCatching { photoBytesLoader(avatarUri) }.getOrNull()
        } ?: return ContactPhotoWriteResult.Failed
        return try {
            contacts.setContactPhoto(address, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Timber.d("ContactPhotoWriteBack: photo write denied (%s)", e.javaClass.simpleName)
            ContactPhotoWriteResult.NoPermission
        } catch (e: Exception) {
            Timber.d("ContactPhotoWriteBack: photo write failed (%s)", e.javaClass.simpleName)
            ContactPhotoWriteResult.Failed
        }
    }

    /** One scan candidate per 1:1 conversation holding a Relay avatar: raw address to avatar URI. */
    private suspend fun pendingCandidates(): List<Pair<String, String>> {
        val all = withContext(io) {
            val mine = conversations.observeAll(includeArchived = true).first()
            val vault = conversations.observeVault().first()
            (mine + vault).distinctBy { it.id }
        }
        return all.mapNotNull { conversation ->
            if (conversation.isGroup) return@mapNotNull null
            val avatarUri = conversation.avatarUri ?: return@mapNotNull null
            val address = conversation.firstAddress?.raw ?: return@mapNotNull null
            address to avatarUri
        }
    }

    /**
     * One scan row: resolve the system contact with the same
     * strict lookup the UI uses, and write Relay's photo only
     * when the contact exists AND has no photo yet (neither
     * thumbnail nor full-size). A lookup that throws (permission
     * revoked mid-scan) skips the row, never the scan.
     */
    private suspend fun writeBackIfPhotoMissing(address: String, avatarUri: String) {
        try {
            val contact = contacts.lookupByPhone(address) ?: return
            if (contact.photoUri != null || contact.photoFullUri != null) return
            writeBackPhoto(address, avatarUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("ContactPhotoWriteBack: pending row failed (%s)", e.javaClass.simpleName)
        }
    }

    /**
     * The direct framework call, NOT `ContextCompat.checkSelfPermission`:
     * core 1.19's version calls `TextUtils.equals` on its pre-33 path,
     * and both are stubs that throw on plain-JVM unit tests — the same
     * trap as `RepositorySyncMessageSource.contactsReadiness`.
     */
    private fun hasWritePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The picked image as 512 px JPEG bytes (the desktop's
     * `set_contact_photo` size — the same size the phone renders
     * for a contact photo, small enough for the PHOTO row). A
     * photo that already fits the cap is returned as-is (no
     * re-encode); a larger one is center-square-cropped and
     * downscaled. Null when the URI cannot be read, is not an
     * image, or stays over the cap after downscaling.
     *
     * Every BitmapFactory call is guarded: the constructor itself
     * throws Stub! on plain JVM, and a 12 Mpx decode can OOM —
     * both are "no bytes", never a crash.
     */
    private fun loadPhotoBytes(avatarUri: String): ByteArray? {
        val raw = runCatching {
            context.contentResolver.openInputStream(Uri.parse(avatarUri))?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (raw.isEmpty()) return null
        return preparePhotoBytes(raw) ?: raw.takeIf { it.size <= MAX_PHOTO_BYTES }
    }

    /** Center-square-crop + downscale to 512 px JPEG, stepping the quality down until it fits the cap. */
    private fun preparePhotoBytes(raw: ByteArray): ByteArray? {
        val decoded = runCatching { decodeNear(raw, PHOTO_SIZE_PX) }.getOrNull() ?: return null
        try {
            val size = minOf(decoded.width, decoded.height)
            val cropped = runCatching {
                val square = Bitmap.createBitmap(
                    decoded,
                    (decoded.width - size) / 2,
                    (decoded.height - size) / 2,
                    size,
                    size,
                )
                if (square.width == PHOTO_SIZE_PX) {
                    square
                } else {
                    Bitmap.createScaledBitmap(square, PHOTO_SIZE_PX, PHOTO_SIZE_PX, true)
                        .also { if (it !== square) square.recycle() }
                }
            }.getOrNull() ?: return null
            try {
                for (quality in intArrayOf(85, 75, 60, 45)) {
                    val out = ByteArrayOutputStream()
                    val ok = runCatching {
                        cropped.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    }.getOrDefault(false)
                    if (ok && out.size() in 1..MAX_PHOTO_BYTES) return out.toByteArray()
                }
                return null
            } finally {
                if (cropped !== decoded) cropped.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    /**
     * Decode [raw] near [maxDim] (two passes: bounds first, then a
     * power-of-two sample so a 12 Mpx pick never allocates its full
     * 48 MB ARGB bitmap). Null when the bytes are not an image.
     */
    private fun decodeNear(raw: ByteArray, maxDim: Int): Bitmap? {
        val bounds = runCatching {
            BitmapFactory.Options().apply { inJustDecodeBounds = true }
        }.getOrNull()
        if (bounds != null) {
            runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds) }
        }
        val width = bounds?.outWidth ?: 0
        val height = bounds?.outHeight ?: 0
        if (width <= 0 || height <= 0) return null
        val opts = runCatching {
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(width, height, maxDim)
            }
        }.getOrNull()
        val bitmap = opts?.let {
            runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size, it) }.getOrNull()
        } ?: return null
        if (bitmap.width <= maxDim && bitmap.height <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        return runCatching {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt(),
                (bitmap.height * ratio).toInt(),
                true,
            )
        }.getOrElse {
            bitmap.recycle()
            null
        }?.also { if (it !== bitmap) bitmap.recycle() }
    }

    /** Power of two, the only value BitmapFactory really honours: stop once the decoded size drops under [maxDim]. */
    private fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    private companion object {
        /** The contact photo's size: a 512 px square JPEG, like the desktop's prepared photo. */
        const val PHOTO_SIZE_PX = 512

        /** The largest photo the phone writes, in bytes — the sync protocol's `set_contact_photo` cap. */
        const val MAX_PHOTO_BYTES = SyncProtocol.MAX_CONTACT_PHOTO_BYTES
    }
}
