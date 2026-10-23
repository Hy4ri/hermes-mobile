package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.chat.ToolStatus
import com.m57.hermescontrol.ui.chat.isToolRunning

/** A flat disclosure row; tool details keep their existing independent ToolBubble contract. */
@Composable
internal fun FullBleedToolGroupHeader(
    group: AgentEntry.ToolGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val statusColors = LocalHermesStatusColors.current
    val running = group.entries.count { it.message.isToolRunning }
    val failed = group.entries.count { it.message.toolStatus == ToolStatus.FAILED }
    val completed = group.entries.count { it.message.toolStatus == ToolStatus.COMPLETED }
    val disclosure =
        stringResource(if (expanded) R.string.chat_tool_group_collapse else R.string.chat_tool_group_expand)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md)
                .sizeIn(minWidth = spacing.xl * 2, minHeight = spacing.xl * 2)
                .testTag("fullbleed_tool_group_${group.id}")
                .semantics { stateDescription = disclosure }
                .clickable(role = Role.Button, onClickLabel = disclosure, onClick = onToggle)
                .padding(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Filled.Build,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(spacing.lg),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(
                text = pluralStringResource(R.plurals.chat_tool_group_calls, group.entries.size, group.entries.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val statuses =
                buildList {
                    if (running > 0) add(pluralStringResource(R.plurals.chat_tool_group_running, running, running))
                    if (failed > 0) add(pluralStringResource(R.plurals.chat_tool_group_failed, failed, failed))
                    if (completed >
                        0
                    ) {
                        add(pluralStringResource(R.plurals.chat_tool_group_completed, completed, completed))
                    }
                }
            if (statuses.isNotEmpty()) {
                Text(
                    text = statuses.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        when {
                            failed > 0 -> statusColors.error
                            running > 0 -> statusColors.info
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            Text(
                text = disclosure,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(spacing.lg),
        )
    }
}
