package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.ContextBreakdown
import kotlin.math.min

/**
 * Detail sheet shown when the context meter chip is tapped. Lists the session's
 * token breakdown (input / output / cache read / cache write / reasoning) and
 * the message count, plus a prominent used / full bar.
 *
 * `fullTokens` is the model context window (denominator); `breakdown` carries
 * the per-category counts from `GET /api/sessions/{id}`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextDetailSheet(
    breakdown: ContextBreakdown,
    fullTokens: Long?,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        val full = fullTokens ?: 0L
        val used = breakdown.inputTokens
        val fraction = if (full > 0L) min(1f, used.toFloat() / full.toFloat()) else 0f
        val pct = (fraction * 100).toInt()
        val statusColors = LocalHermesStatusColors.current
        val barColor =
            when {
                pct >= 90 -> statusColors.error
                pct >= 70 -> statusColors.warning
                else -> MaterialTheme.colorScheme.primary
            }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Context window",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // Big used / full header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = formatTokens(used),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = barColor,
                )
                Text(
                    text = "of ${formatTokens(full)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Progress bar
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                        ),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(8.dp)
                            .background(color = barColor, shape = RoundedCornerShape(4.dp)),
                )
            }
            Text(
                text = "$pct% of context window used",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Breakdown rows
            ContextRow(label = "Prompt (input)", value = breakdown.inputTokens)
            ContextRow(label = "Completion (output)", value = breakdown.outputTokens)
            ContextRow(label = "Cache read", value = breakdown.cacheReadTokens)
            ContextRow(label = "Cache write", value = breakdown.cacheWriteTokens)
            ContextRow(label = "Reasoning", value = breakdown.reasoningTokens)
            ContextRow(label = "Messages", value = breakdown.messageCount.toLong(), isCount = true)

            Text(
                text = "Prompt tokens reflect total context used by this session so far.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun ContextRow(
    label: String,
    value: Long,
    isCount: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (isCount) value.toString() else formatTokens(value),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
