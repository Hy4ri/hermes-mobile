package com.m57.hermescontrol.ui.cron

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.util.CronExpressionFormatter

@Composable
fun CronJobsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: CronJobsViewModel = viewModel { CronJobsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    var selectedJob by remember { mutableStateOf<CronJob?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadCronJobs()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_cron)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadCronJobs() },
        actions = {
            IconButton(onClick = { viewModel.openNewJobDialog() }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cron_action_add),
                )
            }
        },
    ) {
        when {
            state.isLoading && state.jobs.isEmpty() -> {
                SkeletonListState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "Unknown error",
                    onRetry = { viewModel.loadCronJobs() },
                )
            }

            state.jobs.isEmpty() -> {
                EmptyState(
                    icon = Icons.Filled.Schedule,
                    title = stringResource(R.string.cron_empty_title),
                    subtitle = stringResource(R.string.cron_empty_desc),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    items(state.jobs, key = { it.id }) { job ->
                        Card(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = { selectedJob = job }),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                        ) {
                            Column(modifier = Modifier.padding(spacing.md)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = job.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    StatusBadge(
                                        text =
                                            if (job.state == "active") {
                                                stringResource(R.string.cron_status_active)
                                            } else {
                                                stringResource(R.string.cron_status_paused)
                                            },
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
                                    text = CronExpressionFormatter.cronToHumanReadable(job.scheduleText),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(spacing.xs))
                                Text(
                                    text = job.scheduleText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                                Spacer(modifier = Modifier.height(spacing.sm))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    IconButton(
                                        onClick = { viewModel.openEditJobDialog(job.id) },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = stringResource(R.string.cron_action_edit),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (job.state == "active") {
                                        IconButton(
                                            onClick = { viewModel.pauseCronJob(job.id) },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Pause,
                                                contentDescription = stringResource(R.string.cron_action_pause),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    } else {
                                        IconButton(
                                            onClick = { viewModel.resumeCronJob(job.id) },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.PlayArrow,
                                                contentDescription = stringResource(R.string.cron_action_resume),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { viewModel.triggerCronJob(job.id) },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = stringResource(R.string.cron_action_run),
                                            tint = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteCronJob(job.id) },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.action_delete),
                                            tint = MaterialTheme.colorScheme.error,
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

    // ── Editor Dialog ──
    if (state.editorState.isOpen) {
        CronJobEditorDialog(
            state = state.editorState,
            onFieldChange = { name, value -> viewModel.updateEditorField(name, value) },
            onToggleNoAgent = { viewModel.toggleNoAgent() },
            onSave = { viewModel.saveEditor() },
            onDismiss = { viewModel.closeEditor() },
            onClearToast = { viewModel.clearEditorToast() },
        )
    }

    // ── Run Details Dialog ──
    selectedJob?.let { job ->
        AlertDialog(
            onDismissRequest = { selectedJob = null },
            title = { Text(job.name, style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    RunDetailRow("Status", job.lastRunStatus.ifEmpty { "unknown" })
                    job.last_run_at?.let { if (it.isNotBlank()) RunDetailRow("Last run", it) }
                    RunDetailRow("Schedule", CronExpressionFormatter.cronToHumanReadable(job.scheduleText))
                    if (job.last_error != null && job.last_error.isNotBlank()) {
                        RunDetailRow("Error", job.last_error)
                    }
                    job.script?.let { if (it.isNotBlank()) RunDetailRow("Script", it) }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedJob = null }) { Text("Close") }
            },
        )
    }
}

@Composable
fun CronJobEditorDialog(
    state: CronJobEditorState,
    onFieldChange: (String, String) -> Unit,
    onToggleNoAgent: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onClearToast: () -> Unit,
) {
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val hasChanges =
        state.name.isNotEmpty() || state.schedule.isNotEmpty() ||
            state.prompt.isNotEmpty() || state.skills.isNotEmpty()

    ToastEffect(toastMessage = state.toastMessage, onClearToast = onClearToast)

    Dialog(
        onDismissRequest = {
            if (hasChanges && !state.isNew) {
                showDiscardConfirm = true
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
        ) {
            HermesScaffold(
                title = {
                    Text(
                        if (state.isNew) {
                            stringResource(R.string.cron_edit_title_new)
                        } else {
                            stringResource(R.string.cron_edit_title)
                        },
                    )
                },
                navigationIcon =
                    NavIcon.Back(
                        onBack = {
                            if (hasChanges && !state.isNew) {
                                showDiscardConfirm = true
                            } else {
                                onDismiss()
                            }
                        },
                    ),
                actions = {
                    if (!state.isLoading) {
                        IconButton(
                            onClick = onSave,
                            enabled = !state.isSaving,
                        ) {
                            if (state.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.fillMaxSize(0.6f),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.cron_edit_action_save),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                if (state.isLoading) {
                    LoadingState()
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(horizontal = 16.dp)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Name
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = { onFieldChange("name", it) },
                            label = { Text(stringResource(R.string.cron_edit_field_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )

                        // Schedule (required)
                        OutlinedTextField(
                            value = state.schedule,
                            onValueChange = { onFieldChange("schedule", it) },
                            label = { Text(stringResource(R.string.cron_edit_field_schedule)) },
                            placeholder = { Text(stringResource(R.string.cron_edit_hint_schedule)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )

                        // Prompt
                        OutlinedTextField(
                            value = state.prompt,
                            onValueChange = { onFieldChange("prompt", it) },
                            label = { Text(stringResource(R.string.cron_edit_field_prompt)) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                            textStyle =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                        )

                        // Delivery
                        OutlinedTextField(
                            value = state.deliver,
                            onValueChange = { onFieldChange("deliver", it) },
                            label = { Text(stringResource(R.string.cron_edit_field_deliver)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            supportingText = {
                                Text(
                                    stringResource(R.string.cron_edit_hint_deliver),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            },
                        )

                        // Skills
                        OutlinedTextField(
                            value = state.skills,
                            onValueChange = { onFieldChange("skills", it) },
                            label = { Text(stringResource(R.string.cron_edit_field_skills)) },
                            placeholder = { Text(stringResource(R.string.cron_edit_hint_skills)) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(100.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                        )

                        // Model
                        OutlinedTextField(
                            value = state.model,
                            onValueChange = { onFieldChange("model", it) },
                            label = { Text(stringResource(R.string.cron_edit_field_model)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )

                        // Provider
                        OutlinedTextField(
                            value = state.provider,
                            onValueChange = { onFieldChange("provider", it) },
                            label = { Text(stringResource(R.string.cron_edit_field_provider)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )

                        // Base URL
                        OutlinedTextField(
                            value = state.base_url,
                            onValueChange = { onFieldChange("base_url", it) },
                            label = { Text(stringResource(R.string.cron_edit_field_base_url)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )

                        // Script path
                        OutlinedTextField(
                            value = state.script,
                            onValueChange = { onFieldChange("script", it) },
                            label = { Text(stringResource(R.string.cron_edit_field_script)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )

                        // Work directory
                        OutlinedTextField(
                            value = state.workdir,
                            onValueChange = { onFieldChange("workdir", it) },
                            label = { Text(stringResource(R.string.cron_edit_field_workdir)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )

                        // No Agent toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = state.no_agent,
                                onCheckedChange = { onToggleNoAgent() },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.cron_edit_field_no_agent),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.cron_discard_title)) },
            text = { Text(stringResource(R.string.cron_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.action_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun RunDetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.65f),
        )
    }
}
