/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import android.content.Context
import com.relay.sms.core.ext.canonicalPhone
import com.relay.sms.data.local.db.entity.E2ePeerEntity
import com.relay.sms.data.local.db.entity.E2ePeerState
import com.relay.sms.domain.purge.MS_PER_DAY
import com.relay.sms.domain.repository.BlockedNumberRepository
import com.relay.sms.domain.settings.AppSettingsSource
import com.relay.sms.system.scheduler.E2eAutoFallbackWorker
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 58 — the gated AUTOMATIC `RLYH:` fallback (design §2.4).
 *
 * The problem (device-proven 2026-11-26): two fresh installs stuck
 * at PROBING because one phone's outbound data SMS vanish in
 * transit — the probe reaches the peer, the peer's bundle reply
 * never arrives, and the handshake stalls forever. The manual
 * "Retry another way" (a regular-SMS `RLYH:` probe) connects
 * instantly, because regular SMS does not drop on that line.
 * This class sends that same probe automatically — but ONLY to a
 * peer the phone has proof runs Relay, so no non-Relay contact
 * ever receives Relay gibberish.
 *
 * The gate ([evaluate], pure — see its KDoc for the check order)
 * is threefold:
 * - EVIDENCE: the peer row must carry proof the peer runs Relay
 *   (their identity keys, a bundle reply we sent, a capability
 *   declaration, or a recorded handshake channel). No evidence →
 *   never send. On a Relay phone the `RLYH:` text is intercepted
 *   and never shown, so the gibberish objection does not apply.
 * - STALL: the handshake must be stalled for [STALL_MS] (10 seconds)
 *   past the last handshake activity (the later of our last
 *   bundle reply and our last probe). Ten seconds is the floor:
 *   the round trip is ~2-3 seconds when it works (device-proven:
 *   the probe was received 1s after send), so 10 seconds is
 *   3-5x that margin. Below ~10s the fallback would routinely
 *   fire before a healthy data reply could arrive — carrier
 *   delays of a few seconds are normal — and turn into a
 *   parallel send on every new contact (an extra SMS even when
 *   data SMS works fine). At 10s a healthy data path almost
 *   always wins, and a stalled one is rescued in ~10s. Once
 *   the data-SMS handshake stalls, nothing recovers it on its
 *   own — the phone's own next data probe is 24h away, and in
 *   every observed stall the messages never arrived, not even
 *   minutes later — and firing early costs at most one
 *   invisible regular SMS plus a duplicate bundle the handler
 *   already ignores.
 *
 *   Honest limit: the worker's 10s delay is a minimum, not
 *   exact — Doze can defer it, so in practice the worker fires
 *   ~10s to a couple of minutes after the stall. The thread-open
 *   backstop fires immediately when the user opens the thread
 *   (once 10s have passed since the anchor).
 * - CAP: at most one automatic probe per peer per [CAP_MS]
 *   (24 hours), tracked in the peer row's own timestamp
 *   (`lastAutoFallbackAt`, schema v27) — deliberately separate
 *   from the data-SMS probe budget, which an automatic fallback
 *   never consumes (it never calls `recordProbeSent`, never
 *   flips the peer to NOT_RELAY).
 *
 * Trigger — two-fold, so the 2026-11-26 scenario is covered
 * without a new background service or permission:
 * 1. [E2eAutoFallbackWorker], a one-time WorkManager job armed
 *    by [schedule] whenever a bundle reply is sent (in
 *    [E2eHandshakeHandler.handleProbe]) or a bundle arrives
 *    without a session (in [E2eHandshakeHandler.handleBundle]).
 *    Round 60 — also armed whenever a probe goes out (in
 *    [E2eProbeManager.probeAddressDetailed]): a probe send
 *    re-arms the 10-second timer, so a worker armed before the
 *    probe cannot wake and skip as "not stalled yet" — the
 *    round-58 known limitation below is closed by this arming.
 *    The work is unique per peer with `REPLACE`, so the latest
 *    handshake activity re-arms the 10-second timer, and the worker
 *    re-reads the peer row at run time and re-checks the gate
 *    before sending. This is the path that fires with no user
 *    action: in the 2026-11-26 test the partner's phone had
 *    received Kristin's probe and sent a bundle, so its worker
 *    fires ~10 seconds later and the pair connects on its own.
 * 2. A thread-open evaluation in [E2eProbeManager.onThreadOpened],
 *    alongside the normal probe. It covers pre-existing stalls
 *    (a pair already stuck when the app updates — no new bundle
 *    reply will arm a worker for them) and the worker-skipped
 *    case below. Thread-open only, never send.
 *
 * Known limitation (round 58, CLOSED in round 60): a data-SMS
 * probe sent AFTER a worker was armed did not re-arm it (only
 * bundle replies did, to avoid touching the shared probe core) —
 * so a worker could wake and skip as "not stalled yet" if a probe
 * went out in the meantime. Round 60 arms the worker on every
 * probe send ([E2eProbeManager.probeAddressDetailed]), so the
 * latest handshake activity — probe or bundle — re-arms the
 * timer. The thread-open check remains as the backstop for a pair
 * already stalled when the app updates.
 *
 * `autoDiscoverRelayContacts` is RESPECTED (the fallback does not
 * fire while discovery is off). It is an automatic outbound
 * probe, and the toggle is the user's "do not probe
 * automatically" switch — the evidence gate already limits the
 * send to proven-Relay contacts, but the toggle still governs
 * whether the phone initiates anything on its own. Inbound
 * handshakes are unaffected (they are not probes). When the
 * setting is durably unreadable the gate fails OPEN, like
 * [E2eProbeManager.gateFor]: a broken settings read must not
 * silently disable a feature the user left on.
 *
 * The send reuses the manual path's transport and wire format
 * ([E2eDataSmsSender.sendTextMessage] with a regular-SMS
 * `RLYH:` probe, `kind = AUTO_FALLBACK` so the send's own
 * failure/sent-ok tags are separable in the error log) — the
 * bundle reply follows the probe's channel, which is what makes
 * the fallback work where data SMS drops.
 */
