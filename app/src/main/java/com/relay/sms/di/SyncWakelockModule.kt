/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.di

import com.relay.sms.sync.PowerManagerSyncWakelock
import com.relay.sms.sync.SyncWakelock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Sync wakelock — the Hilt binding for the bounded partial
 * wakelock the sync engines hold while a sync run is actively
 * streaming (see [SyncWakelock]). One instance for the app: the
 * lock is a single system resource, and a device is either a hub
 * or a client, never both at once, so the hub engine and the
 * client engine never compete for it.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncWakelockModule {

    /** The sync wakelock is the PowerManager-backed one. */
    @Provides
    @Singleton
    fun provideSyncWakelock(impl: PowerManagerSyncWakelock): SyncWakelock = impl
}
