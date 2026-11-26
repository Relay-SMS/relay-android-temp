/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens.splash

import com.google.common.truth.Truth.assertThat
import com.relay.sms.R
import org.junit.jupiter.api.Test

/**
 * Round 57 — le facteur d'agrandissement du logo Lottie.
 *
 * [lottieMarkScale] est le seul morceau de logique pure extrait du splash :
 * la marque visible occupe 107/500 du canvas, la boîte fait 240 dp, la cible
 * est 120 dp de présence — `120 / (240 × 107/500) ≈ 2,336`. Ces tests
 * pincent le calcul pour qu'un changement de boîte ou de cible ne fausse
 * pas la taille rendue sans qu'on s'en aperçoive.
 *
 * Round 57 (variantes clair/sombre) — [surfaceLuminance] et [splashLogoRes]
 * sont testés ici aussi : la variante bleue doit être choisie sur une
 * surface claire, la blanche sur une surface sombre.
 *
 * Pure Kotlin, no coroutines — block bodies, so every assertion
 * ends the test with a void statement (JUnit silently skips a
 * `@Test` whose body returns a value).
 */
class SplashScreenMathTest {

    @Test
    fun `la marque rendue fait 120 dp dans une boite de 240 dp`() {
        assertThat(lottieMarkScale(240f, 107f / 500f, 120f))
            .isWithin(0.001f)
            .of(120f / (240f * 107f / 500f))
    }

    @Test
    fun `doubler la boite divise le facteur par deux`() {
        val small = lottieMarkScale(240f, 107f / 500f, 120f)
        val large = lottieMarkScale(480f, 107f / 500f, 120f)
        assertThat(large).isWithin(0.001f).of(small / 2f)
    }

    @Test
    fun `doubler la cible double le facteur`() {
        val target120 = lottieMarkScale(240f, 107f / 500f, 120f)
        val target240 = lottieMarkScale(240f, 107f / 500f, 240f)
        assertThat(target240).isWithin(0.001f).of(target120 * 2f)
    }

    @Test
    fun `une marque plein cadre ne demande aucun agrandissement`() {
        assertThat(lottieMarkScale(240f, 1f, 240f)).isWithin(0.001f).of(1f)
    }

    @Test
    fun `la luminance du blanc vaut 1 et celle du noir 0`() {
        assertThat(surfaceLuminance(1f, 1f, 1f)).isWithin(0.001f).of(1f)
        assertThat(surfaceLuminance(0f, 0f, 0f)).isWithin(0.001f).of(0f)
    }

    @Test
    fun `la surface sombre est sous le seuil et la claire au-dessus`() {
        val dark = 27f / 255f // #1B1B1B, surface du thème sombre
        assertThat(surfaceLuminance(dark, dark, dark)).isLessThan(0.5f)
        assertThat(surfaceLuminance(1f, 1f, 1f)).isGreaterThan(0.5f)
    }

    @Test
    fun `surface sombre donne la variante blanche, surface claire la bleue`() {
        assertThat(splashLogoRes(true)).isEqualTo(R.raw.relay_splash_logo)
        assertThat(splashLogoRes(false)).isEqualTo(R.raw.relay_splash_logo_light)
    }
}
