/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.sms

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Round 52 — locks down [TelephonyReader.readThreadRecipients], the
 * provider lookup the group import and the one-shot group repair
 * both depend on.
 *
 * Round 50 queried `content://mms-sms/conversations/{id}` for the
 * thread row — but AOSP maps that URI to the thread's MESSAGES, so
 * the query threw, `runCatching` swallowed it, and every group
 * silently filed as a 1:1. The fix queries
 * `conversations/{id}/recipients`. The first test below pins the
 * exact URI string (and pins the bare URI as NOT queried), so the
 * regression cannot come back silently.
 *
 * `Uri.parse` is statically mocked: on plain JVM the android.jar
 * stub throws, and `readThreadRecipients` wraps the call in
 * `runCatching` — so without the static mock every test here would
 * see `null` (the success paths would fail, the failure paths would
 * pass vacuously). The mock returns a distinct [Uri] per string so
 * the two provider queries (thread row, then canonical-addresses)
 * can be stubbed independently.
 */
class TelephonyReaderRecipientsTest {

    companion object {
        private const val THREAD_ID = 42L
    }

    private lateinit var resolver: ContentResolver
    private lateinit var reader: TelephonyReader
    private lateinit var idsUri: Uri
    private lateinit var addressesUri: Uri
    private val parsedUris = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class)
        idsUri = mockk()
        addressesUri = mockk()
        every { Uri.parse(any()) } answers {
            val raw = firstArg<String>()
            parsedUris += raw
            when {
                raw.endsWith("/recipients") -> idsUri
                raw.endsWith("canonical-addresses") -> addressesUri
                else -> mockk()
            }
        }
        resolver = mockk()
        val context = mockk<Context>()
        every { context.contentResolver } returns resolver
        reader = TelephonyReader(context)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    /** A cursor over the single `recipient_ids` row, or over no rows when [raw] is null. */
    private fun idsCursor(raw: String?): Cursor {
        val cursor = mockk<Cursor>()
        every { cursor.moveToFirst() } returns (raw != null)
        if (raw != null) every { cursor.getString(0) } returns raw
        every { cursor.close() } just Runs
        return cursor
    }

    /** A cursor over one `address` row per entry (nulls model NULL cells). */
    private fun addressesCursor(vararg rows: String?): Cursor {
        val cursor = mockk<Cursor>()
        var index = -1
        every { cursor.moveToNext() } answers { ++index < rows.size }
        every { cursor.getString(0) } answers { rows[index] }
        every { cursor.close() } just Runs
        return cursor
    }

    private fun stubIds(raw: String?) {
        every { resolver.query(idsUri, any(), any(), any(), any()) } returns idsCursor(raw)
    }

    private fun stubAddresses(vararg rows: String?) {
        every { resolver.query(addressesUri, any(), any(), any(), any()) } returns
            addressesCursor(*rows)
    }

    @Test
    fun `queries the recipients URI, not the bare conversations URI`() {
        stubIds("3 7")
        stubAddresses("+16100000001", "+16100000002")

        val result = reader.readThreadRecipients(THREAD_ID)

        assertThat(result).containsExactly("+16100000001", "+16100000002")
        assertThat(parsedUris).contains("content://mms-sms/conversations/42/recipients")
        assertThat(parsedUris).doesNotContain("content://mms-sms/conversations/42")
        verify { Uri.parse("content://mms-sms/conversations/42/recipients") }
    }

    @Test
    fun `resolves recipient ids to addresses`() {
        stubIds("3 7 9")
        stubAddresses("+16100000001", "+16100000002", "+16100000003")

        val result = reader.readThreadRecipients(THREAD_ID)

        assertThat(result).containsExactly("+16100000001", "+16100000002", "+16100000003")
    }

    @Test
    fun `non-positive thread id returns an empty set without querying`() {
        assertThat(reader.readThreadRecipients(0L)).isEmpty()
        assertThat(reader.readThreadRecipients(-5L)).isEmpty()
        verify(exactly = 0) { resolver.query(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { Uri.parse(any()) }
    }

    @Test
    fun `missing thread row returns an empty set and never reads addresses`() {
        stubIds(null)

        assertThat(reader.readThreadRecipients(THREAD_ID)).isEmpty()
        verify(exactly = 0) { resolver.query(addressesUri, any(), any(), any(), any()) }
    }

    @Test
    fun `empty recipient ids return an empty set`() {
        stubIds("")

        assertThat(reader.readThreadRecipients(THREAD_ID)).isEmpty()
        verify(exactly = 0) { resolver.query(addressesUri, any(), any(), any(), any()) }
    }

    @Test
    fun `non-numeric recipient ids are ignored`() {
        val argsSlot = slot<Array<String>>()
        stubIds("3 abc 7")
        every { resolver.query(addressesUri, any(), any(), capture(argsSlot), any()) } returns
            addressesCursor("+16100000001", "+16100000002")

        val result = reader.readThreadRecipients(THREAD_ID)

        assertThat(result).containsExactly("+16100000001", "+16100000002")
        assertThat(argsSlot.captured.toList()).containsExactly("3", "7").inOrder()
    }

    @Test
    fun `duplicate recipient ids are resolved once`() {
        val argsSlot = slot<Array<String>>()
        stubIds("3 3 7 3")
        every { resolver.query(addressesUri, any(), any(), capture(argsSlot), any()) } returns
            addressesCursor("+16100000001", "+16100000002")

        reader.readThreadRecipients(THREAD_ID)

        assertThat(argsSlot.captured.toList()).containsExactly("3", "7").inOrder()
    }

    @Test
    fun `insert-address-token blank and null addresses are filtered`() {
        stubIds("3 7 9 11 13")
        stubAddresses("+16100000001", "insert-address-token", "", "  ", null)

        val result = reader.readThreadRecipients(THREAD_ID)

        assertThat(result).containsExactly("+16100000001")
    }

    @Test
    fun `provider throw on the thread query returns null`() {
        every { resolver.query(idsUri, any(), any(), any(), any()) } throws
            IllegalStateException("provider down")

        assertThat(reader.readThreadRecipients(THREAD_ID)).isNull()
    }

    @Test
    fun `null cursor on the thread query returns null`() {
        every { resolver.query(idsUri, any(), any(), any(), any()) } returns null

        assertThat(reader.readThreadRecipients(THREAD_ID)).isNull()
    }

    @Test
    fun `provider throw on the addresses query returns null`() {
        stubIds("3 7")
        every { resolver.query(addressesUri, any(), any(), any(), any()) } throws
            IllegalStateException("provider down")

        assertThat(reader.readThreadRecipients(THREAD_ID)).isNull()
    }
}
