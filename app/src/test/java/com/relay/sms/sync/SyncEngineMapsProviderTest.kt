/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Round 57 — maps provider sync: the engine's half of the
 * choice + marker sync. The state rides after every snapshot
 * (next to the category mutes), changes on the phone push the
 * state live, and a `set_maps_provider` from the desktop applies
 * through the source and broadcasts back. First sync the phone
 * wins (the desktop has no marker yet); after that the newer
 * write wins, and a stale write is refused with code `stale`.
 */
class SyncEngineMapsProviderTest {

    @Test fun `a sync sends the maps provider state right after the category mutes`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 0))
        source.mapsProviderSnapshot = MapsProviderState(provider = "google", updatedAt = 1_795_000_000_000L)
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            val types = sent.map { it.frameType() }
            assertThat(types).contains("maps_provider")
            assertThat(types.indexOf("maps_provider")).isEqualTo(types.indexOf("sync_complete") + 2)
            assertThat(types.indexOf("category_mutes")).isEqualTo(types.indexOf("sync_complete") + 1)
            val frame = sent.first { it.frameType() == "maps_provider" }.asObject()
            assertThat(frame["provider"]?.jsonPrimitive?.content).isEqualTo("google")
            assertThat(frame["updated_at"]?.jsonPrimitive?.content).isEqualTo("1795000000000")
        }
    }

    @Test fun `a sync sends the never-written default state`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 0))
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            val frame = sent.first { it.frameType() == "maps_provider" }.asObject()
            assertThat(frame["provider"]?.jsonPrimitive?.content).isEqualTo("system")
            assertThat(frame["updated_at"]?.jsonPrimitive?.content).isEqualTo("0")
        }
    }

    @Test fun `an unreadable settings read skips the frame after sync_complete`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 0))
        source.mapsProviderSnapshot = null
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).contains("sync_complete")
            assertThat(sent.map { it.frameType() }).doesNotContain("maps_provider")
        }
    }

    @Test fun `a provider change on the phone pushes the state`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            assertThat(sent).isEmpty()

            assertThat(
                source.mapsProviderFlow.tryEmit(
                    MapsProviderState(provider = "apple", updatedAt = 1_795_000_000_001L),
                ),
            ).isTrue()
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("maps_provider")
            val frame = sent.first().asObject()
            assertThat(frame["provider"]?.jsonPrimitive?.content).isEqualTo("apple")
            assertThat(frame["updated_at"]?.jsonPrimitive?.content).isEqualTo("1795000000001")
        }
    }

    @Test fun `re-emitting an unchanged state sends nothing`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()

            assertThat(
                source.mapsProviderFlow.tryEmit(
                    MapsProviderState(provider = "google", updatedAt = 1_795_000_000_000L),
                ),
            ).isTrue()
            advanceUntilIdle()
            assertThat(sent).hasSize(1)

            // An equal state, re-emitted (an unrelated settings
            // write re-emits the flow upstream) — the collector
            // dedupes it.
            assertThat(
                source.mapsProviderFlow.tryEmit(
                    MapsProviderState(provider = "google", updatedAt = 1_795_000_000_000L),
                ),
            ).isTrue()
            advanceUntilIdle()
            assertThat(sent).hasSize(1)
        }
    }

    @Test fun `set_maps_provider with a newer marker applies, answers ok, and broadcasts the state`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            assertThat(sent).isEmpty()

            engine.onSetMapsProvider(
                SyncProtocol.SetMapsProvider(provider = "google", updatedAt = 1_795_000_000_000L),
            )
            advanceUntilIdle()
            assertThat(source.mapsProviderSets).containsExactly(
                MapsProviderSetCall(provider = "google", updatedAt = 1_795_000_000_000L),
            )

            val result = sent.first { it.frameType() == "action_result" }.asObject()
            assertThat(result["action"]?.jsonPrimitive?.content).isEqualTo("set_maps_provider")
            assertThat(result["ok"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(result.containsKey("conversation_id")).isFalse()

            // The apply updated the settings flow, so the collector
            // broadcast the updated state back to this session.
            val frame = sent.first { it.frameType() == "maps_provider" }.asObject()
            assertThat(frame["provider"]?.jsonPrimitive?.content).isEqualTo("google")
            assertThat(frame["updated_at"]?.jsonPrimitive?.content).isEqualTo("1795000000000")
        }
    }

    @Test fun `a refused set_maps_provider answers ok false and broadcasts nothing`() = runTest {
        val source = FakeSyncMessageSource()
        source.mapsProviderResult = ActionResult(
            ok = false,
            code = SyncProtocol.MessageFailed.STALE,
            message = "Your phone already has a newer maps choice.",
        )
        runEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()

            engine.onSetMapsProvider(
                SyncProtocol.SetMapsProvider(provider = "osm", updatedAt = 1_000L),
            )
            advanceUntilIdle()
            assertThat(source.mapsProviderSets).containsExactly(
                MapsProviderSetCall(provider = "osm", updatedAt = 1_000L),
            )
            val result = sent.first { it.frameType() == "action_result" }.asObject()
            assertThat(result["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(result["code"]?.jsonPrimitive?.content).isEqualTo("stale")
            assertThat(sent.map { it.frameType() }).doesNotContain("maps_provider")
        }
    }
}
