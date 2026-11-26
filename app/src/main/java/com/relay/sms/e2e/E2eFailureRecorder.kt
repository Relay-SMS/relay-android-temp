/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import com.relay.sms.crashlog.CrashLogSettings
import com.relay.sms.crashlog.CrashReportFormatter
import com.relay.sms.crashlog.CrashReportStore
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records E2E failures in the local error log (Settings →
 * Advanced), so a silent encryption failure leaves a readable
 * trace instead of just a missing fingerprint.
 *
 * Nine writers use it: the [E2eCrypto] provider (a native-load
 * failure that degrades to [UnavailableE2eCrypto]), the
 * Encryption settings ViewModel (an identity load that fails
 * after the library loaded — Keystore, Room, or a native call),
 * the data-SMS sender (a handshake SMS the radio refuses —
 * probe or bundle reply, tagged by kind; recorded sanitized,
 * the exception's class only, so no address can leak in — see
 * [E2eDataSmsSenderImpl]), the sent-intent handler (a handshake
 * SMS the radio accepted and then rejected — the async verdict,
 * tagged by kind, with the result-code name — see
 * [E2eDataSmsSentHandler]), the handshake handler (success
 * events — probe received, bundle sent, bundle rate-limited,
 * bundle received, decode failed, blocked sender, session-create
 * failed, account-load failed, one-time-key exhausted, capability
 * received — via [recordEvent], so the inbound path is visible in
 * release builds, where Timber is a no-op), the probe manager (a
 * manual retry that did not send — gated or failed, with the
 * reason, under `e2e-manual-retry`; automatic gate refusals and
 * sends under `e2e-probe-gated` / `e2e-probe-sent`, with the
 * reason / attempt count, the channel, the trigger, and the
 * address — round 57), the data-SMS receiver (every
 * data SMS arriving on the handshake port, under
 * `e2e-data-sms-received`), the data-SMS gate (every drop with
 * its reason, under `e2e-data-sms-dropped`), and the handshake-SMS
 * gate (an `RLYH:` receipt under `e2e-handshake-sms-received`,
 * and a client-mode swallow under
 * `e2e-handshake-sms-client-mode`).
 * All write through the same [CrashReportStore] the crash
 * handler uses, so the entries appear in the same list, with
 * the exception's simple name as the title (failures) or the
 * tag itself (events) and the full chain in the detail text.
 *
 * The error-log switch gates every write, the same way it gates
 * the crash handler ([CrashLogSettings.enabled] is read live on
 * each call, so turning the log off mid-session takes effect at
 * once). Nothing here logs keys, payload bytes, or message
 * content: the recorded text is the exception chain (our fixed
 * messages, loader messages, Keystore/Room errors, and code
 * locations from the stack trace) — the same content the crash
 * reports already carry — or a fixed event message. Event
 * messages DO carry the sender's address (a phone number already
 * in the user's threads) where the event is per-sender: without
 * it, a log full of "a probe arrived" cannot say from whom,
 * which is the whole question when a third phone cannot complete
 * a handshake. Never a key, a payload byte, or message content.
 *
 * Dedupe: one record per distinct root cause per process. The
 * provider's failure and the ViewModel's failure are often the
 * SAME underlying cause — the fallback wraps the native-load
 * error in an `E2eCryptoException` — and the settings screen
 * can be opened many times, so without dedupe one outage would
 * fill the 50-report rotation. The key is the root cause's
 * class + message (see [E2eDisplayFormat.rootCause]). Events
 * are NEVER deduped: every occurrence is a diagnostic signal —
 * a probe arriving twice means two probes went out, and the
 * tester tapping Retry repeatedly needs to see EACH event with
 * its timestamp (round 44: the old per-process dedupe hid every
 * probe and bundle after the first, so the log looked dead
 * while the handshake was flowing). The 50-report rotation caps
 * the total, and events are bounded per handshake — ~11–13 for
 * one full exchange — so spam is not a risk: the log holds at
 * least one full handshake plus recent crash reports.
 *
 * Never throws: a diagnostics failure must never break the
 * path it is diagnosing.
 */
@Singleton
class E2eFailureRecorder @Inject constructor(
    private val store: CrashReportStore,
    private val settings: CrashLogSettings,
) {

    /** Root causes already recorded this process (class name + message). */
    private val recordedRootCauses: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    /**
     * Records [throwable] under [tag] ("e2e-native-load",
     * "e2e-fingerprint", "e2e-probe-send", "e2e-bundle-send") in
     * the local error log. Skips the write when the error log is
     * off, and when this root cause was already recorded this
     * process.
     */
    fun record(tag: String, throwable: Throwable) {
        runCatching {
            if (!settings.enabled) return@runCatching
            val root = E2eDisplayFormat.rootCause(throwable)
            val key = root.javaClass.name + "|" + (root.message ?: "")
            if (!recordedRootCauses.add(key)) return@runCatching
            val now = System.currentTimeMillis()
            val text = CrashReportFormatter.formatDiagnostic(tag, now, throwable)
            val crashType = throwable.javaClass.simpleName.ifBlank { "Unknown" }
            store.writeReport(text, now, crashType)
        }
    }

    /**
     * Records a SUCCESS event under [tag]
     * ("e2e-probe-received", "e2e-bundle-sent",
     * "e2e-bundle-rate-limited", "e2e-data-sms-received",
     * "e2e-data-sms-dropped", "e2e-decode-failed",
     * "e2e-blocked-sender", "e2e-bundle-received",
     * "e2e-session-create-failed", "e2e-account-load-failed",
     * "e2e-onetime-key-exhausted", "e2e-capability-received",
     * "e2e-handshake-sms-received",
     * "e2e-handshake-sms-client-mode") in the local error log.
     * Success events have no Throwable, so the entry is built
     * from a synthetic marker carrying only the fixed [message]
     * — no keys, no payload bytes, no message content (the
     * sender's address may appear, see the class KDoc) — through
     * the same [CrashReportFormatter.formatDiagnostic] path, with
     * the tag as the list title (the detail text carries the tag
     * line, the marker message, and the call path). Skips the write
     * when the error log is off. NEVER deduped: every occurrence
     * writes an entry with its timestamp (see the class KDoc) —
     * the tester tapping Retry repeatedly must see each probe
     * and bundle, not just the first.
     */
    fun recordEvent(tag: String, message: String) {
        runCatching {
            if (!settings.enabled) return@runCatching
            val now = System.currentTimeMillis()
            val text = CrashReportFormatter.formatDiagnostic(tag, now, E2eDiagnosticEvent(message))
            store.writeReport(text, now, tag)
        }
    }
}

/**
 * Marker throwable for success-event diagnostics: carries only
 * a fixed message — never keys, payload bytes, or message
 * content (the sender's address may appear, see the
 * [E2eFailureRecorder] KDoc). The formatter prints its class +
 * message + the call path, which is the diagnostic payload for
 * an event entry.
 */
class E2eDiagnosticEvent(message: String) : Exception(message)
