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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.ui.chat.SubagentIndicator
import com.m57.hermescontrol.ui.chat.SubagentLogLine
import com.m57.hermescontrol.ui.chat.TodoItem

/**
 * Dedicated live task delegation & plan card rendered inline in the chat stream.
 *
 * Displays individual subagent goals, live transcript logs, and agent todo/plan items,
 * and smoothly transitions to a consolidated summary upon completion.
 */
@Composable
fun DelegationTaskCard(
    indicators: List<SubagentIndicator> = emptyList(),
    todos: List<TodoItem> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (indicators.isEmpty() && todos.isEmpty()) return

    val subagentsCount = indicators.size
    val subagentsDone = indicators.count { it.isComplete }
    val subagentsFailed = indicators.count { it.isFailed }

    val todosCount = todos.size
    val todosDone = todos.count { it.isCompleted }
    val todosFailed = todos.count { it.isCancelled }

    val totalCount = subagentsCount + todosCount
    val completedCount = subagentsDone + todosDone
    val failedCount = subagentsFailed + todosFailed
    val allCompleted = completedCount + failedCount >= totalCount && totalCount > 0

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
                        if (indicators.isNotEmpty() && todos.isNotEmpty()) {
                            if (allCompleted) {
                                "Tasks & Plan Complete ($completedCount/$totalCount)"
                            } else {
                                "Tasks & Plan ($completedCount/$totalCount completed)"
                            }
                        } else if (indicators.isNotEmpty()) {
                            if (allCompleted) {
                                "Task Delegation Complete ($completedCount/$totalCount)"
                            } else {
                                "Task Delegation Running ($completedCount/$totalCount completed)"
                            }
                        } else {
                            if (allCompleted) {
                                "Agent Plan Complete ($completedCount/$totalCount)"
                            } else {
                                "Agent Plan ($completedCount/$totalCount completed)"
                            }
                        }

                    Text(
                        text = headerTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    val subtitleText =
                        if (allCompleted) {
                            "All tasks finished"
                        } else {
                            val activeSubagent = indicators.firstOrNull { it.isRunning }
                            val activeTodo = todos.firstOrNull { it.isInProgress }
                            when {
                                activeSubagent != null -> "Subagent: ${activeSubagent.goal}"
                                activeTodo != null -> "In Progress: ${activeTodo.content}"
                                else -> "Processing task list..."
                            }
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

            // Expanded Body: Todos & Subagents
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Todos Section
                    if (todos.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "AGENT PLAN",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp,
                                )
                            }
                            todos.forEach { todo ->
                                TodoItemRow(todo = todo)
                            }
                        }
                    }

                    // Subagents Section
                    if (indicators.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (todos.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "SUBAGENTS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                            }
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
    }
}

@Composable
private fun TodoItemRow(todo: TodoItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                todo.isCompleted -> {
                    Text(text = "✅", fontSize = 14.sp)
                }
                todo.isInProgress -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                todo.isCancelled -> {
                    Text(text = "❌", fontSize = 14.sp)
                }
                else -> {
                    Text(text = "⭕", fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = todo.content,
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        textDecoration = if (todo.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                    ),
                fontWeight = if (todo.isInProgress) FontWeight.Bold else FontWeight.Normal,
                color =
                    when {
                        todo.isCompleted -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        todo.isInProgress -> MaterialTheme.colorScheme.onSurface
                        todo.isCancelled -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (todo.isInProgress) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "IN PROGRESS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
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