@Singleton
class E2eAutoFallback @Inject constructor(
    private val peers: E2ePeerRepository,
    private val accountManager: Lazy<E2eAccountManager>,
    private val dataSmsSender: E2eDataSmsSender,
    private val settings: AppSettingsSource,
    private val blockedRepo: BlockedNumberRepository,
    private val recorder: E2eFailureRecorder,
    @ApplicationContext private val context: Context,
) {

    /**
     * Evaluates the gate for [address] and, when it fires, sends
     * the automatic fallback probe (a regular-SMS `RLYH:` probe,
     * the manual path's transport and wire format). The result
     * says what happened; a [AutoFallbackResult.Skipped] is
     * recorded in the error log only when [logSkipped] is true —
     * the worker logs its skips (one evaluation per arm), the
     * thread-open backstop does not (it runs on every open).
     *
     * Throws only when a dependency fails (account load, peer
     * read, blocked check, settings read): callers wrap the call
     * in `runCatching` and log, like the probe path. A radio
     * refusal is NOT a throw — it returns
     * [AutoFallbackResult.SendFailed] and records nothing, so a
     * later evaluation may retry.
     */
    suspend fun maybeSend(
        address: String,
        now: Long = System.currentTimeMillis(),
        logSkipped: Boolean = false,
    ): AutoFallbackResult {
        val peer = peers.getPeer(address)
        val isBlocked = blockedRepo.isBlocked(address)
        val autoDiscover = settings.hydratedOrNull()?.encryption?.autoDiscoverRelayContacts ?: true
        val key = peer?.address ?: address.canonicalPhone()
        return when (val decision = evaluate(peer, address, isBlocked, autoDiscover, now)) {
            is Decision.Fire -> send(address, key, decision.evidence, now)
            is Decision.Skip -> {
                if (logSkipped) {
                    recorder.recordEvent(
                        SKIPPED_TAG,
                        "Automatic fallback skipped for $key (${decision.reason.label}).",
                    )
                }
                AutoFallbackResult.Skipped(decision.reason)
            }
        }
    }

    /**
     * Arms the 10-second check for [address]: enqueues the
     * one-time [E2eAutoFallbackWorker] for the canonical peer
     * address. Unique work with `REPLACE`, so the latest
     * handshake activity re-arms the timer — a bundle reply sent
     * now supersedes one armed 10 seconds ago.
     */
    fun schedule(address: String) {
        E2eAutoFallbackWorker.enqueue(context, address.canonicalPhone())
    }

    private suspend fun send(
        address: String,
        key: String,
        evidence: String,
        now: Long,
    ): AutoFallbackResult {
        // The default SMS subscription from settings, `null`
        // (system default) when unreadable — the same as the probe
        // path (design §11.1).
        val subId = runCatching { settings.hydratedOrNull()?.sending?.defaultSubId }
            .getOrNull()
        val keys = accountManager.get().ensureAccount()
        // `manual = true` here is NOT a claim that the user tapped
        // anything: on the wire the flag is the receiver's bundle
        // rate-limit bypass (round 47) — a receiver that already
        // answered a data-SMS probe from this sender within the day
        // answers a manual-flagged probe anyway. The automatic
        // fallback needs exactly that bypass, for the same reason a
        // manual retry does: the peer already answered our
        // data-SMS probe (that is the stall's evidence), so a plain
        // probe would be rate-limited on their phone and the
        // fallback would fail silently. The flag's only
        // receiver-side effect is that bypass
        // (E2eHandshakeHandler.handleProbe); it does not change the
        // packet format, the channel, or the session.
        val probe = E2eHandshake.encodeProbe(keys.curve25519, keys.ed25519, manual = true)
        val sent = dataSmsSender.sendTextMessage(
            address,
            subId,
            E2eHandshakeSms.encode(probe),
            kind = DataSmsKind.AUTO_FALLBACK,
        )
        if (!sent) return AutoFallbackResult.SendFailed
        // Recorded AFTER the radio accepted the send, so a failed
        // send does not consume the cap. The data-SMS probe budget
        // is untouched: no `recordProbeSent`, no NOT_RELAY flip.
        peers.recordAutoFallback(address, now)
        recorder.recordEvent(
            SENT_TAG,
            "Automatic fallback sent to $key (channel=TEXT_SMS, evidence=$evidence).",
        )
        return AutoFallbackResult.Sent
    }

    companion object {
        /** How long the handshake must be stalled before the fallback may fire (10 seconds). */
        const val STALL_MS: Long = 10_000L

        /** The fallback's own cap: at most one automatic probe per peer per 24 hours. */
        const val CAP_MS: Long = MS_PER_DAY

        /** Error-log tag for a sent automatic fallback probe. */
        const val SENT_TAG = "e2e-auto-fallback-sent"

        /** Error-log tag for a considered-but-skipped automatic fallback (worker only). */
        const val SKIPPED_TAG = "e2e-auto-fallback-skipped"

        /**
         * The pure gate: decides whether the automatic fallback
         * probe may be sent to [address] right now. Pure — no
         * reads, no sends — so it is fully unit-testable. The
         * checks run in this order (the first hit wins, and its
         * reason is the one logged):
         * 1. the address must be a probeable phone number
         *    ([E2eProbePolicy.isProbeableAddress]) — a short code
         *    or a blocked number never gets an automatic probe;
         * 2. discovery must be on (see the class KDoc for why);
         * 3. a peer row must exist AND carry proof the peer runs
         *    Relay ([evidenceOf]) — no evidence, no send, ever;
         * 4. the peer must not already be ENCRYPTED;
         * 5. the handshake must be stalled: the later of the last
         *    bundle reply and the last probe must be at least
         *    [STALL_MS] ago. Both null means no anchor — the
         *    stall cannot be measured yet (a capability-only peer
         *    has evidence but no anchor; the next thread-open
         *    probe sets it) — so it does not fire;
         * 6. the fallback's own cap: no automatic probe within
         *    the last [CAP_MS].
         */
        fun evaluate(
            peer: E2ePeerEntity?,
            address: String,
            isBlocked: Boolean,
            autoDiscover: Boolean,
            now: Long,
        ): Decision {
            // Read once, up front — the `when` below is a single
            // return (Detekt's ReturnCount is 4), and the branches
            // still fire in the documented order, first hit wins.
            val evidence = peer?.let { evidenceOf(it) }
            val anchor = peer?.let {
                listOfNotNull(it.lastBundleReplyAt, it.lastProbeAt).maxOrNull()
            }
            val lastFallback = peer?.lastAutoFallbackAt
            return when {
                !E2eProbePolicy.isProbeableAddress(address) ->
                    Decision.Skip(SkipReason.InvalidAddress)
                isBlocked -> Decision.Skip(SkipReason.Blocked)
                !autoDiscover -> Decision.Skip(SkipReason.DiscoveryOff)
                peer == null -> Decision.Skip(SkipReason.NoEvidence)
                peer.state == E2ePeerState.ENCRYPTED ->
                    Decision.Skip(SkipReason.AlreadyEncrypted)
                evidence == null -> Decision.Skip(SkipReason.NoEvidence)
                anchor == null || now - anchor < STALL_MS ->
                    Decision.Skip(SkipReason.NotStalledYet)
                lastFallback != null && now - lastFallback < CAP_MS ->
                    Decision.Skip(SkipReason.Capped)
                else -> Decision.Fire(evidence)
            }
        }

        /**
         * The proof this peer runs Relay, strongest first, or
         * null when the row carries none. Their identity keys
         * only arrive in a bundle (bundle-received); a bundle
         * reply we sent means their probe reached us
         * (probe-received); a capability declaration is a direct
         * statement (capability); a recorded handshake channel
         * means a probe arrived while not ENCRYPTED or a bundle
         * arrived — the belt-and-braces case, kept last because
         * the first three already cover the paths that set it.
         */
        private fun evidenceOf(peer: E2ePeerEntity): String? = when {
            peer.theirCurveKey != null || peer.theirEdKey != null -> "bundle-received"
            peer.lastBundleReplyAt != null -> "probe-received"
            peer.mediaCapable || peer.groupCapable || peer.groupMediaCapable -> "capability"
            peer.handshakeChannel != null -> "handshake-channel"
            else -> null
        }
    }
}

