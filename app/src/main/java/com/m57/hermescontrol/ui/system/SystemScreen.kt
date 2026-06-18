package com.m57.hermescontrol.ui.system

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SystemScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: SystemViewModel = viewModel { SystemViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val statusColors = LocalHermesStatusColors.current

    LaunchedEffect(Unit) {
        viewModel.loadSystemData()
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    HermesScaffold(
        title = { Text("System") },
        onOpenDrawer = onOpenDrawer,
        onRefresh = { viewModel.loadSystemData() },
    ) {
        when {
            state.isLoading -> LoadingState()
            state.errorMessage != null ->
                ErrorState(
                    message = state.errorMessage ?: "Unknown error",
                    onRetry = { viewModel.loadSystemData() },
                )
            state.stats == null && state.doctorReport == null ->
                EmptyState(
                    title = "No system data",
                    subtitle = "Tap refresh to load system diagnostics.",
                )
            else ->
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
                            SectionHeader(title = "Performance")
                        }
                        item {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                stats.cpuPercent?.let { cpu ->
                                    StatCard(
                                        label = "CPU Usage",
                                        value = "$cpu%",
                                        icon = Icons.Filled.Speed,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                stats.memoryPercent?.let { mem ->
                                    StatCard(
                                        label = "Memory",
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
                            SectionHeader(title = "Doctor")
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
                                            text = if (report.ok) "Running" else "Failed",
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
                                        InfoRow(label = "Process PID", value = pid.toString())
                                    }
                                    report.name?.let { name ->
                                        InfoRow(label = "Process Name", value = name)
                                    }
                                }
                            }
                        }
                    }

                    // Admin actions
                    item {
                        SectionHeader(title = "Administration")
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
                                    Text("Trigger System Backup")
                                }
                            }
                        }
                    }
                }
        }
    }
}
