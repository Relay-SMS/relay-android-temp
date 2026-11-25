/*
 * Copyright 2026 Relay Messaging (modifications). The original work is
 * copyright its respective holders and is licensed under the Apache
 * License 2.0. See LICENSE.
 */

package com.relay.sms.ui.components

import android.util.Patterns
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.relay.sms.domain.smartactions.SmartAction
import com.relay.sms.domain.smartactions.SmartActionDetector
import java.time.ZoneId

/**
 * v1.3.2 — affiche le corps d'un message en détectant les URLs (http/https + domaines
 * nus genre `google.com`) et en les rendant **cliquables** : tap ouvre l'URL dans le
 * navigateur système via [androidx.compose.ui.platform.UriHandler] (qu'utilise
 * automatiquement [LinkAnnotation.Url]).
 *
 * Sécurité :
 *   - Le scheme est normalisé à `https://` pour les domaines nus (`google.com` →
 *     `https://google.com`) afin de ne JAMAIS générer une intent ouvrant un scheme
 *     exotique. Si l'URL contient déjà `http://` ou `https://`, on garde tel quel.
 *   - La regex [Patterns.WEB_URL] est l'implémentation Android stable (les autres
 *     apps SMS s'en servent), couvre les TLDs courants + IDN + IP.
 *   - Strip des caractères de queue qui sont quasi toujours de la ponctuation et
 *     pas de l'URL (`.`, `,`, `;`, `:`, `!`, `?`, `)`) — évite `google.com.` qui
 *     pointerait nulle part.
 *
 * Style :
 *   - Le lien est souligné, en gras léger, couleur = couleur du texte parent
 *     (lisible sur fond clair comme foncé sans hardcode).
 *
 * Round 53 — smart actions: the same pass also detects emails, physical
 * addresses, dates/times and tracking numbers ([SmartActionDetector]) and
 * renders them as clickable spans in the same link style; a tap calls
 * [onSmartAction] with the detected action. Overlap priority is Url >
 * Email > Tracking > DateTime > Address > Phone (see
 * [resolveHitOverlaps]), so a tracking number or an email inside a URL
 * never fragments it.
 */
@Composable
fun MessageTextWithLinks(
    text: String,
    /**
     * Feedback round 6 — the id of the message [text] belongs to, for the
     * linkify cache ([LinkHitCache]). `-1` = unknown: the regexes run
     * uncached (unit tests, previews). The bubble passes `message.id`.
     */
    messageId: Long = -1L,
    style: TextStyle,
    color: Color,
    /**
     * v1.3.11 (F4) — invoked when the user taps a phone number detected inside [text].
     * Default no-op so existing call sites keep working until they opt in. Wrapped in
     * [rememberUpdatedState] below so the lambda identity can change between
     * recompositions without invalidating the (potentially expensive) regex pass.
     */
    onPhoneClick: (String) -> Unit = {},
    /**
     * Round 53 — invoked when the user taps a smart action detected inside
     * [text] (an email, address, date/time or tracking number). Default
     * no-op so existing call sites keep working until they opt in. Wrapped
     * in [rememberUpdatedState] below, like [onPhoneClick], so the lambda
     * identity can change without invalidating the regex pass. Pass as a
     * NAMED argument — adding a lambda parameter as the last one would
     * silently rebind existing trailing lambdas (see AGENTS.md).
     */
    onSmartAction: (SmartAction) -> Unit = {},
    /**
     * Feedback round 5 — thread search highlight: every occurrence of the
     * query in [text], and whether the search is currently sitting on this
     * message. Empty by default: no search, no highlight.
     */
    searchHighlight: SearchHighlight = SearchHighlight(),
    modifier: Modifier = Modifier,
) {
    // v1.3.11 (F4) — keep the closure inside the AnnotatedString stable so the regex
    // pass below only re-runs when [text] changes, while the actual phone callback the
    // listener forwards to always points to the latest [onPhoneClick].
    val phoneClickState = rememberUpdatedState(onPhoneClick)
    val smartActionState = rememberUpdatedState(onSmartAction)
    val annotated = remember(text, searchHighlight) {
        buildLinkifiedText(
            text,
            messageId = messageId,
            onPhoneClick = { number -> phoneClickState.value(number) },
            onSmartAction = { action -> smartActionState.value(action) },
            highlightRanges = searchHighlight.ranges,
            highlightCurrent = searchHighlight.current,
        )
    }
    // Note : le SpanStyle des liens (dans buildLinkifiedText) ne fixe PAS de `color`,
    // de sorte que le lien hérite de la couleur du texte parent (gérée ici par
    // [color]). Ça garantit la lisibilité en thème clair ET sombre sans hardcode.
    Text(
        text = annotated,
        style = style,
        color = color,
        modifier = modifier,
    )
}