/** The outcome of one [E2eAutoFallback.evaluate] call. */
sealed interface Decision {
    /** Fire: send the automatic fallback probe; [evidence] names the proof (the log label). */
    data class Fire(val evidence: String) : Decision

    /** Do not fire; [reason] is the PII-free log label. */
    data class Skip(val reason: SkipReason) : Decision
}

/** Why the fallback did not fire — the `e2e-auto-fallback-skipped` reason. */
enum class SkipReason(val label: String) {
    /** The peer is already ENCRYPTED — nothing to fall back from. */
    AlreadyEncrypted("already encrypted"),

    /** No proof the peer runs Relay — the core of the feature. */
    NoEvidence("no proof the peer runs Relay"),

    /** The handshake has not been stalled for [E2eAutoFallback.STALL_MS] yet. */
    NotStalledYet("handshake not stalled long enough"),

    /** An automatic probe was already sent within [E2eAutoFallback.CAP_MS]. */
    Capped("fallback already sent within 24 hours"),

    /** The `autoDiscoverRelayContacts` toggle is off. */
    DiscoveryOff("discovery is off"),

    /** The address is not a probeable phone number (short code, service number). */
    InvalidAddress("not a phone number"),

    /** The number is blocked. */
    Blocked("number is blocked"),
}

/** The outcome of one [E2eAutoFallback.maybeSend] call. */
sealed interface AutoFallbackResult {
    /** The fallback probe was sent (the radio accepted it). */
    data object Sent : AutoFallbackResult

    /** The fallback did not fire; [reason] says why. */
    data class Skipped(val reason: SkipReason) : AutoFallbackResult

    /** The radio refused the send; nothing was recorded, so a later evaluation may retry. */
    data object SendFailed : AutoFallbackResult
}
