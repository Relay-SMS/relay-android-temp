/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition

/**
 * Round 58 — the loading logo: the animated Relay mark shown during
 * the cold-start loading window (settings read + one-shot database
 * repair) on every open AFTER the first, in place of the static OS
 * splash logo (which is transparent since round 58 — the system
 * splash cannot play Lottie itself, it is drawn before the app can
 * run any code).
 *
 * It is the same animation as the first-open intro ([SplashScreen]),
 * same 240 dp box and same mark scale, same light/dark variant
 * ([splashLogoRes]) — but with no text and no tap: it plays once
 * and holds its final frame, so if the repair runs long the mark
 * simply sits there until the list appears. It vanishes on its own
 * when loading completes; there is nothing to dismiss.
 *
 * It never appears on a first open (see [shouldShowLoadingLogo]):
 * the loading screen there stays a plain surface background, and
 * the intro plays right after — a logo in both places would put it
 * on screen twice in a row.
 */
@Composable
fun LoadingLogo() {
    val surface = MaterialTheme.colorScheme.surface
    val logoRes = splashLogoRes(
        surfaceLuminance(surface.red, surface.green, surface.blue) < 0.5f,
    )
    val composition = rememberLottieComposition(
        LottieCompositionSpec.RawRes(logoRes),
    ).value
    val lottieAnimatable = rememberLottieAnimatable()

    // Play once, then hold the final frame. While the composition is
    // still loading (`null`), the slot renders empty — no static
    // placeholder, same as the intro.
    LaunchedEffect(composition) {
        val comp = composition ?: return@LaunchedEffect
        lottieAnimatable.animate(comp, iterations = 1)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        LottieAnimation(
            composition = composition,
            progress = { lottieAnimatable.progress },
            modifier = Modifier
                .size(LOTTIE_BOX_DP.dp)
                .graphicsLayer(
                    scaleX = LOTTIE_MARK_SCALE,
                    scaleY = LOTTIE_MARK_SCALE,
                )
                .semantics { testTag = LOADING_LOGO_TAG },
        )
    }
}

/**
 * Whether the loading screen shows the animated logo: only while the
 * app is still loading AND the first-open intro has already been
 * shown. Pure and tested on JVM ([LoadingLogoTest]).
 */
internal fun shouldShowLoadingLogo(isLoading: Boolean, splashAlreadyShown: Boolean): Boolean =
    isLoading && splashAlreadyShown

internal const val LOADING_LOGO_TAG = "loading_logo"
