/*
 * Copyright 2026 Relay Messaging (modifications). Relay's modifications
 * to this file are proprietary to Relay Messaging and are not licensed
 * under the Apache License 2.0. See LICENSE-RELAY for the terms. The
 * original work is copyright its respective holders and is licensed
 * under the Apache License 2.0. See LICENSE.
 */

package com.relay.sms.ui.screens.splash

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition
import com.relay.sms.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.3.7 — Splash de présentation joué **uniquement à la première ouverture** de
 * l'app après install (cf. [SplashViewModel.shouldShow]). Le logo est une animation
 * Lottie ([lottie-compose][com.airbnb.lottie.compose], Apache-2.0, rendu local,
 * aucun réseau, aucun tracker — cohérent avec la règle zéro-tracker de Relay).
 * C'est la seule dépendance externe ajoutée pour cet écran.
 *
 * **Animations** (durée totale ≈ 2,5 s) :
 *   - **logo** : l'animation (96 images à 60 ips, soit 1,6 s) est jouée **une
 *     seule fois** puis figée sur sa dernière image (`iterations = 1`,
 *     l'animatable garde le progrès final). Deux variantes selon le thème :
 *     `res/raw/relay_splash_logo_light.json` (bleu `#1E4FD8`) en clair,
 *     `res/raw/relay_splash_logo.json` (blanc) en sombre — choisies par la
 *     luminance de la surface, cf. [splashLogoRes]. Tant que la composition
 *     charge encore (`null`), l'emplacement reste vide — aucune image
 *     statique n'est affichée en attendant.
 *   - **nom + tagline** : ils n'entrent qu'une fois le logo terminé — l'entrée est
 *     déclenchée par le retour de l'appel `suspend` `animate()`, jamais par un
 *     délai codé en dur pour la durée de l'animation (un futur fichier de
 *     remplacement change la durée gratuitement). Entrée en `slide-up + fade`
 *     ensemble : le bloc part [TAGLINE_SLIDE_UP_DP] sous sa position finale et
 *     remonte à 0 pendant que l'alpha passe de 0 à 1, même tween
 *     [TAGLINE_ANIM_MS] ms, EaseOutCubic.
 *   - **hint "Toucher pour passer"** : fade in à [HINT_DELAY_MS] ms, opacité finale
 *     [HINT_FINAL_ALPHA] (volontairement discret pour ne pas voler l'attention).
 *
 * **Interactions** (toutes idempotentes via [dismissOnce]) :
 *   - **Tap n'importe où** → skip immédiat, à tout moment, y compris pendant
 *     l'animation du logo (le `LaunchedEffect` est annulé avec la branche).
 *   - **Back hardware / gesture** → skip immédiat (cf. [BackHandler]).
 *
 * Round 57 — **plus d'auto-dismiss** : le timer de 5,5 s est retiré. À la
 * première ouverture, quatre pop-ups de permission peuvent dépasser le
 * timer, et l'utilisateur atterrissait sur la liste sans avoir pu taper —
 * le splash ne part plus que sur tap ou retour, jamais tout seul.
 *
 * Round 58 — les dialogues de permission attendent la sortie du splash
 * (cf. `MainActivity` + `ConversationsScreen`) : ils s'empilaient sur
 * l'animation du logo et l'utilisateur la ratait entièrement. Le gap
 * logo→"Relay" passe de 24 dp à 12 dp (demande utilisatrice), et l'icône
 * du splash système redevient transparente (`splash_transparent.xml`) :
 * à la première ouverture, seul le fond flashe avant l'animation.
 *
 * **Couleur du logo** : l'animation d'origine est d'un blanc fixe ; le blanc
 * est invisible sur le thème clair (surface ≈ blanche), donc une seconde
 * variante existe : `relay_splash_logo_light.json`, copie exacte du fichier
 * blanc dont les 10 couleurs ont été remplacées par le bleu Relay `#1E4FD8`
 * (`[0.11765, 0.30980, 0.84706, 1]`). Le fichier blanc reste la variante
 * sombre. Le choix se fait par la luminance de la surface du splash (même
 * idiome que `bubbleIncomingColor` : Rec. 709, seuil 0,5), jamais par
 * `isSystemInDarkTheme()` — un thème clair/sombre forcé dans les réglages
 * est donc respecté, tout comme les couleurs dynamiques. Les deux fichiers
 * doivent rester identiques à part les couleurs ; si l'animation est
 * régénérée, refaire la copie puis le remplacement.
 *
 * **Remplacer l'animation** : deux fichiers, `res/raw/relay_splash_logo.json`
 * (blanc, sombre) et `res/raw/relay_splash_logo_light.json` (bleu, clair),
 * tous deux extraits de `a/animation.json` du `.lottie` — le second est la
 * copie du premier avec les couleurs remplacées (voir ci-dessus). Aucun code
 * à toucher, sauf si la taille visible de la marque change — voir
 * [LOTTIE_MARK_FRACTION].
 *
 * **Taille** : la marque visible occupe 107×107 unités du canvas 500×500
 * (mesuré à l'image finale : le tracé de l'anneau s'étend de 23 à 130),
 * soit [LOTTIE_MARK_FRACTION] du canvas. L'ancien logo statique rendait une
 * marque d'environ 120 dp de présence ; pour garder la même présence, la boîte
 * Lottie fait [LOTTIE_BOX_DP] dp et son contenu est agrandi par le facteur
 * [lottieMarkScale] (≈ 2,34×) via `graphicsLayer` — l'empreinte de mise en
 * page reste 240 dp, seule la marque visible grandit.
 *
 * **Pourquoi ce design est sans faille** :
 *
 *  1. `LaunchedEffect(Unit)` ne se relance pas tant que la branche d'affichage reste en
 *     composition. Toute écriture concurrente du flag splash (par [SplashViewModel.markShown])
 *     ne peut pas re-déclencher l'effet et donc pas non plus rendre `onFinished()` réentrant.
 *     ⚠️ La branche QUITTE la composition quand `shouldShow` passe à `false`, et y REVIENT si
 *     le flag est remis à `false` par une réinitialisation (v1.28.6) : l'effet repart alors
 *     avec un garde réarmé, cf. le `LaunchedEffect(shouldShow)` ci-dessous.
 *  2. [dismissOnce] est gardé par un [AtomicBoolean] — thread-safe même si un futur
 *     refactor déplace un appel sur un dispatcher background. En pratique Compose
 *     execute sur Main, mais on ceinture-et-bretelles.
 *  3. [SplashViewModel.shouldShow] est un `StateFlow` `Eagerly` `stateIn` : la valeur
 *     initiale `true` est immédiatement remplacée par la vraie valeur DataStore
 *     dès la construction du ViewModel — pas de "flash" splash pour un user qui l'a
 *     déjà vu, car la lecture est sync au premier rendu.
 *  4. Si `shouldShow` arrive à `false` au cold start (deuxième ouverture+), la branche
 *     `if (!shouldShow)` skippe TOUT le rendu animation et l'effet [LaunchedEffect]
 *     se contente d'appeler [onFinished] une fois — exactement comme le tap-to-skip
 *     mais sans persister un flag déjà à `true`.
 *  5. `coroutineScope` parent encapsule les 2 lances de l'entrée texte. Si
 *     une animation throw `CancellationException` (recomposition forcée par config
 *     change p.ex.), tout le scope est annulé proprement ; le `dismissOnce` n'est
 *     pas exécuté tant qu'un tap ou un retour ne l'a pas déclenché.
 *
 * **Limites connues acceptées** :
 *
 *  - **Rotation pendant splash** : les animations recommencent à zéro. Acceptable car
 *    durée 2,5 s, probabilité d'occurrence sur cette fenêtre est négligeable. Coder un
 *    state-save des `Animatable` serait du overengineering pour un écran one-shot.
 *  - **Logo animé** : la marque est dessinée par Lottie (vecteur, pas un bitmap) —
 *    elle scale parfaitement sur tablettes haute densité.
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    viewModel: SplashViewModel = hiltViewModel(),
) {
    val shouldShow by viewModel.shouldShow.collectAsStateWithLifecycle()

    // Garde d'idempotence partagée entre toutes les portes de sortie (tap, back,
    // branche cold-start "déjà vu"). Survit aux recompositions grâce à
    // `remember(viewModel, onFinished)`.
    val firedGuard = remember(viewModel, onFinished) { AtomicBoolean(false) }
    val dismissOnce: () -> Unit = remember(viewModel, onFinished) {
        {
            if (firedGuard.compareAndSet(false, true)) {
                viewModel.markShown()
                onFinished()
            }
        }
    }

    // v1.28.6 — REARMEMENT. Ce composable vit dans `AppRoot` pendant toute la vie de
    // l'activite, et `firedGuard` avec lui. « Reinitialiser tous les reglages » et « Supprimer
    // toutes mes donnees » remettent `splashShown` a `false` : `shouldShow` repasse a `true`, la
    // branche d'affichage re-entre en composition, les animations rejouent... mais le garde
    // etait reste a `true` depuis la premiere sortie. Tap et retour passaient
    // tous par `dismissOnce`, qui ne faisait plus rien : le `Box` plein ecran cliquable restait
    // en overlay au-dessus du graphe et avalait chaque geste jusqu'a ce que l'utilisateur tue
    // l'application (signale par un testeur le 2026-09-12). Le garde est donc rearme a chaque
    // passage `false -> true` de `shouldShow`, soit exactement une fois par « premiere ouverture ».
    // Limite assumee : la transition doit etre OBSERVEE par la composition ; une remise a `false`
    // dans la meme frame que la sortie (impossible a la main, mesure dans `SplashScreenTest`) est
    // invisible d'ici.
    LaunchedEffect(shouldShow) {
        if (shouldShow) firedGuard.set(false)
    }

    // Branche "déjà vu" : redirige immédiatement sans rendre l'UI splash. Le
    // `markShown()` est court-circuité (flag déjà à `true`, write inutile).
    if (!shouldShow) {
        LaunchedEffect(Unit) {
            if (firedGuard.compareAndSet(false, true)) {
                onFinished()
            }
        }
        return
    }

    val density = LocalDensity.current
    val taglineStartOffsetPx = with(density) { TAGLINE_SLIDE_UP_DP.dp.toPx() }
    val taglineAlpha = remember { Animatable(initialValue = 0f) }
    val taglineOffsetY = remember(taglineStartOffsetPx) { Animatable(taglineStartOffsetPx) }
    val hintAlpha = remember { Animatable(initialValue = 0f) }

    // Round 57 — logo Lottie, variante selon le thème : la surface du splash
    // est blanche en clair, noire en sombre — on choisit la variante par la
    // luminance de la surface (même idiome que `bubbleIncomingColor`), jamais
    // par `isSystemInDarkTheme()`, pour suivre un thème clair/sombre forcé.
    // Tant que la composition est `null`, `LottieAnimation` rend une boîte
    // vide de la taille de l'emplacement (pas d'image statique en attendant).
    val surface = MaterialTheme.colorScheme.surface
    val logoRes = splashLogoRes(
        surfaceLuminance(surface.red, surface.green, surface.blue) < 0.5f,
    )
    val composition = rememberLottieComposition(
        LottieCompositionSpec.RawRes(logoRes),
    ).value
    val lottieAnimatable = rememberLottieAnimatable()

    // Le logo joue une fois ; l'entrée du texte attend la FIN de l'animation —
    // le retour de `animate()` (suspend) en est la preuve, pas un délai codé
    // en dur. Si la branche quitte la composition (tap, back, rotation),
    // l'effet est annulé et l'entrée texte ne se lance jamais.
    LaunchedEffect(composition) {
        val comp = composition ?: return@LaunchedEffect
        lottieAnimatable.animate(comp, iterations = 1)
        coroutineScope {
            launch {
                taglineAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = TAGLINE_ANIM_MS, easing = EaseOutCubic),
                )
            }
            launch {
                taglineOffsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = TAGLINE_ANIM_MS, easing = EaseOutCubic),
                )
            }
        }
    }

    // Hint : timing inchangé (fade in à HINT_DELAY_MS depuis le début du splash,
    // indépendant de la fin de l'animation logo).
    LaunchedEffect(Unit) {
        delay(HINT_DELAY_MS)
        hintAlpha.animateTo(
            targetValue = HINT_FINAL_ALPHA,
            animationSpec = tween(durationMillis = HINT_ANIM_MS),
        )
    }

    BackHandler(enabled = true) { dismissOnce() }

    val interaction = remember { MutableInteractionSource() }
    val logoDescription = stringResource(R.string.splash_logo_content_description)
    // Les deux textes partagent la même entrée : slide-up + fade ensemble,
    // pilotés par le même alpha et le même décalage (graphicsLayer, pas un
    // offset de mise en page — la mise en page ne bouge pas pendant l'entrée).
    val textEntrance = Modifier.graphicsLayer(
        translationY = taglineOffsetY.value,
        alpha = taglineAlpha.value,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = stringResource(R.string.splash_skip_label),
                onClick = dismissOnce,
            )
            // v1.28.6 — le tag n'est pas annonce par TalkBack ; il permet a `SplashScreenTest`
            // de constater la presence de l'overlay et d'y poser un vrai evenement de pointeur.
            .clearAndSetSemantics { testTag = SPLASH_TEST_TAG },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
                // Round 60 — the logo + title + tagline group sits
                // ~11 dp too low (Kristin's screenshot, 389x865:
                // visible group 299..587, centre 443, screen centre
                // 432.5). The whole centred group shifts up by
                // [GROUP_SHIFT_UP_DP]; the "Tap anywhere" hint is a
                // sibling pinned to the bottom (48 dp) and does not
                // move. The gaps inside the group are untouched.
                .offset(y = -GROUP_SHIFT_UP_DP.dp),
        ) {
            // Round 57 — la marque animée remplace l'image statique
            // `ic_splash_logo` (le vecteur reste le foreground de l'icône
            // adaptative, il n'est plus utilisé ici). La boîte fait
            // LOTTIE_BOX_DP dp ; `graphicsLayer` agrandit le contenu rendu
            // pour que la marque visible atteigne LOTTIE_TARGET_MARK_DP dp
            // (voir le KDoc : 107/500 du canvas). Composition `null` =
            // emplacement vide, sans placeholder.
            LottieAnimation(
                composition = composition,
                progress = { lottieAnimatable.progress },
                modifier = Modifier
                    .size(LOTTIE_BOX_DP.dp)
                    .graphicsLayer(
                        scaleX = LOTTIE_MARK_SCALE,
                        scaleY = LOTTIE_MARK_SCALE,
                    )
                    .clearAndSetSemantics {
                        testTag = SPLASH_LOGO_TAG
                        contentDescription = logoDescription
                    },
            )
            // Round 58 (Kristin, device test) — the logo→"Relay" gap was
            // too wide; halved from 24 dp to 12 dp. The 12 dp spacer
            // below ("Relay"→tagline) is unchanged.
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = textEntrance
                    // `semantics` (pas `clearAndSetSemantics`) : le tag
                    // s'ajoute sans effacer le texte pour TalkBack.
                    .semantics { testTag = SPLASH_APP_NAME_TAG },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.splash_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = textEntrance
                    .semantics { testTag = SPLASH_TAGLINE_TAG },
            )
        }

        Text(
            text = stringResource(R.string.splash_skip_hint),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .alpha(hintAlpha.value),
        )
    }
}