/**
 * v1.3.2 — cap d'entrée pour la regex `Patterns.WEB_URL`. Cette regex Java NFA peut
 * approcher un comportement quadratique sur certaines entrées pathologiques (ReDoS).
 * Aucun SMS standard ne dépasse cette taille en pratique (160 chars GSM-7, ~1 600
 * chars MMS texte concaténé). Au-delà, on rend le texte brut sans linkify — pas de
 * perte fonctionnelle, juste pas de clic auto sur les URLs d'un message géant.
 */
private const val LINKIFY_INPUT_CAP = 2_000

/**
 * v1.3.2 — style centralisé du lien (instance unique, pas réalloué à chaque appel).
 * Pas de `color = …` : on hérite de LocalContentColor du Text parent pour rester
 * lisible en thème clair ET sombre.
 */
private val LINK_STYLE = TextLinkStyles(
    style = SpanStyle(
        textDecoration = TextDecoration.Underline,
        fontWeight = FontWeight.Medium,
    ),
)

/** v1.3.2 — caractères de ponctuation strippés en queue d'URL avant linkify. */
private val URL_TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '»', '"', '\'')

/**
 * v1.3.11 (F4) — minimum / maximum digit count for a `Patterns.PHONE` match to be
 * accepted as a clickable phone number. `Patterns.PHONE` is intentionally lax (matches
 * any digit-with-separators run); without this band we'd surface a tap action on order
 * IDs, postal codes and CB last-4 digits.
 *
 * 7 is the floor of E.164 subscriber lengths (Niue / Tokelau); 15 is the spec ceiling
 * (E.164 §6.2.1). Matches Google Messages' own filter empirically.
 */
private const val PHONE_MIN_DIGITS = 7
private const val PHONE_MAX_DIGITS = 15

/**
 * Round 53 — the kinds of clickable span. Declaration order IS the
 * overlap priority ([resolveHitOverlaps] sorts on [ordinal]): Url 0,
 * Email 1, Tracking 2, DateTime 3, Address 4, Phone 5. An email beats a
 * tracking number so `1Z…@example.com` stays one email; an address beats
 * a phone so a ZIP code is not offered as a call.
 */
internal enum class HitKind { Url, Email, Tracking, DateTime, Address, Phone }

/**
 * One clickable span: [start]/[end] are exclusive-end offsets into the
 * body; [action] carries the detected smart action for the Email /
 * Tracking / DateTime / Address kinds (`null` for Url and Phone).
 */
internal data class LinkHit(
    val start: Int,
    val end: Int,
    val kind: HitKind,
    val action: SmartAction? = null,
)

/**
 * v1.3.2 (URL) + v1.3.11 F4 (phone) — construit l'AnnotatedString avec les URLs ET les
 * numéros de téléphone détectés. Public-internal pour test JUnit isolé (sans Compose
 * runtime).
 *
 * Sécurité :
 *
 *   - **Cap entrée** : si [text] excède [LINKIFY_INPUT_CAP] (2 000 chars), on
 *     court-circuite — pas de regex, juste un append brut. Anti-ReDoS pour les
 *     entrées pathologiques.
 *   - **Whitelist scheme URL** : seules `http://` / `https://` sont permises comme
 *     liens cliquables. Une URL détectée avec un autre scheme (`javascript:`,
 *     `data:`, `file:`, `content:`, `intent:` …) est rendue en texte brut SANS
 *     `withLink`. Aucun risque qu'une app installée déclarant un intent-filter
 *     exotique intercepte un tap utilisateur sur un body malicieux.
 *   - **Bare domain** (`google.com`) : normalisé en `https://google.com`.
 *   - **Strip ponctuation queue** : `Hello google.com.` → URL = `google.com`,
 *     le `.` final reste hors-lien.
 *   - **Filtre digit count téléphone** : un match `Patterns.PHONE` n'est promu
 *     cliquable que si son nombre de chiffres tombe dans `[PHONE_MIN_DIGITS,
 *     PHONE_MAX_DIGITS]` — évite la pollution sur les codes courts (CB derniers
 *     4 digits, codes promo) et les chaînes de chiffres pathologiques.
 *   - **Pas d'intent direct** : le tap téléphone ne déclenche AUCUN intent depuis
 *     ce composable. Il appelle [onPhoneClick] qui owns le dialog de confirmation
 *     (cf. [PhoneActionsDialog]) avec actions explicites.
 *
 * Chevauchements URL ↔ téléphone : URL gagne (priorité 0 vs 1 dans le tri). Évite
 * qu'une URL contenant des chiffres soit fragmentée en un lien URL + un sous-lien
 * téléphone décalé.
 *
 * Round 53 — smart actions: [onSmartAction] receives the tap for an
 * email, address, date/time or tracking-number span. Like the phone tap,
 * it triggers NO intent from this composable — the caller owns the
 * intent (see `SmartActionIntents.perform`).
 */
