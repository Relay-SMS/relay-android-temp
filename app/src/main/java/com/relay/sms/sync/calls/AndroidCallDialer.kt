/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.sync.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

/**
 * The real [CallDialer]: places a cellular call from a linked
 * desktop's `dial_request`, and ends the current call for an
 * `end_call_request`.
 *
 * The dial goes through `TelecomManager.placeCall`, NOT an
 * `ACTION_CALL` intent — that is the whole point of this class.
 * The sync server runs in a background service, and since Android
 * 10 the OS silently blocks a background app from starting an
 * activity: `startActivity(ACTION_CALL)` returns normally with
 * NO exception, the dialer reports success, the phone answers
 * `dial_result ok: true` — and no call is ever placed (the
 * desktop sits on "Calling" forever, the phone's call app never
 * opens). `placeCall` is a call into the system telecom service
 * instead of an activity start, so the background-activity-start
 * block does not apply: the telecom service itself brings up the
 * default dialer's in-call UI. It needs CALL_PHONE (checked
 * first, same as before) and works for any app holding it.
 *
 * Ending goes through `TelecomManager.endCall`, which needs
 * only ANSWER_PHONE_CALLS — NOT the default-dialer role (the
 * round-29 note claiming otherwise was wrong: verified from
 * AOSP `TelecomServiceImpl`, `endCall` enforces only the
 * answer-call permission, no dialer-role check anywhere). It
 * ends the foreground call, else the first ACTIVE / DIALING /
 * PULLING / RINGING / ON_HOLD call, and the OS refuses emergency
 * calls and self-managed calls — a `false` return is that
 * refusal (or the call ending in the race), not a crash. Only
 * ANSWERING a call still goes over Bluetooth HFP.
 *
 * `ACTION_CALL` stays as a fallback if `placeCall` throws: a
 * throw means the call was NOT placed, so the fallback cannot
 * double-dial, and it covers the case where the app happens to
 * be in the foreground (where an activity start is allowed).
 *
 * The number goes through `Uri.fromParts("tel", …)` instead of
 * `Uri.parse("tel:$number")`: a desktop-sent number can carry
 * spaces or a `+`, which `parse` would leave unencoded. No
 * number ever reaches the log (the sync layer logs counts only
 * — names and numbers stay out of logcat).
 *
 * The call itself is the phone's own cellular call: it rings
 * through the earpiece/speaker exactly as a call dialed on the
 * phone would, and the desktop's audio path (Bluetooth HFP) is
 * separate work on the desktop side. The observer reports the
 * resulting off-hook transition back as `call_state` `active`,
 * so the desktop sees its own dial land — and the hangup as
 * `call_state` `ended`, so the desktop sees its own end land.
 */
class AndroidCallDialer @Inject constructor(
    @ApplicationContext private val context: Context,
) : CallDialer {

    override suspend fun dial(number: String): DialOutcome {
        val cleaned = number.trim()
        if (cleaned.isBlank()) return DialOutcome.NoNumber
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Timber.w("AndroidCallDialer: CALL_PHONE not granted — dial refused")
            return DialOutcome.PermissionDenied
        }
        val uri = Uri.fromParts("tel", cleaned, null)
        // The telecom service places the call (see the class note):
        // no telephony on this device, no call to place.
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: return DialOutcome.Failed
        return try {
            telecom.placeCall(uri, Bundle.EMPTY)
            DialOutcome.Success
        } catch (se: SecurityException) {
            // The permission was revoked between the check and the
            // call, or the OS refused for another reason.
            Timber.w(se, "AndroidCallDialer: dial refused by the OS")
            DialOutcome.Failed
        } catch (t: Throwable) {
            // `placeCall` threw before placing anything — fall
            // back to the intent, which works when the app is in
            // the foreground. If that throws too, the dial failed.
            Timber.w(t, "AndroidCallDialer: placeCall failed, trying ACTION_CALL")
            dialViaIntent(uri)
        }
    }

    override suspend fun endCall(): EndCallOutcome {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ANSWER_PHONE_CALLS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Timber.w("AndroidCallDialer: ANSWER_PHONE_CALLS not granted — end refused")
            return EndCallOutcome.PermissionDenied
        }
        // No telephony on this device, no call to end.
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: return EndCallOutcome.Failed
        if (!telecom.isInCall) return EndCallOutcome.NoCall
        return try {
            if (telecom.endCall()) {
                EndCallOutcome.Success
            } else {
                // The OS refused (emergency or self-managed
                // call), or the call ended in the race.
                Timber.w("AndroidCallDialer: endCall refused by the OS")
                EndCallOutcome.Failed
            }
        } catch (se: SecurityException) {
            // `endCall` can only throw this for the missing
            // permission — revoked between the check and the call.
            Timber.w(se, "AndroidCallDialer: end refused by the OS")
            EndCallOutcome.PermissionDenied
        } catch (t: Throwable) {
            Timber.w(t, "AndroidCallDialer: endCall failed")
            EndCallOutcome.Failed
        }
    }

    private fun dialViaIntent(uri: Uri): DialOutcome {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = uri
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            DialOutcome.Success
        } catch (t: Throwable) {
            Timber.w(t, "AndroidCallDialer: dial failed (no dialer?)")
            DialOutcome.Failed
        }
    }
}
