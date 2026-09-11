package com.m57.hermescontrol.ui.system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.Spacing
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.system.SystemUiState
import com.m57.hermescontrol.ui.system.SystemViewModel

fun LazyListScope.actionLogSection(
    state: SystemUiState,
    spacing: Spacing,
    viewModel: SystemViewModel,
) {
    item {
        SectionHeader(
            title = stringResource(R.string.system_action_log_title),
            trailing = {
                TextButton(onClick = { viewModel.closeActionLog() }) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(spacing.xs))
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    item {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
        ) {
            Column(modifier = Modifier.padding(spacing.md)) {
                // Action title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Text(
                        text = state.activeAction ?: "",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    val actionLog = state.actionLog
                    if (actionLog != null) {
                        if (actionLog.running == true) {
                            StatusBadge(
                                text = stringResource(R.string.system_action_log_running),
                                status = StatusBadgeType.INFO,
                            )
                        } else {
                            StatusBadge(
                                text = stringResource(R.string.system_action_log_done),
                                status = StatusBadgeType.SUCCESS,
                            )
                            actionLog.exit_code?.let { code ->
                                Spacer(modifier = Modifier.size(spacing.xs))
                                StatusBadge(
                                    text = stringResource(R.string.system_action_log_exit, code),
                                    status =
                                        if (code == 0) StatusBadgeType.SUCCESS else StatusBadgeType.ERROR,
                                )
                            }
                        }
                    } else {
                        StatusBadge(
                            text = stringResource(R.string.system_action_log_starting),
                            status = StatusBadgeType.NEUTRAL,
                        )
                    }
                }

                // Output lines
                state.actionLog?.lines?.let { lines ->
                    if (lines.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                        ) {
                            Column(modifier = Modifier.padding(spacing.sm)) {
                                lines.take(20).forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (lines.size > 20) {
                                    Text(
                                        text = stringResource(R.string.system_more_lines, lines.size - 20),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
