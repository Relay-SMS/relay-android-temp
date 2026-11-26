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
 * Round 55 — locks down [TelephonyReader.snapshotMmsCount], the
 * cheap aggregate the first-run import uses for the MMS-phase
 * progress bar. Same shape as the SMS fingerprint
 * ([TelephonyReader.snapshotSmsFingerprint]): a `COUNT(_id)`
 * aggregate over `content://mms`, metadata only, no row bodies.
 *
 * `Uri.parse` is statically mocked: on plain JVM the android.jar
 * stub throws, and [snapshotMmsCount] would see a null cursor and
 * return 0 — so without the static mock every test here would
 * pass vacuously on the failure path. The mock records the parsed
 * URI strings so the test pins the exact `content://mms` URI.
 */
class TelephonyReaderMmsCountTest {

    private lateinit var resolver: ContentResolver
    private lateinit var reader: TelephonyReader
    private lateinit var mmsUri: Uri
    private val parsedUris = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class)
        mmsUri = mockk()
        every { Uri.parse(any()) } answers {
            val raw = firstArg<String>()
            parsedUris += raw
            if (raw == "content://mms") mmsUri else mockk()
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

    /** A cursor over the single aggregate row, or over no rows when [count] is null. */
    private fun countCursor(count: Int?): Cursor {
        val cursor = mockk<Cursor>()
        every { cursor.moveToFirst() } returns (count != null)
        if (count != null) every { cursor.getInt(0) } returns count
        every { cursor.close() } just Runs
        return cursor
    }

    @Test
    fun `count query returns the MMS row count`() {
        val projectionSlot = slot<Array<String>>()
        every {
            resolver.query(mmsUri, capture(projectionSlot), any(), any(), any())
        } returns countCursor(42)

        assertThat(reader.snapshotMmsCount()).isEqualTo(42)
        assertThat(parsedUris).contains("content://mms")
        assertThat(projectionSlot.captured.toList()).containsExactly("COUNT(_id) AS c")
        verify { Uri.parse("content://mms") }
    }

    @Test
    fun `null cursor returns 0 so the caller degrades to the spinner`() {
        every { resolver.query(any(), any(), any(), any(), any()) } returns null

        assertThat(reader.snapshotMmsCount()).isEqualTo(0)
    }

    @Test
    fun `empty provider returns 0`() {
        every { resolver.query(any(), any(), any(), any(), any()) } returns countCursor(null)

        assertThat(reader.snapshotMmsCount()).isEqualTo(0)
    }
}
