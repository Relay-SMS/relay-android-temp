/*
 * Copyright 2026 Relay Messaging. All rights reserved.
 *
 * This file is proprietary to Relay Messaging and is not licensed under the
 * Apache License 2.0. See LICENSE-RELAY for the terms.
 */

package com.relay.sms.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Round 55 — one smart-action pill under the bubble text (Call / Send
 * email / Get directions / Add to calendar / Track package): the OTP pill's
 * style exactly — a transparent capsule with a 1dp on-surface border,
 * 15dp horizontal / 9dp vertical padding, body text in the on-surface
 * colour — with the icon on the LEFT and the label on the right (the
 * OTP pill carries its glyph on the right; the smart-action pills put
 * it first, as Kristin asked). The pill carries its own `clickable`
 * like the OTP pill: it sits inside the bubble's `combinedClickable`
 * and the inner clickable wins, so tapping the pill acts instead of
 * opening the menu. [labelArg] fills a format label (the tracking
 * pill's "Track %1$s package" takes the carrier's display name);
 * `null` renders [labelRes] as-is.
 */
@Composable
fun SmartActionPill(
    icon: ImageVector,
    @StringRes labelRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelArg: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    // Hoisted: stringResource is @Composable and cannot run inside .semantics {}.
    val label = if (labelArg != null) stringResource(labelRes, labelArg) else stringResource(labelRes)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .border(1.dp, cs.onSurface.copy(alpha = 0.6f), CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = cs.onSurface,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
            maxLines = 1,
        )
    }
}
