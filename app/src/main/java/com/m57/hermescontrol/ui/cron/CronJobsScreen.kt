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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import com.m57.hermescontrol.data.model.CronBlueprint
import com.m57.hermescontrol.data.model.CronBlueprintField
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.model.DeliveryTarget
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.ExposedDropdownField
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
                    actionLabel = stringResource(R.string.empty_action_create_job),
                    onAction = { viewModel.openNewJobDialog() },
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
                                    // Run-status badge: blocked_config looks like a
                                    // failure with no reason — surface it distinctly.
                                    when (job.lastRunStatus) {
                                        "blocked_config" -> {
                                            StatusBadge(
                                                text = stringResource(R.string.cron_status_blocked_config),
                                                status = StatusBadgeType.ERROR,
                                            )
                                        }

                                        "no_change" -> {
                                            StatusBadge(
                                                text = stringResource(R.string.cron_status_no_change),
                                                status = StatusBadgeType.INFO,
                                            )
                                        }
                                    }
                                    // Run-continuity badge: the job's own previous
                                    // output is injected into each run.
                                    if (job.context_from?.contains("self") == true) {
                                        StatusBadge(
                                            text = stringResource(R.string.cron_badge_continuity),
                                            status = StatusBadgeType.INFO,
                                        )
                                    }
                                    // Dispatch error badge: a scheduled fire was missed
                                    // (gateway unreachable / api_server not bound).
                                    if (job.last_fire_error != null) {
                                        StatusBadge(
                                            text = stringResource(R.string.cron_badge_dispatch_error),
                                            status = StatusBadgeType.ERROR,
                                        )
                                    }
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
                                        onClick = { viewModel.requestDeleteJob(job) },
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
            onToggleRunContinuity = { viewModel.toggleRunContinuity() },
            onSetMonitorMode = { viewModel.setMonitorMode(it) },
            onSelectBlueprint = { viewModel.selectBlueprint(it) },
            onBlueprintFieldChange = { name, value -> viewModel.updateBlueprintValue(name, value) },
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
                    RunDetailRow(
                        "Status",
                        when (job.lastRunStatus) {
                            "blocked_config" -> stringResource(R.string.cron_status_blocked_config)
                            "no_change" -> stringResource(R.string.cron_status_no_change)
                            else -> job.lastRunStatus.ifEmpty { "unknown" }
                        },
                    )
                    job.last_run_at?.let { if (it.isNotBlank()) RunDetailRow("Last run", it) }
                    RunDetailRow("Schedule", CronExpressionFormatter.cronToHumanReadable(job.scheduleText))
                    if (job.last_error != null && job.last_error.isNotBlank()) {
                        RunDetailRow("Error", job.last_error)
                    }
                    job.last_fire_error?.let { fireErr ->
                        if (fireErr.detail != null || fireErr.at != null) {
                            RunDetailRow(
                                stringResource(R.string.cron_detail_dispatch_error),
                                buildString {
                                    fireErr.detail?.let { append(it) }
                                    fireErr.at?.let { at ->
                                        if (fireErr.detail != null) append(" ")
                                        append("($at)")
                                    }
                                },
                            )
                        }
                    }
                    job.script?.let { if (it.isNotBlank()) RunDetailRow("Script", it) }
                    job.monitorSource?.let { if (it.isNotBlank()) RunDetailRow("Monitor", it) }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedJob = null }) { Text("Close") }
            },
        )
    }

    // ── Delete Confirm Dialog ──
    state.deleteTarget?.let { job ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteDialog() },
            title = { Text(stringResource(R.string.cron_delete_title)) },
            text = { Text(stringResource(R.string.cron_delete_message, job.name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDeleteJob() }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
fun CronJobEditorDialog(
    state: CronJobEditorState,
    onFieldChange: (String, String) -> Unit,
    onToggleNoAgent: () -> Unit,
    onToggleRunContinuity: () -> Unit,
    onSetMonitorMode: (String) -> Unit,
    onSelectBlueprint: (String?) -> Unit,
    onBlueprintFieldChange: (String, String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onClearToast: () -> Unit,
) {
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val hasChanges =
        state.name.isNotEmpty() || state.schedule.isNotEmpty() ||
            state.prompt.isNotEmpty() || state.skills.isNotEmpty() ||
            state.monitor_script.isNotEmpty() || state.monitor_url.isNotEmpty() ||
            state.monitorMode != "off"

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
                    LoadingState(modifier = Modifier.padding(padding))
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val blueprint = state.selectedBlueprint

                        // Start-from blueprint picker (new jobs only; hidden when the
                        // catalog failed to load)
                        if (state.isNew && state.blueprints.isNotEmpty()) {
                            BlueprintStartFromDropdown(
                                blueprints = state.blueprints,
                                selectedKey = state.selectedBlueprintKey,
                                onSelect = onSelectBlueprint,
                            )
                            blueprint?.let {
                                Text(
                                    text = it.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (blueprint != null) {
                            // Blueprint mode: typed slots only — the backend renders the
                            // prompt and schedule from the filled values.
                            blueprint.fields.forEach { field ->
                                BlueprintSlotField(
                                    field = field,
                                    value = state.blueprintValues[field.name].orEmpty(),
                                    deliveryOptions = state.deliveryOptions,
                                    deliveryTargets = state.deliveryTargets,
                                    onValueChange = { onBlueprintFieldChange(field.name, it) },
                                )
                            }
                        } else {
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
                            DeliverField(
                                label = stringResource(R.string.cron_edit_field_deliver),
                                value = state.deliver,
                                options = state.deliveryOptions,
                                deliveryTargets = state.deliveryTargets,
                                onValueChange = { onFieldChange("deliver", it) },
                                hint = stringResource(R.string.cron_edit_hint_deliver),
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

                            // Run Continuity toggle (recurring jobs: inject own
                            // previous output into each run via context_from=["self"])
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = state.runContinuity,
                                    onCheckedChange = { onToggleRunContinuity() },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.cron_edit_field_run_continuity),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = stringResource(R.string.cron_edit_hint_run_continuity),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                }
                            }

                            // Monitor mode (disabled for no-agent jobs — the
                            // backend rejects monitor + no_agent together)
                            MonitorModeSection(
                                monitorMode = state.monitorMode,
                                monitorScript = state.monitor_script,
                                monitorUrl = state.monitor_url,
                                enabled = !state.no_agent,
                                onFieldChange = onFieldChange,
                                onSetMonitorMode = onSetMonitorMode,
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
private fun MonitorModeSection(
    monitorMode: String,
    monitorScript: String,
    monitorUrl: String,
    enabled: Boolean,
    onFieldChange: (String, String) -> Unit,
    onSetMonitorMode: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    val offLabel = stringResource(R.string.cron_edit_monitor_off)
    val scriptLabel = stringResource(R.string.cron_edit_monitor_script)
    val urlLabel = stringResource(R.string.cron_edit_monitor_url)

    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        ExposedDropdownField(
            label = stringResource(R.string.cron_edit_monitor_mode),
            options = listOf(offLabel, scriptLabel, urlLabel),
            selectedValue =
                when (monitorMode) {
                    "script" -> scriptLabel
                    "url" -> urlLabel
                    else -> offLabel
                },
            enabled = enabled,
            onOptionSelected = { selected ->
                when (selected) {
                    scriptLabel -> onSetMonitorMode("script")
                    urlLabel -> onSetMonitorMode("url")
                    else -> onSetMonitorMode("off")
                }
            },
        )
        when (monitorMode) {
            "script" -> {
                OutlinedTextField(
                    value = monitorScript,
                    onValueChange = { onFieldChange("monitor_script", it) },
                    label = { Text(stringResource(R.string.cron_edit_monitor_script_field)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = enabled,
                )
            }

            "url" -> {
                OutlinedTextField(
                    value = monitorUrl,
                    onValueChange = { onFieldChange("monitor_url", it) },
                    label = { Text(stringResource(R.string.cron_edit_monitor_url_field)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = enabled,
                )
            }
        }
        Text(
            text = stringResource(R.string.cron_edit_monitor_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlueprintStartFromDropdown(
    blueprints: List<CronBlueprint>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedTitle =
        blueprints.firstOrNull { it.key == selectedKey }?.title
            ?: stringResource(R.string.cron_edit_blank_job)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedTitle,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.cron_edit_start_from)) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.cron_edit_blank_job)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            blueprints.forEach { blueprint ->
                DropdownMenuItem(
                    text = { Text(blueprint.title) },
                    onClick = {
                        onSelect(blueprint.key)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun BlueprintSlotField(
    field: CronBlueprintField,
    value: String,
    deliveryOptions: List<String>,
    deliveryTargets: List<DeliveryTarget>,
    onValueChange: (String) -> Unit,
) {
    when {
        field.name == "deliver" -> {
            DeliverField(
                label = field.label,
                value = value,
                options = deliveryOptions,
                deliveryTargets = deliveryTargets,
                onValueChange = onValueChange,
            )
        }

        field.type == "enum" || field.type == "weekdays" -> {
            ExposedDropdownField(
                label = field.label,
                options = field.options,
                selectedValue = value,
                onOptionSelected = onValueChange,
            )
        }

        else -> {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(field.label) },
                placeholder =
                    if (field.type == "time") {
                        { Text(stringResource(R.string.cron_edit_hint_time)) }
                    } else {
                        null
                    },
                supportingText =
                    if (field.help.isNotBlank()) {
                        {
                            Text(
                                text = field.help,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    } else {
                        null
                    },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

/**
 * Delivery target picker: an editable dropdown fed by the backend's
 * delivery-targets endpoint (origin + local + connected platforms). Picking
 * fills the field; typing keeps the legacy free-text behaviour (e.g.
 * `telegram:chat_id`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeliverField(
    label: String,
    value: String,
    options: List<String>,
    deliveryTargets: List<DeliveryTarget>,
    onValueChange: (String) -> Unit,
    hint: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayNames =
        deliveryTargets.associate { it.id to it.name.ifBlank { it.id } } + ("origin" to "origin")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            supportingText =
                hint?.let {
                    {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                },
            singleLine = true,
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { id ->
                DropdownMenuItem(
                    text = { Text(displayNames[id] ?: id) },
                    onClick = {
                        onValueChange(id)
                        expanded = false
                    },
                )
            }
        }
    }
}
