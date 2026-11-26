/*
 * Copyright 2026 Relay Messaging (modifications). Relay's modifications
 * to this file are proprietary to Relay Messaging and are not licensed
 * under the Apache License 2.0. See LICENSE-RELAY for the terms. The
 * original work is copyright its respective holders and is licensed
 * under the Apache License 2.0. See LICENSE.
 */

package com.relay.sms.domain.repository

import com.relay.sms.domain.model.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.InputStream

interface ContactRepository {
    suspend fun lookupByPhone(rawPhone: String): Contact?

    /**
     * Contact-name live refresh — the provider-change version
     * ([com.relay.sms.data.contacts.ContactsReader.changes]):
     * 0 at start, +1 on every cache invalidation (a provider
     * change the `MainApplication` ContentObserver saw, or a
     * photo write). The list and thread ViewModels collect it
     * (dropping the initial value) and re-run the resolutions
     * no Room row covers — list photos, group sender names —
     * when it moves. Default `flowOf(0L)`: fakes and paths
     * without a live provider never emit a change, so existing
     * callers keep compiling untouched.
     */
    fun observeContactChanges(): Flow<Long> = flowOf(0L)

    /**
     * Round 44 — whether READ_CONTACTS is granted right now. The
     * stale-display-name heal ([ConversationRepository
     * .clearStaleDisplayName]) only clears when a lookup the
     * provider could actually answer came back empty — a denied
     * permission also reads as "no contact", and clearing on that
     * would erase correct names. Default `false` (fail closed):
     * fakes and paths that cannot verify never clear. Backed by
     * the real provider, implementations override it.
     */
    suspend fun hasContactsPermission(): Boolean = false

    suspend fun listAll(): List<Contact>

    /**
     * Slice 5 — [listAll] plus the photo-staleness version
     * ([Contact.photoUpdatedAt]) per contact, from the Contacts
     * table. The contact-sync snapshot source; the compose picker
     * keeps using [listAll].
     */
    suspend fun listAllDetailed(): List<Contact>

    /**
     * Slice 5 — opens the photo stream for the contact behind
     * [address] (same `PhoneLookup` the UI uses): the FULL-SIZE
     * photo first, the thumbnail as the fallback when the full
     * photo is missing or unreadable (round 43 — the desktop
     * shows the avatar at 96dp+, where the thumbnail pixelates).
     * Null when there is no contact, no photo, or an unreadable
     * stream. The caller closes the stream.
     */
    suspend fun openPhotoStream(address: String): InputStream?

    /**
     * Round 3 — writes a contact photo picked on the desktop: the
     * prepared 512 px JPEG for [address], resolved with the same
     * `PhoneLookup` the UI uses. [ContactPhotoWriteResult.NotFound]
     * when no contact exists for the number — the phone never
     * creates a contact for a photo set.
     * [ContactPhotoWriteResult.NoPermission] when WRITE_CONTACTS is
     * not granted (or was revoked mid-write).
     */
    suspend fun setContactPhoto(address: String, photoBytes: ByteArray): ContactPhotoWriteResult
}

/** Round 3 — what [ContactRepository.setContactPhoto] did. */
sealed interface ContactPhotoWriteResult {
    /** The photo is on the contact. */
    data object Ok : ContactPhotoWriteResult

    /** No contact exists for the number — nothing to attach the photo to. */
    data object NotFound : ContactPhotoWriteResult

    /** WRITE_CONTACTS is not granted (or was revoked mid-write). */
    data object NoPermission : ContactPhotoWriteResult

    /** Anything else — the provider refused, or the write failed. */
    data object Failed : ContactPhotoWriteResult
}
