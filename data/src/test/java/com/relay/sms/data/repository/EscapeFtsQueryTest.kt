package com.relay.sms.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.6.1 (audit QUAL-18) — garde anti-régression sur l'échappement FTS4 utilisé par
 * la recherche conversation. Toute modification de l'algo doit garder ces cas verts,
 * sous peine de faire crash `MATCH` SQLite sur de vraies queries utilisateur.
 */
class EscapeFtsQueryTest {

    @Test fun `empty input returns empty`() {
        assertThat(escapeFtsQuery("")).isEmpty()
    }

    @Test fun `whitespace-only input returns empty`() {
        assertThat(escapeFtsQuery("   \t\n  ")).isEmpty()
    }

    @Test fun `single token gets star suffix`() {
        assertThat(escapeFtsQuery("bonjour")).isEqualTo("bonjour*")
    }

    @Test fun `multi tokens are joined with single space and starred`() {
        assertThat(escapeFtsQuery("foo bar baz")).isEqualTo("foo* bar* baz*")
    }

    @Test fun `multiple whitespaces between tokens collapse to single space`() {
        assertThat(escapeFtsQuery("foo   bar")).isEqualTo("foo* bar*")
    }

    @Test fun `FTS reserved chars are stripped per token`() {
        // " * ^ : - ( ) +  → all dropped, what remains keeps the prefix marker.
        assertThat(escapeFtsQuery("fo\"o ba*r ba^z (te)st pl+us mi-nus")).isEqualTo(
            "foo* bar* baz* test* plus* minus*",
        )
    }

    @Test fun `token that becomes empty after stripping is filtered out`() {
        // "***" reduces to "" — must be dropped, not produce a lone "*".
        assertThat(escapeFtsQuery("hello *** world")).isEqualTo("hello* world*")
    }

    @Test fun `control chars C0 are replaced by space (split safely)`() {
        // NUL / SOH / BEL / TAB are dangerous if forwarded to FTS. They become spaces
        // so tokens around them split naturally.
        assertThat(escapeFtsQuery("foo\u0000barbazend")).isEqualTo(
            "foo* bar* baz* end*",
        )
    }

    @Test fun `DEL char is replaced by space`() {
        assertThat(escapeFtsQuery("foobar")).isEqualTo("foo* bar*")
    }

    @Test fun `BiDi RLO override is fully stripped, not converted to space`() {
        // RLO (U+202E) is removed outright — adjacent letters merge into a single token.
        // Without this, an attacker could split a search term visually.
        assertThat(escapeFtsQuery("foo‮bar")).isEqualTo("foobar*")
    }

    @Test fun `zero-width space is fully stripped`() {
        // U+200B (ZWSP) was a classic vector to hide chars in queries.
        assertThat(escapeFtsQuery("hel​lo")).isEqualTo("hello*")
    }

    @Test fun `BOM is stripped`() {
        assertThat(escapeFtsQuery("﻿hello")).isEqualTo("hello*")
    }

    @Test fun `only reserved chars input returns empty`() {
        assertThat(escapeFtsQuery("*** ( ) +")).isEmpty()
    }

    @Test fun `Unicode letters outside reserved set are kept`() {
        // Accents, CJK et emojis ne sont PAS reserved FTS — ils passent à travers.
        assertThat(escapeFtsQuery("café 日本 ❤️")).isEqualTo("café* 日本* ❤️*")
    }

    @Test fun `leading and trailing whitespace is trimmed before split`() {
        assertThat(escapeFtsQuery("  hello world  ")).isEqualTo("hello* world*")
    }
}
