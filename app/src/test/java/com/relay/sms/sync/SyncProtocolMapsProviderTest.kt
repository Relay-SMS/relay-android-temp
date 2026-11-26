/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Round 57 — maps provider sync: the wire shapes of
 * `maps_provider` and `set_maps_provider`. Each side carries its
 * value plus a write marker (`updated_at`): the phone wins on
 * first sync and on a tie, otherwise the newer write wins.
 */
class SyncProtocolMapsProviderTest {

    @Test fun `maps_provider encodes the provider and its marker`() {
        val encoded = SyncProtocol.encodeMapsProvider(
            SyncProtocol.MapsProvider(provider = "google", updatedAt = 1_795_000_000_000L),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("maps_provider")
        assertThat(obj["provider"]?.jsonPrimitive?.content).isEqualTo("google")
        assertThat(obj["updated_at"]?.jsonPrimitive?.content).isEqualTo("1795000000000")
    }

    @Test fun `maps_provider encodes the never-written default`() {
        val encoded = SyncProtocol.encodeMapsProvider(
            SyncProtocol.MapsProvider(provider = "system", updatedAt = 0L),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("maps_provider")
        assertThat(obj["provider"]?.jsonPrimitive?.content).isEqualTo("system")
        assertThat(obj["updated_at"]?.jsonPrimitive?.content).isEqualTo("0")
    }

    @Test fun `set_maps_provider parses`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"set_maps_provider","provider":"osm","updated_at":1795000000000}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.SetMapsProvider(
                SyncProtocol.SetMapsProvider(provider = "osm", updatedAt = 1_795_000_000_000L),
            ),
        )
    }

    @Test fun `set_maps_provider with an unknown provider string still parses`() {
        // Lenient parsing: the provider is just a string on the
        // wire — an unknown one parses fine and is refused at
        // apply time (`invalid_request`), never a crash.
        assertThat(
            SyncProtocol.parse(
                """{"type":"set_maps_provider","provider":"waze","updated_at":1795000000000}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.SetMapsProvider(
                SyncProtocol.SetMapsProvider(provider = "waze", updatedAt = 1_795_000_000_000L),
            ),
        )
    }

    @Test fun `set_maps_provider without updated_at is malformed`() {
        // Like `set_muted`'s `value`, the marker is required — a
        // missing marker is never an undated write.
        assertThat(
            SyncProtocol.parse("""{"type":"set_maps_provider","provider":"google"}"""),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `set_maps_provider without a provider is malformed`() {
        assertThat(
            SyncProtocol.parse("""{"type":"set_maps_provider","updated_at":1795000000000}"""),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `maps_provider from the client side is unknown`() {
        // The phone only SENDS `maps_provider` — an inbound one
        // is not a frame the server accepts.
        assertThat(
            SyncProtocol.parse(
                """{"type":"maps_provider","provider":"google","updated_at":1795000000000}""",
            ),
        ).isEqualTo(SyncProtocol.Parsed.Unknown("maps_provider"))
    }
}
