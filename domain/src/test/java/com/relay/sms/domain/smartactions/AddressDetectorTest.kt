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
 * Positive and negative cases for [AddressDetector]. The negative cases
 * are the point: "5 minutes walk down Main Street" is a sentence, not an
 * address — the capitalised-first-word gate exists for it, and the tests
 * pin the gate.
 */
class AddressDetectorTest {

    private fun firstAddress(text: String): String? =
        (AddressDetector.findFirst(text)?.action as? SmartAction.Address)?.text

    @Test fun `plain street address`() {
        assertThat(firstAddress("Meet at 123 Main Street")).isEqualTo("123 Main Street")
    }

    @Test fun `abbreviated suffix`() {
        assertThat(firstAddress("123 Main St")).isEqualTo("123 Main St")
    }

    @Test fun `city state and zip tail are included`() {
        assertThat(
            firstAddress("1600 Amphitheatre Parkway, Mountain View, CA 94043"),
        ).isEqualTo("1600 Amphitheatre Parkway, Mountain View, CA 94043")
    }

    @Test fun `zip plus four tail`() {
        assertThat(
            firstAddress("123 Main Street, Springfield, IL 62701-1234"),
        ).isEqualTo("123 Main Street, Springfield, IL 62701-1234")
    }

    @Test fun `bare zip tail`() {
        assertThat(firstAddress("123 Main St, 90210")).isEqualTo("123 Main St, 90210")
    }

    @Test fun `state and zip tail without city`() {
        assertThat(firstAddress("123 Main St, IL 62701")).isEqualTo("123 Main St, IL 62701")
    }

    @Test fun `ordinal street name`() {
        assertThat(firstAddress("45 5th Avenue")).isEqualTo("45 5th Avenue")
    }

    @Test fun `house number with letter suffix`() {
        assertThat(firstAddress("221B Baker Street")).isEqualTo("221B Baker Street")
    }

    @Test fun `apartment unit is included`() {
        assertThat(firstAddress("123 Main St, Apt 4")).isEqualTo("123 Main St, Apt 4")
    }

    @Test fun `four word street name`() {
        assertThat(firstAddress("123 Martin Luther King Jr Boulevard"))
            .isEqualTo("123 Martin Luther King Jr Boulevard")
    }

    @Test fun `directional prefix`() {
        assertThat(firstAddress("123 N Main St")).isEqualTo("123 N Main St")
    }

    @Test fun `sentence shaped like an address is not one`() {
        assertThat(firstAddress("5 minutes walk down Main Street")).isNull()
    }

    @Test fun `lowercase street name is missed by design`() {
        // The capitalised-first-word gate: a lowercase address is the
        // accepted cost of keeping sentences out (see the detector KDoc).
        assertThat(firstAddress("meet at 123 main st")).isNull()
    }

    @Test fun `street without a house number is not an address`() {
        assertThat(firstAddress("Meet at Main Street")).isNull()
    }

    @Test fun `bare number is not an address`() {
        assertThat(firstAddress("See you in 2026")).isNull()
        assertThat(firstAddress("I have 3 cats and 2 dogs")).isNull()
        assertThat(firstAddress("Call 911")).isNull()
    }

    @Test fun `decimal before a street name is not a house number`() {
        assertThat(firstAddress("Version 2.0 Street")).isNull()
    }

    @Test fun `plural suffix is not an address`() {
        assertThat(firstAddress("123 Main Streets")).isNull()
    }

    @Test fun `two addresses in order`() {
        val found = AddressDetector.findAll("from 10 Oak Ave to 20 Pine Rd")
        assertThat(found.map { (it.action as SmartAction.Address).text })
            .containsExactly("10 Oak Ave", "20 Pine Rd").inOrder()
    }
}
