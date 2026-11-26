/*
 * Copyright 2026 Relay Messaging (modifications). Relay's modifications
 * to this file are proprietary to Relay Messaging and are not licensed
 * under the Apache License 2.0. See LICENSE-RELAY for the terms. The
 * original work is copyright its respective holders and is licensed
 * under the Apache License 2.0. See LICENSE.
 */

package com.relay.sms.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.google.common.truth.Truth.assertThat
import com.relay.sms.R
import com.relay.sms.domain.smartactions.SmartAction
import com.relay.sms.domain.smartactions.TrackingCarrier
import org.junit.jupiter.api.Test

/**
 * v1.3.2 — garde de sécurité sur le whitelist scheme de [toSafeHttpsTargetOrNull].
 * Sans cette validation, une URL avec `javascript:` ou `intent:` détectée par
 * `Patterns.WEB_URL` dans un message reçu malicieux pourrait être rendue cliquable
 * et ouvrir une intent système hostile via [androidx.compose.ui.platform.UriHandler].
 *
 * Les cas dépendant de `android.util.Patterns` (regex Android non disponible en JVM
 * pure sans Robolectric) sont reportés v1.3.3 — la garde scheme reste vérifiée ici
 * de manière isolée, c'est la défense critique.
 */
class MessageTextWithLinksTest {

    @Test fun `https URL is preserved as-is`() {
        assertThat("https://google.com".toSafeHttpsTargetOrNull()).isEqualTo("https://google.com")
        assertThat("https://example.org/path?q=1".toSafeHttpsTargetOrNull())
            .isEqualTo("https://example.org/path?q=1")
    }

    @Test fun `http URL is preserved (legacy sites)`() {
        assertThat("http://legacy.com".toSafeHttpsTargetOrNull()).isEqualTo("http://legacy.com")
    }

    @Test fun `https and http detection is case-insensitive`() {
        // Le scheme peut être en majuscules dans certains SMS (recopiés d'un mail).
        assertThat("HTTPS://X.COM".toSafeHttpsTargetOrNull()).isEqualTo("HTTPS://X.COM")
        assertThat("Http://Y.com".toSafeHttpsTargetOrNull()).isEqualTo("Http://Y.com")
    }

    @Test fun `bare domain gets https prepended`() {
        assertThat("google.com".toSafeHttpsTargetOrNull()).isEqualTo("https://google.com")
        assertThat("sub.example.org/path".toSafeHttpsTargetOrNull())
            .isEqualTo("https://sub.example.org/path")
    }

    @Test fun `javascript scheme is refused`() {
        // Le critique : un message recu malicieux contenant "javascript:alert(1)"
        // ne doit JAMAIS produire un LinkAnnotation.Url cliquable.
        assertThat("javascript:alert(1)".toSafeHttpsTargetOrNull()).isNull()
    }

    @Test fun `data scheme is refused`() {
        assertThat("data:text/html,<script>alert(1)</script>".toSafeHttpsTargetOrNull()).isNull()
    }

    @Test fun `intent scheme is refused`() {
        // Android intent: scheme peut router vers une activité hostile.
        assertThat("intent://x.y#Intent;scheme=http;end".toSafeHttpsTargetOrNull()).isNull()
    }

    @Test fun `file scheme is refused`() {
        assertThat("file:///etc/passwd".toSafeHttpsTargetOrNull()).isNull()
    }

    @Test fun `content scheme is refused`() {
        assertThat("content://com.evil.app/data".toSafeHttpsTargetOrNull()).isNull()
    }

    @Test fun `tel scheme is refused`() {
        // Réservé aux gestes téléphone explicites, pas aux URLs dans le body.
        assertThat("tel:+33612345678".toSafeHttpsTargetOrNull()).isNull()
    }

