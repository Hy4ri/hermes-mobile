package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Security risk chip for [tool.output_risk] events.
 *
 * Shows a compact ⚠ badge when the backend flagged tool output as risky.
 * Shared by [TodoTaskCard] and the inline tool renderer.
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
        Text(
            text = "⚠",
            style = MaterialTheme.typography.labelSmall,
            color = chipColor,
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

/**
 * Format an epoch-millis timestamp as a short chat time (HH:mm / h:mm a).
 * Shared by the inline tool renderer and other chat surfaces.
 */
internal fun formatChatTimestamp(
    timestamp: Long,
    is24Hour: Boolean,
): String {
    val pattern = if (is24Hour) "HH:mm" else "h:mm a"
    return DateTimeFormatter
        .ofPattern(pattern)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))
}
