package com.m57.hermescontrol.ui.cron

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

@Composable
fun CronJobsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: CronJobsViewModel = viewModel { CronJobsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = LocalSpacing.current

    LaunchedEffect(Unit) {
        viewModel.loadCronJobs()
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    HermesScaffold(
        title = { Text("Cron Jobs") },
        onOpenDrawer = onOpenDrawer,
        onRefresh = { viewModel.loadCronJobs() },
    ) {
        when {
            state.isLoading -> LoadingState()
            state.errorMessage != null ->
                ErrorState(
                    message = state.errorMessage ?: "Unknown error",
                    onRetry = { viewModel.loadCronJobs() },
                )
            state.jobs.isEmpty() ->
                EmptyState(
                    title = "No cron jobs",
                    subtitle = "Scheduled tasks from Hermes will appear here.",
                    icon = Icons.Filled.Schedule,
                )
            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    items(state.jobs) { job ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                        ) {
                            Column(modifier = Modifier.padding(spacing.md)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = job.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    StatusBadge(
                                        text = if (job.state == "active") "Active" else "Paused",
                                        status =
                                            if (job.state == "active") {
                                                StatusBadgeType.SUCCESS
                                            } else {
                                                StatusBadgeType.NEUTRAL
                                            },
                                    )
                                }
                                Spacer(modifier = Modifier.height(spacing.xs))
                                Text(
                                    text = job.scheduleText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(spacing.sm))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                                ) {
                                    if (job.state == "active") {
                                        OutlinedButton(
                                            onClick = { viewModel.pauseCronJob(job.id) },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text("Pause")
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { viewModel.resumeCronJob(job.id) },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text("Resume")
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.triggerCronJob(job.id) },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("Run")
                                    }
                                    Button(
                                        onClick = { viewModel.deleteCronJob(job.id) },
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error,
                                            ),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("Delete")
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }
}
