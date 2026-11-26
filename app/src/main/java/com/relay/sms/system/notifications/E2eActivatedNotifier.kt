/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.system.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.relay.sms.MainActivity
import com.relay.sms.R
import com.relay.sms.data.local.datastore.SettingsRepository
import com.relay.sms.di.IoDispatcher
import com.relay.sms.domain.repository.ContactRepository
import com.relay.sms.domain.settings.NotificationStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 57 — "[Name] is on Relay!".
 *
 * Posted once per ACTIVATED episode: the caller fires this exactly
 * when [com.relay.sms.e2e.E2eNoticeStore.recordActivated] returns
 * true — a genuinely new activation, including a re-activation after
 * an "Encryption ended" notice — from the bundle path
 * ([com.relay.sms.e2e.E2eHandshakeHandler]) and from the receive
 * path ([com.relay.sms.e2e.E2eReceiveManager], first encrypted
 * message on a new session). A re-establishment while the thread
 * already shows encryption as active records no notice and posts
 * nothing. The thread-open backfill
 * ([com.relay.sms.e2e.E2eNoticeStore.backfillActivatedIfNeeded]) does
 * NOT post: it repairs a past event while the user is already
 * looking at the thread.
 *
 * The notification carries the contact's avatar (large icon, from
 * [ContactRepository.openPhotoStream] — full-size photo first,
 * thumbnail fallback) and name, falling back to the number when
 * there is no contact. Tapping it opens the conversation, through
 * the same authenticated open intent as the incoming-message
 * notifier ([NotificationIntentToken]).
 *
 * Channel fit: the incoming-messages channel, via
 * [canalDesMessages] — so the user's notification style governs
 * this popup exactly as it governs message popups (SILENT routes
 * to the low-importance channel, HEADS_UP / BANNER to the high
 * one, with the BANNER sound cut like the message notifier does).
 * No new channel: this is a message-thread event, not a new
 * notification family.
 *
 * Privacy: the shared [CorrespondentVisibilityPolicy] decides what
 * the notification may say — a vault conversation or a panic-decoy
 * session posts nothing, and a hidden-preview setting anonymises
 * the name ("A contact is on Relay!"). Notifications disabled in
 * Settings, or POST_NOTIFICATIONS refused on Android 13+, also
 * post nothing. The notification id is stable per conversation
 * (same tag + id), so a re-activation replaces the previous popup
 * instead of stacking a second one.
 */
@Singleton
class E2eActivatedNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val contacts: ContactRepository,
    private val visibilite: CorrespondentVisibilityPolicy,
    private val intentToken: NotificationIntentToken,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Posts "[Name] is on Relay!" for the newly activated
     * conversation. Best-effort and never throwing: the E2E paths
     * that call this must not fail because a notification could
     * not be posted.
     */
    suspend fun notifyActivated(address: String, conversationId: Long) {
        runCatching { notifyActivatedOrThrow(address, conversationId) }
            .onFailure { Timber.w(it, "E2eActivatedNotifier: could not post activation notification") }
    }

    private suspend fun notifyActivatedOrThrow(address: String, conversationId: Long) = withContext(io) {
        val appSettings = settings.hydratedOrNull()
        val notifSettings = appSettings?.notifications
        // Unreadable settings fail OPEN (same repli as the message
        // notifier's FALLBACK_NOTIFICATIONS): silencing an event
        // the user asked to see is worse than posting once, and
        // nothing private leaks — the privacy guards below still
        // apply either way.
        if (notifSettings?.enabled == false) return@withContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext
            }
        }

        val displayName = runCatching { contacts.lookupByPhone(address) }
            .getOrNull()?.displayName?.takeIf { it.isNotBlank() } ?: address
        val (title, body) = when (visibilite.verdictPour(address, conversationId)) {
            CorrespondentVisibilityPolicy.Verdict.TAIRE -> return@withContext
            CorrespondentVisibilityPolicy.Verdict.ANONYMISER ->
                context.getString(R.string.app_name) to
                    context.getString(R.string.e2e_activated_notif_body_anonymous)
            CorrespondentVisibilityPolicy.Verdict.NOMMER ->
                displayName to context.getString(R.string.e2e_activated_notif_body, displayName)
        }

        val channelId = canalDesMessages(notifSettings?.style ?: NotificationStyle.HEADS_UP)
        val openIntent = PendingIntent.getActivity(
            context,
            E2E_ACTIVATED_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .setAction(IncomingMessageNotifier.ACTION_OPEN_CONVERSATION)
                .putExtra(IncomingMessageNotifier.EXTRA_ADDRESS, address)
                .putExtra(IncomingMessageNotifier.EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(NotificationIntentToken.EXTRA_NAV_TOKEN, intentToken.current())
                // The identity the PendingIntent carries for
                // `filterEquals`: without it this open intent could
                // collide with a message notification's open intent
                // (same action, extras don't count — cf. the
                // F19 fix in IncomingMessageNotifier).
                .setData(
                    android.net.Uri.parse("relay://e2e-activated/$conversationId"),
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(visibilite.ecranVerrouille().versNotificationCompat())
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .also { builder ->
                loadAvatarBitmap(address)?.let { builder.setLargeIcon(it) }
                // BANNER style: cut the sound so Android does not
                // infer a heads-up, like the message notifier.
                if (notifSettings?.style == NotificationStyle.BANNER) {
                    builder.setSound(null)
                    builder.setDefaults(0)
                }
            }
            .build()
        NotificationManagerCompat.from(context).notify(
            conversationTagFor(conversationId),
            E2E_ACTIVATED_NOTIFICATION_ID,
            notif,
        )
    }

    /**
     * The contact's photo as a notification large icon, sampled
     * down to [AVATAR_MAX_PX] on the long side (the shade shows it
     * at ~64dp; the full-size photo would waste memory). Null when
     * there is no contact, no photo, or the bytes cannot be read —
     * the notification posts without an avatar then.
     *
     * Guarded end to end: `BitmapFactory.Options()` itself throws
     * `Stub!` on plain JVM (cf. AGENTS.md), so a test classpath
     * must never reach an unguarded decode here.
     */
    private suspend fun loadAvatarBitmap(address: String): Bitmap? {
        val bytes = runCatching {
            contacts.openPhotoStream(address)?.use { it.readBytes() }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            var sampleSize = 1
            while (maxSide / sampleSize > AVATAR_MAX_PX) sampleSize *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    companion object {
        /**
         * The notification id, stable per conversation: the tag
         * ([conversationTagFor]) is what distinguishes
         * conversations, so one id serves them all — and a
         * re-activation for the same conversation replaces the
         * previous popup. 60_000 sits outside the ranges the
         * other notifiers use (incoming 1xx, sent 2xx, safety
         * call 5xx, emergency 7xx, MMS failure 80_000–89_999).
         */
        const val E2E_ACTIVATED_NOTIFICATION_ID = 60_000

        /** Long side of the sampled avatar, in pixels. */
        private const val AVATAR_MAX_PX = 192
    }
}
