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
 * Lives in its own file because Detekt's MatchingDeclarationName
 * counts only the public class-like top-level declaration: sharing
 * the file with [MapPreviewCard] made this the file's single counted
 * declaration, so CI failed the card file for the name mismatch.
 */
sealed interface MapOpenRequest {
    /** Open the provider's preview page (the Apple Maps page for the Apple card). */
    data class OpenPage(val url: String, val address: String) : MapOpenRequest

    /** Open directions to [address] in the user's maps provider. */
    data class OpenDirections(val address: String) : MapOpenRequest
}
