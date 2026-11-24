/*
 * Copyright 2026 Relay Messaging (modifications). The original work is
 * copyright its respective holders and is licensed under the Apache
 * License 2.0. See LICENSE.
 */

package com.relay.sms.ui.screens.about

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The "Support the project" row on the About screen opens the Relay website
 * for now (2026-11-24): a single URL for every language, updated to a real
 * donations page later. It must never point back at the old upstream site.
 */
class DonateUrlTest {

    @Test
    fun `donate row opens the Relay website`() {
        assertThat(DONATE_URL).isEqualTo("https://getrelaysms.com")
    }

    @Test
    fun `donate url is an https link and never the old upstream site`() {
        assertThat(DONATE_URL).startsWith("https://")
        assertThat(DONATE_URL).doesNotContain("files-tech")
    }
}
