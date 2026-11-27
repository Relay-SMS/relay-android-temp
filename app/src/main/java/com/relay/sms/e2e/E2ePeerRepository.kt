/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import com.relay.sms.core.ext.canonicalPhone
import com.relay.sms.data.local.db.dao.E2ePeerDao
import com.relay.sms.data.local.db.entity.E2ePeerEntity
import com.relay.sms.data.local.db.entity.E2ePeerState
import com.relay.sms.domain.purge.MS_PER_DAY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Peer state CRUD + probe budget bookkeeping (design §2.4, §4.3).
 *
 * One row per peer address holds the key-exchange state machine and the probe
 * budget: at most [MAX_PROBE_ATTEMPTS] attempts per [PROBE_WINDOW_MS] window, at
 * least [PROBE_MIN_INTERVAL_MS] apart. The window resets when it elapses — a peer
 * that installs Relay later is re-probed in the next window, and a NOT_RELAY peer
 * becomes probeable again.
 *
 * [canProbe] answers the BUDGET question only (window + spacing). State
 * eligibility — which states may be probed (UNKNOWN / NOT_RELAY, or ENCRYPTED for
 * a decrypt-failure re-probe) — belongs to the state machine, which reads the
 * state via [state] and decides. Splitting the two keeps the budget testable in
 * isolation and the policy in one place.
 *
 * Round 60 — the re-probes inside the first hour after a probe have
 * their OWN allowance ([E2eProbeRetryPolicy.MAX_RETRIES] per
 * one-hour window, tracked in `retry_attempts` /
 * `retry_window_started_at`): [recordRetryProbeSent] bumps only
 * that count, never the 30-day budget, so the retries cannot burn
 * the budget in 30 minutes. Every [recordProbeSent] opens a fresh
 * retry window; the timing reset clears both allowances.
 *
 * The bundle-reply rate limit (design §2.3, at most one bundle per sender per
 * day) is persisted here too via [recordBundleReply]; the eligibility check
 * lives in [E2eProbePolicy], which reads the stored timestamp.
 *
 * Every write is a targeted DAO update touching disjoint columns (never a whole-row
 * copy): a bundle arriving between a read and a write cannot be clobbered by a
 * stale probe bookkeeping write.
 *
 * Round 42 — the address is canonicalized at this boundary: every
 * method normalizes its address to [canonicalPhone] (formatting
 * stripped, NANP country code unified — see the round-46 note
 * below) before touching the DAO. The readers and writers used to
 * disagree — the thread UI observed the normalized form while the
 * probe path wrote the raw conversation form and the handshake
 * handler wrote the carrier form — and the DAO matches exactly,
 * so a peer whose number was stored with formatting had its probe
 * writes land under a key the UI never observed: the Details
 * panel stayed on UNKNOWN no matter what happened, and the
 * safety-number sheet found no keys. One canonical key per peer,
 * whatever form each caller passes. Rows stored under a
 * non-normalized form before this change become unreachable
 * (their peer re-exchanges from UNKNOWN on the next probe); rows
 * already in E.164 form are unaffected — normalization is
 * idempotent.
 *
 * Round 46 — the round-42 key was `normalizePhone` alone, which
 * strips formatting but does NOT reunite the E.164 form
 * (`"+16108423232"`) with the national form (`"6108423232"`):
 * the bundle arrived under `"+1…"`, the UI and the send path
 * read `"610…"`, the thread stayed PROBING and the message went
 * out plain (device-proven 2026-11-23). The canonical key is now
 * [canonicalPhone], which strips the NANP country code to the
 * 10-digit form. Rows stored under E.164 forms before this
 * change become unreachable and re-establish from UNKNOWN on the
 * next probe (no migration); rows already in 10-digit national
 * form are unaffected.
 */
