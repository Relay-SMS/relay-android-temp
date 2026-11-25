/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.domain.smartactions

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Positive and negative cases for [EmailDetector]. The negative cases are
 * the point: a version number with an `@`, a fragment of a longer token
 * and a bare host must never surface a "Send email" action — a false
 * positive teaches the user to ignore the feature.
 */
class EmailDetectorTest {

    @Test fun `simple address`() {
        assertThat(EmailDetector.findFirst("mail me at bob@example.com")?.action)
            .isEqualTo(SmartAction.Email("bob@example.com"))
    }

    @Test fun `dots plus tag and subdomain`() {
        val match = EmailDetector.findFirst("write to first.last+tag@sub.example.co.uk please")
        assertThat((match?.action as? SmartAction.Email)?.address)
            .isEqualTo("first.last+tag@sub.example.co.uk")
    }

    @Test fun `trailing period stays outside the match`() {
        val match = EmailDetector.findFirst("Contact bob@example.com.")
        assertThat((match?.action as? SmartAction.Email)?.address).isEqualTo("bob@example.com")
        assertThat(match?.range?.last).isEqualTo("Contact bob@example.com".length - 1)
    }

    @Test fun `trailing comma stays outside the match`() {
        val match = EmailDetector.findFirst("bob@example.com, or call me")
        assertThat((match?.action as? SmartAction.Email)?.address).isEqualTo("bob@example.com")
    }

    @Test fun `uppercase address matches with case preserved`() {
        assertThat(EmailDetector.findFirst("BOB@EXAMPLE.COM")?.action)
            .isEqualTo(SmartAction.Email("BOB@EXAMPLE.COM"))
    }

    @Test fun `two addresses in order`() {
        val found = EmailDetector.findAll("a@x.com and b@y.org")
        assertThat(found.map { (it.action as SmartAction.Email).address })
            .containsExactly("a@x.com", "b@y.org").inOrder()
    }

    @Test fun `no top-level domain is not an address`() {
        assertThat(EmailDetector.findFirst("mail bob@example")).isNull()
    }

    @Test fun `numeric top-level domain is not an address`() {
        assertThat(EmailDetector.findFirst("see bob@host.123")).isNull()
    }

    @Test fun `double at-sign is not an address`() {
        assertThat(EmailDetector.findFirst("bob@@example.com")).isNull()
    }

    @Test fun `single letter top-level domain is not an address`() {
        assertThat(EmailDetector.findFirst("bob@example.c")).isNull()
    }

    @Test fun `plain text has no address`() {
        assertThat(EmailDetector.findFirst("hello, how are you?")).isNull()
        assertThat(EmailDetector.findFirst("")).isNull()
    }
}
