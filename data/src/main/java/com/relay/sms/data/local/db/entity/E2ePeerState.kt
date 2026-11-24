/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.local.db.entity

/**
 * E2E encryption (schema v22) — one peer's place in the key-exchange state machine
 * (design §4.3). Stored BY NAME in `e2e_peers.state`, like the sticky category
 * (see [com.relay.sms.data.local.db.MessageEnumConverters]): add entries, never
 * rename them. An unknown name — a row from a future build — reads as [UNKNOWN]
 * instead of crashing the read.
 *
 * The 1:1 conversation's encryption state is derived from its single address's peer
 * state. A GROUP's encryption state is derived from ALL its members' peer states
 * (round 49, design §13): encrypted only when every member is ENCRYPTED and
 * group-capable.
 */
enum class E2ePeerState {
    /** Nothing has happened yet: no probe sent, no bundle received. */
    UNKNOWN,

    /** A probe is out (or a bundle reply was sent) and the exchange is in flight. */
    PROBING,

    /** A live session exists: messages to this peer go out encrypted. */
    ENCRYPTED,

    /** The probe budget is exhausted with no reply — the peer does not run Relay (yet). */
    NOT_RELAY,
}
