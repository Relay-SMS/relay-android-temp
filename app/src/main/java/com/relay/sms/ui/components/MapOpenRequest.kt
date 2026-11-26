/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.components

/**
 * Round 55 — a map open the thread gates behind the "Set your map
 * provider" sheet ([MapProviderSheet]): the first map tap shows the
 * sheet instead of opening, and the sheet's choice (or dismissal)
 * opens the pending request with the chosen (or current) provider.
 *
 * Round 56 — the inline map preview card is gone (Kristin tested
 * Apple/Google/OSM and none of them really work): the bubble keeps
 * only the address text + the "Get directions" pill, so the only
 * request left is [OpenDirections]. The card's image-tap open went
 * with it.
 *
 * Lives in its own file because Detekt's MatchingDeclarationName
 * counts only the public class-like top-level declaration: the type
 * used to share the (now deleted) map preview card's file, and CI
 * failed that file for the name mismatch (round 55).
 */
sealed interface MapOpenRequest {
    /** Open directions to [address] in the user's maps provider. */
    data class OpenDirections(val address: String) : MapOpenRequest
}
