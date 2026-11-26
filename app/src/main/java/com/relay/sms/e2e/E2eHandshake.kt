/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

/**
 * The probe / bundle handshake packets (design §2.2), carried by data SMS
 * on port [E2E_DATA_SMS_PORT]. Data SMS is invisible and never stored in
 * the system inbox, so a probe to a phone without Relay is silently
 * dropped by the OS — the recipient sees nothing.
 *
 * Round 45 — the same packets can also travel as regular SMS
 * ([E2eHandshakeSms]): one `RLYH:` text each, for peers whose data
 * SMS never arrive. A bundle reply follows the probe's arrival
 * channel ([HandshakeChannel]) — symmetric, no extra signalling.
 *
 * Both packets share a header — magic `RLYH` (4 bytes) + version `0x01`
 * (1 byte) + type (1 byte) — and both fit in one 140-byte data-SMS
 * segment:
 *
 *     Probe:  "RLYH" 0x01 0x01 <curve32> <ed32>                    = 70 bytes
 *     Bundle: "RLYH" 0x01 0x02 <curve32> <ed32> <one-time-key32>   = 102 bytes
 *
 * Round 47 — the manual flag: bit 7 (`0x80`) of the type byte marks a
 * probe the user sent by tapping Retry in the Details panel (or the
 * desktop retry command). A manual probe's type byte is `0x81`; an
 * automatic probe's stays `0x01`. The flag lives inside the packet,
 * so both transports carry it unchanged — the data-SMS bytes and the
 * `RLYH:` base64 text are the same 70 bytes, and the text form stays
 * 101 chars. The receiver ([E2eHandshakeHandler]) bypasses its
 * one-bundle-per-sender-per-day rate limit for a manual probe: the
 * sender-side budget bypass alone did not help, because the
 * receiver's limit still blocked the bundle reply and the retry
 * failed unless both phones reset timing first. Automatic probes
 * are NEVER marked manual — the limit still guards against probe
 * spam. A manual bundle send still consumes the limit (the reply
 * timestamp is recorded), so the NEXT automatic probe is gated.
 *
 * Version note: a build from before round 47 drops a manual probe —
 * `0x81` is not a type it knows — so a manual retry against a peer
 * on an old build gets no reply until they update. Automatic probes
 * are byte-identical to before, so the automatic handshake with old
 * builds is unaffected. There is no spare byte for a signal an old
 * build would ignore: every header byte is validated (magic,
 * version, type, exact length) and the key bytes are real keys.
 *
 * Round 48 — the capability declaration (type `0x03`): "I can send
 * and receive encrypted media" (flags bit 0). It is a NEW packet
 * type, not a flag inside the probe/bundle — those packets have no
 * spare byte (see above), so the signal could not ride inside
 * them. It is sent AFTER the session is established (see
 * [E2eMediaCapabilityManager]), on the same channel the handshake
 * used ([HandshakeChannel]).
 *
 * Round 49 — flags bit 1 declares encrypted groups ("I can send
 * and receive encrypted group messages", design §13). The packet
 * was designed for this: the flags byte's remaining bits were
 * reserved so a future capability could ride the same packet
 * without a new type. One declaration carries both bits, so a
 * single send announces both capabilities — and a build from
 * before round 49 reads only bit 0, ignoring bit 1 exactly as its
 * decoder was written to (reserved bits ignored).
 *
 * Round 54 — flags bit 2 declares encrypted group media ("I can
 * send and receive encrypted media in groups", design §13.7):
 * the group bit alone no longer implies it. A round-49 build
 * reads bits 0–1 and ignores bit 2, so it still declares (and is
 * read as) group-capable while never claiming group media — the
 * gate is what keeps a group-media key from reaching it.
 *
 * The declaration is deliberately PROBE-SIZED (70 bytes, flags in
 * the first payload byte, the rest zeroes) — not the 6-byte header
 * alone. An old build drops an unknown packet type silently on the
 * data-SMS path ([decode] returns null), but on the `RLYH:` text
 * path the old gate ([E2eHandshakeSmsGate]) only swallows texts
 * whose decoded length is 70 or 102 bytes
 * ([E2eHandshakeSms.isHandshakeSms]) — anything else falls through
 * to the plain-text insert path and would surface as a visible
 * `RLYH:…` gibberish bubble on the old build. At 70 bytes the
 * declaration passes the old gate's length check, the old
 * [decode] returns null for the unknown type, and the text is
 * swallowed (system inbox only, never a bubble) — silent on both
 * channels, on every old build. The size costs nothing: one data
 * segment either way, and the `RLYH:` text stays one 101-char
 * segment, like a probe.
 *
 * No signature on the reply: this is TOFU — a man-in-the-middle who swaps
 * the whole bundle defeats any signature on it anyway. Detection is the
 * safety-number verification ([E2eSafetyNumber]). The Olm pre-key message
 * itself is authenticated, so a forged bundle yields a session that fails
 * to decrypt and is dropped.
 *
 * Pure Kotlin, no Android APIs — fully unit-testable on the JVM.
 */
