/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import com.google.common.truth.Truth.assertThat
import com.relay.sms.core.crypto.AeadCipher
import com.relay.sms.core.result.Outcome
import org.junit.jupiter.api.Test

/**
 * Group media key wrap / unwrap (round 54, design §13.7) — pure
 * JVM, no stores: a `MediaKey` JSON wrapped in a sender key
 * (the same envelope + AAD as a group text) round-trips, and the
 * AAD binding fails the GCM tag when the group, the sender, or
 * the key id changes. This is the crypto core the manager's
 * `encryptGroupMediaKey` and the receiver's `GROUP_MEDIA_KEY`
 * branch both rely on.
 *
 * Every test ends with a void assertion — JUnit silently skips a
 * `@Test` whose body returns a value.
 */
class E2eGroupMediaKeyTest {

    private companion object {
        val MEMBERS = listOf("6108423232", "6108423233", "6108423234")
        val GROUP_ID = E2eGroupWireFormat.deriveGroupIdHex(MEMBERS)
        const val SENDER = "6108423232"
        const val KEY_ID = "0123456789abcdef"
        const val OTHER_GROUP = "fedcba9876543210fedcba9876543210"
        const val OTHER_KEY_ID = "aaaaaaaaaaaaaaaa"
    }

    private val aead = AeadCipher()
    private val senderKey = E2eGroupWireFormat.generateSenderKey()

    private fun keyJson(): String = E2eMediaKeyMessage.encode(
        E2eMediaKeyMessage.MediaKey(
            id = "0123456789abcdef",
            key = ByteArray(32) { 5 },
            mime = "image/jpeg",
            sizeBytes = 100L,
            sha256Hex = "b".repeat(64),
            fileName = "photo.jpg",
        ),
    )

    /** Wrap exactly as [E2eGroupSessionManager.encryptGroupMediaKey] does. */
    private fun wrap(
        json: String,
        groupId: String = GROUP_ID,
        sender: String = SENDER,
        keyId: String = KEY_ID,
    ): ByteArray {
        val aad = E2eGroupWireFormat.aadFor(groupId, sender, keyId)
        val blob = when (val encrypted = aead.encryptRaw(senderKey, json.toByteArray(Charsets.UTF_8), aad)) {
            is Outcome.Success -> encrypted.value
            is Outcome.Failure -> error("wrap failed: ${encrypted.error}")
        }
        return E2eGroupWireFormat.encodeEnvelope(groupId, keyId, blob)!!
    }

    /** Unwrap exactly as [E2eGroupSessionManager.decryptGroupMessage] does. */
    private fun unwrap(
        envelopeBytes: ByteArray,
        groupId: String = GROUP_ID,
        sender: String = SENDER,
        keyId: String = KEY_ID,
    ): String? {
        val envelope = E2eGroupWireFormat.parseEnvelope(envelopeBytes) ?: return null
        val aad = E2eGroupWireFormat.aadFor(groupId, sender, keyId)
        return when (val decrypted = aead.decryptRaw(senderKey, envelope.aeadBlob, aad)) {
            is Outcome.Success -> String(decrypted.value, Charsets.UTF_8)
            is Outcome.Failure -> null
        }
    }

    @Test
    fun `a wrapped media key round-trips and decodes as a MediaKey`() {
        val json = keyJson()

        val unwrapped = unwrap(wrap(json))

        assertThat(unwrapped).isEqualTo(json)
        val decoded = E2eMediaKeyMessage.decode(unwrapped!!)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.id).isEqualTo("0123456789abcdef")
        assertThat(decoded.mime).isEqualTo("image/jpeg")
        assertThat(decoded.sizeBytes).isEqualTo(100L)
    }

    @Test
    fun `a wrapped key fails to open under another group id`() {
        // Transplant the blob into an envelope for another group:
        // the receiver computes the AAD from the envelope's group
        // id, so the GCM tag fails.
        val wrapped = wrap(keyJson())
        val envelope = E2eGroupWireFormat.parseEnvelope(wrapped)!!
        val transplanted = E2eGroupWireFormat.encodeEnvelope(
            OTHER_GROUP,
            KEY_ID,
            envelope.aeadBlob,
        )!!

        val unwrapped = unwrap(transplanted, groupId = OTHER_GROUP)

        assertThat(unwrapped).isNull()
    }

    @Test
    fun `a wrapped key fails to open as another sender`() {
        val wrapped = wrap(keyJson())

        val unwrapped = unwrap(wrapped, sender = "6108423233")

        assertThat(unwrapped).isNull()
    }

    @Test
    fun `a wrapped key fails to open under another key id`() {
        // Transplant the blob into an envelope under another key
        // id: the receiver computes the AAD from the envelope's
        // key id, so the GCM tag fails.
        val wrapped = wrap(keyJson())
        val envelope = E2eGroupWireFormat.parseEnvelope(wrapped)!!
        val transplanted = E2eGroupWireFormat.encodeEnvelope(
            GROUP_ID,
            OTHER_KEY_ID,
            envelope.aeadBlob,
        )!!

        val unwrapped = unwrap(transplanted, keyId = OTHER_KEY_ID)

        assertThat(unwrapped).isNull()
    }

    @Test
    fun `a tampered wrapped key fails to open`() {
        val wrapped = wrap(keyJson())
        wrapped[wrapped.size - 1] = (wrapped[wrapped.size - 1].toInt() xor 0x01).toByte()

        val unwrapped = unwrap(wrapped)

        assertThat(unwrapped).isNull()
    }

    @Test
    fun `malformed JSON after decrypt decodes to null`() {
        assertThat(E2eMediaKeyMessage.decode("not json")).isNull()
        assertThat(E2eMediaKeyMessage.decode("{\"v\":2,\"id\":\"0123456789abcdef\"}")).isNull()
    }
}
