/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.di

import android.os.Build
import com.relay.sms.sync.calls.AndroidCallDialer
import com.relay.sms.sync.calls.CallDialer
import com.relay.sms.sync.calls.CallStateObserver
import com.relay.sms.sync.calls.TelephonyCallStateObserver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The device's API level ([Build.VERSION.SDK_INT]), for the
 * end-call API-28 floor in [AndroidCallDialer]. A qualifier so
 * the binding never collides with another plain `Int`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SdkInt

/**
 * Live calls — the Hilt bindings for the hub phone's call
 * signaling ([CallStateObserver]) and dial execution
 * ([CallDialer]). Same shape as [SyncWakelockModule]: one
 * instance for the app. The observer is a single system
 * listener (one registration, shared by every session), and
 * the dialer is stateless.
 *
 * NOTE: the Hilt graph is CI-only — the local harness has no
 * kapt, so these bindings are verified by CI's Build & verify,
 * not by a local build.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncCallsModule {

    /** Call-state observation comes from the telephony listener. */
    @Provides
    @Singleton
    fun provideCallStateObserver(impl: TelephonyCallStateObserver): CallStateObserver = impl

    /** Dialing and ending calls go through the platform telecom dialer. */
    @Provides
    @Singleton
    fun provideCallDialer(impl: AndroidCallDialer): CallDialer = impl

    /** The device's API level, for the end-call API-28 floor. */
    @Provides
    @SdkInt
    fun provideSdkInt(): Int = Build.VERSION.SDK_INT
}