    @Test fun `domain with path containing colon does not trigger exotic-scheme detection`() {
        // Edge case : un path peut contenir un `:` après le premier `/` (ex. UUID
        // typographique). Le test "scheme = avant le premier /" évite ce faux positif.
        val out = "example.com/path:foo/bar".toSafeHttpsTargetOrNull()
        assertThat(out).isEqualTo("https://example.com/path:foo/bar")
    }

    // ──────────────── v1.3.11 (F4) — phone link helper unit tests ────────────────
    //
    // The full phone-detection path (`collectLinkHits` → `Patterns.PHONE.matcher`) requires
    // `android.util.Patterns` and would need Robolectric to run in pure JVM. We still
    // pin the **two purely-Kotlin helpers** that gate the phone-link promotion decision
    // so a future refactor cannot silently widen the band filter:
    //
    //   - [countDigits] : counts the digits inside a substring range — the value compared
    //     against [PHONE_DIGITS_MIN] / [PHONE_DIGITS_MAX].
    //   - [PHONE_DIGITS_MIN] / [PHONE_DIGITS_MAX] : the digit-count band itself.
    //
    // Together these ensure that even if `Patterns.PHONE` matches a 4-digit promo code or
    // a 20-digit IBAN, the link is filtered out before [LinkAnnotation.Clickable] is
    // emitted.

    @Test fun `countDigits counts only ASCII digits within the substring range`() {
        assertThat(countDigits("+33 612 34 56 78", 0, 16)).isEqualTo(11)
        assertThat(countDigits("Order #12345", 0, 12)).isEqualTo(5)
        assertThat(countDigits("no digits here", 0, 14)).isEqualTo(0)
        assertThat(countDigits("abc 123 def 456", 4, 7)).isEqualTo(3)
    }

    @Test fun `countDigits handles empty range without throwing`() {
        assertThat(countDigits("anything", 3, 3)).isEqualTo(0)
    }

    @Test fun `phone digit band rejects too-short and too-long sequences`() {
        // The band MUST stay at [7, 15] — anything lower lets order IDs / promo codes
        // through, anything higher accepts IBANs / credit-card numbers.
        assertThat(PHONE_DIGITS_MIN).isEqualTo(7)
        assertThat(PHONE_DIGITS_MAX).isEqualTo(15)
        // Sanity: a 6-digit count is below the floor, 16 is above the ceiling.
        assertThat(6 in PHONE_DIGITS_MIN..PHONE_DIGITS_MAX).isFalse()
        assertThat(16 in PHONE_DIGITS_MIN..PHONE_DIGITS_MAX).isFalse()
        // Common French national + international formats fall inside the band.
        assertThat(10 in PHONE_DIGITS_MIN..PHONE_DIGITS_MAX).isTrue() // 06 12 34 56 78
        assertThat(11 in PHONE_DIGITS_MIN..PHONE_DIGITS_MAX).isTrue() // +33 6 12 34 56 78
    }

    // ──────────────── Feedback round 5 — thread search highlight ────────────────
    //
    // [addSearchHighlight] is exercised directly (no `Patterns` involved, so it
    // runs in plain JVM): the text itself is never rewritten, only the span
    // styles over the matched ranges. The yellow block + black text must read
    // on both bubble colours (white text on blue outgoing, dark text on grey
    // incoming); the message the search sits on gets the stronger orange.

    private fun highlighted(
        text: String,
        ranges: List<IntRange>,
        current: Boolean = false,
    ) = buildAnnotatedString {
        append(text)
        addSearchHighlight(text.length, ranges, current)
    }

    @Test fun `highlight covers every matched range and nothing else`() {
        val out = highlighted("hello world hello", listOf(0..4, 12..16))
        assertThat(out.text).isEqualTo("hello world hello")
        assertThat(out.spanStyles.map { it.start to it.end })
            .containsExactly(0 to 5, 12 to 17).inOrder()
        assertThat(out.spanStyles.map { it.item.background })
            .containsExactly(Color(0xFFFFEB3B), Color(0xFFFFEB3B))
        assertThat(out.spanStyles.map { it.item.color })
            .containsExactly(Color.Black, Color.Black)
    }

