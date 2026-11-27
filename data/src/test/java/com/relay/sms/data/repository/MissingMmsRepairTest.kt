/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.data.repository

import com.google.common.truth.Truth.assertThat
import com.relay.sms.data.local.db.dao.MessageDao
import com.relay.sms.data.sms.TelephonyReader
import com.relay.sms.domain.model.MessageDirection
import com.relay.sms.domain.model.MessageStatus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Round 60 — locks down [MissingMmsRepair]: MMS present in the
 * system provider but missing from Room are re-imported through
 * the importer's own path; rows Room already mirrors are left
 * alone; a failed provider read reports incomplete so the pass
 * re-runs at the next cold start.
 */
class MissingMmsRepairTest {

    private val reader = mockk<TelephonyReader>()
    private val messageDao = mockk<MessageDao>()
    private val mirror = mockk<ConversationMirror>()

    private fun repair() = MissingMmsRepair(
        telephonyReader = reader,
        messageDao = messageDao,
        conversationMirror = mirror,
        io = Dispatchers.Unconfined,
    )

    private fun row(telephonyId: Long) = TelephonyReader.MmsImportRow(
        telephonyId = telephonyId,
        threadId = 42L,
        dateMs = 1_700_000_000_000L,
        read = true,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.RECEIVED,
        address = "+15551234567",
        subId = null,
        textBody = "hello",
        attachments = emptyList(),
    )

    private fun stubReader(rows: List<TelephonyReader.MmsImportRow>, complete: Boolean = true) {
        coEvery { reader.readMmsBatched(any(), any()) } coAnswers {
            val onPage = secondArg<suspend (List<TelephonyReader.MmsImportRow>) -> Unit>()
            if (rows.isNotEmpty()) onPage(rows)
            complete
        }
    }

    @Test fun `imports only the rows Room does not mirror`() = runTest {
        coEvery { messageDao.listMirroredTelephonyUris() } returns listOf("content://mms/9")
        stubReader(listOf(row(7L), row(9L)))
        val imported = slot<List<TelephonyReader.MmsImportRow>>()
        coEvery { mirror.bulkImportMmsFromTelephony(capture(imported)) } just Runs

        val result = repair().repair()

        assertThat(result).isEqualTo(MissingMmsRepair.Result(repaired = 1, complete = true))
        assertThat(imported.captured.map { it.telephonyId }).containsExactly(7L)
    }

    @Test fun `imports nothing when Room already mirrors everything`() = runTest {
        coEvery { messageDao.listMirroredTelephonyUris() } returns
            listOf("content://mms/7", "content://mms/9")
        stubReader(listOf(row(7L), row(9L)))

        val result = repair().repair()

        assertThat(result).isEqualTo(MissingMmsRepair.Result(repaired = 0, complete = true))
        coVerify(exactly = 0) { mirror.bulkImportMmsFromTelephony(any()) }
    }

    @Test fun `failed provider read reports incomplete`() = runTest {
        coEvery { messageDao.listMirroredTelephonyUris() } returns emptyList()
        stubReader(listOf(row(7L)), complete = false)
        coEvery { mirror.bulkImportMmsFromTelephony(any()) } just Runs

        val result = repair().repair()

        assertThat(result.complete).isFalse()
        assertThat(result.repaired).isEqualTo(1)
    }

    @Test fun `failed Room read reports incomplete without importing`() = runTest {
        coEvery { messageDao.listMirroredTelephonyUris() } throws RuntimeException("db locked")

        val result = repair().repair()

        assertThat(result).isEqualTo(MissingMmsRepair.Result(repaired = 0, complete = false))
        coVerify(exactly = 0) { mirror.bulkImportMmsFromTelephony(any()) }
        coVerify(exactly = 0) { reader.readMmsBatched(any(), any()) }
    }
}
