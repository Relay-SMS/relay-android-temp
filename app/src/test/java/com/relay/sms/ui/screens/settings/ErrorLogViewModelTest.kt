/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens.settings

import com.google.common.truth.Truth.assertThat
import com.relay.sms.crashlog.CrashReportFormatter
import com.relay.sms.crashlog.CrashReportStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The Error log ViewModel: copy-all composes every saved report
 * into one text (newest first, title + timestamp per entry), the
 * empty log copies nothing, and the list/select/clear behaviour
 * the screen already relies on is unchanged.
 */
class ErrorLogViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @TempDir
    lateinit var tempDir: File

    private lateinit var store: CrashReportStore

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = CrashReportStore(File(tempDir, "crash-reports"))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ErrorLogViewModel(store, dispatcher)

    /** The screen's locale-aware date + time, as the copied text uses it. */
    private fun formattedDate(timestampMillis: Long): String {
        return DateFormat
            .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
            .format(Date(timestampMillis))
    }

    @Test
    fun `copyAll combines every report newest first with title and timestamp`() {
        store.writeReport("old crash body", 1_000L, "NullPointerException")
        store.writeReport(
            "new stop body",
            3_000L,
            CrashReportFormatter.UNEXPECTED_STOP_TYPE,
        )

        var copied: String? = null
        viewModel().copyAll("App stopped unexpectedly") { copied = it }

        val text = copied
        assertThat(text).isNotNull()
        assertThat(text).contains("old crash body")
        assertThat(text).contains("new stop body")
        assertThat(text!!.indexOf("new stop body")).isLessThan(text.indexOf("old crash body"))
        assertThat(text).contains("NullPointerException")
        assertThat(text).contains("App stopped unexpectedly")
        assertThat(text).doesNotContain(CrashReportFormatter.UNEXPECTED_STOP_TYPE)
        assertThat(text).contains(formattedDate(1_000L))
        assertThat(text).contains(formattedDate(3_000L))
        assertThat(text).contains("---")
    }

    @Test
    fun `copyAll on an empty log invokes no callback`() {
        var called = false
        viewModel().copyAll("App stopped unexpectedly") { called = true }

        assertThat(called).isFalse()
    }

    @Test
    fun `refresh select and clearAll still work`() {
        store.writeReport("first body", 1_000L, "A")
        store.writeReport("second body", 2_000L, "B")
        val vm = viewModel()

        assertThat(vm.reports.value).hasSize(2)
        vm.select(vm.reports.value[0])
        assertThat(vm.selected.value?.content).isEqualTo("second body")
        vm.clearSelection()
        assertThat(vm.selected.value).isNull()
        vm.clearAll()
        assertThat(vm.reports.value).isEmpty()
        assertThat(store.listReports()).isEmpty()
    }
}
