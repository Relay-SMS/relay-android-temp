/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.e2e

import android.content.Context
import com.relay.sms.system.scheduler.E2eProbeRetryWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 60 — arms the automatic re-probe worker
 * ([E2eProbeRetryWorker]).
 *
 * A one-method seam, so [E2eProbeManager] — which owns no `Context`
 * — can arm the retry schedule without taking one: the manager calls
 * [schedule] after a probe send, and the worker re-checks the retry
 * gate ([E2eProbeRetryPolicy]) at run time before sending anything.
 * The worker itself re-arms for the next retry in the window; this
 * class only arms the first one (and any re-arm after a fresh probe
 * supersedes a pending one — unique work with `REPLACE`).
 */
@Singleton
class E2eProbeRetryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Arms one re-probe check for [address] after [delayMs]: a
     * one-time [E2eProbeRetryWorker] run, unique per peer with
     * `REPLACE`, so the latest probe send re-arms the timer.
     * [address] is canonicalized by the worker's enqueue.
     */
    fun schedule(address: String, delayMs: Long) {
        E2eProbeRetryWorker.enqueue(context, address, delayMs)
    }
}
