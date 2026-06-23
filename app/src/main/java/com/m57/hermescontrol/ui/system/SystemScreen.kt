package com.m57.hermescontrol.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.InfoRow
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.StatCard
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SystemScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: SystemViewModel = viewModel { SystemViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val statusColors = LocalHermesStatusColors.current

    LaunchedEffect(Unit) {
        viewModel.loadSystemData()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_system)) },
        onOpenDrawer = onOpenDrawer,
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadSystemData() },
    ) {
        when {
            state.isLoading && state.stats == null && state.doctorReport == null -> {
                LoadingState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: stringResource(R.string.error_unknown),
                    onRetry = { viewModel.loadSystemData() },
                )
            }

            state.stats == null && state.doctorReport == null -> {
                EmptyState(
                    title = stringResource(R.string.system_empty_title),
                    subtitle = stringResource(R.string.system_empty_desc),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            horizontal = spacing.md,
                            vertical = spacing.sm,
                        ),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    // Stats row
                    state.stats?.let { stats ->
                        item {
                            SectionHeader(title = stringResource(R.string.system_sec_performance))
                        }
                        item {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                stats.cpuPercent?.let { cpu ->
                                    StatCard(
                                        label = stringResource(R.string.system_metric_cpu),
                                        value = "$cpu%",
                                        icon = Icons.Filled.Speed,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                stats.memoryPercent?.let { mem ->
                                    StatCard(
                                        label = stringResource(R.string.system_metric_memory),
                                        value = "$mem%",
                                        icon = Icons.Filled.Memory,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }

                    // Doctor diagnostics
                    state.doctorReport?.let { report ->
                        item {
                            SectionHeader(title = stringResource(R.string.system_sec_doctor))
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
                                    Row(
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.HealthAndSafety,
                                            contentDescription = null,
                                            tint =
                                                if (report.ok) {
                                                    statusColors.success
                                                } else {
                                                    statusColors.error
                                                },
                                        )
                                        StatusBadge(
                                            text =
                                                if (report.ok) {
                                                    stringResource(R.string.system_status_running)
                                                } else {
                                                    stringResource(R.string.system_status_failed)
                                                },
                                            status =
                                                if (report.ok) {
                                                    StatusBadgeType.SUCCESS
                                                } else {
                                                    StatusBadgeType.ERROR
                                                },
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(spacing.sm))
                                    report.pid?.let { pid ->
                                        InfoRow(
                                            label = stringResource(R.string.system_info_pid),
                                            value = pid.toString(),
                                        )
                                    }
                                    report.name?.let { name ->
                                        InfoRow(label = stringResource(R.string.system_info_proc_name), value = name)
                                    }
                                }
                            }
                        }
                    }

                    // Admin actions
                    item {
                        SectionHeader(title = stringResource(R.string.system_sec_admin))
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
                                Button(
                                    onClick = { viewModel.triggerBackup() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Backup,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = spacing.xs),
                                    )
                                    Text(stringResource(R.string.system_action_backup))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
