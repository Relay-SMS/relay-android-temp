/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens.splash

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 58 — le logo de chargement ne s'affiche que pendant le
 * chargement ET sur une ouverture suivante. Sur une première
 * ouverture, l'écran de chargement reste un fond uni : le logo y
 * précéderait l'intro, et l'utilisateur le verrait deux fois
 * d'affilée (double logo).
 *
 * Pure Kotlin, no coroutines — block bodies, so every assertion
 * ends the test with a void statement (JUnit silently skips a
 * `@Test` whose body returns a value).
 */
class LoadingLogoTest {

    @Test
    fun `le logo s'affiche pendant le chargement sur une ouverture suivante`() {
        assertThat(shouldShowLoadingLogo(isLoading = true, splashAlreadyShown = true)).isTrue()
    }

    @Test
    fun `pas de logo pendant le chargement de la premiere ouverture`() {
        assertThat(shouldShowLoadingLogo(isLoading = true, splashAlreadyShown = false)).isFalse()
    }

    @Test
    fun `pas de logo une fois le chargement termine, meme sur une ouverture suivante`() {
        assertThat(shouldShowLoadingLogo(isLoading = false, splashAlreadyShown = true)).isFalse()
    }

    @Test
    fun `pas de logo une fois le chargement termine sur une premiere ouverture`() {
        assertThat(shouldShowLoadingLogo(isLoading = false, splashAlreadyShown = false)).isFalse()
    }
}
