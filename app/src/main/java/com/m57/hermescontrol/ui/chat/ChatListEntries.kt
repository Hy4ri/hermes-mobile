package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every [TOOL_CALL_DIVIDER_INTERVAL]th tool call within a single turn renders a
 * subtle divider between the tool bubble and the next bubble, labeled with the
 * per-turn call count against the real per-turn budget (e.g. `5/90`) so the
 * user can see how much of the turn's tool budget has been burned (issue #767).
 *
 * The divider is pure derived state: it is computed from the in-memory message
 * list at render time and is never persisted, so scrolling back through old
 * history shows the same milestones the live stream did.
 */
const val TOOL_CALL_DIVIDER_INTERVAL = 5

/**
 * Returns the message indices that complete a tool-call milestone — a [MessageRole.TOOL]
 * message whose per-turn tool-call count is a multiple of [TOOL_CALL_DIVIDER_INTERVAL] —
 * mapped to the per-turn count at that point.
 *
 * The counter resets at each [MessageRole.USER] message (a new turn), so
 * milestones read `5, 10, 15...` within a turn and restart at `5` on the next.
 */
fun toolCallMilestones(messages: List<ChatMessage>): Map<Int, Int> {
    val milestones = mutableMapOf<Int, Int>()
    var toolCount = 0
    messages.forEachIndexed { index, message ->
        when (message.role) {
            MessageRole.USER -> toolCount = 0
            MessageRole.TOOL -> {
                toolCount += 1
                if (toolCount % TOOL_CALL_DIVIDER_INTERVAL == 0) {
                    milestones[index] = toolCount
                }
            }
            else -> Unit
        }
    }
    return milestones
}

/**
 * Divider label: `count/maxPerTurn` when the real budget is known (fetched from
 * the backend config), bare `count` when it isn't — never a hardcoded default.
 */
fun toolCallDividerLabel(
    count: Int,
    maxPerTurn: Int?,
): String =
    if (maxPerTurn != null && maxPerTurn > 0) {
        "$count/$maxPerTurn"
    } else {
        count.toString()
    }

/**
 * Subtle "beat counter" divider: a hairline, the per-turn count vs budget
 * (e.g. `5/90`), and a hairline. Rendered between bubbles after every 5th
 * tool call of the current turn.
 *
 * @param maxPerTurn the real per-turn tool-call budget (agent.max_turns from
 * the backend config), or null when it could not be fetched — the label then
 * degrades to the bare count.
 */
@Composable
fun ToolCallDivider(
    count: Int,
    maxPerTurn: Int? = null,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 0.5.dp,
            color = lineColor,
        )
        Text(
            text = toolCallDividerLabel(count, maxPerTurn),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = labelColor,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 0.5.dp,
            color = lineColor,
        )
    }
}
