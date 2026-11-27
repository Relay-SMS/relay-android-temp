/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import com.relay.sms.data.local.db.dao.ConversationDao
import com.relay.sms.data.local.db.dao.MessageDao
import com.relay.sms.data.local.db.entity.E2eNoticeKind
import com.relay.sms.data.local.db.entity.E2ePeerEntity
import com.relay.sms.data.sms.PhoneIdentity
import com.relay.sms.domain.model.PhoneAddress
import com.relay.sms.domain.repository.BlockedNumberRepository
import timber.log.Timber
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import com.relay.sms.data.local.db.entity.E2ePeerState as StoredPeerState

/**
 * Handles the handshake data SMS (design §2.3): probes and bundle
 * replies on port [E2eHandshake.E2E_DATA_SMS_PORT].
 *
 * Round 45 — the same packets can also arrive as regular SMS
 * ([E2eHandshakeSms], one `RLYH:` text each), handled by
 * [handleSms]. A bundle reply follows the probe's arrival channel
 * ([HandshakeChannel]) — a probe that arrived by text is answered
 * by text, a probe by data SMS by data SMS. Symmetric, no extra
 * signalling.
 *
 * - **Probe received** → record the peer's identity keys, reply with a
 *   bundle (our identity keys + one one-time key), rate-limited to one
 *   bundle per sender per day. A probe from a known peer with
 *   DIFFERENT identity keys is a key-change event (design §4.4): the
 *   verified flag clears and a KEY_CHANGED notice is recorded.
 *   Round 47 — a MANUAL probe (flag bit, [E2eHandshake]) bypasses
 *   the rate limit: the sender's user tapped Retry, and the whole
 *   point of the tap is to get a bundle back now. Automatic probes
 *   are never marked manual, so the limit still guards against
 *   probe spam — and a successful manual bundle send still
 *   consumes the limit, so the next automatic probe is gated.
 * - **Bundle received** → create the outbound session, flip the peer
 *   to ENCRYPTED, record the ACTIVATED notice when the thread's latest
 *   status is not already "activated" ([E2eNoticeStore.recordActivated]
 *   — a re-establishment after an "Encryption ended" notice earns a
 *   fresh one). First reply wins (§2.4 dual-install): a bundle identical
 *   to one already used (same keys + a live current session) is ignored.
 *   A bundle with different keys from a known peer is a key change: the
 *   new session becomes current, the old one stays as a fallback, and kept
 *   ciphertext is retried. Round 48 — the bundle's arrival channel is
 *   recorded ([E2ePeerRepository.recordHandshakeChannel]) and our
 *   media-capability declaration goes out
 *   ([E2eMediaCapabilityManager.announceNow]): the session exists now,
 *   so the declaration has something to advertise.
 * - **Media-capability declaration received** (round 48) → the peer is
 *   recorded as media-capable, and our own declaration goes back when
 *   we have not announced to them yet (both in
 *   [E2eMediaCapabilityManager.onDeclarationReceived]). No session
 *   work, no notice — the declaration is pure capability signaling.
 *
 * Round 48 — the handshake channel is recorded per peer: a bundle
 * arriving on a channel proves our probe reached them on it, and a
 * probe arriving while not yet ENCRYPTED means our bundle reply goes
 * out on that channel (the session establishment proves it arrived).
 * The media-capability declaration reuses that proven channel
 * ([E2eMediaCapabilityManager]); a probe arriving for an
 * already-ENCRYPTED peer does NOT overwrite it — a later exchange on
 * another channel must not displace the proven one.
 *
 * Anything that is not one of our packets (bad magic, version, type,
 * length) is dropped — and the drop is recorded with its reason
 * (`e2e-decode-failed`), so a packet that arrives but fails
 * validation is separable in the log from one that never arrived.
 * Blocked senders are dropped too, like on the SMS path (recorded
 * as `e2e-blocked-sender`).
 *
 * Error-log rules (§8.3): no keys, no payload bytes, no message
 * content — state transitions and conversation ids only. The
 * handshake events (probe received, bundle sent, bundle
 * rate-limited) are recorded in the error log via
 * [E2eFailureRecorder] too — fixed messages only — so the inbound
 * path stays visible in release builds, where Timber is a no-op
 * (round 42 diagnostic). The diagnostic events do carry the
 * sender's address (a phone number already in the user's threads):
 * without it, a log full of "a probe arrived" cannot say from
 * whom, which is the whole question when a third phone cannot
 * complete a handshake. Never a key, a payload byte, or content.
 */