@Singleton
class E2ePeerRepository @Inject constructor(
    private val peerDao: E2ePeerDao,
) {

    /** The peer's row, or null when no exchange has ever started with this address. */
    suspend fun getPeer(address: String): E2ePeerEntity? =
        peerDao.findByAddress(address.canonicalPhone())

    /** The peer's state; UNKNOWN when no row exists (nothing has happened yet). */
    suspend fun state(address: String): E2ePeerState =
        peerDao.findByAddress(address.canonicalPhone())?.state ?: E2ePeerState.UNKNOWN

    /**
     * Live view of one peer's row (null = no exchange has ever started).
     * The thread UI collects it: the composer placeholder, the send icon
     * and the details-panel row follow the key exchange live.
     */
    fun observePeer(address: String): Flow<E2ePeerEntity?> =
        peerDao.observeByAddress(address.canonicalPhone())

    /**
     * Live view of the verified contacts, most recently updated first
     * (Settings → Verified contacts).
     */
    fun observeVerifiedPeers(): Flow<List<E2ePeerEntity>> = peerDao.observeVerifiedPeers()

    // The live ENCRYPTED-address set ([observeEncryptedAddresses],
    // collected by the conversation list for its avatar lock badges)
    // lives below with the sync API (§9.2) — one definition, shared
    // by the list and the sync engine.

    /**
     * E2E sync (round 42) — every peer row, address order: the
     * device-link snapshot source (the safety-number + verified
     * set). One-shot, for the snapshot paths.
     */
    suspend fun allPeers(): List<E2ePeerEntity> = peerDao.allPeers()

    /**
     * E2E sync (round 42) — the live version of [allPeers]:
     * re-emits whenever any `e2e_peers` row changes, so a state
     * flip, a key arrival, or a verified toggle re-sends the full
     * set to every linked device.
     */
    fun observeAllPeers(): Flow<List<E2ePeerEntity>> = peerDao.observeAllPeers()

    /** Returns the peer's row, creating it in state UNKNOWN when it does not exist yet. */
    suspend fun getOrCreateUnknown(
        address: String,
        now: Long = System.currentTimeMillis(),
    ): E2ePeerEntity {
        val key = address.canonicalPhone()
        peerDao.insert(E2ePeerEntity(address = key, updatedAt = now))
        // The insert is IGNORE on conflict, so the row is either the one just
        // inserted or the pre-existing one — re-read to return the real row.
        return checkNotNull(peerDao.findByAddress(key)) {
            "peer row missing right after insert"
        }
    }

    /** Sets the peer's state, creating the row in that state when it does not exist yet. */
    suspend fun setState(
        address: String,
        state: E2ePeerState,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = address.canonicalPhone()
        getOrCreateUnknown(key, now)
        peerDao.updateState(key, state, now)
    }

    /** Records the peer's identity keys from a received bundle (base64, 32 bytes each). */
    suspend fun recordKeys(
        address: String,
        theirCurveKey: String,
        theirEdKey: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = address.canonicalPhone()
        getOrCreateUnknown(key, now)
        peerDao.updateKeys(key, theirCurveKey, theirEdKey, now)
    }

    /**
     * Sets (or clears, on a key change) the verified flag. Never
     * sent to the peer — the flag is a local trust marker, not
     * part of the key exchange. It IS synced to the user's own
     * linked devices over the encrypted device link (round 42),
     * so the desktop shows the same verified state as the phone.
     */
    suspend fun setVerified(
        address: String,
        verified: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = address.canonicalPhone()
        getOrCreateUnknown(key, now)
        peerDao.setVerified(key, verified, now)
    }

    /**
     * Round 48 — whether this peer declared it handles encrypted
     * media (the 0x03 capability packet). False when no row exists
     * or no declaration ever arrived: an old build never declares,
     * so media to it stays plain. The sender gate
     * ([E2eMediaGateImpl]) reads this before encrypting any MMS.
     */
    suspend fun isMediaCapable(address: String): Boolean =
        peerDao.findByAddress(address.canonicalPhone())?.mediaCapable == true

    /**
     * Round 48 — records that this peer declared it handles
     * encrypted media. Called only by the declaration receive path
     * ([E2eMediaCapabilityManager.onDeclarationReceived]) — the
     * flag is the peer's own statement about itself, never a
     * sender-side guess.
     */
    suspend fun setMediaCapable(
        address: String,
        capable: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = address.canonicalPhone()
        getOrCreateUnknown(key, now)
        peerDao.setMediaCapable(key, capable, now)
    }

    /**
     * Round 48 — whether WE already sent our media-capability
     * declaration to this peer (and the radio accepted it). False
     * when no row exists: the thread-open hook sends the
     * declaration exactly once per peer, and a failed send leaves
     * this false so the next thread-open retries.
     */
    suspend fun isMediaAnnounced(address: String): Boolean =
        peerDao.findByAddress(address.canonicalPhone())?.mediaAnnounced == true

    /**
     * Round 48 — records that our declaration went out to this
     * peer. Call AFTER the radio accepted the send — a failed send
     * must not consume the one-shot (see [isMediaAnnounced]).
     */
    suspend fun setMediaAnnounced(
        address: String,
        announced: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = address.canonicalPhone()
        getOrCreateUnknown(key, now)
        peerDao.setMediaAnnounced(key, announced, now)
    }

    /**
     * Round 49 — whether this peer declared it handles encrypted
     * groups (the 0x03 capability packet's group flag). False
     * when no row exists or no declaration ever arrived: an old
     * build never declares, so a group with them stays plain.
     * The group state derivation ([E2eGroupState]) reads this
     * before a group may encrypt.
     */
    suspend fun isGroupCapable(address: String): Boolean =
        peerDao.findByAddress(address.canonicalPhone())?.groupCapable == true

    /**
     * Round 49 — records that this peer declared it handles
     * encrypted groups. Called only by the declaration receive
     * path ([E2eMediaCapabilityManager.onDeclarationReceived]) —
     * the flag is the peer's own statement about itself, never a
     * sender-side guess.
     */
    suspend fun setGroupCapable(
        address: String,
        capable: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = address.canonicalPhone()
        getOrCreateUnknown(key, now)
        peerDao.setGroupCapable(key, capable, now)
    }

    /**
     * Round 49 — whether WE already sent our group-capability
     * declaration to this peer (and the radio accepted it). False
     * when no row exists: the thread-open hook sends the
     * declaration exactly once per peer, and a failed send leaves
     * this false so the next thread-open retries. The declaration
     * is one packet for media + groups, but the flags are
     * separate — a peer announced in round 48 (media only) still
     * needs the round-49 declaration, which the unset group flag
     * triggers.
     */
    suspend fun isGroupAnnounced(address: String): Boolean =
        peerDao.findByAddress(address.canonicalPhone())?.groupAnnounced == true

    /**
     * Round 49 — records that our declaration went out to this
     * peer. Call AFTER the radio accepted the send — a failed send
     * must not consume the one-shot (see [isGroupAnnounced]).
     */
    suspend fun setGroupAnnounced(
        address: String,
        announced: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = address.canonicalPhone()
        getOrCreateUnknown(key, now)
        peerDao.setGroupAnnounced(key, announced, now)
    }

    /**
     * Round 54 — this peer's group-media capability as one read:
     * whether they declared they handle encrypted media in groups
     * (the 0x03 capability packet's group-media flag, bit 2) and
     * whether WE already sent our declaration to them. False/false
     * when no row exists or no declaration ever arrived: a
     * round-49 build never declares it, so group media with it
     * stays plain even though group text encrypts. The group-media
     * send gate reads [capable] before a group MMS may encrypt;
     * the capability manager reads [announced] before re-declaring.
     * One read (not two flag reads) because the two flags always
     * travel together — one packet carries both, and the announce
     * rule checks all three announced flags at once.
     */
    data class GroupMediaCapability(val capable: Boolean, val announced: Boolean)

    suspend fun groupMediaCapability(address: String): GroupMediaCapability {
        val row = peerDao.findByAddress(address.canonicalPhone())
        return GroupMediaCapability(
            capable = row?.groupMediaCapable == true,
            announced = row?.groupMediaAnnounced == true,
        )
    }

    /**
     * Round 54 — records one side of this peer's group-media
     * capability: [capable] is the peer's own declaration about
     * itself (called only by the declaration receive path —
     * [E2eMediaCapabilityManager.onDeclarationReceived] — never a
     * sender-side guess); [announced] records that OUR declaration
     * went out to this peer (call AFTER the radio accepted the
     * send — a failed send must not consume the one-shot). A null
     * side is left untouched, so each caller sets only its own
     * flag. One setter (not a pair) keeps this repository under
     * Detekt's function-count threshold.
     */
    suspend fun setGroupMediaCapability(
        address: String,
        capable: Boolean? = null,
        announced: Boolean? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = address.canonicalPhone()
        getOrCreateUnknown(key, now)
        capable?.let { peerDao.setGroupMediaCapable(key, it, now) }
        announced?.let { peerDao.setGroupMediaAnnounced(key, it, now) }
    }

    /**
     * Round 48 — the channel our outbound handshake packets
     * provably used for this peer, or null when the handshake
     * predates round 48 and no channel was ever recorded. The
     * column stores the [HandshakeChannel] name raw (the enum
     * lives in `:app`, the entity in `:data`); an unrecognized
     * stored value reads as null — the declaration then goes out
     * on both channels, which is always safe.
     */
    suspend fun handshakeChannel(address: String): HandshakeChannel? {
        val raw = peerDao.findByAddress(address.canonicalPhone())?.handshakeChannel
            ?: return null
        return runCatching { HandshakeChannel.valueOf(raw) }.getOrNull()
    }

    /**
     * Round 48 — records the channel our outbound handshake
     * packets provably used for this peer. Called when a bundle
     * arrives (our probe reached them on that channel) and when a
     * probe arrives while not yet ENCRYPTED (our bundle reply goes
     * out on that channel, and the session establishment proves
     * it arrived). Never called for an already-ENCRYPTED peer's
     * probe — a later exchange on another channel must not
     * overwrite the proven one.
     */
    suspend fun recordHandshakeChannel(
        address: String,
        channel: HandshakeChannel,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = address.canonicalPhone()
        getOrCreateUnknown(key, now)
        peerDao.setHandshakeChannel(key, channel.name, now)
    }

    /**
     * Why the probe BUDGET refuses another probe now, or null when
     * it allows one: [ProbeGate.BudgetExhausted] when the attempt
     * cap is spent inside the window, [ProbeGate.ProbeTooSoon]
     * when the last probe was less than 24 hours ago. Round 59 —
     * split out of the old `canProbe` Boolean: the two refusals
     * used to share one reason, so the error log said "probe
     * budget exhausted" when the budget was not spent. The Boolean
     * wrapper is gone with it (its only caller moved to this
     * reason, and the class sits at Detekt's TooManyFunctions
     * ceiling). State eligibility is the caller's decision (see
     * the class KDoc).
     */
    suspend fun canProbeReason(address: String, now: Long = System.currentTimeMillis()): ProbeGate? {
        val peer = peerDao.findByAddress(address.canonicalPhone()) ?: return null
        val windowStart = peer.windowStartedAt
        if (windowStart == null || now - windowStart >= PROBE_WINDOW_MS) return null
        if (peer.probeAttempts >= MAX_PROBE_ATTEMPTS) return ProbeGate.BudgetExhausted
        val lastProbe = peer.lastProbeAt
        return if (lastProbe == null || now - lastProbe >= PROBE_MIN_INTERVAL_MS) {
            null
        } else {
            ProbeGate.ProbeTooSoon
        }
    }

    /**
     * Records a probe send: bumps the attempt count (resetting the window when 30
     * days have elapsed) and moves the peer to PROBING. Call AFTER the probe data
     * SMS is sent — a failed send must not consume budget.
     *
     * Round 60 — every probe also opens a fresh one-hour retry
     * window (the DAO resets `retry_attempts` to 0 and restarts
     * `retry_window_started_at` at the probe time): a new probe
     * round earns its own re-probes, and the retries themselves
     * never call this — they call [recordRetryProbeSent], which
     * leaves the 30-day budget untouched.
     */
    suspend fun recordProbeSent(address: String, now: Long = System.currentTimeMillis()) {
        val key = address.canonicalPhone()
        val peer = getOrCreateUnknown(key, now)
        val windowStart = peer.windowStartedAt
        val (attempts, windowStartedAt) =
            if (windowStart == null || now - windowStart >= PROBE_WINDOW_MS) {
                1 to now
            } else {
                peer.probeAttempts + 1 to windowStart
            }
        peerDao.recordProbe(key, attempts, windowStartedAt, now, now)
        peerDao.updateState(key, E2ePeerState.PROBING, now)
    }

    /**
     * Round 60 — records a re-probe send inside the retry window
     * (design §2.4): bumps only the retry count (and moves
     * `lastProbeAt` to the send time). Call AFTER the probe data
     * SMS is sent — a failed send must not consume the retry
     * allowance. The 30-day budget is untouched: a retry never
     * bumps [probeAttempts], never resets [windowStartedAt], and
     * never flips the peer to NOT_RELAY — the caller
     * ([E2eProbeManager]) checks the retry gate first
     * ([E2eProbeRetryPolicy.canRetry]) and keeps the state
     * PROBING. The peer row must exist (a retry only ever follows
     * a recorded probe); like [recordProbeSent], this creates it
     * in UNKNOWN first, so a missing row cannot lose the count.
     */
    suspend fun recordRetryProbeSent(address: String, now: Long = System.currentTimeMillis()) {
        val key = address.canonicalPhone()
        val peer = getOrCreateUnknown(key, now)
        peerDao.recordRetryProbe(key, peer.retryAttempts + 1, now, now)
    }

    /**
     * Records a bundle reply sent to this peer (design §2.3): at most one
     * bundle per sender per day. Call AFTER the bundle SMS is sent — a failed
     * send must not consume the day's reply. The caller checks the rate limit
     * first via [E2eProbePolicy.canReplyWithBundle] on the stored timestamp
     * (null when no reply was ever sent).
     */
    suspend fun recordBundleReply(address: String, now: Long = System.currentTimeMillis()) {
        val key = address.canonicalPhone()
        getOrCreateUnknown(key, now)
        peerDao.recordBundleReply(key, now, now)
    }

    /**
     * Round 58 — records an automatic fallback probe sent to this
     * peer (a regular-SMS `RLYH:` probe). Call AFTER the radio
     * accepted the send — a failed send must not consume the
     * fallback's own cap (one per 24 hours, read from this
     * timestamp). The data-SMS probe budget is untouched: an
     * automatic fallback never consumes probe attempts and never
     * flips the peer to NOT_RELAY.
     */
    suspend fun recordAutoFallback(address: String, now: Long = System.currentTimeMillis()) {
        val key = address.canonicalPhone()
        getOrCreateUnknown(key, now)
        peerDao.recordAutoFallback(key, now, now)
    }

    /** Verified contacts, most recently updated first (Settings → Verified contacts). */
    suspend fun verifiedPeers(): List<E2ePeerEntity> = peerDao.verifiedPeers()

    /**
     * Round 43 — testing/diagnostic reset (Settings → Advanced):
     * clears the probe-budget and bundle-reply timing for ALL
     * peers (attempt counts, window starts, last-probe and
     * last-bundle-reply times) and returns every non-ENCRYPTED
     * peer to UNKNOWN, so encryption can be tested again right
     * away instead of waiting out the 24-hour bundle rate limit
     * or the 30-day probe window. Keys, sessions, verified flags
     * and ENCRYPTED states are untouched — a working pair stays
     * encrypted and verified. Round 58 — the automatic fallback's
     * own cap (last fallback time) is cleared too: otherwise a
     * reset would leave the fallback gated for 24 hours and the
     * re-test could not exercise it. The reset runs on THIS phone
     * only: the partner pair's blocker is the partner's bundle
     * rate limit, so the reset must be run on that phone too.
     */
    suspend fun resetEncryptionTiming(now: Long = System.currentTimeMillis()) {
        peerDao.resetTiming(now)
    }

    /**
     * E2E sync (design §9.2) — the addresses whose peer state is
     * ENCRYPTED, as a set: the hub maps them onto the 1:1
     * conversations it syncs (the `encrypted` flag on
     * `SyncedConversation`). One-shot, for the snapshot paths.
     */
    suspend fun encryptedAddresses(): Set<String> =
        peerDao.encryptedAddresses().toSet()

    /**
     * E2E sync (design §9.2) — the live version of
     * [encryptedAddresses]: re-emits whenever any `e2e_peers` row
     * changes, so a state flip to ENCRYPTED re-emits the hub's
     * conversation list and the sync engine pushes a
     * `conversation_updated` for the flipped thread.
     */
    fun observeEncryptedAddresses(): Flow<Set<String>> =
        peerDao.observeEncryptedAddresses().map { it.toSet() }

    /**
     * Round 49 (E2E groups, design §13) — the addresses whose
     * peer state is ENCRYPTED AND whose Relay version supports
     * group sender keys: a group encrypts only when EVERY member
     * is in this set. One-shot, for the snapshot paths.
     */
    suspend fun groupEncryptedAddresses(): Set<String> =
        peerDao.groupEncryptedAddresses().toSet()

    /**
     * Round 49 — the live version of [groupEncryptedAddresses]:
     * re-emits whenever any `e2e_peers` row changes, so a member
     * flip re-emits the hub's conversation list and the sync
     * engine pushes a `conversation_updated` for the flipped
     * group thread.
     */
    fun observeGroupEncryptedAddresses(): Flow<Set<String>> =
        peerDao.observeGroupEncryptedAddresses().map { it.toSet() }

    /** Deletes a peer's row (panic wipe / reset — its sessions are deleted separately). */
    suspend fun deletePeer(address: String) {
        peerDao.deleteByAddress(address.canonicalPhone())
    }

    companion object {
        /** Probe attempts allowed per window (design §2.4). */
        const val MAX_PROBE_ATTEMPTS = 3

        /** The probe budget window: 30 days (design §2.4). */
        const val PROBE_WINDOW_MS: Long = 30 * MS_PER_DAY

        /** Minimum spacing between two probes to the same peer: 24 hours (design §2.4). */
        const val PROBE_MIN_INTERVAL_MS: Long = MS_PER_DAY
    }
}
