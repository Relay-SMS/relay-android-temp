/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.system.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.relay.sms.core.ext.stripInvisibleChars
import com.relay.sms.di.ApplicationScope
import com.relay.sms.di.IoDispatcher
import com.relay.sms.domain.settings.AppSettingsSource
import com.relay.sms.e2e.E2eDataSmsGate
import com.relay.sms.e2e.E2eFailureRecorder
import com.relay.sms.e2e.E2eHandshake
import com.relay.sms.e2e.E2eHandshakeHandler
import com.relay.sms.e2e.ParsedDataSms
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Receives the handshake data SMS (design §2.3): probes and bundle
 * replies on port [E2eHandshake.E2E_DATA_SMS_PORT], declared in the
 * manifest with the `sms://localhost:<port>` data filter.
 *
 * Data SMS never reach the system inbox and never become messages:
 * this receiver parses the packet, records its arrival in the
 * error log (`e2e-data-sms-received`), and hands the cached parse
 * to [E2eDataSmsGate], which hands the raw bytes to
 * [E2eHandshakeHandler] — the handler validates the magic +
 * version and drops anything else, recording the decode reason
 * (`e2e-decode-failed`). There is no salvage path (unlike
 * [SmsDeliverReceiver]): a dropped handshake packet is not a lost
 * user message — the next probe restarts the exchange.
 *
 * Thin glue only: the decision (action check, client-mode drop,
 * handler call) lives in [E2eDataSmsGate], which is testable on plain
 * JVM. The handler is resolved lazily on the IO dispatcher: it reaches
 * Room through the peer store, and field injection happens on the main
 * thread before `onReceive` runs.
 *
 * Client mode — a linked device drops the packet (recorded as
 * `e2e-data-sms-dropped`, see [E2eDataSmsGate]): it cannot do E2E
 * messaging (no default-SMS role, no SMS_DELIVER), so answering a
 * handshake addressed to its own number would provision an
 * identity it must never hold.
 */
@AndroidEntryPoint
class E2eDataSmsReceiver : BroadcastReceiver() {

    @Inject lateinit var handshakeHandlerLazy: dagger.Lazy<E2eHandshakeHandler>

    @Inject lateinit var settingsLazy: dagger.Lazy<AppSettingsSource>

    @Inject lateinit var failureRecorderLazy: dagger.Lazy<E2eFailureRecorder>

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION) return
        val subId = intent.extractIncomingSubId()
        val pending = goAsync()
        scope.launch {
            try {
                // The parse runs FIRST, before the gate's mode check
                // — and the arrival is recorded before anything can
                // drop it. This is the "did the PDU reach the app"
                // line: without it, a data SMS the radio never
                // delivers and one the gate drops look identical
                // in the log (nothing). Sender address, byte count
                // and the type byte when the header is readable —
                // never payload bytes or key material.
                val parsed = runCatching {
                    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                        ?: emptyArray()
                    messages.firstOrNull()?.let {
                        ParsedDataSms(
                            address = it.displayOriginatingAddress?.stripInvisibleChars(),
                            data = it.userData,
                        )
                    }
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                }.getOrNull()
                val data = parsed?.data
                val typeByte = if (data != null && data.size >= 6) {
                    "0x%02X".format(data[5].toInt() and 0xFF)
                } else {
                    "unknown"
                }
                failureRecorderLazy.get().recordEvent(
                    "e2e-data-sms-received",
                    "Data SMS received from ${parsed?.address ?: "unknown sender"}; " +
                        "${data?.size ?: 0} bytes; type $typeByte.",
                )
                E2eDataSmsGate(
                    settingsLazy,
                    handshakeHandlerLazy,
                    ioDispatcher,
                    failureRecorderLazy,
                ).handle(
                    intent.action,
                    subId,
                ) { parsed }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "E2E: data SMS handling failed")
            } finally {
                pending.finish()
            }
        }
    }
}
