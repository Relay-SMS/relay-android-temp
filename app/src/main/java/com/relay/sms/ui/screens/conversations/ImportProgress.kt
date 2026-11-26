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
 *
 * Round 56 — the vertical gaps are Kristin's Figma numbers (px = dp
 * 1:1), measured glyph edge to glyph edge, so each spacer is the
 * visual gap MINUS the font-padding insets of the two texts it
 * separates. Inset model (Compose's default centred line-height
 * style: the font's natural line box, ascent+descent, sits centred
 * in the explicit lineHeight box): top inset = (L-N)/2 + (A-C)*F,
 * bottom inset = (L-N)/2 when the line has descenders, (L-N)/2 +
 * D*F when it does not (lowest glyph then sits on the baseline).
 * Inter (bundled, both weights identical): A=0.969em, D=0.241em,
 * C=0.729em, N=1.210em.
 * - Title ConvoName 17/22: N=20.57, top inset 4.82, bottom 0.72.
 * - Desc/count Preview 15/20: N=18.15, top inset 4.54, bottom 0.93
 *   (descenders); the "N of M" count has no descenders, bottom 4.54.
 * - Paragraph 13/20: N=15.73, top inset 5.27.
 * Gaps: title->desc 12 = 0.72 + 6.75 + 4.54; desc->count 16 = 0.93 +
 * 10.5 + 4.54; count->bar 23 = 4.54 + 18.5; bar->paragraph 23 = 0 +
 * 17.75 + 5.27 (that spacer lives in [ImportingPlaceholder]).
 * Spacers are rounded to the nearest 0.25dp (<=0.07px off target).
 * The spinner branch's count ("N messages imported…") HAS
 * descenders, so its count->spinner spacer is 22dp, not 18.5dp.
 * Pixel-exactness is confirmed on device; this math is the local
 * proxy.
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
            // Round 56 — 12px visual title->desc = 0.72 title bottom
            // inset + 6.75dp padding + 4.54 desc top inset (see KDoc).
            modifier = Modifier.fillMaxWidth().padding(top = 6.75.dp),
        )
        // Round 56 — 16px visual desc->count = 0.93 desc bottom inset
        // + 10.5dp + 4.54 count top inset (see KDoc).
        Spacer(modifier = Modifier.height(10.5.dp))
        if (total != null && total > 0) {
            Text(
                text = stringResource(R.string.client_sync_count, imported, total),
                style = RelayText.Preview,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // Round 56 — 23px visual count->bar = 4.54 count bottom
            // inset ("N of M" has no descenders) + 18.5dp; the bar's
            // top edge is its layout edge (see KDoc).
            Spacer(modifier = Modifier.height(18.5.dp))
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
                // Round 56 — 23px visual count->spinner = 0.93 count
                // bottom inset ("N messages imported…" HAS descenders,
                // unlike "N of M") + 22dp; the spinner's top edge is
                // its layout edge (see KDoc).
                Spacer(modifier = Modifier.height(22.dp))
            }
            CircularProgressIndicator(
                color = brandBlue(),
            )
        }
    }
}
