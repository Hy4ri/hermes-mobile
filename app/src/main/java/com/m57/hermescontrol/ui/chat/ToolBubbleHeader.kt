package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.LocalHermesStatusColors

@Composable
internal fun ToolBubbleHeader(
    message: ChatMessage,
    config: ToolDisplayConfig,
    contentColor: Color,
    statusColors: HermesStatusColors,
    displayName: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Status icon or spinner
        if (message.isToolRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else {
            val icon =
                when (message.toolStatus) {
                    ToolStatus.COMPLETED -> Icons.Filled.CheckCircle
                    ToolStatus.FAILED -> Icons.Filled.Error
                    else -> Icons.Filled.Build
                }
            val tint =
                when (message.toolStatus) {
                    ToolStatus.COMPLETED -> statusColors.success
                    ToolStatus.FAILED -> statusColors.error
                    else -> contentColor.copy(alpha = 0.6f)
                }
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        }

        Text(
            text = displayName ?: message.toolName ?: stringResource(R.string.chat_tool_fallback),
            style =
                MaterialTheme.typography.labelMedium.copy(
                    color = contentColor,
                    fontFamily = FontFamily.Monospace,
                ),
        )
    }
}

/**
 * Security risk chip for [tool.output_risk] events.
 *
 * Shows a compact ⚠ badge when the backend flagged tool output as risky.
 * Renders in the tool card between the header row and the summary line.
 */
@Composable
internal fun SecurityRiskChip(
    riskData: ToolOutputRiskData,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val statusColors = LocalHermesStatusColors.current
    val (chipColor, label) =
        when {
            riskData.risk == "high" -> statusColors.error to "Risky output"
            riskData.risk == "medium" -> statusColors.warning to "Caution"
            else -> statusColors.warning to "Redacted"
        }

    Row(
        modifier =
            modifier
                .padding(top = 4.dp, start = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = chipColor,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                ),
            color = chipColor,
        )
        if (riskData.redacted && (riskData.risk == "high" || riskData.risk == "medium")) {
            Text(
                text = stringResource(R.string.tool_redacted),
                style = MaterialTheme.typography.labelSmall,
                color = chipColor.copy(alpha = 0.7f),
            )
        }
    }
}
