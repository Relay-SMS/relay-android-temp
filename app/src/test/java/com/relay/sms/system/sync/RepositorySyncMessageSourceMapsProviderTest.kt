/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.system.sync

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.relay.sms.domain.repository.ContactRepository
import com.relay.sms.domain.repository.ConversationRepository
import com.relay.sms.domain.repository.ScheduledMessageRepository
import com.relay.sms.domain.sender.DefaultSmsAppChecker
import com.relay.sms.domain.settings.AppSettings
import com.relay.sms.domain.settings.AppSettingsSource
import com.relay.sms.domain.settings.MapsProvider
import com.relay.sms.domain.usecase.BlockNumberUseCase
import com.relay.sms.domain.usecase.DeleteScheduledMessageUseCase
import com.relay.sms.domain.usecase.EnvoyerMessageUseCase
import com.relay.sms.domain.usecase.RetrySendUseCase
import com.relay.sms.domain.usecase.ScheduleMessageUseCase
import com.relay.sms.domain.usecase.SendReactionUseCase
import com.relay.sms.domain.usecase.UnblockNumberUseCase
import com.relay.sms.sync.MapsProviderState
import com.relay.sms.sync.SyncMessageSource
import com.relay.sms.sync.SyncProtocol
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Round 57 — maps provider sync: the glue's mapping between
 * AppSettings (`smartActions.mapsProvider` + its write marker)
 * and the wire state, plus the last-write-wins apply rule. The
 * enum-to-wire table is explicit, so every name is pinned here;
 * an unknown wire provider is refused with `invalid_request`,
 * and a write whose marker is not strictly newer than the stored
 * one is refused with `stale` — the phone keeps its own value,
 * and a tie stays with the phone.
 */
class RepositorySyncMessageSourceMapsProviderTest {

    private val settings = FakeMapsProviderSettings()

    // Typed as the interface, like the other glue tests.
    private val source: SyncMessageSource = RepositorySyncMessageSource(
        context = mockk<Context>(),
        repository = mockk<ConversationRepository>(),
        defaultSmsAppChecker = mockk<DefaultSmsAppChecker>(),
        envoyerMessage = mockk<EnvoyerMessageUseCase>(),
        retrySend = mockk<RetrySendUseCase>(),
        sendReaction = mockk<SendReactionUseCase>(),
        settings = settings,
        scheduledMessageRepository = mockk<ScheduledMessageRepository>(),
        scheduleMessageUseCase = mockk<ScheduleMessageUseCase>(),
        deleteScheduledMessage = mockk<DeleteScheduledMessageUseCase>(),
        contactsRepository = mockk<ContactRepository>(),
        blockNumber = mockk<BlockNumberUseCase>(),
        unblockNumber = mockk<UnblockNumberUseCase>(),
    )

    @Test fun `observeMapsProvider maps the settings choice and marker to the wire state`() = runTest {
        settings.update { current ->
            current.copy(
                smartActions = current.smartActions.copy(
                    mapsProvider = MapsProvider.GOOGLE_MAPS,
                    mapsProviderUpdatedAt = 1_795_000_000_000L,
                ),
            )
        }
        assertThat(source.observeMapsProvider().first()).isEqualTo(
            MapsProviderState(provider = "google", updatedAt = 1_795_000_000_000L),
        )
    }

    @Test fun `observeMapsProvider re-emits when the settings change`() = runTest {
        val collected = async { source.observeMapsProvider().take(2).toList() }
        runCurrent()
        settings.update { current ->
            current.copy(
                smartActions = current.smartActions.copy(
                    mapsProvider = MapsProvider.OPENSTREETMAP,
                    mapsProviderUpdatedAt = 1_795_000_000_001L,
                ),
            )
        }
        assertThat(collected.await()).containsExactly(
            MapsProviderState(provider = "system", updatedAt = 0L),
            MapsProviderState(provider = "osm", updatedAt = 1_795_000_000_001L),
        ).inOrder()
    }

    @Test fun `mapsProvider returns the wire state`() = runTest {
        settings.update { current ->
            current.copy(
                smartActions = current.smartActions.copy(
                    mapsProvider = MapsProvider.APPLE_MAPS,
                    mapsProviderUpdatedAt = 1_795_000_000_002L,
                ),
            )
        }
        assertThat(source.mapsProvider()).isEqualTo(
            MapsProviderState(provider = "apple", updatedAt = 1_795_000_000_002L),
        )
    }

