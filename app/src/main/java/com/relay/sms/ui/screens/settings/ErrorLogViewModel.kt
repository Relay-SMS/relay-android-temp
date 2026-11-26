/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.sms.crashlog.CrashReportFormatter
import com.relay.sms.crashlog.CrashReportStore
import com.relay.sms.crashlog.CrashReportSummary
import com.relay.sms.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Error log screen (Settings → Advanced) — the saved crash reports.
 *
 * Reads are small file scans on the IO dispatcher; the store never
 * throws, so there is no error state to surface — a failed read
 * just shows an empty list. The selected report's full text is
 * loaded on tap and held until the user goes back to the list.
 */
@HiltViewModel
class ErrorLogViewModel @Inject constructor(
    private val store: CrashReportStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /** One report opened for reading, with its full text. */
    data class SelectedReport(val summary: CrashReportSummary, val content: String)

    private val _reports = MutableStateFlow<List<CrashReportSummary>>(emptyList())
    val reports: StateFlow<List<CrashReportSummary>> = _reports.asStateFlow()

    private val _selected = MutableStateFlow<SelectedReport?>(null)
    val selected: StateFlow<SelectedReport?> = _selected.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads the report list (e.g. after returning to the screen). */
    fun refresh() {
        viewModelScope.launch(ioDispatcher) {
            _reports.value = store.listReports()
        }
    }

    /** Loads one report's full text for the detail view. */
    fun select(summary: CrashReportSummary) {
        viewModelScope.launch(ioDispatcher) {
            store.readReport(summary.id)?.let { content ->
                _selected.value = SelectedReport(summary, content)
            }
        }
    }

    /** Goes back from the detail view to the list. */
    fun clearSelection() {
        _selected.value = null
    }

    /** Deletes every saved report and returns to the (empty) list. */
    fun clearAll() {
        viewModelScope.launch(ioDispatcher) {
            store.clearAll()
            _selected.value = null
            _reports.value = emptyList()
        }
    }

    /**
     * Composes every saved report into one text — newest first,
     * each headed by its title and timestamp — and delivers it to
     * [onText] for the screen to copy. [unexpectedStopTitle] is
     * the localized title for an "unexpected stop" entry (the
     * screen owns the string resource; the ViewModel has no
     * Resources access). When the log is empty the callback is
     * never invoked, so the screen copies nothing and shows no
     * confirmation. Never throws: an entry whose text cannot be
     * read is skipped, and a failed list read yields an empty
     * text (no callback).
     */
    fun copyAll(unexpectedStopTitle: String, onText: (String) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            val text = runCatching { composeAllReports(unexpectedStopTitle) }.getOrNull()
            if (!text.isNullOrEmpty()) onText(text)
        }
    }

    /** Every readable report, newest first, separated by a rule. */
    private fun composeAllReports(unexpectedStopTitle: String): String {
        return buildString {
            var first = true
            for (summary in store.listReports()) {
                val content = store.readReport(summary.id) ?: continue
                if (!first) append("\n\n---\n\n")
                first = false
                val title = if (summary.crashType == CrashReportFormatter.UNEXPECTED_STOP_TYPE) {
                    unexpectedStopTitle
                } else {
                    summary.crashType
                }
                appendLine(title)
                appendLine(formatReportDate(summary.timestampMillis))
                appendLine()
                append(content.trimEnd())
            }
        }
    }

    /**
     * Locale-aware date + time, as the list rows show it.
     * Duplicated from the screen's formatter on purpose: the
     * screen's helper is a private UI-file function, and sharing
     * it would couple this plain-JVM-tested ViewModel to the UI
     * file (or add a new file) for a stable 3-line java.text
     * call.
     */
    private fun formatReportDate(timestampMillis: Long): String {
        return DateFormat
            .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
            .format(Date(timestampMillis))
    }
}
