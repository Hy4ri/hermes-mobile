package com.m57.hermescontrol.ui.kanban.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.kanban.KanbanArcState
import com.m57.hermescontrol.ui.kanban.KanbanRuntimeHelper

@Composable
fun KanbanTaskCard(
    task: KanbanTask,
    onTaskClick: (KanbanTask) -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    defaultAssignee: String? = null,
    onActionClick: ((KanbanTask) -> Unit)? = null,
) {
    val statusColors = LocalHermesStatusColors.current
    val statusTone =
        when (task.status.lowercase()) {
            "ready" -> statusColors.info
            "running" -> statusColors.success
            "blocked" -> statusColors.error
            "review" -> statusColors.warning
            "done" -> statusColors.success
            else -> MaterialTheme.colorScheme.outline
        }

    val arcState = KanbanRuntimeHelper.arcState(task, defaultAssignee)
    val isWontRun = KanbanRuntimeHelper.isWontRun(task, defaultAssignee)
    val elapsed = KanbanRuntimeHelper.formatElapsed(task.startedAt)

    val cardColors =
        if (isSelected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            )
        } else {
            CardDefaults.cardColors()
        }

    Card(
        onClick = { onTaskClick(task) },
        colors = cardColors,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .background(color = statusTone, shape = CircleShape),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = task.status.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusTone,
                    )
                    when {
                        arcState == KanbanArcState.RUNNING -> {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "· ${elapsed ?: "working"}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = statusColors.success,
                            )
                        }

                        arcState == KanbanArcState.STALE -> {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "· STALE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = statusColors.warning,
                            )
                        }

                        isWontRun -> {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "· WON'T RUN",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = statusColors.error,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${task.id.removePrefix("t_").take(6)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onActionClick != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { onActionClick(task) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Task actions",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val summary = task.latestSummary ?: task.body
            if (!summary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val displayAssignee =
                    task.assignee
                        ?: if (arcState == KanbanArcState.QUEUED && !defaultAssignee.isNullOrBlank()) {
                            "$defaultAssignee (Default)"
                        } else {
                            null
                        }
                displayAssignee?.let { assignee ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = assignee,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Priority
                val priority = task.priority ?: 0
                if (priority > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowUpward,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = statusColors.warning,
                        )
                        Text(
                            text = priority.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColors.warning,
                        )
                    }
                }

                // Child progress
                task.progress?.let { prog ->
                    if (prog.total > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "${prog.done}/${prog.total}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Comments
                if (task.commentCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ChatBubbleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = task.commentCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Links
                val links = task.linkCounts?.let { it.parents + it.children } ?: 0
                if (links > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Hub,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = links.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Warnings
                val warnings = task.warnings?.count ?: 0
                if (warnings > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = statusColors.error,
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = warnings.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColors.error,
                        )
                    }
                }
            }
        }
    }
}