    @Test fun `current match uses the stronger orange`() {
        val out = highlighted("hello world", listOf(0..4), current = true)
        assertThat(out.spanStyles).hasSize(1)
        assertThat(out.spanStyles[0].item.background).isEqualTo(Color(0xFFFF9800))
        assertThat(out.spanStyles[0].item.color).isEqualTo(Color.Black)
    }

    @Test fun `no ranges means no spans`() {
        val out = highlighted("hello world", emptyList())
        assertThat(out.spanStyles).isEmpty()
    }

    @Test fun `ranges are clamped to the text and empty ones skipped`() {
        val out = highlighted("hello world", listOf(6..20, 15..99))
        // 6..20 covers "world" (clamped to the end); the fully out-of-range
        // one produces nothing.
        assertThat(out.spanStyles.map { it.start to it.end })
            .containsExactly(6 to 11)
    }

    // ──────────────── Round 53 — smart-action overlap priority ────────────────
    //
    // [resolveHitOverlaps] is exercised directly (no `Patterns` involved, so it
    // runs in plain JVM): the priority is Url > Email > Tracking > DateTime >
    // Address > Phone, so a tracking number or an email inside a URL never
    // fragments it, and a ZIP code is not offered as a call.

    @Test fun `url wins over an email inside it`() {
        val url = LinkHit(0, 30, HitKind.Url)
        val email = LinkHit(
            10,
            25,
            HitKind.Email,
            SmartAction.Email("bob@example.com"),
        )
        assertThat(resolveHitOverlaps(listOf(email, url))).containsExactly(url)
    }

    @Test fun `url wins over a tracking number inside it`() {
        val url = LinkHit(0, 40, HitKind.Url)
        val tracking = LinkHit(
            20,
            38,
            HitKind.Tracking,
            SmartAction.Tracking(TrackingCarrier.UPS, "1Z999AA10123456784"),
        )
        assertThat(resolveHitOverlaps(listOf(tracking, url))).containsExactly(url)
    }

    @Test fun `email wins over a tracking number at the same start`() {
        val email = LinkHit(0, 20, HitKind.Email, SmartAction.Email("1z999@example.com"))
        val tracking = LinkHit(
            0,
            18,
            HitKind.Tracking,
            SmartAction.Tracking(TrackingCarrier.UPS, "1Z999AA10123456784"),
        )
        assertThat(resolveHitOverlaps(listOf(tracking, email))).containsExactly(email)
    }

    @Test fun `address wins over a phone at the same start`() {
        val address = LinkHit(0, 14, HitKind.Address, SmartAction.Address("123 Main Street"))
        val phone = LinkHit(0, 3, HitKind.Phone)
        assertThat(resolveHitOverlaps(listOf(phone, address))).containsExactly(address)
    }

    @Test fun `date time wins over an address at the same start`() {
        val date = LinkHit(
            0,
            10,
            HitKind.DateTime,
            SmartAction.DateTime("Dec 25", 0L, 0L, true),
        )
        val address = LinkHit(0, 14, HitKind.Address, SmartAction.Address("123 Main Street"))
        assertThat(resolveHitOverlaps(listOf(address, date))).containsExactly(date)
    }

    @Test fun `non-overlapping hits are all kept in position order`() {
        val hits = listOf(
            LinkHit(20, 30, HitKind.Phone),
            LinkHit(0, 10, HitKind.Email, SmartAction.Email("a@b.com")),
            LinkHit(
                12,
                18,
                HitKind.DateTime,
                SmartAction.DateTime("Dec 25", 0L, 0L, true),
            ),
        )
        assertThat(resolveHitOverlaps(hits).map { it.kind })
            .containsExactly(HitKind.Email, HitKind.DateTime, HitKind.Phone).inOrder()
    }

