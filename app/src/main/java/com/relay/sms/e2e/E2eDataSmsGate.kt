/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import android.provider.Telephony
import com.relay.sms.domain.settings.AppSettings
import com.relay.sms.domain.settings.AppSettingsSource
import com.relay.sms.domain.settings.DeviceMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The decision logic behind
 * [com.relay.sms.system.receiver.E2eDataSmsReceiver] (design §2.3),
 * kept out of the receiver so it is testable on plain JVM: the receiver
 * is a Hilt entry point, and Hilt weaves an `inject(context)` call into
 * its real `onReceive` — a mocked `Context` has no application
 * component, so calling the real `onReceive` under MockK throws
 * `MockKException` (CI run 44935640392: all three receiver tests failed
 * at the `callOriginal()` stub, while the same tests passed locally
 * where no Hilt transform runs). The receiver itself stays thin glue:
 * action guard, `goAsync`, message parsing, error logging.
 *
 * One call handles one received intent:
 * 1. A non-data-SMS action returns `false` before any settings read
 *    (and before parsing).
 * 2. Client mode drops the packet — a linked device must
 *    never hold an E2E identity (no default-SMS role, no SMS_DELIVER),
 *    so no handler call happens. The receiver has already parsed
 *    it (for the arrival line), but the gate itself never asks
 *    for the parse in client mode — [first] is not called.
 * 3. Otherwise the first parsed message is handed to the handler on
 *    the IO dispatcher (it reaches Room through the peer store).
 *
 * [first] is a supplier, not a value, so the gate stays testable
 * on plain JVM with a canned parse — and so the receiver can parse
 * once, record the arrival (`e2e-data-sms-received`), and hand the
 * gate the cached value. The parse itself now runs before the mode
 * check (in the receiver): the arrival line is the diagnostic that
 * proves the PDU reached the app at all, and it must fire even for
 * a packet the gate is about to drop in client mode.
 * A null message, a null address or null user data drops too
 * (a malformed packet is not a lost user message — the next probe
 * restarts the exchange) — recorded, not silent (see below).
 *
 * Every drop is recorded in the error log via [E2eFailureRecorder]
 * (`e2e-data-sms-dropped`, with a fixed reason word) — a drop the
 * receiver never sees is otherwise invisible in release builds,
 * where Timber is a no-op. The receiver itself records the arrival
 * first (`e2e-data-sms-received`), so arrival-without-handling
 * narrows to exactly one of these drop reasons.
 */
class E2eDataSmsGate(
    private val settingsLazy: dagger.Lazy<AppSettingsSource>,
    private val handlerLazy: dagger.Lazy<E2eHandshakeHandler>,
    private val ioDispatcher: CoroutineDispatcher,
    private val recorderLazy: dagger.Lazy<E2eFailureRecorder>,
) {
    suspend fun handle(
        action: String?,
        subId: Int?,
        first: () -> ParsedDataSms?,
    ): Boolean {
        // The two pre-parse drops, in order: a non-data-SMS action
        // returns before any settings read, and client mode drops
        // before any parse (see the class KDoc). One early return
        // for both, so the drop reason stays a single value.
        val preParseDrop = when {
            action != Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION -> "wrong-action"
            // Same gate shape as `ScheduledSendAttempt`
            // (`hydratedOrNull`, never `state.value` — a cold
            // process has not hydrated yet and the default would
            // read as hub mode).
            (settingsLazy.get().hydratedOrNull() ?: AppSettings()).mode == DeviceMode.CLIENT ->
                "client-mode"
            else -> null
        }
        if (preParseDrop != null) {
            recordDrop(preParseDrop)
            return false
        }
        val message = first()
        val address = message?.address
        val data = message?.data
        when {
            message == null -> recordDrop("no-message")
            address == null -> recordDrop("null-address")
            data == null -> recordDrop("null-data")
            else -> {
                withContext(ioDispatcher) {
                    handlerLazy.get().handleDataSms(address, data, subId)
                }
                return true
            }
        }
        return false
    }

    /** One line in the error log for a dropped packet, with a fixed reason word. */
    private fun recordDrop(reason: String) {
        recorderLazy.get().recordEvent(
            "e2e-data-sms-dropped",
            "Data SMS dropped: $reason.",
        )
    }
}

/**
 * One parsed data SMS: the sender's address (invisible characters
 * already stripped) and the raw binary payload — the probe or bundle
 * bytes, never text. Either field may be null on a malformed packet.
 */
data class ParsedDataSms(
    val address: String?,
    val data: ByteArray?,
)
