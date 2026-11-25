/*
 * Copyright 2026 Relay Messaging (modifications). Relay's modifications
 * to this file are proprietary to Relay Messaging and are not licensed
 * under the Apache License 2.0. See LICENSE-RELAY for the terms. The
 * original work is copyright its respective holders and is licensed
 * under the Apache License 2.0. See LICENSE.
 */

package com.relay.sms.data.repository

import com.relay.sms.data.contacts.ContactsReader
import com.relay.sms.di.IoDispatcher
import com.relay.sms.domain.model.Contact
import com.relay.sms.domain.repository.ContactPhotoWriteResult
import com.relay.sms.domain.repository.ContactRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepositoryImpl @Inject constructor(
    private val reader: ContactsReader,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ContactRepository {

    override suspend fun lookupByPhone(rawPhone: String): Contact? = withContext(io) {
        reader.lookupByPhone(rawPhone)
    }

    override suspend fun hasContactsPermission(): Boolean = withContext(io) {
        reader.hasContactsPermission()
    }

    override suspend fun listAll(): List<Contact> = withContext(io) { reader.listAll() }

    override suspend fun listAllDetailed(): List<Contact> = withContext(io) {
        reader.listAllDetailed()
    }

    override suspend fun openPhotoStream(address: String): InputStream? = withContext(io) {
        reader.openPhotoStream(address)
    }

    override suspend fun setContactPhoto(
        address: String,
        photoBytes: ByteArray,
    ): ContactPhotoWriteResult = withContext(io) {
        reader.setPhoto(address, photoBytes)
    }
}
