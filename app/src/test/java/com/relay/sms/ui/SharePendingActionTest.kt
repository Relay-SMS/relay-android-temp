/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui

import com.google.common.truth.Truth.assertThat
import com.relay.sms.system.share.IncomingShareHolder
import org.junit.jupiter.api.Test

/**
 * Round 56 — AppRoot's share auto-route guard ([decideSharePendingAction]).
 *
 * The guard must tell an in-app forward apart from an outside share: a forward
 * stages its payload through the same holder while the user sits in the source
 * thread, so the old "clear anything pending while on a Thread" rule ate every
 * forward before the destination thread could consume it.
 */
class SharePendingActionTest {

    private fun pending(isForward: Boolean = false, postedAt: Long = System.currentTimeMillis()) =
        IncomingShareHolder.Pending(
            uris = emptyList(),
            mimeType = null,
            text = "hello",
            postedAt = postedAt,
            isForward = isForward,
        )

    @Test
    fun `no pending does nothing`() {
        assertThat(decideSharePendingAction(null, null, false, false))
            .isEqualTo(SharePendingAction.Ignore)
    }

    @Test
    fun `an external share on the list routes to Compose`() {
        assertThat(decideSharePendingAction(pending(), null, false, false))
            .isEqualTo(SharePendingAction.NavigateToCompose)
    }

    @Test
    fun `an external share while on a Thread is cleared`() {
        assertThat(
            decideSharePendingAction(pending(), "com.relay.sms.ui.Thread", false, false),
        ).isEqualTo(SharePendingAction.Clear)
    }

    @Test
    fun `an external share while on Compose is cleared`() {
        assertThat(
            decideSharePendingAction(pending(), "com.relay.sms.ui.Compose", false, false),
        ).isEqualTo(SharePendingAction.Clear)
    }

    @Test
    fun `an expired share is ignored`() {
        val stale = pending(postedAt = System.currentTimeMillis() - 61_000L)
        assertThat(decideSharePendingAction(stale, null, false, false))
            .isEqualTo(SharePendingAction.Ignore)
    }

    @Test
    fun `a forward pending on a Thread is left alone`() {
        assertThat(
            decideSharePendingAction(
                pending(isForward = true),
                "com.relay.sms.ui.Thread",
                false,
                false,
            ),
        ).isEqualTo(SharePendingAction.Ignore)
    }

    @Test
    fun `a forward pending never routes to Compose`() {
        assertThat(decideSharePendingAction(pending(isForward = true), null, false, false))
            .isEqualTo(SharePendingAction.Ignore)
    }

    @Test
    fun `a pending while locked is ignored`() {
        assertThat(decideSharePendingAction(pending(), null, true, false))
            .isEqualTo(SharePendingAction.Ignore)
    }

    @Test
    fun `a pending in a decoy session is ignored`() {
        assertThat(decideSharePendingAction(pending(), null, false, true))
            .isEqualTo(SharePendingAction.Ignore)
    }
}
