package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.ui.chat.SubagentIndicator
import com.m57.hermescontrol.ui.chat.SubagentLogLine

/**
 * Dedicated live task delegation card rendered inline in the chat stream.
 *
 * Displays individual subagent goals, current status, live transcript logs,
 * and smoothly transitions to a consolidated summary upon completion.
 */
@Composable
fun DelegationTaskCard(
    indicators: List<SubagentIndicator>,
    modifier: Modifier = Modifier,
) {
    if (indicators.isEmpty()) return

    val totalCount = indicators.firstOrNull()?.taskCount ?: indicators.size
    val completedCount = indicators.count { it.isComplete }
    val failedCount = indicators.count { it.isFailed }
    val allCompleted = completedCount + failedCount >= indicators.size && indicators.isNotEmpty()
    var isExpanded by remember { mutableStateOf(true) }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .animateContentSize()
                .testTag("delegation_task_card"),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (allCompleted) {
                        if (failedCount > 0) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        }
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
            ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            // Header Row
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (allCompleted) {
                    if (failedCount > 0) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Failed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Complete",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    val headerTitle =
                        if (allCompleted) {
                            if (failedCount > 0) {
                                "Task Delegation Failed ($failedCount/$totalCount failed)"
                            } else {
                                "Task Delegation Complete ($totalCount/$totalCount)"
                            }
                        } else {
                            "Task Delegation Running ($completedCount/$totalCount completed)"
                        }
                    Text(
                        text = headerTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    val subtitleText =
                        if (allCompleted) {
                            "All subagent tasks finished"
                        } else {
                            val activeSubagent = indicators.firstOrNull { it.isRunning }
                            activeSubagent?.goal?.let { "Active: $it" } ?: "Processing subagent tasks..."
                        }

                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Expanded Body: Individual Subagents
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    indicators.forEachIndexed { index, indicator ->
                        SubagentItemRow(
                            indicator = indicator,
                            taskNumber = indicator.taskIndex ?: (index + 1),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubagentItemRow(
    indicator: SubagentIndicator,
    taskNumber: Int,
) {
    var showLogs by remember { mutableStateOf(indicator.isRunning) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showLogs = !showLogs },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status Icon
                if (indicator.isComplete) {
                    Text(text = "✅", fontSize = 14.sp)
                } else if (indicator.isFailed) {
                    Text(text = "❌", fontSize = 14.sp)
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Goal text
                val goalDisplay = indicator.goal?.takeIf { it.isNotBlank() } ?: "Subagent task #$taskNumber"
                Text(
                    text = "#$taskNumber: $goalDisplay",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (indicator.logs.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Transcript Logs",
                        tint =
                            if (showLogs) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // Summary text if completed
            if (!indicator.summary.isNullOrBlank()) {
                Text(
                    text = indicator.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 22.dp, top = 4.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Live Transcript Logs Dropdown
            AnimatedVisibility(
                visible = showLogs && indicator.logs.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 22.dp, top = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "LIVE TRANSCRIPT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 9.sp,
                    )
                    indicator.logs.takeLast(8).forEach { logLine ->
                        LogLineItem(logLine = logLine)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLineItem(logLine: SubagentLogLine) {
    val color =
        when {
            logLine.isError -> MaterialTheme.colorScheme.error
            logLine.isSummary -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Text(
        text = "› ${logLine.text}",
        style =
            MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            ),
        color = color,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}
