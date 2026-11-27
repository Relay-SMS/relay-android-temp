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
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Round 60 — locks down the zero-part MMS rule in
 * [TelephonyReader.readMmsBatched]: an MMS with no parts at all
 * is an MMS whose download had not finished when the pass ran.
 * The row is dropped (nothing to show), but the pass must NOT
 * call itself complete while the row is recent — completing
 * would set `mmsImportCompleted` and the MMS would stay missing
 * until a reinstall, because no live receiver watches the
 * system store for a download finishing. An old part-less row
 * is an expired carrier stub: it must not hold the import
 * incomplete forever.
 *
 * The provider is mocked like [TelephonyReaderRecipientsTest]:
 * `Uri.parse` is statically mocked (plain-JVM android.jar stubs
 * throw), and each raw URI string maps to one distinct [Uri]
 * mock so the resolver stub can dispatch on it.
 */
class TelephonyReaderZeroPartMmsTest {

    private lateinit var resolver: ContentResolver
    private lateinit var reader: TelephonyReader
    private val uriByRaw = HashMap<String, Uri>()

    @BeforeEach
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val raw = firstArg<String>()
            uriByRaw.getOrPut(raw) { mockk() }
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

    /** One-row cursor over the `content://mms` columns the reader reads, by index. */
    private fun mainCursor(mmsId: Long, threadId: Long, dateSec: Long): Cursor {
        val cursor = mockk<Cursor>()
        var index = -1
        every { cursor.moveToNext() } answers { ++index < 1 }
        every { cursor.getLong(0) } returns mmsId
        every { cursor.getLong(1) } returns threadId
        every { cursor.getLong(2) } returns dateSec
        every { cursor.getInt(3) } returns 1
        every { cursor.getInt(4) } returns 1 // MMS_MSG_BOX_INBOX
        every { cursor.isNull(5) } returns true
        every { cursor.close() } just Runs
        return cursor
    }

    /** One-row `content://mms/{id}/addr` cursor: a FROM (137) address. */
    private fun addrCursor(address: String): Cursor {
        val cursor = mockk<Cursor>()
        var index = -1
        every { cursor.moveToNext() } answers { ++index < 1 }
        every { cursor.getString(0) } returns address
        every { cursor.getInt(1) } returns 137
        every { cursor.close() } just Runs
        return cursor
    }

    /** An empty cursor (no rows): no parts, no thread recipients. */
    private fun emptyCursor(): Cursor {
        val cursor = mockk<Cursor>()
        every { cursor.moveToNext() } returns false
        every { cursor.moveToFirst() } returns false
        every { cursor.close() } just Runs
        return cursor
    }

    private fun stubProvider(mmsId: Long, threadId: Long, dateSec: Long) {
        val main = mainCursor(mmsId, threadId, dateSec)
        val addr = addrCursor("+15551234567")
        val parts = emptyCursor()
        val recipients = emptyCursor()
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            when (firstArg<Uri>()) {
                uriByRaw["content://mms"] -> main
                uriByRaw["content://mms/$mmsId/addr"] -> addr
                uriByRaw["content://mms/part"] -> parts
                uriByRaw["content://mms-sms/conversations/$threadId/recipients"] -> recipients
                else -> null
            }
        }
    }

    @Test fun `recent MMS with no parts is dropped and the pass is incomplete`() = runTest {
        val nowSec = System.currentTimeMillis() / 1000L
        stubProvider(mmsId = 7L, threadId = 42L, dateSec = nowSec)
        val pages = ArrayList<List<TelephonyReader.MmsImportRow>>()

        val complete = reader.readMmsBatched(pageSize = 200) { pages += it }

        assertThat(complete).isFalse()
        assertThat(pages.flatten()).isEmpty()
    }

    @Test fun `old MMS with no parts is dropped but the pass is complete`() = runTest {
        val oldSec = System.currentTimeMillis() / 1000L -
            TelephonyReader.ZERO_PART_RECENT_WINDOW_MS / 1000L - 86_400L
        stubProvider(mmsId = 7L, threadId = 42L, dateSec = oldSec)
        val pages = ArrayList<List<TelephonyReader.MmsImportRow>>()

        val complete = reader.readMmsBatched(pageSize = 200) { pages += it }

        assertThat(complete).isTrue()
        assertThat(pages.flatten()).isEmpty()
    }
}