object E2eHandshake {

    /** Data-SMS port for the handshake (adjacent to the sync port 47831). */
    const val E2E_DATA_SMS_PORT: Int = 47832

    /** Total size of a probe packet, in bytes. */
    const val PROBE_SIZE: Int = 70

    /** Total size of a bundle-reply packet, in bytes. */
    const val BUNDLE_SIZE: Int = 102

    /**
     * Total size of a capability declaration packet, in bytes
     * (round 48). Deliberately probe-sized: see the class KDoc for
     * why a shorter packet would surface as visible gibberish on
     * old builds on the `RLYH:` text path.
     */
    const val CAPABILITY_SIZE: Int = PROBE_SIZE

    private const val HEADER_SIZE: Int = 6
    private const val VERSION: Byte = 0x01
    private const val TYPE_PROBE: Byte = 0x01
    private const val TYPE_BUNDLE: Byte = 0x02
    private const val TYPE_CAPABILITY: Byte = 0x03

    /**
     * Flags byte offset inside a capability packet: the first
     * payload byte, right after the 6-byte header. Bit 0
     * ([MEDIA_FLAG]) says the sender handles encrypted media;
     * bit 1 ([GROUP_FLAG], round 49) says the sender handles
     * encrypted groups; bit 2 ([GROUP_MEDIA_FLAG], round 54)
     * says the sender handles encrypted media in groups.
     * Bits 3–7 are reserved (zero today) so a future capability
     * can ride the same packet without a new type.
     */
    private const val CAPABILITY_FLAGS_OFFSET: Int = HEADER_SIZE

    /** Flags bit 0: the sender can send and receive encrypted media. */
    private const val MEDIA_FLAG: Int = 0x01

    /** Flags bit 1 (round 49): the sender can send and receive encrypted groups. */
    private const val GROUP_FLAG: Int = 0x02

    /** Flags bit 2 (round 54): the sender can send and receive encrypted media in groups. */
    private const val GROUP_MEDIA_FLAG: Int = 0x04

    /**
     * Bit 7 of the type byte: set on a probe the user sent by tapping
     * Retry (round 47). A non-const val — `0x80` does not fit a Byte
     * literal, so the const form cannot spell it.
     */
    private val MANUAL_FLAG: Byte = 0x80.toByte()

    /** The type byte with the flag bit masked out — the packet type itself. */
    private const val TYPE_MASK: Byte = 0x7F

    private val MAGIC: ByteArray = byteArrayOf(0x52, 0x4C, 0x59, 0x48) // "RLYH"

    /**
     * A decoded handshake packet. The key-carrying classes are plain
     * classes (not data classes): data-class equality on [ByteArray]
     * is referential, which would silently break key comparisons.
     * [Capability] carries no keys, so a data class is safe there.
     */
    sealed interface HandshakePacket {

        /**
         * A probe: "I run Relay, here are my identity keys".
         * [manual] is true when the user sent this probe by tapping
         * Retry (round 47) — the receiver bypasses its bundle
         * rate limit for it. Automatic probes are never manual.
         */
        class Probe(
            /** Our / their Curve25519 identity key (32 bytes). */
            val curveKey: ByteArray,
            /** Our / their Ed25519 identity key (32 bytes). */
            val edKey: ByteArray,
            val manual: Boolean = false,
        ) : HandshakePacket {

            init {
                require(curveKey.size == E2E_KEY_LENGTH) { "curve key must be 32 bytes" }
                require(edKey.size == E2E_KEY_LENGTH) { "ed key must be 32 bytes" }
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Probe) return false
                return curveKey.contentEquals(other.curveKey) &&
                    edKey.contentEquals(other.edKey) &&
                    manual == other.manual
            }