internal fun buildLinkifiedText(
    text: String,
    /**
     * Feedback round 6 — id of the message [text] belongs to (`-1` =
     * unknown). When known, the regex hits come from [LinkHitCache] instead
     * of re-running `Patterns.WEB_URL` + `Patterns.PHONE` on every first
     * composition of the bubble.
     */
    messageId: Long = -1L,
    onPhoneClick: (String) -> Unit = {},
    onSmartAction: (SmartAction) -> Unit = {},
    /**
     * Feedback round 5 — thread search highlight, applied on top of the
     * linkified text (see [addSearchHighlight]). The ranges index into
     * [text] itself, so they stay valid whatever the link pass appends.
     */
    highlightRanges: List<IntRange> = emptyList(),
    highlightCurrent: Boolean = false,
): AnnotatedString = buildAnnotatedString {
    if (text.isEmpty()) return@buildAnnotatedString
    if (text.length > LINKIFY_INPUT_CAP) {
        append(text)
        addSearchHighlight(text.length, highlightRanges, highlightCurrent)
        return@buildAnnotatedString
    }

    val hits = if (messageId >= 0L) LinkHitCache.hitsFor(messageId, text) else collectLinkHits(text)
    if (hits.isEmpty()) {
        append(text)
        addSearchHighlight(text.length, highlightRanges, highlightCurrent)
        return@buildAnnotatedString
    }

    var cursor = 0
    for (hit in hits) {
        if (hit.start > cursor) {
            append(text.substring(cursor, hit.start))
        }
        val raw = text.substring(hit.start, hit.end)
        when (hit.kind) {
            HitKind.Url -> {
                val stripped = raw.trimEnd(*URL_TRAILING_PUNCTUATION)
                val safeTarget = stripped.toSafeHttpsTargetOrNull()
                if (safeTarget != null) {
                    withLink(LinkAnnotation.Url(url = safeTarget, styles = LINK_STYLE)) {
                        append(stripped)
                    }
                } else {
                    // Scheme exotique → rendu brut, pas cliquable.
                    append(stripped)
                }
                if (stripped.length < raw.length) {
                    append(raw.substring(stripped.length))
                }
            }
            HitKind.Phone -> {
                // Bind the raw match into a local so the listener closes over a stable
                // String — not over a loop variable that could mutate between matches.
                val number = raw
                withLink(
                    LinkAnnotation.Clickable(
                        tag = number,
                        styles = LINK_STYLE,
                        linkInteractionListener = { onPhoneClick(number) },
                    ),
                ) {
                    append(number)
                }
            }
            HitKind.Email, HitKind.Tracking, HitKind.DateTime, HitKind.Address -> {
                // The action is bound into a local for the same reason as
                // the phone number above. A hit without its action (never
                // produced by collectLinkHits) renders as plain text.
                val action = hit.action
                if (action != null) {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = raw,
                            styles = LINK_STYLE,
                            linkInteractionListener = { onSmartAction(action) },
                        ),
                    ) {
                        append(raw)
                    }
                } else {
                    append(raw)
                }
            }
        }
        cursor = hit.end
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
    addSearchHighlight(text.length, highlightRanges, highlightCurrent)
}

/**
 * Round 17 — the first fetchable URL in [text], for the link preview card
 * ([LinkPreviewCard]). Reuses the same hits the linkify pass finds
 * ([collectLinkHits] / [LinkHitCache]), so the preview always matches a
 * link the bubble renders clickable. `null` when there is no URL, the body
 * exceeds [LINKIFY_INPUT_CAP] (no linkify there either), or the first URL
 * has an exotic scheme (see [String.toSafeHttpsTargetOrNull]).
 */
