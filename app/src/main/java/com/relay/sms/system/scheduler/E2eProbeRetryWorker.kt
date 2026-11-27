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
import com.relay.sms.core.ext.canonicalPhone
import com.relay.sms.e2e.E2eProbeManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Round 60 — the one-time check behind the automatic re-probe
 * schedule ([com.relay.sms.e2e.E2eProbeRetryPolicy]).
 *
 * Armed by [com.relay.sms.e2e.E2eProbeRetryScheduler.schedule]
 * whenever a probe goes out: at the next retry's due time the worker
 * asks [E2eProbeManager.retryUnansweredHandshake] to send the
 * re-probe — which re-checks the retry gate (still PROBING, inside
 * the first-hour window, retries left, due) before sending anything,
 * so a handshake that completed in the meantime sends nothing. The
 * worker never retries: every outcome — sent, skipped, or failed —
 * returns `Result.success()`. A systematic failure must not wake the
 * phone indefinitely, and the thread-open / send triggers in
 * `E2eProbeManager` are the backstop — they send a due retry
 * opportunistically, and a failed send consumes nothing, so nothing
 * is lost by not retrying here. Skips are NOT logged: a worker can
 * wake to find the handshake already complete, which is the common
 * case, not a diagnosis.
 */
@HiltWorker
class E2eProbeRetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val probeManager: E2eProbeManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val address = inputData.getString(KEY_ADDRESS)
        if (address == null) {
            Timber.w("E2E probe retry: no address in the work input, nothing to do")
        } else {
            probeManager.retryUnansweredHandshake(address)
        }
        Result.success()
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        // Bounded by design (see the class KDoc): a failure here
        // is not retried — the thread-open backstop covers it.
        Timber.w(t, "E2E probe retry: check failed")
        Result.success()
    }

    companion object {
        private const val KEY_ADDRESS = "address"

        /**
         * Arms one re-probe check for [address] after [delayMs]: a
         * one-time request, unique per peer with `REPLACE` so the
         * latest probe send re-arms the timer. The address is
         * canonicalized here, so the work name and the worker's
         * lookup key agree whatever form the caller passes.
         */
        fun enqueue(context: Context, address: String, delayMs: Long) {
            val canonicalAddress = address.canonicalPhone()
            val request = OneTimeWorkRequestBuilder<E2eProbeRetryWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_ADDRESS to canonicalAddress))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "e2e-probe-retry:$canonicalAddress",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
