package com.m57.hermescontrol.ui.common

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icon-only top-bar action with a single localized [label] that is both its
 * long-press/hover tooltip and its accessibility description (#1651).
 *
 * Use this instead of a raw [IconButton] for top-bar actions so a sighted
 * user can long-press any icon to see what it does, while TalkBack keeps
 * announcing the same label — one string, two affordances, no drift.
 * Optional [tint] defaults to [LocalContentColor.current] (e.g. error tint for destructive actions).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesIconButton(
    imageVector: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = label,
                tint = tint,
            )
        }
    }
}
