package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.SubagentIndicator
import com.m57.hermescontrol.ui.chat.TodoItem

/**
 * Compact, glanceable progress strip shown above the chat timeline while work is
 * active. Distinct from [TodoTaskCard]: the card is the permanent timeline record,
 * this chip is the temporary "what is happening right now" surface. It is bound to
 * the same hydrated [todos] / [indicators] state the card uses (never the old
 * broken `state.todos` gate from #811) so a resumed session rebuilds it for free.
 *
 * Visibility is driven by [visible]; the caller keeps it true only while at least
 * one todo is incomplete or a subagent is running, and hides it automatically when
 * all work completes or is cancelled.
 */
@Composable
internal fun TaskProgressChip(
    visible: Boolean,
    todos: List<TodoItem>,
    indicators: List<SubagentIndicator>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val completed = todos.count { it.isCompleted }
        val inProgress = todos.firstOrNull { it.isInProgress }
        val activeAgents = indicators.count { it.isRunning }

        val label =
            buildString {
                append(
                    stringResource(
                        R.string.task_progress_count,
                        completed,
                        todos.size,
                    ),
                )
                inProgress?.content?.let {
                    append(" · ")
                    append(it)
                }
                if (activeAgents > 0) {
                    append(" · ")
                    append(stringResource(R.string.task_progress_agents, activeAgents))
                }
            }

        Surface(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onClick)
                    .testTag("task_progress_chip"),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ElectricBolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = stringResource(R.string.task_progress_open_details),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