// v1.3.7 — timings calibrés pour laisser le temps de lire la tagline (~12 mots
// → 3-4 s de lecture confortable + marge pour traiter visuellement). Le logo
// (1,6 s) + l'entrée texte (0,8 s) durent ≈ 2,5 s au total ; le splash reste
// ensuite affiché jusqu'au tap ou au retour (round 57 — plus d'auto-dismiss,
// cf. le KDoc ci-dessus). Si re-perçu trop long après usage réel, raccourcir
// les délais relatifs.
internal const val SPLASH_TEST_TAG = "splash_overlay"
internal const val SPLASH_LOGO_TAG = "splash_logo"
internal const val SPLASH_APP_NAME_TAG = "splash_app_name"
internal const val SPLASH_TAGLINE_TAG = "splash_tagline"
private const val TAGLINE_ANIM_MS = 800
private const val TAGLINE_SLIDE_UP_DP = 12f

/** Round 60 — the centred group's upward shift (see the Column above). */
private const val GROUP_SHIFT_UP_DP = 11f
private const val HINT_DELAY_MS = 2500L
private const val HINT_ANIM_MS = 500
private const val HINT_FINAL_ALPHA = 0.6f

// Round 57 — taille du logo Lottie. La marque visible occupe 107/500 du
// canvas 500×500 (mesuré dans animation.json, image finale) ; l'ancienne
// marque statique avait ~120 dp de présence. La boîte fait 240 dp et le
// contenu rendu est agrandi par `lottieMarkScale` pour retrouver ces 120 dp.
// Round 58 — `internal`, pas `private` : `LoadingLogo` partage la même
// boîte et la même échelle (une seule source de vérité pour la taille).
internal const val LOTTIE_BOX_DP = 240f
internal const val LOTTIE_MARK_FRACTION = 107f / 500f
internal const val LOTTIE_TARGET_MARK_DP = 120f
internal val LOTTIE_MARK_SCALE =
    lottieMarkScale(LOTTIE_BOX_DP, LOTTIE_MARK_FRACTION, LOTTIE_TARGET_MARK_DP)

