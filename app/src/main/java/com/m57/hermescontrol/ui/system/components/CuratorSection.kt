package com.m57.hermescontrol.ui.system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.Spacing
import com.m57.hermescontrol.ui.common.InfoRow
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.system.SystemUiState
import com.m57.hermescontrol.ui.system.SystemViewModel

fun LazyListScope.curatorSection(
    state: SystemUiState,
    spacing: Spacing,
    viewModel: SystemViewModel,
) {
    state.curator?.let { curator ->
        item {
            SectionHeader(title = stringResource(R.string.system_sec_curator))
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
                    // Status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        val statusText: String
                        val statusType: StatusBadgeType
                        when {
                            curator.enabled != true -> {
                                statusText = stringResource(R.string.system_status_disabled)
                                statusType = StatusBadgeType.ERROR
                            }

                            curator.paused == true -> {
                                statusText = stringResource(R.string.system_status_paused)
                                statusType = StatusBadgeType.WARNING
                            }

                            else -> {
                                statusText = stringResource(R.string.system_status_active)
                                statusType = StatusBadgeType.SUCCESS
                            }
                        }
                        StatusBadge(text = statusText, status = statusType)
                    }

                    // Interval info
                    curator.interval_hours?.let { hrs ->
                        InfoRow(
                            label = stringResource(R.string.system_curator_interval, hrs),
                            value = "",
                        )
                        Spacer(modifier = Modifier.height(spacing.xs))
                    }

                    // Last run
                    curator.last_run_at?.let { lastRun ->
                        InfoRow(
                            label = stringResource(R.string.system_curator_last_run, lastRun),
                            value = "",
                        )
                    } ?: InfoRow(
                        label = stringResource(R.string.system_curator_never_run),
                        value = "",
                    )

                    // Actions
                    if (curator.enabled == true) {
                        Spacer(modifier = Modifier.height(spacing.sm))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.toggleCuratorPaused() },
                                modifier = Modifier.weight(1f),
                                contentPadding = ActionButtonPadding,
                            ) {
                                if (curator.paused == true) {
                                    ActionButtonContent(
                                        icon = Icons.Filled.PlayArrow,
                                        text = stringResource(R.string.system_curator_resume),
                                    )
                                } else {
                                    ActionButtonContent(
                                        icon = Icons.Filled.Pause,
                                        text = stringResource(R.string.system_curator_pause),
                                    )
                                }
                            }
                            Button(
                                onClick = { viewModel.runCuratorNow() },
                                modifier = Modifier.weight(1f),
                                contentPadding = ActionButtonPadding,
                            ) {
                                ActionButtonContent(
                                    icon = Icons.Filled.Refresh,
                                    text = stringResource(R.string.system_curator_run_now),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
