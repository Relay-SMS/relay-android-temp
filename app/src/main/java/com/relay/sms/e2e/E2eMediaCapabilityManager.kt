/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import com.relay.sms.data.local.db.entity.E2ePeerState
import com.relay.sms.domain.repository.BlockedNumberRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capability signaling (round 48 media, round 49 groups, design
 * §2.7 / §13): the "I can do encrypted media / encrypted groups"
 * declaration, and the record of who declared what.
 *
 * The probe/bundle packets have NO spare byte (both are validated
 * magic + version + type + exact length, and old builds drop
 * anything else), so the capability signal could not ride inside
 * the handshake — it is a NEW handshake packet type (`0x03`, see
 * [E2eHandshake]), sent AFTER the session is established, on the
 * SAME channel the handshake used ([HandshakeChannel]). One
 * packet announces BOTH capabilities: the flags byte carries the
 * media bit (bit 0) and the group bit (bit 1) — a round-48 build
 * reads the media bit and ignores the group bit, a round-49
 * build reads both. The announced flags stay separate per
 * capability, so a peer announced in round 48 (media only) still
 * gets the round-49 declaration: the unset group flag re-fires
 * the thread-open trigger.
 *
 * Round 54 — a third announced flag ([E2ePeerRepository.groupMediaCapability])
 * for the group-media bit (bit 2): a peer announced in round 49
 * (media + groups) still gets the round-54 declaration, since the
 * unset group-media flag re-fires the thread-open trigger. The
 * same three-flag rule gates the announce-back.
 *
 * Three send triggers, all best-effort and idempotent (a duplicate
 * declaration is a no-op on receipt — the flag just sets again):
 *
 * 1. **Session establishment** ([announceNow]): the bundle receiver
 *    announces when the bundle lands ([E2eHandshakeHandler]), and
 *    the bundle sender announces when the first pre-key message
 *    arrives ([E2eReceiveManager]) — both sides of a NEW exchange
 *    declare exactly once, on the channel the exchange proved.
 * 2. **Thread open** ([announceIfNeeded]): an already-ENCRYPTED peer
 *    whose session predates round 48 never fires an establishment
 *    event, so opening their thread sends the declaration once
 *    ([E2eProbeManager] — the same funnel as the probe trigger).
 *    The [E2ePeerRepository.isMediaAnnounced] flag makes it
 *    one-shot per peer; a failed send leaves the flag clear, so
 *    the next thread-open retries.
 * 3. **Announce-back** ([onDeclarationReceived]): receiving a
 *    declaration from a peer we have not announced to yet sends
 *    ours back, so capability becomes symmetric as soon as EITHER
 *    side announces — neither side waits for the other to open
 *    the thread first.
 *
 * Channel selection: the peer row records the channel our outbound
 * handshake packets provably used
 * ([E2ePeerRepository.handshakeChannel]) — a bundle arriving on a
 * channel proves our probe reached them on it, and a pre-key
 * message proves our bundle reply did. The declaration goes out
 * on THAT channel. When no channel is recorded (a handshake from
 * before round 48), the declaration goes out on BOTH channels —
 * and BOTH sends happen even if the first is accepted: radio
 * acceptance proves nothing about carrier delivery for data SMS
 * (the partner pair's line accepts the send and drops the segment
 * downstream — that is the whole reason the text channel exists).
 * The flag marks announced when at least one send is accepted.
 *
 * The discovery toggle does NOT gate declarations: the toggle
 * stops DISCOVERING new Relay contacts (probes to strangers), and
 * a declaration goes to an already-ENCRYPTED peer — no discovery
 * is involved, and no segment is spent on anyone we do not already
 * encrypt with. The blocked check DOES apply: a blocked peer gets
 * nothing, like on every other send path.
 *
 * All methods never throw: a diagnostics-grade failure (a dead
 * database, a failing sender) returns `false` instead of breaking
 * the handshake or the thread-open path that called it.
 *
 * Round 57 — declaration traffic is visible in the error log
 * ([E2eFailureRecorder.recordEvent]): a send under
 * `e2e-capability-sent` (with the channel and the address), a
 * blocked-peer refusal under `e2e-capability-blocked` (with the
 * address), and a radio refusal on every attempted channel under
 * `e2e-capability-send-failed` (with the address). A received
 * declaration is recorded ONCE, by [E2eHandshakeHandler] under
 * `e2e-capability-received` (with the address and the flags) —
 * this manager does not record receipt itself, so one incoming
 * packet leaves one entry. The sender also records each accepted
 * send (`e2e-sms-accepted`) and each synchronous refusal (under
 * the kind's failure tag), so a declaration leaves two traces at
 * different layers. The already-announced / not-ENCRYPTED no-ops
 * record nothing: every thread-open on an announced peer would
 * otherwise spam the 10-report rotation.
 */
@Singleton
class E2eMediaCapabilityManager @Inject constructor(
    private val peers: E2ePeerRepository,
    private val dataSmsSender: E2eDataSmsSender,
    private val blockedRepo: BlockedNumberRepository,
    private val recorder: E2eFailureRecorder,
) {

    /**
     * Thread-open trigger: sends our declaration to an ENCRYPTED
     * peer we have not announced to yet. Returns `true` when a
     * declaration went out on this call; `false` when nothing was
     * sent (already announced, not ENCRYPTED, blocked, or the
     * radio refused both channels). A `false` from a refused send
     * is retried on the next thread-open — the announced flags
     * are only set after an accepted send. Round 49: the
     * declaration re-fires when EITHER capability flag is unset —
     * a peer announced in round 48 (media only) still needs the
     * round-49 declaration, which carries the group bit. Round
     * 54: the same rule with THREE flags — a peer announced in
     * round 49 (media + groups) still needs the round-54
     * declaration, which carries the group-media bit.
     */
    suspend fun announceIfNeeded(
        address: String,
        subId: Int?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        return runCatching {
            if (peers.state(address) != E2ePeerState.ENCRYPTED) return@runCatching false
            if (peers.isMediaAnnounced(address) && peers.isGroupAnnounced(address) &&
                peers.groupMediaCapability(address).announced
            ) {
                return@runCatching false
            }
            announce(address, subId, now)
        }.getOrDefault(false)
    }

    /**
     * Establishment trigger: sends our declaration now, whether or
     * not we announced before — a re-establishment (key change,
     * reinstall) re-declares, so a peer that wiped its state
     * re-learns our capability with the new session. Still gated
     * on ENCRYPTED + not blocked: a declaration without a session
     * is meaningless. Returns `true` when a declaration went out.
     */
    suspend fun announceNow(
        address: String,
        subId: Int?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        return runCatching {
            if (peers.state(address) != E2ePeerState.ENCRYPTED) return@runCatching false
            announce(address, subId, now)
        }.getOrDefault(false)
    }

    /**
     * A declaration arrived from this peer: record the
     * capabilities they declared (media and/or groups, as
     * decoded from the flags byte), and announce back when we
     * have not announced to them yet (the symmetric close — see
     * the class KDoc). Called by [E2eHandshakeHandler] after its
     * blocked check, so no second blocked check happens here;
     * the announce-back re-checks anyway (cheap, and the
     * handler's check is about the inbound packet, this one
     * about the outbound send).
     *
     * The announce-back is gated on ENCRYPTED, like every other
     * declaration: a declaration without a session is
     * meaningless, and the sender cannot be mid-exchange here
     * without one — the bundle side only gains a session when
     * the first encrypted message arrives, and that arrival
     * fires its own announce (see the class KDoc), so a peer
     * that is PROBING now still declares once it is ENCRYPTED.
     * Round 49: the announce-back fires when EITHER announced
     * flag is unset. Round 54: the same rule with THREE flags.
     * Never throws.
     */
    suspend fun onDeclarationReceived(
        address: String,
        subId: Int?,
        now: Long = System.currentTimeMillis(),
        mediaCapable: Boolean = true,
        groupCapable: Boolean = false,
        groupMediaCapable: Boolean = false,
    ) {
        runCatching {
            peers.setMediaCapable(address, mediaCapable, now)
            peers.setGroupCapable(address, groupCapable, now)
            peers.setGroupMediaCapability(address, capable = groupMediaCapable, now = now)
            Timber.i(
                "E2E: peer declared capabilities (media=$mediaCapable, groups=$groupCapable, " +
                    "groupMedia=$groupMediaCapable)",
            )
            // No receipt event here: [E2eHandshakeHandler] already
            // recorded this packet under `e2e-capability-received`
            // (with the address and the flags) before calling us —
            // one incoming packet, one entry.
            val announced = peers.isMediaAnnounced(address) && peers.isGroupAnnounced(address) &&
                peers.groupMediaCapability(address).announced
            if (peers.state(address) == E2ePeerState.ENCRYPTED && !announced) {
                announce(address, subId, now)
            }
        }.onFailure { Timber.w(it, "E2E: capability declaration handling failed") }
    }

    /**
     * Sends one declaration on the peer's proven channel (or both
     * channels when none is recorded — see the class KDoc) and
     * marks the peer announced (ALL THREE capability flags — one
     * packet announces all three) when at least one send was accepted.
     * Returns `true` when at least one send went out. Blocked
     * peers get nothing.
     */
    private suspend fun announce(address: String, subId: Int?, now: Long): Boolean {
        if (runCatching { blockedRepo.isBlocked(address) }.getOrDefault(false)) {
            recorder.recordEvent(
                CAPABILITY_BLOCKED_TAG,
                "Capability declaration refused for $address: number is blocked.",
            )
            return false
        }
        val packet = E2eHandshake.encodeCapabilityDeclaration()
        // Which channels accepted the declaration, for the log
        // line: the no-proven-channel branch attempts BOTH, so
        // both names can appear even when only one was accepted.
        val acceptedChannels = mutableListOf<String>()
        val sent = when (peers.handshakeChannel(address)) {
            HandshakeChannel.DATA_SMS -> sendData(address, subId, packet).also {
                if (it) acceptedChannels += HandshakeChannel.DATA_SMS.name
            }
            HandshakeChannel.TEXT_SMS -> sendText(address, subId, packet).also {
                if (it) acceptedChannels += HandshakeChannel.TEXT_SMS.name
            }
            // No proven channel (a pre-round-48 handshake): both
            // channels, unconditionally — a data send the radio
            // accepts may still be dropped downstream, so the
            // text send is not skipped when the data send is
            // accepted. Announced when either is accepted.
            null -> {
                val dataSent = sendData(address, subId, packet)
                val textSent = sendText(address, subId, packet)
                if (dataSent) acceptedChannels += HandshakeChannel.DATA_SMS.name
                if (textSent) acceptedChannels += HandshakeChannel.TEXT_SMS.name
                dataSent || textSent
            }
        }
        if (sent) {
            peers.setMediaAnnounced(address, true, now)
            peers.setGroupAnnounced(address, true, now)
            peers.setGroupMediaCapability(address, announced = true, now = now)
            Timber.i("E2E: capability declaration sent")
            recorder.recordEvent(
                CAPABILITY_SENT_TAG,
                "Capability declaration sent to $address " +
                    "(channel=${acceptedChannels.joinToString("+")}).",
            )
        } else {
            recorder.recordEvent(
                CAPABILITY_SEND_FAILED_TAG,
                "Capability declaration refused by radio for $address " +
                    "on every attempted channel.",
            )
        }
        return sent
    }

    private companion object {
        /** Error-log tag for a sent capability declaration. */
        const val CAPABILITY_SENT_TAG = "e2e-capability-sent"

        /** Error-log tag for a blocked-peer declaration refusal. */
        const val CAPABILITY_BLOCKED_TAG = "e2e-capability-blocked"

        /** Error-log tag for a radio-refused declaration (all channels). */
        const val CAPABILITY_SEND_FAILED_TAG = "e2e-capability-send-failed"
    }

    private fun sendData(address: String, subId: Int?, packet: ByteArray): Boolean =
        dataSmsSender.sendDataMessage(
            address = address,
            subId = subId,
            port = E2eHandshake.E2E_DATA_SMS_PORT,
            data = packet,
            kind = DataSmsKind.CAPABILITY,
        )

    private fun sendText(address: String, subId: Int?, packet: ByteArray): Boolean =
        dataSmsSender.sendTextMessage(
            address = address,
            subId = subId,
            text = E2eHandshakeSms.encode(packet),
            kind = DataSmsKind.CAPABILITY,
        )
}
