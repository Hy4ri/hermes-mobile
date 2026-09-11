package com.m57.hermescontrol.ui.sessions.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.SessionTreeItem
import com.m57.hermescontrol.theme.Spacing
import com.m57.hermescontrol.ui.sessions.AutomationSessionGroup

internal fun LazyListScope.automationGroups(
    groups: List<AutomationSessionGroup>,
    expandedGroups: Set<String>,
    sessionItemsById: Map<String, SessionTreeItem>,
    spacing: Spacing,
    onToggleGroup: (String) -> Unit,
    renderSessionCard: @Composable (SessionTreeItem) -> Unit,
) {
    groups.forEach { group ->
        item(key = "automation-group:${group.key}") {
            val expanded = group.key in expandedGroups
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onToggleGroup(group.key) }
                        .padding(horizontal = spacing.sm, vertical = spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector =
                        if (expanded) {
                            Icons.Filled.KeyboardArrowDown
                        } else {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        },
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(spacing.sm))
                Column {
                    Text(
                        text =
                            group.title
                                ?: group.jobId?.let {
                                    stringResource(
                                        R.string.sessions_section_automation_job,
                                        it,
                                    )
                                } ?: stringResource(
                                R.string.sessions_section_automation_unknown,
                            ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.sessions_section_automation_runs,
                                group.sessions.size,
                            ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (group.key in expandedGroups) {
            items(group.sessions, key = { it.id }) { session ->
                sessionItemsById[session.id]?.let { renderSessionCard(it) }
            }
        }
    }
}