internal fun firstPreviewUrl(text: String, messageId: Long = -1L): String? {
    if (text.isEmpty() || text.length > LINKIFY_INPUT_CAP) return null
    val hits = if (messageId >= 0L) LinkHitCache.hitsFor(messageId, text) else collectLinkHits(text)
    val first = hits.firstOrNull { it.kind == HitKind.Url } ?: return null
    val raw = text.substring(first.start, first.end)
    return raw.trimEnd(*URL_TRAILING_PUNCTUATION).toSafeHttpsTargetOrNull()
}

/**
 * Round 53 — the first physical address in [text], for the map preview
 * card ([MapPreviewCard]). Reuses the same hits the linkify pass finds
 * ([collectLinkHits] / [LinkHitCache]), so the preview always matches an
 * address the bubble renders clickable. `null` when there is no address
 * or the body exceeds [LINKIFY_INPUT_CAP] (no linkify there either).
 */
internal fun firstSmartAddress(text: String, messageId: Long = -1L): String? {
    if (text.isEmpty() || text.length > LINKIFY_INPUT_CAP) return null
    val hits = if (messageId >= 0L) LinkHitCache.hitsFor(messageId, text) else collectLinkHits(text)
    val first = hits.firstOrNull { it.kind == HitKind.Address } ?: return null
    return (first.action as? SmartAction.Address)?.text
}

/**
 * Feedback round 5 — thread search highlight, as a span style on top of the
 * built text. Black text on a solid yellow block reads on both bubble
 * colours (white text on blue outgoing, dark text on grey incoming); the
 * message the search is currently sitting on gets the stronger orange.
 * Ranges are clamped to the text and empty ones skipped, so a stale range
 * can never crash the render. Applied after the link spans: the two kinds
 * of span overlap independently, and a highlighted link keeps its underline.
 */
internal fun AnnotatedString.Builder.addSearchHighlight(
    textLength: Int,
    ranges: List<IntRange>,
    current: Boolean,
) {
    if (ranges.isEmpty() || textLength <= 0) return
    val style = if (current) SEARCH_CURRENT_MATCH_STYLE else SEARCH_MATCH_STYLE
    for (range in ranges) {
        val start = range.first.coerceIn(0, textLength)
        val end = (range.last + 1).coerceIn(0, textLength)
        if (start < end) addStyle(style, start, end)
    }
}

/**
 * Feedback round 5 — thread search highlight: every occurrence of the query
 * in a message body, as inclusive ranges, plus whether the search is
 * currently sitting on this message ([current] gets the stronger colour).
 * One object — not two parameters — so [MessageBubble] stays under the
 * parameter-count limit.
 */
data class SearchHighlight(
    val ranges: List<IntRange> = emptyList(),
    val current: Boolean = false,
)

/** Feedback round 5 — search match highlight (see [addSearchHighlight]). */
private val SEARCH_MATCH_STYLE = SpanStyle(
    background = Color(0xFFFFEB3B),
    color = Color.Black,
)

/** Feedback round 5 — current search match highlight (see [addSearchHighlight]). */
private val SEARCH_CURRENT_MATCH_STYLE = SpanStyle(
    background = Color(0xFFFF9800),
    color = Color.Black,
)

/**
 * Feedback round 6 — per-message cache for the linkify regex hits
 * ([collectLinkHits] runs `Patterns.WEB_URL` + `Patterns.PHONE` + the
 * smart-action detectors over the whole body). The composable `remember`
 * only survives while the bubble is composed; scrolling a bubble
 * off-screen disposes it, so scrolling back re-ran the regexes on every
 * first composition. Hits are keyed by message id and validated against
 * the body (bodies are immutable per id — the check is a safety net). Only the regex pass is cached: the final
 * `AnnotatedString` is still built per composition, because the search
 * highlights change with navigation and must not be cached. Bounded (256
 * entries, eldest evicted) so a long thread cannot grow it without limit.
 */
private object LinkHitCache {
    private const val MAX_ENTRIES: Int = 256
    private val entries = object : LinkedHashMap<Long, Pair<String, List<LinkHit>>>(MAX_ENTRIES) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, Pair<String, List<LinkHit>>>,
        ): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun hitsFor(messageId: Long, text: String): List<LinkHit> {
        val cached = entries[messageId]
        if (cached != null && cached.first == text) return cached.second
        val hits = collectLinkHits(text)
        entries[messageId] = text to hits
        return hits
    }
}