            override fun hashCode(): Int {
                var result = curveKey.contentHashCode()
                result = 31 * result + edKey.contentHashCode()
                result = 31 * result + manual.hashCode()
                return result
            }

            override fun toString(): String =
                "Probe(<32-byte curve key>, <32-byte ed key>, manual=$manual)"
        }

        /** A bundle reply: identity keys + one one-time public key. */
        class Bundle(
            /** Our / their Curve25519 identity key (32 bytes). */
            val curveKey: ByteArray,
            /** Our / their Ed25519 identity key (32 bytes). */
            val edKey: ByteArray,
            val oneTimeKey: ByteArray,
        ) : HandshakePacket {

            init {
                require(curveKey.size == E2E_KEY_LENGTH) { "curve key must be 32 bytes" }
                require(edKey.size == E2E_KEY_LENGTH) { "ed key must be 32 bytes" }
                require(oneTimeKey.size == E2E_KEY_LENGTH) { "one-time key must be 32 bytes" }
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Bundle) return false
                return curveKey.contentEquals(other.curveKey) &&
                    edKey.contentEquals(other.edKey) &&
                    oneTimeKey.contentEquals(other.oneTimeKey)
            }

            override fun hashCode(): Int {
                var result = curveKey.contentHashCode()
                result = 31 * result + edKey.contentHashCode()
                result = 31 * result + oneTimeKey.contentHashCode()
                return result
            }

