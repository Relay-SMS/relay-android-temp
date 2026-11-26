/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.system.notifications

import android.content.Context
import com.relay.sms.data.local.datastore.SettingsRepository
import com.relay.sms.domain.model.Contact
import com.relay.sms.domain.repository.ContactRepository
import com.relay.sms.domain.settings.AppSettings
import com.relay.sms.domain.settings.NotificationSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Round 57 — the "[Name] is on Relay!" notifier, plain-JVM guard
 * tests only.
 *
 * The actual post goes through Android statics (`PendingIntent`,
 * `Intent`, `NotificationCompat`), which throw `Stub!` on plain
 * JVM — so the posted notification itself is CI/Robolectric-only
 * (the avatar decode is guarded end to end for the same reason).
 * What IS pinned here runs before any of that: the disabled
 * short-circuit, the fail-open on unreadable settings, the TAIRE
 * verdict short-circuit, and the never-throwing contract (a stub
 * failure in the post path is swallowed, never escapes to the E2E
 * paths that call this).
 */
class E2eActivatedNotifierTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private class Harness(
        val notifier: E2eActivatedNotifier,
        val settings: SettingsRepository,
        val contacts: ContactRepository,
        val policy: CorrespondentVisibilityPolicy,
        val token: NotificationIntentToken,
    )

    private fun harness(): Harness {
        val settings = mockk<SettingsRepository>()
        val contacts = mockk<ContactRepository>()
        val policy = mockk<CorrespondentVisibilityPolicy>()
        val token = mockk<NotificationIntentToken>()
        val notifier = E2eActivatedNotifier(
            context = mockk<Context>(relaxed = true),
            settings = settings,
            contacts = contacts,
            visibilite = policy,
            intentToken = token,
            io = dispatcher,
        )
        return Harness(notifier, settings, contacts, policy, token)
    }

    private fun contact() = Contact(
        id = 1L,
        displayName = "Marie",
        phones = emptyList(),
    )

    @Test fun `disabled notifications post nothing and never read the contact`() = runTest {
        val h = harness()
        coEvery { h.settings.hydratedOrNull() } returns
            AppSettings(notifications = NotificationSettings(enabled = false))

        h.notifier.notifyActivated("+15551234567", 7L)

        coVerify(exactly = 0) { h.contacts.lookupByPhone(any()) }
        coVerify(exactly = 0) { h.policy.verdictPour(any(), any()) }
        coVerify(exactly = 0) { h.token.current() }
    }

    @Test fun `unreadable settings fail open to the contact lookup`() = runTest {
        val h = harness()
        coEvery { h.settings.hydratedOrNull() } returns null
        coEvery { h.contacts.lookupByPhone(any()) } returns contact()
        coEvery { h.policy.verdictPour(any(), any()) } returns
            CorrespondentVisibilityPolicy.Verdict.NOMMER

        // The post itself dies on the Android stubs below and is
        // swallowed — the lookup must still have happened.
        h.notifier.notifyActivated("+15551234567", 7L)

        coVerify { h.contacts.lookupByPhone("+15551234567") }
    }

    @Test fun `a TAIRE verdict posts nothing and never builds the open intent`() = runTest {
        val h = harness()
        coEvery { h.settings.hydratedOrNull() } returns AppSettings()
        coEvery { h.contacts.lookupByPhone(any()) } returns contact()
        coEvery { h.policy.verdictPour(any(), any()) } returns
            CorrespondentVisibilityPolicy.Verdict.TAIRE

        h.notifier.notifyActivated("+15551234567", 7L)

        coVerify { h.contacts.lookupByPhone("+15551234567") }
        coVerify(exactly = 0) { h.token.current() }
    }

    @Test fun `a NOMMER verdict never throws when the Android post path is a stub`() = runTest {
        val h = harness()
        coEvery { h.settings.hydratedOrNull() } returns AppSettings()
        coEvery { h.contacts.lookupByPhone(any()) } returns contact()
        coEvery { h.policy.verdictPour(any(), any()) } returns
            CorrespondentVisibilityPolicy.Verdict.NOMMER

        // `PendingIntent.getActivity` throws Stub! on plain JVM;
        // `notifyActivated` must swallow it, never fail the E2E
        // path that called it.
        h.notifier.notifyActivated("+15551234567", 7L)

        coVerify { h.contacts.lookupByPhone("+15551234567") }
    }
}