/**
 * Facteur d'agrandissement (`graphicsLayer`) à appliquer au contenu d'une
 * boîte Lottie pour que la marque visible atteigne [targetMarkDp] dp.
 * Pur et testé sur JVM ([SplashScreenMathTest]) : `120 / (240 × 107/500) ≈ 2,34`.
 */
internal fun lottieMarkScale(boxDp: Float, markFraction: Float, targetMarkDp: Float): Float =
    targetMarkDp / (boxDp * markFraction)

/**
 * Luminance Rec. 709 d'une couleur (même formule que `bubbleIncomingColor`,
 * `bubbleStatusColor` et `settingsBlockColor`) : la surface du thème clair
 * est proche de 1, celle du sombre proche de 0 — le seuil 0,5 les sépare.
 * Pure et testée sur JVM ([SplashScreenMathTest]).
 */
internal fun surfaceLuminance(red: Float, green: Float, blue: Float): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue

/**
 * Ressource Lottie du logo selon le thème effectif du splash : la variante
 * bleue (`relay_splash_logo_light`) sur une surface claire, la variante
 * blanche (`relay_splash_logo`) sur une surface sombre. Pure et testée sur
 * JVM ([SplashScreenMathTest]).
 */
internal fun splashLogoRes(isDarkSurface: Boolean): Int =
    if (isDarkSurface) R.raw.relay_splash_logo else R.raw.relay_splash_logo_light
