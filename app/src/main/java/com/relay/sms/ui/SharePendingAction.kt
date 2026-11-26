/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui

import com.relay.sms.system.share.IncomingShareHolder

/**
 * Round 56 — what AppRoot's share auto-route does with a pending share.
 * Extracted from the `LaunchedEffect` in [AppRoot] so the decision is
 * unit-testable (the effect itself is Compose-only).
 */
internal enum class SharePendingAction {
    /** Leave the pending alone; nothing happens. */
    Ignore,

    /** Drop the pending without routing anywhere. */
    Clear,

    /** Open the contact picker for the pending share. */
    NavigateToCompose,
}

/**
 * Round 56 — decides what AppRoot does when a share is pending.
 *
 *   - no pending, lock screen up, panic-decoy session, or an expired
 *     pending → [SharePendingAction.Ignore];
 *   - a pending posted by an in-app forward ([IncomingShareHolder.Pending.isForward])
 *     → [SharePendingAction.Ignore]: the forward flow navigates itself
 *     (pop + open the destination thread, or the Compose flow for a new
 *     recipient), so the guard must not touch it;
 *   - an external share while already on Compose / Thread → [SharePendingAction.Clear]
 *     (v1.4.1 SEC-03: a lingering share could otherwise land in the wrong
 *     conversation's composer);
 *   - anything else → [SharePendingAction.NavigateToCompose].
 */
internal fun decideSharePendingAction(
    pending: IncomingShareHolder.Pending?,
    route: String?,
    showLock: Boolean,
    isPanicDecoy: Boolean,
): SharePendingAction {
    val alreadyInFlow = route?.contains("Compose") == true ||
        route?.contains("Thread") == true
    return when {
        pending == null || showLock || isPanicDecoy -> SharePendingAction.Ignore
        pending.isExpired() -> SharePendingAction.Ignore
        pending.isForward -> SharePendingAction.Ignore
        alreadyInFlow -> SharePendingAction.Clear
        else -> SharePendingAction.NavigateToCompose
    }
}
