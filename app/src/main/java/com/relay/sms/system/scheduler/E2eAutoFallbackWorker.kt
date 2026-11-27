/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.system.scheduler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.relay.sms.e2e.E2eAutoFallback
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Round 58 — the one-time check behind the gated automatic
 * `RLYH:` fallback ([E2eAutoFallback]).
 *
 * Armed by [E2eAutoFallback.schedule] whenever a bundle reply is
 * sent or a bundle arrives without a session: 10 seconds after
 * that handshake activity, the worker re-reads the peer row and
 * re-checks the gate ([E2eAutoFallback.maybeSend]) before
 * sending anything — the delay is the stall window, not a
 * promise to send. The work is unique per peer
 * (`e2e-auto-fallback:<canonical address>`) with `REPLACE`, so
 * the latest handshake activity re-arms the timer: a bundle
 * reply sent now supersedes one armed 10 seconds ago.
 *
 * The worker NEVER retries: every outcome — sent, skipped, or
 * failed — returns `Result.success()`. A systematic failure
 * must not wake the phone indefinitely, and the thread-open
 * evaluation in `E2eProbeManager.onThreadOpened` is the
 * backstop — it re-evaluates on the next thread open, and a
 * failed send does not consume the fallback's cap, so nothing is
 * lost by not retrying here. Skips ARE logged (with the reason)
 * — one evaluation per arm, so the log shows why the fallback
 * did or did not fire.
 */
@HiltWorker
class E2eAutoFallbackWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val autoFallback: E2eAutoFallback,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val address = inputData.getString(KEY_ADDRESS)
        if (address == null) {
            Timber.w("E2E auto fallback: no address in the work input, nothing to do")
        } else {
            // The worker logs its skips (logSkipped = true): one
            // evaluation per arm, so the log shows the decision.
            autoFallback.maybeSend(address, logSkipped = true)
        }
        Result.success()
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        // Bounded by design (see the class KDoc): a failure here
        // is not retried — the thread-open backstop covers it.
        Timber.w(t, "E2E auto fallback: check failed")
        Result.success()
    }

    companion object {
        private const val KEY_ADDRESS = "address"

        /**
         * Arms the 10-second check for [canonicalAddress]: a
         * one-time request, delayed [E2eAutoFallback.STALL_MS]
         * (the stall window), unique per peer with `REPLACE` so
         * the latest handshake activity re-arms the timer.
         */
        fun enqueue(context: Context, canonicalAddress: String) {
            val request = OneTimeWorkRequestBuilder<E2eAutoFallbackWorker>()
                .setInitialDelay(E2eAutoFallback.STALL_MS, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_ADDRESS to canonicalAddress))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "e2e-auto-fallback:$canonicalAddress",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
