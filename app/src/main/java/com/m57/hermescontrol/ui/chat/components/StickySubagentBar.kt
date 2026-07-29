package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.ui.chat.SubagentIndicator
import com.m57.hermescontrol.ui.chat.TodoItem

/**
 * Compact sticky progress indicator displayed below the top app bar
 * when background subagents or agent todos are in progress.
 */
@Composable
fun StickySubagentBar(
    indicators: List<SubagentIndicator> = emptyList(),
    todos: List<TodoItem> = emptyList(),
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeSubagents = indicators.count { it.isRunning }
    val activeTodos = todos.count { it.isInProgress }
    val isVisible = activeSubagents > 0 || activeTodos > 0

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onClick)
                    .testTag("sticky_subagent_bar"),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.ElectricBolt,
                        contentDescription = "Active Tasks",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    val labelText =
                        when {
                            activeSubagents > 0 && activeTodos > 0 -> {
                                "$activeSubagents Subagents · $activeTodos Task Active"
                            }
                            activeSubagents > 0 -> {
                                val activeSub = indicators.firstOrNull { it.isRunning }
                                val goalText = activeSub?.goal?.takeIf { it.isNotBlank() }
                                val countStr =
                                    if (activeSubagents == 1) {
                                        "1 Subagent Active"
                                    } else {
                                        "$activeSubagents Subagents Active"
                                    }
                                if (goalText != null) "$countStr · $goalText" else countStr
                            }
                            else -> {
                                val activeTodo = todos.firstOrNull { it.isInProgress }
                                val todoText = activeTodo?.content?.takeIf { it.isNotBlank() }
                                if (todoText != null) "Task In Progress: $todoText" else "1 Task In Progress"
                            }
                        }

                    Text(
                        text = labelText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Inspect",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Inspect",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
