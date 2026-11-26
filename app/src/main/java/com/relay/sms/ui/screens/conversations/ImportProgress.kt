/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.screens.conversations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.relay.sms.R
import com.relay.sms.data.sync.TelephonySyncManager
import com.relay.sms.ui.theme.RelayText
import com.relay.sms.ui.theme.brandBlue

/**
 * The first-run import progress: the phase desc, the count line plus
 * the bar, in the same look as the client-mode sync bar
 * ([ClientSyncProgressRow] — count line, 8 dp spacer,
 * [LinearProgressIndicator]).
 *
 * Lives in its own file because `ConversationsScreen` sits at the
 * Detekt complexity ceiling: the count-line and determinate /
 * spinner branches live here, and [ImportingPlaceholder] stays
 * a dumb render that just passes the numbers through.
 *
 * - Round 55 — the layout follows Kristin's spec: the title above
 *   already says "Importing:", so this starts with the per-phase
 *   desc ("Photos, videos and voice messages" / "Text messages"),
 *   then the progress. Total known (`total > 0`): "N of M" (it
 *   reuses `client_sync_count`) + a determinate bar, in BOTH
 *   phases — the MMS phase counts its rows up front just like SMS.
 * - Total unknown: a spinner instead of a bar stuck at 0, so the
 *   screen never looks dead while the phase is still working. When
 *   some rows already landed, the "N messages imported…" line
 *   still shows above the spinner.
 */
@Composable
fun ImportProgressContent(
    imported: Int,
    total: Int?,
    phase: TelephonySyncManager.ImportPhase = TelephonySyncManager.ImportPhase.SMS,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                when (phase) {
                    TelephonySyncManager.ImportPhase.MMS ->
                        R.string.conversations_importing_phase_mms
                    TelephonySyncManager.ImportPhase.SMS ->
                        R.string.conversations_importing_phase_sms
                },
            ),
            style = RelayText.Preview,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (total != null && total > 0) {
            Text(
                text = stringResource(R.string.client_sync_count, imported, total),
                style = RelayText.Preview,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            val fraction = (imported.toFloat() / total).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = brandBlue(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        } else {
            if (imported > 0) {
                Text(
                    text = stringResource(R.string.conversations_importing_count, imported),
                    style = RelayText.Preview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            CircularProgressIndicator(
                color = brandBlue(),
            )
        }
    }
}
