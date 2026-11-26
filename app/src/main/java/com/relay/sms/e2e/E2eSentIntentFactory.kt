/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 43 — builds the sent [PendingIntent] for a handshake data
 * SMS, so the radio's accept/reject verdict lands in
 * [E2eDataSmsSentReceiver] instead of vanishing into a
 * fire-and-forget send.
 *
 * One intent per send: the request code is a per-instance counter,
 * so two sends in flight (a probe and a bundle reply) never share
 * an intent and never overwrite each other's kind extra.
 * [PendingIntent.FLAG_IMMUTABLE] (Android 12+): the kind and
 * address extras are fixed at creation and the radio must not
 * mutate them.
 *
 * Round 57 — the intent also carries the destination address
 * ([E2eDataSmsSentReceiver.EXTRA_ADDRESS]), so the radio's verdict
 * can name the peer it concerns (the receiver passes it to
 * [E2eDataSmsSentHandler.handle]). The address is a phone number
 * already in the user's threads; it never leaves the app (the
 * PendingIntent targets our own receiver explicitly).
 *
 * Never throws: a failure to build the intent returns null and the
 * sender falls back to fire-and-forget — a diagnostic that cannot
 * break the send it observes.
 */
@Singleton
class E2eSentIntentFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val requestCodes = AtomicInteger(0)

    fun sentIntent(kind: DataSmsKind, address: String): PendingIntent? {
        return runCatching {
            PendingIntent.getBroadcast(
                context,
                requestCodes.incrementAndGet(),
                Intent(context, E2eDataSmsSentReceiver::class.java).apply {
                    action = E2eDataSmsSentReceiver.ACTION_DATA_SMS_SENT
                    putExtra(E2eDataSmsSentReceiver.EXTRA_KIND, kind.name)
                    putExtra(E2eDataSmsSentReceiver.EXTRA_ADDRESS, address)
                },
                PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()
    }
}