@Singleton
class E2eHandshakeHandler @Inject constructor(
    private val peers: E2ePeerRepository,
    private val sessions: dagger.Lazy<E2eSessionStore>,
    private val accountManager: dagger.Lazy<E2eAccountManager>,
    private val crypto: dagger.Lazy<E2eCrypto>,
    private val decryptor: E2eSessionDecryptor,
    private val notices: E2eNoticeStore,
    private val dataSmsSender: E2eDataSmsSender,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val phoneIdentity: PhoneIdentity,
    private val blockedRepo: BlockedNumberRepository,
    private val recorder: E2eFailureRecorder,
    private val mediaCapability: E2eMediaCapabilityManager,
    // Round 49 — the group session manager (group key-change notices).
    private val groupSessions: E2eGroupSessionManager,
    // Round 57 — "[Name] is on Relay!" notification, fired when a
    // bundle records a genuinely new ACTIVATED notice (see
    // [handleBundle]). Nullable with a null default so the existing
    // handler tests — which construct the handler by hand — keep
    // compiling untouched; Hilt always provides the real notifier.
    private val activatedNotifier: com.relay.sms.system.notifications.E2eActivatedNotifier? = null,
    // Round 58 — the gated automatic `RLYH:` fallback
    // ([E2eAutoFallback]): armed when a bundle reply is sent (see
    // [handleProbe]) or a bundle arrives without a session (see
    // [handleBundle]). Same nullable-default precedent as the
    // notifier above; Hilt always provides the real fallback.
    private val autoFallback: E2eAutoFallback? = null,
) {

    /**
     * Handles one received data SMS. Returns `true` when the packet was
     * one of ours (probe or bundle) and was processed; `false` when it
     * was dropped (not our packet, blocked sender, or a failed bundle
     * build). Never throws.
     */
    suspend fun handleDataSms(
        address: String,
        data: ByteArray,
        subId: Int?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val packet = when (val result = E2eHandshake.decodeWithReason(data)) {
            is E2eHandshake.DecodeResult.Packet -> result.packet
            is E2eHandshake.DecodeResult.Failure -> {
                // A packet that reached the handler but fails
                // validation is a diagnostic signal, not silence:
                // bad magic here (vs. no arrival line at all in the
                // receiver) separates "the bytes are wrong" from
                // "the bytes never arrived".
                recorder.recordEvent(
                    "e2e-decode-failed",
                    "Data SMS decode failed from $address: " +
                        "${result.reason.name.lowercase().replace('_', '-')}; " +
                        "${data.size} bytes; type ${typeByteLabel(data)}.",
                )
                return false
            }
        }
        return handlePacket(address, packet, subId, now, HandshakeChannel.DATA_SMS)
    }

    /**
     * Handles one received handshake SMS (round 45): the `RLYH:`
     * text form of a probe or bundle. Same contract as
     * [handleDataSms], and the same handling — the only difference
     * is the channel: a probe that arrived by text earns its bundle
     * reply by text. Returns `false` for anything that is not a
     * well-formed handshake SMS. Never throws.
     */
    suspend fun handleSms(
        address: String,
        text: String,
        subId: Int?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val bytes = E2eHandshakeSms.parse(text) ?: return false
        val packet = when (val result = E2eHandshake.decodeWithReason(bytes)) {
            is E2eHandshake.DecodeResult.Packet -> result.packet
            is E2eHandshake.DecodeResult.Failure -> {
                recorder.recordEvent(
                    "e2e-decode-failed",
                    "Handshake SMS decode failed from $address: " +
                        "${result.reason.name.lowercase().replace('_', '-')}; " +
                        "${bytes.size} bytes; type ${typeByteLabel(bytes)}.",
                )
                return false
            }
        }
        return handlePacket(address, packet, subId, now, HandshakeChannel.TEXT_SMS)
    }

    /**
     * The type byte of [bytes] as `0xNN` when the header is
     * readable, `none` otherwise — for decode-failure diagnostics.
     * The byte itself, never any payload byte after the header.
     */
    private fun typeByteLabel(bytes: ByteArray): String =
        if (bytes.size >= 6) "0x%02X".format(bytes[5].toInt() and 0xFF) else "none"

    /**
     * Shared core of [handleDataSms] and [handleSms]: the blocked
     * check, then the probe / bundle handling. The channel only
     * decides how the bundle reply travels (see [handleProbe]) —
     * a bundle is a bundle once decoded, whatever carried it.
     */
    private suspend fun handlePacket(
        address: String,
        packet: E2eHandshake.HandshakePacket,
        subId: Int?,
        now: Long,
        channel: HandshakeChannel,
    ): Boolean {
        if (runCatching { blockedRepo.isBlocked(address) }.getOrDefault(false)) {
            recorder.recordEvent(
                "e2e-blocked-sender",
                "Handshake packet dropped: sender $address is blocked.",
            )
            return false
        }
        // The packet type on receipt: the probe / bundle /
        // capability events below each carry it visibly, so the
        // log can say which kind of packet arrived, not just
        // that something did.
        Timber.i("E2E: handshake packet received (%s) on %s", packetTypeName(packet), channel)
        return when (packet) {
            is E2eHandshake.HandshakePacket.Probe -> handleProbe(address, packet, subId, now, channel)
            is E2eHandshake.HandshakePacket.Bundle -> handleBundle(address, packet, subId, now, channel)
            is E2eHandshake.HandshakePacket.Capability -> {
                recorder.recordEvent(
                    "e2e-capability-received",
                    "Capability declaration received from $address; " +
                        "media=${packet.mediaCapable}; " +
                        "groups=${packet.groupCapable}; " +
                        "group media=${packet.groupMediaCapable}.",
                )
                mediaCapability.onDeclarationReceived(
                    address,
                    subId,
                    now,
                    mediaCapable = packet.mediaCapable,
                    groupCapable = packet.groupCapable,
                    groupMediaCapable = packet.groupMediaCapable,
                )
                true
            }
        }
    }

    /** Short name for a decoded packet, for the receipt log line. */
    private fun packetTypeName(packet: E2eHandshake.HandshakePacket): String =
        when (packet) {
            is E2eHandshake.HandshakePacket.Probe -> "probe"
            is E2eHandshake.HandshakePacket.Bundle -> "bundle"
            is E2eHandshake.HandshakePacket.Capability -> "capability"
        }

    /**
     * Retries every undecryptable placeholder from a peer against the
     * current sessions (design §4.4 step 3): after a re-establishment,
     * kept ciphertext may decrypt after all. Returns the number of
     * placeholders that became readable messages. Called after a bundle
     * establishes a new session, and after a pre-key message does.
     */
    suspend fun retryDecryptFailed(address: String): Int {
        var retried = 0
        for (row in messageDao.findDecryptFailed(address)) {
            val plaintext = decryptKept(row.ciphertext, address) ?: continue
            messageDao.update(
                row.copy(body = plaintext, decryptFailed = false, ciphertext = null),
            )
            messageDao.refreshConversationPreview(row.conversationId)
            retried++
        }
        if (retried > 0) Timber.i("E2E: %d kept ciphertext(s) decrypted on retry", retried)
        return retried
    }

    private suspend fun handleProbe(
        address: String,
        probe: E2eHandshake.HandshakePacket.Probe,
        subId: Int?,
        now: Long,
        channel: HandshakeChannel,
    ): Boolean {
        // Round 42 diagnostic — the inbound path is Timber-only,
        // and Timber is a no-op in release builds, so without this
        // entry a received probe is invisible on device. Fires
        // past the client-mode gate (E2eDataSmsGate) and the decode
        // + blocked checks, so it means "a probe reached the
        // handler". No keys, no payload — the address names the
        // sender, which is the whole question when a handshake
        // fails.
        recorder.recordEvent("e2e-probe-received", "Probe received from $address; replying with a bundle.")
        val peer = peers.getPeer(address)
        val curveB64 = Base64.getEncoder().encodeToString(probe.curveKey)
        val edB64 = Base64.getEncoder().encodeToString(probe.edKey)
        if (keysChanged(peer, curveB64, edB64)) {
            onKeyChanged(address, peer?.verified == true, now)
        }
        peers.recordKeys(address, curveB64, edB64, now)
        // The exchange is in flight — unless we are already ENCRYPTED
        // (a key change keeps the state, §4.4 step 3).
        if (peer?.state != StoredPeerState.ENCRYPTED) {
            peers.setState(address, StoredPeerState.PROBING, now)
            // Round 48 — record the probe's arrival channel: our
            // bundle reply goes out on it, and the session
            // establishment proves it arrived — so the channel is
            // the proven one for our declaration later. Skipped
            // for an already-ENCRYPTED peer (see the class KDoc).
            peers.recordHandshakeChannel(address, channel, now)
        }
        // Rate limit: at most one bundle per sender per day (§2.3).
        // The keys above are recorded regardless — only the reply is
        // gated, so a re-probe tomorrow still finds fresh keys.
        // Round 47 — a MANUAL probe bypasses the limit: the
        // sender's user tapped Retry, and the tap is pointless if
        // the reply is still gated (the retry failed unless both
        // phones reset timing first). Automatic probes are never
        // marked manual, so the limit still guards against probe
        // spam. The limit is still CONSUMED on a successful manual
        // bundle send (recordBundleReply below), so the next
        // automatic probe is still gated.
        if (!probe.manual && !E2eProbePolicy.canReplyWithBundle(peer?.lastBundleReplyAt, now)) {
            Timber.i("E2E: bundle reply rate-limited (one per sender per day)")
            // Without this entry, "probe received but no
            // bundle-sent and no failure" is ambiguous — with it,
            // every outcome is separable.
            recorder.recordEvent(
                "e2e-bundle-rate-limited",
                "Bundle reply rate-limited for $address (one per sender per day).",
            )
            return true
        }
        val account = accountManager.get()
        val oneTimeKey = account.nextOneTimeKey() ?: run {
            // The pool is empty (a previous reply published the batch):
            // top it up and take a key from the fresh batch.
            account.generateOneTimeKeys()
            account.nextOneTimeKey()
        } ?: run {
            // Still empty after the top-up — the pool is exhausted
            // and no bundle can be built. Timber-only here would
            // hide it in release builds: the probe arrived, the
            // rate limit passed, and still no reply went out.
            recorder.recordEvent(
                "e2e-onetime-key-exhausted",
                "No one-time key available for $address after topping up the pool; no bundle sent.",
            )
            return false
        }
        val keys = account.identityKeys()
        val bundle = E2eHandshake.encodeBundle(keys.curve25519, keys.ed25519, oneTimeKey)
        // Round 45 — the bundle reply follows the probe's arrival
        // channel: a probe that arrived by text is answered by text
        // (that channel is proven to reach the prober), a probe by
        // data SMS by data SMS. Same kind tag either way.
        val sent = when (channel) {
            HandshakeChannel.DATA_SMS -> dataSmsSender.sendDataMessage(
                address = address,
                subId = subId,
                port = E2eHandshake.E2E_DATA_SMS_PORT,
                data = bundle,
                kind = DataSmsKind.BUNDLE,
            )
            HandshakeChannel.TEXT_SMS -> dataSmsSender.sendTextMessage(
                address = address,
                subId = subId,
                text = E2eHandshakeSms.encode(bundle),
                kind = DataSmsKind.BUNDLE,
            )
        }
        // Record the reply ONLY after the bundle SMS went out — a
        // failed send must not consume the day's reply.
        if (!sent) return false
        peers.recordBundleReply(address, now)
        // Round 58 — the automatic fallback: a bundle reply means
        // their probe reached us and we answered, so if the session
        // never establishes (their data SMS drop), the fallback
        // fires ~10 seconds from now. Arming is the handler's job
        // because only it knows a bundle reply went out; the worker
        // re-checks the gate at run time before sending anything.
        autoFallback?.schedule(address)
        // Handing a key out published the whole batch: top the pool
        // back up (and re-save the Account pickle).
        account.generateOneTimeKeys()
        Timber.i("E2E: bundle reply sent")
        recorder.recordEvent("e2e-bundle-sent", "Bundle reply sent to $address.")
        return true
    }

    private suspend fun handleBundle(
        address: String,
        bundle: E2eHandshake.HandshakePacket.Bundle,
        subId: Int?,
        now: Long,
        channel: HandshakeChannel,
    ): Boolean {
        val peer = peers.getPeer(address)
        val wasEncrypted = peer?.state == StoredPeerState.ENCRYPTED
        val curveB64 = Base64.getEncoder().encodeToString(bundle.curveKey)
        val edB64 = Base64.getEncoder().encodeToString(bundle.edKey)
        // Round 48 — the bundle arrived on this channel, so our
        // probe reached them on it: the channel is proven for our
        // outbound handshake packets, and the declaration reuses
        // it. Recorded before the duplicate check: a duplicate
        // bundle is still proof the channel works.
        peers.recordHandshakeChannel(address, channel, now)
        // First reply wins (§2.4 dual-install): a bundle identical to
        // the one that already built our current session is the second
        // install answering the same probe — ignore it.
        if (!keysChanged(peer, curveB64, edB64) &&
            sessions.get().currentSessionId(address) != null
        ) {
            Timber.i("E2E: duplicate bundle ignored (first reply wins)")
            recorder.recordEvent(
                "e2e-bundle-received",
                "Bundle received from $address; duplicate ignored (first reply wins); " +
                    "session created: no; state flipped to ENCRYPTED: no.",
            )
            return true
        }
        if (keysChanged(peer, curveB64, edB64)) {
            onKeyChanged(address, peer?.verified == true, now)
        }
        peers.recordKeys(address, curveB64, edB64, now)
        // The Account must be loaded before creating a session (and a
        // first E2E use creates it here). A throw here used to
        // propagate to the receiver's Timber-only catch — invisible
        // in release builds, and indistinguishable in the log from
        // "no bundle ever arrived". Record it visibly instead.
        val accountError = runCatching { accountManager.get().ensureAccount() }.exceptionOrNull()
        if (accountError is kotlinx.coroutines.CancellationException) throw accountError
        if (accountError != null) {
            recorder.recordEvent(
                "e2e-bundle-received",
                "Bundle received from $address; session created: no; " +
                    "state flipped to ENCRYPTED: no; account load failed.",
            )
            recorder.recordEvent(
                "e2e-account-load-failed",
                "Account load failed for $address " +
                    "(${accountError.javaClass.simpleName}); no session created.",
            )
            return false
        }
        val sessionId = runCatching {
            crypto.get().createOutboundSession(bundle.curveKey, bundle.oneTimeKey)
        }.getOrElse {
            recorder.recordEvent(
                "e2e-bundle-received",
                "Bundle received from $address; session created: no; " +
                    "state flipped to ENCRYPTED: no; session creation failed.",
            )
            recorder.recordEvent(
                "e2e-session-create-failed",
                "Outbound session creation failed for $address " +
                    "(${it.javaClass.simpleName}); peer stays ${peer?.state ?: "unknown"}.",
            )
            // Round 58 — the automatic fallback: their bundle
            // arrived (proof they run Relay — the keys are in the
            // peer row) but no session could be created, so the
            // handshake is stalled with the evidence already in
            // place. Arm the 10-second check: the fallback probe
            // (regular SMS) can restart the exchange where data
            // SMS failed. Not armed on account-load failure (the
            // account itself is broken, so no probe could be sent)
            // or on a duplicate bundle (the session already
            // exists).
            autoFallback?.schedule(address)
            Timber.w(it, "E2E: outbound session creation failed")
            return false
        }
        // The new session becomes current; the old one, if any, stays
        // as a fallback for late messages (§4.2).
        sessions.get().saveNewSession(address, sessionId, now)
        peers.setState(address, StoredPeerState.ENCRYPTED, now)
        recorder.recordEvent(
            "e2e-bundle-received",
            "Bundle received from $address; session created: yes; " +
                "state flipped to ENCRYPTED: ${!wasEncrypted}; duplicate: no.",
        )
        conversationIdForAddress(address)?.let { conversationId ->
            // Round 57 — "[Name] is on Relay!": only a genuinely
            // NEW activation (first establishment, or a
            // re-establishment after DEACTIVATED) earns the popup —
            // [E2eNoticeStore.recordActivated] owns that rule and
            // says so by returning true.
            if (notices.recordActivated(conversationId, now)) {
                activatedNotifier?.notifyActivated(address, conversationId)
            }
        }
        Timber.i("E2E: peer state -> ENCRYPTED (bundle received)")
        retryDecryptFailed(address)
        // Round 48 — the session exists now, so our media
        // capability is worth declaring: the peer learns we can do
        // encrypted media, on the channel this bundle just proved.
        // Best-effort (never throws); a failed send retries on the
        // next thread-open via the announced flag.
        mediaCapability.announceNow(address, subId, now)
        return true
    }

    /**
     * A key-change event (§4.4): the peer's identity keys differ from
     * the ones on file (reinstall / new phone). Messages keep flowing
     * (TOFU + notice): the verified flag clears and an in-thread notice
     * records the change. The caller re-establishes the session.
     */
    private suspend fun onKeyChanged(address: String, wasVerified: Boolean, now: Long) {
        peers.setVerified(address, false, now)
        val kind = if (wasVerified) {
            E2eNoticeKind.KEY_CHANGED_VERIFIED
        } else {
            E2eNoticeKind.KEY_CHANGED
        }
        conversationIdForAddress(address)?.let { conversationId ->
            notices.record(conversationId, kind, now)
            Timber.i("E2E: peer key changed (conversation=%d, wasVerified=%b)", conversationId, wasVerified)
        }
        // Round 49 — the group half: a GROUP_KEY_CHANGED notice in
        // every group where the member held a sender key. Fires
        // even when no 1:1 conversation exists — a member can be
        // in groups without a 1:1 thread.
        groupSessions.onPeerKeyChanged(address, wasVerified, now)
    }

    /** True when the peer has keys on file and either differs from these. */
    private fun keysChanged(peer: E2ePeerEntity?, curveB64: String, edB64: String): Boolean {
        val storedCurve = peer?.theirCurveKey ?: return false
        val storedEd = peer.theirEdKey ?: return false
        return storedCurve != curveB64 || storedEd != edB64
    }

    /** Decrypts one kept ciphertext, or null when it still cannot be read. */
    private suspend fun decryptKept(ciphertext: String?, address: String): String? {
        val payload = if (ciphertext.isNullOrBlank()) {
            null
        } else {
            runCatching {
                E2eWireFormat.parsePayload(Base64.getDecoder().decode(ciphertext))
            }.getOrNull()
        } ?: return null
        // Only text payloads become messages on retry: a kept reaction
        // payload has no placeholder text to restore (reactions fold
        // onto their target on the live path, never as bubbles).
        if (payload.payloadType != E2eWireFormat.PayloadType.TEXT) return null
        return when (val result = decryptor.decrypt(address, payload)) {
            is E2eSessionDecryptor.Outcome.Decrypted ->
                result.plaintext.toString(Charsets.UTF_8)
            E2eSessionDecryptor.Outcome.Failed -> null
        }
    }

    /**
     * The 1:1 conversation for an address, for in-thread notices. Exact
     * CSV match first, then the identity fallback for representation
     * drift (`+33` vs `06`) — the same two-step lookup the reaction
     * fold uses. Null when no conversation exists (yet): a notice has
     * nowhere to render, so it is skipped.
     */
    private suspend fun conversationIdForAddress(address: String): Long? {
        conversationDao.findByAddressesCsv(address)?.let { return it.id }
        val ident = phoneIdentity.snapshot()
        return conversationDao.snapshotOneToOneConversations().firstOrNull { conv ->
            val raw = PhoneAddress.list(conv.addressesCsv).firstOrNull()?.raw
            raw != null && ident.matches(raw, address)
        }?.id
    }
}
