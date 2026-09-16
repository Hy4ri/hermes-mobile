package com.m57.hermescontrol.ui.system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.GatewayMigrationPlan
import com.m57.hermescontrol.theme.Spacing
import com.m57.hermescontrol.ui.common.ConfirmDialog
import com.m57.hermescontrol.ui.common.InfoRow
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.system.SystemUiState
import com.m57.hermescontrol.ui.system.SystemViewModel

fun LazyListScope.gatewaySection(
    state: SystemUiState,
    spacing: Spacing,
    viewModel: SystemViewModel,
) {
    state.status?.let { status ->
        item {
            SectionHeader(title = stringResource(R.string.system_sec_gateway))
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
                    // Status badge + version
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            val isRunning = status.gateway_running == true
                            StatusBadge(
                                text =
                                    if (isRunning) {
                                        stringResource(R.string.system_status_running)
                                    } else {
                                        stringResource(R.string.system_status_stopped)
                                    },
                                status = if (isRunning) StatusBadgeType.SUCCESS else StatusBadgeType.ERROR,
                            )
                        }
                    }

                    // Version · Sessions · PID
                    status.version?.let { ver ->
                        val parts = mutableListOf(ver)
                        status.active_sessions?.let { parts.add("$it session(s)") }
                        state.doctorReport?.let { report ->
                            if (report.ok && report.pid != null) {
                                parts.add("pid ${report.pid}")
                            }
                        }
                        InfoRow(
                            label = stringResource(R.string.system_label_hermes_version),
                            value = parts.joinToString(" · "),
                        )
                    }

                    // Auth required
                    status.auth_required?.let { auth ->
                        InfoRow(
                            label = stringResource(R.string.system_auth_required),
                            value = if (auth) "yes" else "no",
                        )
                    }

                    // Active platforms
                    status.gateway_platforms?.let { platforms ->
                        if (platforms.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
                            platforms.forEach { (name, platform) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                                ) {
                                    Icon(
                                        Icons.Filled.Devices,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    platform?.state?.let { platformState ->
                                        StatusBadge(
                                            text = platformState,
                                            status =
                                                when (platformState.lowercase()) {
                                                    "connected", "running", "ready" -> StatusBadgeType.SUCCESS
                                                    "error", "disconnected", "failed" -> StatusBadgeType.ERROR
                                                    else -> StatusBadgeType.NEUTRAL
                                                },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Action buttons
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        val isRunning = status.gateway_running == true
                        if (!isRunning) {
                            Button(
                                onClick = { viewModel.startGateway() },
                                modifier = Modifier.weight(1f),
                                contentPadding = ActionButtonPadding,
                            ) {
                                ActionButtonContent(
                                    icon = Icons.Filled.PlayArrow,
                                    text = stringResource(R.string.system_gateway_start),
                                )
                            }
                        }
                        if (isRunning) {
                            Button(
                                onClick = { viewModel.restartGateway() },
                                modifier = Modifier.weight(1f),
                                contentPadding = ActionButtonPadding,
                            ) {
                                ActionButtonContent(
                                    icon = Icons.Filled.RestartAlt,
                                    text = stringResource(R.string.system_gateway_restart),
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.stopGateway() },
                                modifier = Modifier.weight(1f),
                                contentPadding = ActionButtonPadding,
                            ) {
                                ActionButtonContent(
                                    icon = Icons.Filled.Stop,
                                    text = stringResource(R.string.system_gateway_stop),
                                    iconTint = MaterialTheme.colorScheme.error,
                                    textColor = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }

        state.migrationPlan?.let { plan ->
            item {
                GatewayMigrationCard(
                    plan = plan,
                    onStartMigration = viewModel::startMigration,
                )
            }
        }
    }
}

@Composable
private fun GatewayMigrationCard(
    plan: GatewayMigrationPlan,
    onStartMigration: () -> Unit,
) {
    var showConfirmation by remember(plan) { mutableStateOf(false) }
    val profileNames =
        plan.profiles
            .mapNotNull { it.profile?.takeIf(String::isNotBlank) }
            .ifEmpty { plan.liveServed.orEmpty() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.system_migration_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.system_migration_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (profileNames.isNotEmpty()) {
                InfoRow(
                    label = stringResource(R.string.system_migration_profiles),
                    value = profileNames.joinToString(" · "),
                )
            }
            InfoRow(
                label = stringResource(R.string.system_migration_automatic_eligibility),
                value =
                    stringResource(
                        if (plan.eligible) {
                            R.string.system_migration_yes
                        } else {
                            R.string.system_migration_no
                        },
                    ),
            )

            if (plan.alreadyMultiplexed) {
                Spacer(modifier = Modifier.height(8.dp))
                StatusBadge(
                    text = stringResource(R.string.system_migration_already_multiplexed),
                    status = StatusBadgeType.SUCCESS,
                )
            }
            if (plan.interrupted) {
                Spacer(modifier = Modifier.height(8.dp))
                StatusBadge(
                    text = stringResource(R.string.system_migration_interrupted),
                    status = StatusBadgeType.WARNING,
                )
            }

            if (plan.blockers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.system_migration_blockers),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                plan.blockers.forEach { blocker ->
                    Text(
                        text = "• $blocker",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (plan.notices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.system_migration_notices),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                plan.notices.forEach { notice ->
                    Text(
                        text = "• $notice",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!plan.alreadyMultiplexed && plan.blockers.isEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { showConfirmation = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.system_migration_start))
                }
            }
        }
    }

    if (showConfirmation) {
        ConfirmDialog(
            title = stringResource(R.string.system_migration_confirm_title),
            message = stringResource(R.string.system_migration_confirm_description),
            onConfirm = {
                showConfirmation = false
                onStartMigration()
            },
            onDismiss = { showConfirmation = false },
            confirmText = stringResource(R.string.system_migration_start),
        )
    }
}
