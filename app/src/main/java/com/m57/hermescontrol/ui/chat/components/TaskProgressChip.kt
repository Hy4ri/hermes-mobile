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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.SubagentIndicator
import com.m57.hermescontrol.ui.chat.TodoItem

/**
 * Compact, glanceable progress strip shown above the chat timeline while work is
 * active. This replaces the old inline [TodoTaskCard] (removed per #942 follow-up):
 * instead of a duplicate card in the message list, the only persistent record is the
 * tool message itself, and this chip is the temporary "what is happening right now"
 * surface. Bound to the same hydrated [todos] / [indicators] state, so a resumed
 * session rebuilds it for free.
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
        val display = computeChipDisplay(todos, indicators)
        val label =
            buildString {
                if (display.hasTodos) {
                    append(stringResource(R.string.task_progress_count, display.currentTaskNumber, display.total))
                    display.currentTaskContent?.let {
                        append(" · ")
                        append(it)
                    }
                } else if (display.activeAgents > 0) {
                    append(
                        pluralStringResource(
                            R.plurals.task_progress_agent_running,
                            display.activeAgents,
                            display.activeAgents,
                        ),
                    )
                }
                if (display.hasTodos && display.activeAgents > 0) {
                    append(" · ")
                    append(
                        pluralStringResource(
                            R.plurals.task_progress_agents,
                            display.activeAgents,
                            display.activeAgents,
                        ),
                    )
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
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
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
                Spacer(modifier = Modifier.width(2.dp))
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

/**
 * Pure, testable projection of the progress chip's data. The composable turns
 * this into a localized label; keeping the math here lets unit tests cover the
 * progress/resume/visibility rules without a Compose host.
 */
internal data class ChipDisplay(
    val hasTodos: Boolean,
    val currentTaskNumber: Int,
    val total: Int,
    val currentTaskContent: String?,
    val activeAgents: Int,
)

internal fun computeChipDisplay(
    todos: List<TodoItem>,
    indicators: List<SubagentIndicator>,
): ChipDisplay {
    // Select the task the chip should surface: prefer the in_progress one, then
    // fall back to the first unfinished/non-cancelled todo. Both the number and
    // the content are derived from THIS same selected item so they can never
    // disagree (e.g. a pending task 1 + in_progress task 2 must read "2/N · task2",
    // not "1/N · task2").
    val selectedIndex =
        todos.indexOfFirst { it.isInProgress }.let { idx ->
            if (idx >= 0) idx else todos.indexOfFirst { !it.isCompleted && !it.isCancelled }
        }
    val currentTaskNumber = if (selectedIndex >= 0) selectedIndex + 1 else todos.count { it.isCompleted }
    val selected = if (selectedIndex >= 0) todos[selectedIndex] else null
    val activeAgents = indicators.count { it.isRunning }
    return ChipDisplay(
        hasTodos = todos.isNotEmpty(),
        currentTaskNumber = currentTaskNumber,
        total = todos.size,
        currentTaskContent = selected?.content,
        activeAgents = activeAgents,
    )
}

/**
 * Whether the chip should be visible: there is at least one unfinished,
 * non-cancelled todo, or a subagent is still running. Cancelled/completed-only
 * state hides it.
 */
internal fun shouldShowProgressChip(
    todos: List<TodoItem>,
    indicators: List<SubagentIndicator>,
): Boolean = todos.any { !it.isCompleted && !it.isCancelled } || indicators.any { it.isRunning }