            override fun toString(): String =
                "Bundle(<32-byte curve key>, <32-byte ed key>, <32-byte one-time key>)"
        }

        /**
         * A capability declaration (round 48, extended round 49,
         * round 54): "I can send and receive encrypted media"
         * (flags bit 0), "I can send and receive encrypted
         * groups" (flags bit 1, round 49), and "I can send and
         * receive encrypted media in groups" (flags bit 2, round
         * 54). Sent after the session is established, on the
         * handshake channel — the receiver records the sender's
         * capabilities and needs no reply beyond its own
         * declaration (see [E2eMediaCapabilityManager]). Carries
         * no keys: the sender's address identifies the peer, and
         * the session already exists. A data class is safe here —
         * the fields are Booleans, no [ByteArray] equality trap.
         */
        data class Capability(
            /** True when the sender handles encrypted media (flags bit 0). */
            val mediaCapable: Boolean,
            /**
             * True when the sender handles encrypted groups
             * (flags bit 1, round 49). Defaults to false so a
             * declaration built before round 49 still decodes.
             */
            val groupCapable: Boolean = false,
            /**
             * True when the sender handles encrypted media in
             * groups (flags bit 2, round 54). Defaults to false
             * so a declaration built before round 54 still
             * decodes — a round-49 build never claims it, so a
             * group with one stays plain for media.
             */
            val groupMediaCapable: Boolean = false,
        ) : HandshakePacket
    }

    /**
     * Encode a probe packet (70 bytes). [manual] sets the flag bit
     * (round 47): the type byte becomes `0x81` instead of `0x01`,
     * telling the receiver to bypass its bundle rate limit for
     * this probe. Only a user-initiated retry sets it — automatic
     * probes always encode with the default `false`.
     */
    fun encodeProbe(curveKey: ByteArray, edKey: ByteArray, manual: Boolean = false): ByteArray {
        require(curveKey.size == E2E_KEY_LENGTH) { "curve key must be 32 bytes" }
        require(edKey.size == E2E_KEY_LENGTH) { "ed key must be 32 bytes" }
        val type = if (manual) (TYPE_PROBE.toInt() or MANUAL_FLAG.toInt()).toByte() else TYPE_PROBE
        return encode(type, curveKey, edKey, null)
    }

    /** Encode a bundle-reply packet (102 bytes). */
    fun encodeBundle(curveKey: ByteArray, edKey: ByteArray, oneTimeKey: ByteArray): ByteArray {
        require(curveKey.size == E2E_KEY_LENGTH) { "curve key must be 32 bytes" }
        require(edKey.size == E2E_KEY_LENGTH) { "ed key must be 32 bytes" }
        require(oneTimeKey.size == E2E_KEY_LENGTH) { "one-time key must be 32 bytes" }
        return encode(TYPE_BUNDLE, curveKey, edKey, oneTimeKey)
    }

    /**
     * Encode a capability declaration packet (round 48, 70 bytes
     * — probe-sized, see the class KDoc): the header, the flags
     * byte with bits 0 (media), 1 (groups, round 49) and 2
     * (group media, round 54) set, and zeroes. Sent after the
     * session is established, never before — a declaration from
     * a peer with no session is meaningless, and the receiver
     * only trusts it alongside an ENCRYPTED state.
     */
    fun encodeCapabilityDeclaration(): ByteArray {
        return ByteArray(CAPABILITY_SIZE).apply {
            MAGIC.copyInto(this, 0)
            this[4] = VERSION
            this[5] = TYPE_CAPABILITY
            this[CAPABILITY_FLAGS_OFFSET] = (MEDIA_FLAG or GROUP_FLAG or GROUP_MEDIA_FLAG).toByte()
        }
    }

    /**
     * Why a packet failed to decode — the diagnostic half of
     * [decodeWithReason]. Never carries payload bytes: only the
     * failure kind, so the handler can record it visibly in the
     * error log without leaking key material.
     */
    enum class DecodeFailureReason {
        /** Fewer than [HEADER_SIZE] bytes — not even a header. */
        TOO_SHORT,

        /** First four bytes are not `RLYH`. */
        BAD_MAGIC,

        /** Version byte is not `0x01`. */
        BAD_VERSION,

        /** Type byte (flag bit masked out) is not probe / bundle / capability. */
        UNKNOWN_TYPE,

        /** A known type whose total length is not its packet size. */
        WRONG_LENGTH,
    }

    /**
     * A decode outcome: either a packet, or the reason it is not
     * one. [decode] delegates to this so existing callers are
     * unaffected; the handler uses this form so a dropped packet
     * can be logged with its reason instead of silently vanishing.
     */
    sealed interface DecodeResult {
        /** The bytes decoded to a packet. */
        data class Packet(val packet: HandshakePacket) : DecodeResult

        /** The bytes are not one of our packets, and why. */
        data class Failure(val reason: DecodeFailureReason) : DecodeResult
    }

    /**
     * Decode a received packet, carrying the failure reason.
     * Returns [DecodeResult.Packet] for a probe, bundle, or
     * capability declaration, and [DecodeResult.Failure] for
     * anything else (bad magic, version, type, or length) — the
     * receiver drops those, and records the reason. Never throws
     * on input.
     */
    fun decodeWithReason(bytes: ByteArray): DecodeResult {
        val headerFailure = when {
            bytes.size < HEADER_SIZE -> DecodeFailureReason.TOO_SHORT
            !bytes.copyOfRange(0, 4).contentEquals(MAGIC) -> DecodeFailureReason.BAD_MAGIC
            bytes[4] != VERSION -> DecodeFailureReason.BAD_VERSION
            else -> null
        }
        if (headerFailure != null) return DecodeResult.Failure(headerFailure)
        // Round 47 — bit 7 of the type byte is the manual flag.
        // Mask it out for the type dispatch; the flag rides on the
        // decoded Probe below. The flag is meaningless on a bundle,
        // so a bundle with the bit set still decodes as a bundle
        // (lenient — a future bundle flag must not break decoding).
        val typeByte = bytes[5]
        val maskedType = (typeByte.toInt() and TYPE_MASK.toInt()).toByte()
        val packet = when (maskedType) {
            TYPE_PROBE -> decodeProbe(bytes, isManual(typeByte))
            TYPE_BUNDLE -> decodeBundle(bytes)
            TYPE_CAPABILITY -> decodeCapability(bytes)
            else -> null
        }
        return when {
            packet != null -> DecodeResult.Packet(packet)
            maskedType != TYPE_PROBE &&
                maskedType != TYPE_BUNDLE &&
                maskedType != TYPE_CAPABILITY ->
                DecodeResult.Failure(DecodeFailureReason.UNKNOWN_TYPE)
            // A known type whose decoder returned null: the total
            // length is not that packet's size.
            else -> DecodeResult.Failure(DecodeFailureReason.WRONG_LENGTH)
        }
    }

    /**
     * Decode a received packet. Returns null for anything that is not one
     * of our packets (bad magic, version, type, or length) — the receiver
     * drops those silently, since a random data SMS to this port is not
     * our business. Never throws on input. Delegates to
     * [decodeWithReason]; callers that need the failure reason use
     * that form directly.
     */
    fun decode(bytes: ByteArray): HandshakePacket? {
        return when (val result = decodeWithReason(bytes)) {
            is DecodeResult.Packet -> result.packet
            is DecodeResult.Failure -> null
        }
    }

    /** True when the type byte carries the manual flag (bit 7 set). */
    private fun isManual(typeByte: Byte): Boolean =
        (typeByte.toInt() and MANUAL_FLAG.toInt()) != 0

    private fun encode(type: Byte, curveKey: ByteArray, edKey: ByteArray, oneTimeKey: ByteArray?): ByteArray {
        val size = if (oneTimeKey == null) PROBE_SIZE else BUNDLE_SIZE
        return ByteArray(size).apply {
            MAGIC.copyInto(this, 0)
            this[4] = VERSION
            this[5] = type
            curveKey.copyInto(this, HEADER_SIZE)
            edKey.copyInto(this, HEADER_SIZE + E2E_KEY_LENGTH)
            oneTimeKey?.copyInto(this, HEADER_SIZE + 2 * E2E_KEY_LENGTH)
        }
    }

    private fun decodeProbe(bytes: ByteArray, manual: Boolean): HandshakePacket.Probe? =
        if (bytes.size != PROBE_SIZE) {
            null
        } else {
            HandshakePacket.Probe(
                curveKey = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + E2E_KEY_LENGTH),
                edKey = bytes.copyOfRange(HEADER_SIZE + E2E_KEY_LENGTH, PROBE_SIZE),
                manual = manual,
            )
        }

    private fun decodeBundle(bytes: ByteArray): HandshakePacket.Bundle? =
        if (bytes.size != BUNDLE_SIZE) {
            null
        } else {
            HandshakePacket.Bundle(
                curveKey = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + E2E_KEY_LENGTH),
                edKey = bytes.copyOfRange(
                    HEADER_SIZE + E2E_KEY_LENGTH,
                    HEADER_SIZE + 2 * E2E_KEY_LENGTH,
                ),
                oneTimeKey = bytes.copyOfRange(HEADER_SIZE + 2 * E2E_KEY_LENGTH, BUNDLE_SIZE),
            )
        }

    /**
     * Decode a capability declaration: exactly [CAPABILITY_SIZE]
     * bytes, flags bits 0 (media), 1 (groups, round 49) and 2
     * (group media, round 54) read leniently — bits 3–7 are
     * reserved for future capabilities and must not break
     * decoding when a newer build sets them.
     */
    private fun decodeCapability(bytes: ByteArray): HandshakePacket.Capability? =
        if (bytes.size != CAPABILITY_SIZE) {
            null
        } else {
            HandshakePacket.Capability(
                mediaCapable = (bytes[CAPABILITY_FLAGS_OFFSET].toInt() and MEDIA_FLAG) != 0,
                groupCapable = (bytes[CAPABILITY_FLAGS_OFFSET].toInt() and GROUP_FLAG) != 0,
                groupMediaCapable = (bytes[CAPABILITY_FLAGS_OFFSET].toInt() and GROUP_MEDIA_FLAG) != 0,
            )
        }
}

/**
 * Which transport carried (or will carry) a handshake packet
 * (round 45). A bundle reply follows the probe's arrival channel,
 * and a manual retry picks one channel per button — symmetric, no
 * extra signalling. Automatic probing stays data-SMS-only.
 */
enum class HandshakeChannel {
    /** Port-addressed data SMS (the original handshake transport). */
    DATA_SMS,

    /** Regular SMS, `RLYH:` + base64 (the round-45 fallback). */
    TEXT_SMS,
}