    @Test fun `empty input resolves to empty`() {
        assertThat(resolveHitOverlaps(emptyList())).isEmpty()
    }

    // ──────────────────── Round 55 — smart-action pills + no underline ────────────────────
    //
    // [linkStyleFor] and [smartActionPillsForHits] are exercised directly (no
    // `Patterns` involved, so they run in plain JVM): the pills list is built
    // over pre-resolved hits, and the span style is pinned per kind — URLs
    // stay underlined, every smart-action + phone span reads as plain text
    // (the pill under the bubble carries the action instead).

    @Test fun `url span stays underlined`() {
        assertThat(linkStyleFor(HitKind.Url).style?.textDecoration)
            .isEqualTo(TextDecoration.Underline)
    }

    @Test fun `smart action and phone spans are not underlined`() {
        for (kind in listOf(
            HitKind.Email,
            HitKind.Tracking,
            HitKind.DateTime,
            HitKind.Address,
            HitKind.Phone,
        )) {
            assertThat(linkStyleFor(kind).style?.textDecoration).isNull()
        }
    }

    @Test fun `one pill per kind with the right label, in detection order`() {
        val text = "0123456789012345678901234567890123456789"
        val hits = listOf(
            LinkHit(0, 10, HitKind.Phone),
            LinkHit(11, 20, HitKind.Email, SmartAction.Email("a@b.com")),
            LinkHit(
                21,
                30,
                HitKind.Tracking,
                SmartAction.Tracking(TrackingCarrier.UPS, "1Z999AA10123456784"),
            ),
            LinkHit(
                31,
                38,
                HitKind.DateTime,
                SmartAction.DateTime("Dec 25", 0L, 0L, true),
            ),
            LinkHit(39, 40, HitKind.Address, SmartAction.Address("123 Main Street")),
        )
        val pills = smartActionPillsForHits(hits, text)
        assertThat(pills.map { it.labelRes }).containsExactly(
            R.string.smart_action_call,
            R.string.smart_action_send_email,
            R.string.smart_action_track_package,
            R.string.smart_action_add_to_calendar,
            R.string.smart_action_open_maps,
        ).inOrder()
        assertThat(pills[0].phoneNumber).isEqualTo("0123456789")
        assertThat(pills[1].action).isEqualTo(SmartAction.Email("a@b.com"))
        assertThat(pills[2].action).isEqualTo(
            SmartAction.Tracking(TrackingCarrier.UPS, "1Z999AA10123456784"),
        )
        assertThat(pills[4].action).isEqualTo(SmartAction.Address("123 Main Street"))
    }

    @Test fun `url gets no pill`() {
        val text = "Call 0612345678 or mail bob@example.com, see https://example.com"
        val hits = listOf(
            LinkHit(5, 15, HitKind.Phone),
            LinkHit(24, 39, HitKind.Email, SmartAction.Email("bob@example.com")),
            LinkHit(45, 64, HitKind.Url),
        )
        val pills = smartActionPillsForHits(hits, text)
        assertThat(pills.map { it.labelRes }).containsExactly(
            R.string.smart_action_call,
            R.string.smart_action_send_email,
        ).inOrder()
        assertThat(pills[0].phoneNumber).isEqualTo("0612345678")
    }

    @Test fun `same entity twice gets one pill`() {
        val text = "0612345678 and 0612345678"
        val hits = listOf(
            LinkHit(0, 10, HitKind.Phone),
            LinkHit(15, 25, HitKind.Phone),
        )
        assertThat(smartActionPillsForHits(hits, text)).hasSize(1)
    }

    @Test fun `hit without its action renders no pill`() {
        val text = "bob@example.com"
        val hits = listOf(LinkHit(0, 15, HitKind.Email, null))
        assertThat(smartActionPillsForHits(hits, text)).isEmpty()
    }
}