    @Test fun `mapsProvider returns null when settings are unreadable`() = runTest {
        settings.hydratedValue = null
        assertThat(source.mapsProvider()).isNull()
    }

    @Test fun `setMapsProvider with a newer marker applies and stamps the received marker`() = runTest {
        settings.update { current ->
            current.copy(
                smartActions = current.smartActions.copy(
                    mapsProvider = MapsProvider.SYSTEM_DEFAULT,
                    mapsProviderUpdatedAt = 1_000L,
                ),
            )
        }
        val result = source.setMapsProvider("google", 2_000L)
        assertThat(result.ok).isTrue()
        assertThat(settings.backing.value.smartActions.mapsProvider)
            .isEqualTo(MapsProvider.GOOGLE_MAPS)
        // The RECEIVED marker, never "now": the marker is what the
        // two sides compare, and the broadcast carries it back.
        assertThat(settings.backing.value.smartActions.mapsProviderUpdatedAt).isEqualTo(2_000L)
    }

    @Test fun `setMapsProvider with an older marker refuses stale and changes nothing`() = runTest {
        settings.update { current ->
            current.copy(
                smartActions = current.smartActions.copy(
                    mapsProvider = MapsProvider.GOOGLE_MAPS,
                    mapsProviderUpdatedAt = 2_000L,
                ),
            )
        }
        val result = source.setMapsProvider("osm", 1_000L)
        assertThat(result.ok).isFalse()
        assertThat(result.code).isEqualTo(SyncProtocol.MessageFailed.STALE)
        assertThat(settings.backing.value.smartActions.mapsProvider)
            .isEqualTo(MapsProvider.GOOGLE_MAPS)
        assertThat(settings.backing.value.smartActions.mapsProviderUpdatedAt).isEqualTo(2_000L)
    }

    @Test fun `setMapsProvider with an equal marker refuses stale and changes nothing`() = runTest {
        // A tie stays with the phone.
        settings.update { current ->
            current.copy(
                smartActions = current.smartActions.copy(
                    mapsProvider = MapsProvider.GOOGLE_MAPS,
                    mapsProviderUpdatedAt = 2_000L,
                ),
            )
        }
        val result = source.setMapsProvider("osm", 2_000L)
        assertThat(result.ok).isFalse()
        assertThat(result.code).isEqualTo(SyncProtocol.MessageFailed.STALE)
        assertThat(settings.backing.value.smartActions.mapsProvider)
            .isEqualTo(MapsProvider.GOOGLE_MAPS)
    }

    @Test fun `setMapsProvider refuses an unknown provider and changes nothing`() = runTest {
        val result = source.setMapsProvider("waze", 2_000L)
        assertThat(result.ok).isFalse()
        assertThat(result.code).isEqualTo(SyncProtocol.MessageFailed.INVALID_REQUEST)
        assertThat(settings.backing.value.smartActions.mapsProvider)
            .isEqualTo(MapsProvider.SYSTEM_DEFAULT)
        assertThat(settings.backing.value.smartActions.mapsProviderUpdatedAt).isEqualTo(0L)
    }

    @Test fun `setMapsProvider maps every wire name`() = runTest {
        val expected = mapOf(
            "system" to MapsProvider.SYSTEM_DEFAULT,
            "google" to MapsProvider.GOOGLE_MAPS,
            "apple" to MapsProvider.APPLE_MAPS,
            "osm" to MapsProvider.OPENSTREETMAP,
        )
        var marker = 1_000L
        for ((wire, provider) in expected) {
            marker += 1_000L
            assertThat(source.setMapsProvider(wire, marker).ok).isTrue()
            assertThat(settings.backing.value.smartActions.mapsProvider).isEqualTo(provider)
        }
    }
}

/** An [AppSettingsSource] backed by a [MutableStateFlow], for glue tests. */
private class FakeMapsProviderSettings(
    initial: AppSettings = AppSettings(),
) : AppSettingsSource {
    val backing = MutableStateFlow(initial)

    /** What [hydratedOrNull] answers; null simulates unreadable settings. */
    var hydratedValue: AppSettings? = initial

    override val flow: Flow<AppSettings> = backing
    override val state: StateFlow<AppSettings> = backing

    override suspend fun hydratedOrNull(): AppSettings? = hydratedValue

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(backing.value)
        backing.value = next
        hydratedValue = next
    }
}
