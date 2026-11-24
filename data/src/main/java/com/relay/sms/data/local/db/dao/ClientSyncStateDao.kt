/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.relay.sms.data.local.db.entity.ClientSyncStateEntity

/** Client mode — per-hub sync state: the mode choice + the cursor. */
@Dao
interface ClientSyncStateDao {

    @Query("SELECT * FROM client_sync_state WHERE hub_id = :hubId LIMIT 1")
    suspend fun get(hubId: String): ClientSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ClientSyncStateEntity)

    @Query("DELETE FROM client_sync_state WHERE hub_id = :hubId")
    suspend fun delete(hubId: String)

    /** Wipes every hub's sync state (Re-sync / Unlink / mode switch). */
    @Query("DELETE FROM client_sync_state")
    suspend fun deleteAll()

    /** The hub ids with stored sync state (the hub-change check reads this). */
    @Query("SELECT hub_id FROM client_sync_state")
    suspend fun listHubIds(): List<String>
}