/**
 * v1.3.11 (F4) — runs the URL and PHONE regexes once each, plus the
 * round-53 smart-action detectors (email, address, date/time, tracking),
 * then merges + dedupes overlapping ranges ([resolveHitOverlaps]).
 */
private fun collectLinkHits(text: String): List<LinkHit> {
    val all = ArrayList<LinkHit>(8)
    val webMatcher = Patterns.WEB_URL.matcher(text)
    while (webMatcher.find()) {
        all += LinkHit(webMatcher.start(), webMatcher.end(), HitKind.Url)
    }
    val phoneMatcher = Patterns.PHONE.matcher(text)
    while (phoneMatcher.find()) {
        val start = phoneMatcher.start()
        val end = phoneMatcher.end()
        val digits = countDigits(text, start, end)
        if (digits in PHONE_MIN_DIGITS..PHONE_MAX_DIGITS) {
            all += LinkHit(start, end, HitKind.Phone)
        }
    }
    // Round 53 — the smart-action detectors run over the same body; their
    // matches join the same overlap resolution below. Dates resolve
    // against now in the device's time zone.
    val nowMillis = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    for (match in SmartActionDetector.detectAll(text, nowMillis, zone)) {
        val kind = when (match.action) {
            is SmartAction.Email -> HitKind.Email
            is SmartAction.Tracking -> HitKind.Tracking
            is SmartAction.DateTime -> HitKind.DateTime
            is SmartAction.Address -> HitKind.Address
            // Phones come from Patterns.PHONE above; the detector never
            // produces a Call (it is a notification-only representation).
            is SmartAction.Call -> continue
        }
        all += LinkHit(match.range.first, match.range.last + 1, kind, match.action)
    }
    return resolveHitOverlaps(all)
}

/**
 * Round 53 — merges + dedupes overlapping ranges. Sort by start (asc),
 * then by kind priority ([HitKind.ordinal]: Url 0, Email 1, Tracking 2,
 * DateTime 3, Address 4, Phone 5), then drop any hit whose range overlaps
 * the previous accepted one — so `https://example.com/+33612345678`
 * renders as a single URL link, an email inside a URL never fragments
 * it, and a ZIP code is not offered as a call. Internal so JUnit can pin
 * the priority without Compose or `Patterns`.
 */
internal fun resolveHitOverlaps(hits: List<LinkHit>): List<LinkHit> {
    if (hits.isEmpty()) return emptyList()
    val sorted = hits.sortedWith(compareBy({ it.start }, { it.kind.ordinal }))
    val accepted = ArrayList<LinkHit>(sorted.size)
    var lastEnd = 0
    for (hit in sorted) {
        if (hit.start >= lastEnd) {
            accepted += hit
            lastEnd = hit.end
        }
    }
    return accepted
}

/**
 * v1.3.11 (F4) — internal so JUnit can pin the digit-count band filter that drives the
 * phone-link promotion decision. Pure helper, no Android dependency.
 */
internal fun countDigits(text: String, start: Int, end: Int): Int {
    var n = 0
    for (i in start until end) {
        if (text[i].isDigit()) n++
    }
    return n
}

/** v1.3.11 (F4) — internal for JUnit visibility; mirrors the const used in the filter. */
internal const val PHONE_DIGITS_MIN: Int = PHONE_MIN_DIGITS
internal const val PHONE_DIGITS_MAX: Int = PHONE_MAX_DIGITS

/**
 * v1.3.2 — retourne une URL cible sûre pour `LinkAnnotation.Url` :
 *
 *   - `https://google.com` (déjà bon scheme) → tel quel
 *   - `google.com` (bare domain, pas de scheme) → `https://google.com`
 *   - `javascript:alert(1)` / `data:…` / `intent:…` / autre → `null` (refusé)
 *
 * Le test "pas de scheme" est strict : présence d'un `:` AVANT le 1ᵉʳ `/` indique
 * un scheme ; si ce scheme n'est ni http(s), on refuse.
 */
internal fun String.toSafeHttpsTargetOrNull(): String? {
    if (startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)) {
        return this
    }
    // Détecte un scheme suspect : `xxx:yyy` avant tout `/`.
    val colonIdx = indexOf(':')
    val slashIdx = indexOf('/')
    val hasExoticScheme = colonIdx > 0 && (slashIdx < 0 || colonIdx < slashIdx)
    if (hasExoticScheme) return null
    return "https://$this"
}
