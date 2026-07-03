package com.m57.hermescontrol.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.CredentialPoolEntry
import com.m57.hermescontrol.data.model.HookEntry
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.InfoRow
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.StatCard
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect

// ── Helpers ─────────────────────────────────────────────────────────────

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
    }
}

private fun formatDuration(totalSeconds: Double): String {
    val days = (totalSeconds / 86400).toInt()
    val hours = ((totalSeconds % 86400) / 3600).toInt()
    val minutes = ((totalSeconds % 3600) / 60).toInt()
    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0 || days > 0) append("${hours}h ")
        append("${minutes}m")
    }
}

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
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        viewModel.loadAll()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    // ── Confirmation dialogs ──────────────────────────────────────────────

    if (state.updateConfirmOpen) {
        AlertDialog(
            onDismissRequest = viewModel::closeUpdateConfirm,
            title = { Text(stringResource(R.string.system_update_confirm_title)) },
            text = { Text(stringResource(R.string.system_update_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.closeUpdateConfirm()
                    viewModel.applyUpdate()
                }) {
                    Text(stringResource(R.string.system_confirm_update_now))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeUpdateConfirm) {
                    Text(stringResource(R.string.system_confirm_cancel))
                }
            },
        )
    }

    // Memory reset confirmation
    var resetTarget by remember { mutableStateOf<String?>(null) }
    if (resetTarget != null) {
        AlertDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text(stringResource(R.string.system_memory_reset_confirm_title)) },
            text = { Text(stringResource(R.string.system_memory_reset_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    resetTarget?.let { viewModel.resetMemory(it) }
                    resetTarget = null
                }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetTarget = null }) {
                    Text(stringResource(R.string.system_confirm_cancel))
                }
            },
        )
    }

    // Credential remove confirmation
    var credToRemove by remember { mutableStateOf<Pair<String, Int>?>(null) }
    if (credToRemove != null) {
        AlertDialog(
            onDismissRequest = { credToRemove = null },
            title = { Text(stringResource(R.string.system_credentials_remove_confirm_title)) },
            text = { Text(stringResource(R.string.system_credentials_remove_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    credToRemove?.let { (p, i) -> viewModel.removeCredential(p, i) }
                    credToRemove = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { credToRemove = null }) {
                    Text(stringResource(R.string.system_confirm_cancel))
                }
            },
        )
    }

    // Prune checkpoints confirmation
    var pruneConfirm by remember { mutableStateOf(false) }
    if (pruneConfirm) {
        AlertDialog(
            onDismissRequest = { pruneConfirm = false },
            title = { Text(stringResource(R.string.system_checkpoints_prune_confirm_title)) },
            text = { Text(stringResource(R.string.system_checkpoints_prune_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    pruneConfirm = false
                    viewModel.pruneCheckpoints()
                }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pruneConfirm = false }) {
                    Text(stringResource(R.string.system_confirm_cancel))
                }
            },
        )
    }

    // Import / Restore confirmation
    var importConfirm by remember { mutableStateOf(false) }
    if (importConfirm) {
        AlertDialog(
            onDismissRequest = { importConfirm = false },
            title = { Text(stringResource(R.string.system_op_import_confirm_title)) },
            text = { Text(stringResource(R.string.system_op_import_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    importConfirm = false
                    viewModel.runImport(state.importPath)
                }) {
                    Text(stringResource(R.string.system_confirm_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { importConfirm = false }) {
                    Text(stringResource(R.string.system_confirm_cancel))
                }
            },
        )
    }

    HermesScaffold(
        modifier = modifier,
        title = { Text(stringResource(R.string.screen_system)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadAll() },
    ) {
        when {
            state.isLoading && state.stats == null && state.doctorReport == null &&
                state.status == null -> {
                LoadingState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: stringResource(R.string.error_unknown),
                    onRetry = { viewModel.loadAll() },
                )
            }

            state.stats == null && state.doctorReport == null && state.status == null -> {
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
                    // ── 1. Host ────────────────────────────────────────────
                    item {
                        HostSection(state, spacing, statusColors, viewModel)
                    }

                    // ── 2. Portal ──────────────────────────────────────────
                    item {
                        PortalSection(state, spacing, uriHandler)
                    }

                    // ── 3. Curator ─────────────────────────────────────────
                    item {
                        CuratorSection(state, spacing, statusColors, viewModel)
                    }

                    // ── 4. Gateway ─────────────────────────────────────────
                    item {
                        GatewaySection(state, spacing, statusColors, viewModel)
                    }

                    // ── 5. Memory ──────────────────────────────────────────
                    item {
                        MemorySection(state, spacing, resetTarget, { resetTarget = it }, viewModel)
                    }

                    // ── 6. Credentials ─────────────────────────────────────
                    item {
                        CredentialsSection(state, spacing, viewModel, credToRemove, { credToRemove = it })
                    }

                    // ── 7. Operations ──────────────────────────────────────
                    item {
                        OperationsSection(state, spacing, viewModel, importConfirm, { importConfirm = it })
                    }

                    // ── 8. Checkpoints ─────────────────────────────────────
                    item {
                        CheckpointsSection(state, spacing, pruneConfirm, { pruneConfirm = it })
                    }

                    // ── 9. Shell Hooks ─────────────────────────────────────
                    item {
                        ShellHooksSection(state, spacing, viewModel)
                    }

                    // ── 10. Action Log ─────────────────────────────────────
                    if (state.activeAction != null) {
                        item {
                            ActionLogSection(state, viewModel)
                        }
                    }
                }
            }
        }
    }
}

// ── Section composables ─────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HostSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    statusColors: com.m57.hermescontrol.theme.HermesStatusColors,
    viewModel: SystemViewModel,
) {
    state.stats?.let { stats ->
        item {
            SectionHeader(title = stringResource(R.string.system_sec_host))
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
                    // OS, Arch, Hostname
                    stats.os?.let { InfoRow(stringResource(R.string.system_label_os), it) }
                    stats.arch?.let { InfoRow(stringResource(R.string.system_label_arch), it) }
                    stats.hostname?.let { InfoRow(stringResource(R.string.system_label_hostname), it) }
                    stats.python_version?.let {
                            python ->
                        val impl = stats.python_impl?.let { " ($it)" } ?: ""
                        InfoRow(stringResource(R.string.system_label_python), "$python$impl")
                    }

                    // Hermes version + update badge
                    stats.hermes_version?.let { ver ->
                        val updateBadge =
                            state.updateInfo?.let { info ->
                                when {
                                    info.update_available == true && info.behind != null && info.behind > 0 ->
                                        " (${stringResource(
                                            R.string.system_version_update_available,
                                        )}: ${stringResource(R.string.system_version_behind, info.behind)})"
                                    info.update_available == true -> " (${stringResource(
                                        R.string.system_version_update_available,
                                    )})"
                                    else -> " (${stringResource(R.string.system_version_latest)})"
                                }
                            } ?: ""
                        InfoRow(stringResource(R.string.system_label_hermes_version), "$ver$updateBadge")
                    }

                    // Update buttons
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.checkForUpdate(false) },
                            enabled = !state.checkingUpdate,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.checkingUpdate) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.size(spacing.sm))
                            } else {
                                Icon(Icons.Filled.Update, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.size(spacing.xs))
                            }
                            Text(stringResource(R.string.system_action_check_updates), maxLines = 1)
                        }
                        state.updateInfo?.let { info ->
                            if (info.update_available == true && info.can_apply == true) {
                                Button(
                                    onClick = { viewModel.openUpdateConfirm() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.size(spacing.xs))
                                    Text(stringResource(R.string.system_action_update_now), maxLines = 1)
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))

                    // CPU / Memory / Disk stat cards
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        stats.cpu_percent?.let { cpu ->
                            StatCard(
                                label = stringResource(R.string.system_label_cpu),
                                value = "${cpu.toInt()}%",
                                icon = Icons.Filled.Speed,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        stats.memory?.percent?.let { mem ->
                            StatCard(
                                label = stringResource(R.string.system_label_memory),
                                value = "${mem.toInt()}%",
                                icon = Icons.Filled.Memory,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        stats.disk?.percent?.let { disk ->
                            StatCard(
                                label = stringResource(R.string.system_label_disk),
                                value = "${disk.toInt()}%",
                                icon = Icons.Filled.Storage,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    // Memory details
                    stats.memory?.let { mem ->
                        InfoRow(
                            label = stringResource(R.string.system_label_memory),
                            value =
                                stringResource(
                                    R.string.system_label_used_total_percent,
                                    formatBytes(mem.used ?: 0L),
                                    formatBytes(mem.total ?: 0L),
                                    "${(mem.percent ?: 0.0).toInt()}",
                                ),
                        )
                    }

                    // Disk details
                    stats.disk?.let { disk ->
                        InfoRow(
                            label = stringResource(R.string.system_label_disk),
                            value =
                                stringResource(
                                    R.string.system_label_used_total_percent,
                                    formatBytes(disk.used ?: 0L),
                                    formatBytes(disk.total ?: 0L),
                                    "${(disk.percent ?: 0.0).toInt()}",
                                ),
                        )
                    }

                    // Uptime
                    stats.uptime_seconds?.let { secs ->
                        InfoRow(
                            label = stringResource(R.string.system_label_uptime),
                            value = formatDuration(secs),
                        )
                    }

                    // Load avg
                    stats.load_avg?.let { loads ->
                        if (loads.isNotEmpty()) {
                            val coresText =
                                stats.cpu_count?.let {
                                    " ($it ${stringResource(
                                        R.string.system_label_cores,
                                    )})"
                                } ?: ""
                            InfoRow(
                                label = stringResource(R.string.system_label_load_avg),
                                value = loads.joinToString(", ") { "%.2f".format(it) } + coresText,
                            )
                        }
                    } ?: stats.cpu_count?.let { cores ->
                        InfoRow(
                            label = stringResource(R.string.system_label_cpu),
                            value = "$cores ${stringResource(R.string.system_label_cores)}",
                        )
                    }

                    // psutil warning
                    if (stats.psutil == false) {
                        Spacer(modifier = Modifier.height(spacing.sm))
                        StatusBadge(
                            text = stringResource(R.string.system_psutil_warning),
                            status = StatusBadgeType.WARNING,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PortalSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    uriHandler: androidx.compose.ui.platform.UriHandler,
) {
    state.portal?.let { portal ->
        item {
            SectionHeader(title = stringResource(R.string.system_sec_portal))
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
                    // Login status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        val loggedIn = portal.logged_in == true
                        StatusBadge(
                            text =
                                if (loggedIn) {
                                    stringResource(R.string.system_status_logged_in)
                                } else {
                                    stringResource(R.string.system_status_not_logged_in)
                                },
                            status = if (loggedIn) StatusBadgeType.SUCCESS else StatusBadgeType.WARNING,
                        )
                        if (!loggedIn) {
                            Text(
                                text = stringResource(R.string.system_portal_login_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Provider
                    portal.provider?.let { provider ->
                        InfoRow(
                            label = stringResource(R.string.system_portal_provider, provider),
                            value = "",
                        )
                    }

                    // Manage subscription link
                    portal.subscription_url?.let { url ->
                        TextButton(
                            onClick = { uriHandler.openUri(url) },
                            modifier = Modifier.padding(top = spacing.xs),
                        ) {
                            Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(spacing.xs))
                            Text(stringResource(R.string.system_portal_manage_subscription))
                        }
                    }

                    // Feature routing
                    portal.features?.let { features ->
                        if (features.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
                            features.forEach { feature ->
                                InfoRow(
                                    label = feature.label ?: stringResource(R.string.system_portal_feature_routing),
                                    value = feature.state ?: "",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CuratorSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    statusColors: com.m57.hermescontrol.theme.HermesStatusColors,
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
                            ) {
                                if (curator.paused == true) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.size(spacing.xs))
                                    Text(stringResource(R.string.system_curator_resume), maxLines = 1)
                                } else {
                                    Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.size(spacing.xs))
                                    Text(stringResource(R.string.system_curator_pause), maxLines = 1)
                                }
                            }
                            Button(
                                onClick = { viewModel.runCuratorNow() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.size(spacing.xs))
                                Text(stringResource(R.string.system_curator_run_now), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GatewaySection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    statusColors: com.m57.hermescontrol.theme.HermesStatusColors,
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

                    // Version + PID
                    status.version?.let { ver ->
                        val pidText =
                            status.active_sessions?.let { sessions ->
                                "$ver · $sessions session(s)"
                            } ?: ver
                        InfoRow(label = stringResource(R.string.system_label_hermes_version), value = pidText)
                    }

                    // Gateway state with PID (from status version or doctor pid)
                    state.doctorReport?.let { report ->
                        if (report.ok && report.pid != null) {
                            InfoRow(
                                label = stringResource(R.string.system_label_hermes_version),
                                value =
                                    stringResource(
                                        R.string.system_gateway_state_pid,
                                        status.version ?: "",
                                        report.pid,
                                    ),
                            )
                        }
                    }

                    // Auth required
                    status.auth_required?.let { auth ->
                        InfoRow(
                            label = "Auth required",
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
                                    platform.state?.let { platformState ->
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
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.size(spacing.xs))
                                Text(stringResource(R.string.system_gateway_start), maxLines = 1)
                            }
                        }
                        if (isRunning) {
                            Button(
                                onClick = { viewModel.restartGateway() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Filled.RestartAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.size(spacing.xs))
                                Text(stringResource(R.string.system_gateway_restart), maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { viewModel.stopGateway() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Filled.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Spacer(modifier = Modifier.size(spacing.xs))
                                Text(
                                    stringResource(R.string.system_gateway_stop),
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemorySection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    resetTarget: String?,
    onResetRequest: (String) -> Unit,
    viewModel: SystemViewModel,
) {
    state.memory?.let { memory ->
        item {
            SectionHeader(title = stringResource(R.string.system_sec_memory))
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
                    // Active provider
                    memory.active?.let { active ->
                        InfoRow(
                            label = stringResource(R.string.system_memory_external, active),
                            value = "",
                        )
                    } ?: InfoRow(
                        label = stringResource(R.string.system_memory_builtin),
                        value = "",
                    )

                    // Change in Plugins link
                    InfoRow(
                        label = stringResource(R.string.system_memory_change_plugins),
                        value = "",
                    )

                    // Builtin file sizes
                    memory.builtin_files?.let { files ->
                        HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
                        InfoRow(
                            label =
                                stringResource(
                                    R.string.system_memory_builtin_label,
                                    files.memory?.let { formatBytes(it) } ?: "?",
                                    files.user?.let { formatBytes(it) } ?: "?",
                                ),
                            value = "",
                        )

                        // Reset buttons
                        Spacer(modifier = Modifier.height(spacing.sm))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            FilledTonalButton(
                                onClick = { onResetRequest("memory") },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.size(spacing.xs))
                                Text(stringResource(R.string.system_memory_reset_memory), maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = { onResetRequest("user") },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.size(spacing.xs))
                                Text(stringResource(R.string.system_memory_reset_user), maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = { onResetRequest("all") },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Filled.DeleteForever,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.size(spacing.xs))
                                Text(stringResource(R.string.system_memory_reset_all), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialsSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    viewModel: SystemViewModel,
    credToRemove: Pair<String, Int>?,
    onRemoveRequest: (Pair<String, Int>) -> Unit,
) {
    item {
        SectionHeader(
            title = stringResource(R.string.system_sec_credentials),
            trailing = {
                val hasCreds = state.credentials.any { it.entries?.isNotEmpty() == true }
                if (hasCreds) {
                    Text(
                        text = "${state.credentials.sumOf { it.entries?.size ?: 0 }} key(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                // Add credential form
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    OutlinedTextField(
                        value = state.credProvider,
                        onValueChange = viewModel::updateCredProvider,
                        label = { Text(stringResource(R.string.system_credentials_provider)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    OutlinedTextField(
                        value = state.credKey,
                        onValueChange = viewModel::updateCredKey,
                        label = { Text(stringResource(R.string.system_credentials_api_key)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    OutlinedTextField(
                        value = state.credLabel,
                        onValueChange = viewModel::updateCredLabel,
                        label = { Text(stringResource(R.string.system_credentials_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.sm))
                Button(
                    onClick = { viewModel.addCredential() },
                    enabled = !state.addingCred && state.credKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.addingCred) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(spacing.sm))
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                    }
                    Text(stringResource(R.string.system_credentials_add_key))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))

                // Provider list with entries
                if (state.credentials.isEmpty()) {
                    Text(
                        text = stringResource(R.string.system_credentials_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = spacing.sm),
                    )
                }

                state.credentials.forEach { provider ->
                    Text(
                        text = provider.provider ?: "?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = spacing.xs),
                    )
                    provider.entries?.forEach { entry ->
                        CredentialEntryRow(
                            entry = entry,
                            providerName = provider.provider ?: "",
                            onRemove = { onRemoveRequest(Pair(provider.provider ?: "", entry.index ?: 0)) },
                            spacing = spacing,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialEntryRow(
    entry: CredentialPoolEntry,
    providerName: String,
    onRemove: () -> Unit,
    spacing: com.m57.hermescontrol.theme.Spacing,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label ?: "(${stringResource(R.string.system_credentials_api_key)} ${entry.index ?: 0})",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                entry.token_preview?.let { preview ->
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entry.last_status?.let { status ->
                    StatusBadge(
                        text = status,
                        status =
                            when (status.lowercase()) {
                                "ok", "valid", "active" -> StatusBadgeType.SUCCESS
                                "error", "invalid", "expired" -> StatusBadgeType.ERROR
                                else -> StatusBadgeType.NEUTRAL
                            },
                    )
                }
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun OperationsSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    viewModel: SystemViewModel,
    importConfirm: Boolean,
    onImportConfirm: (Boolean) -> Unit,
) {
    item {
        SectionHeader(title = stringResource(R.string.system_sec_operations))
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
                // Operation buttons row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    FilledTonalButton(
                        onClick = {
                            // loadAll already fetches doctor report, just call it
                            viewModel.loadAll()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.HealthAndSafety, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                        Text(stringResource(R.string.system_op_doctor), maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.runSecurityAudit() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                        Text(stringResource(R.string.system_op_security_audit), maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.runUpdateSkills() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                        Text(stringResource(R.string.system_op_update_skills), maxLines = 1)
                    }
                }

                Spacer(modifier = Modifier.height(spacing.sm))

                // Operation buttons row 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.runPromptSize() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.HealthAndSafety, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                        Text(stringResource(R.string.system_op_prompt_size), maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.runDump() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                        Text(stringResource(R.string.system_op_dump), maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.runConfigMigrate() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                        Text(stringResource(R.string.system_op_config_migrate), maxLines = 1)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))

                // Backup section
                Text(
                    text = stringResource(R.string.system_op_backup_create),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(spacing.sm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Button(
                        onClick = { viewModel.triggerBackup() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                        Text(stringResource(R.string.system_op_backup_create), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { viewModel.downloadBackup() },
                        enabled = state.backupArchive != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                        Text(stringResource(R.string.system_op_backup_download), maxLines = 1)
                    }
                }

                // Restore from uploaded zip
                Spacer(modifier = Modifier.height(spacing.sm))
                Text(
                    text = stringResource(R.string.system_op_backup_restore_upload),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(spacing.sm))

                OutlinedTextField(
                    value = state.importPath,
                    onValueChange = viewModel::updateImportPath,
                    label = { Text(stringResource(R.string.system_op_restore_path_label)) },
                    placeholder = { Text(stringResource(R.string.system_op_restore_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(spacing.sm))

                Button(
                    onClick = { onImportConfirm(true) },
                    enabled = state.importPath.isNotBlank(),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(spacing.xs))
                    Text(stringResource(R.string.system_op_restore_upload))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))

                // Debug share section
                SectionHeader(title = stringResource(R.string.system_sec_debug_share))
                Spacer(modifier = Modifier.height(spacing.sm))

                Text(
                    text = stringResource(R.string.system_debug_share_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(spacing.sm))

                androidx.compose.material3.Switch(
                    checked = state.shareRedact,
                    onCheckedChange = { viewModel.toggleShareRedact() },
                )
                Spacer(modifier = Modifier.size(spacing.sm))
                Text(
                    text = stringResource(R.string.system_debug_share_redact),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = spacing.xs),
                )

                Spacer(modifier = Modifier.height(spacing.sm))

                Button(
                    onClick = { viewModel.runDebugShare() },
                    enabled = !state.sharing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.sharing) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(spacing.sm))
                    } else {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                    }
                    Text(
                        if (state.sharing) {
                            stringResource(R.string.system_debug_share_uploading)
                        } else {
                            stringResource(R.string.system_debug_share_generate)
                        },
                    )
                }

                // Debug share results
                state.debugShare?.let { share ->
                    Spacer(modifier = Modifier.height(spacing.sm))
                    val clipboardManager = LocalClipboardManager.current

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        StatusBadge(
                            text =
                                if (share.ok == true) {
                                    stringResource(R.string.system_debug_share_uploaded)
                                } else {
                                    stringResource(R.string.system_status_stopped)
                                },
                            status = if (share.ok == true) StatusBadgeType.SUCCESS else StatusBadgeType.ERROR,
                        )
                        share.redacted?.let { redacted ->
                            StatusBadge(
                                text =
                                    if (redacted) {
                                        stringResource(R.string.system_debug_share_redacted)
                                    } else {
                                        stringResource(R.string.system_debug_share_not_redacted)
                                    },
                                status = StatusBadgeType.INFO,
                            )
                        }
                        share.auto_delete_seconds?.let { secs ->
                            StatusBadge(
                                text = stringResource(R.string.system_debug_share_auto_delete, secs / 3600),
                                status = StatusBadgeType.NEUTRAL,
                            )
                        }
                    }

                    share.urls?.forEach { (name, url) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "$name: $url",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(url))
                            }) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.system_debug_share_copy_all),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckpointsSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    pruneConfirm: Boolean,
    onPruneConfirm: (Boolean) -> Unit,
) {
    state.checkpoints?.let { checkpoints ->
        item {
            SectionHeader(title = stringResource(R.string.system_sec_checkpoints))
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
                    val sessionsCount = checkpoints.sessions?.size ?: 0
                    val totalBytes = checkpoints.total_bytes ?: 0L
                    InfoRow(
                        label = "",
                        value =
                            stringResource(
                                R.string.system_checkpoints_summary,
                                sessionsCount,
                                formatBytes(totalBytes),
                            ),
                    )

                    Spacer(modifier = Modifier.height(spacing.sm))
                    Button(
                        onClick = { onPruneConfirm(true) },
                        enabled = sessionsCount > 0,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                        Text(stringResource(R.string.system_checkpoints_prune))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellHooksSection(
    state: SystemUiState,
    spacing: com.m57.hermescontrol.theme.Spacing,
    viewModel: SystemViewModel,
) {
    val hooks = state.hooks?.hooks ?: emptyList()

    item {
        SectionHeader(
            title = stringResource(R.string.system_sec_hooks),
            trailing = {
                FilledTonalButton(onClick = { viewModel.toggleHookModal() }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(spacing.xs))
                    Text(stringResource(R.string.system_hooks_new))
                }
            },
        )
    }

    if (hooks.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.system_hooks_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            )
        }
    }

    items(hooks, key = { "${it.event}:${it.command}" }) { hook ->
        HookCard(hook = hook, spacing = spacing, onDelete = {
            viewModel.deleteHook(hook.event ?: "", hook.command ?: "")
        })
    }

    // Hook creation modal
    if (state.hookModalOpen) {
        AlertDialog(
            onDismissRequest = { viewModel.toggleHookModal() },
            title = { Text(stringResource(R.string.system_hooks_modal_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OutlinedTextField(
                        value = state.hookEvent,
                        onValueChange = viewModel::updateHookEvent,
                        label = { Text(stringResource(R.string.system_hooks_field_event)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.hookCommand,
                        onValueChange = viewModel::updateHookCommand,
                        label = { Text(stringResource(R.string.system_hooks_field_command)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.hookMatcher,
                        onValueChange = viewModel::updateHookMatcher,
                        label = { Text(stringResource(R.string.system_hooks_field_matcher)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.hookTimeout,
                        onValueChange = viewModel::updateHookTimeout,
                        label = { Text(stringResource(R.string.system_hooks_field_timeout)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(
                            checked = state.hookApprove,
                            onCheckedChange = viewModel::updateHookApprove,
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Text(
                            text = stringResource(R.string.system_hooks_field_approve),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.system_hooks_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.createHook() },
                    enabled = !state.creatingHook && state.hookCommand.isNotBlank(),
                ) {
                    if (state.creatingHook) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(spacing.sm))
                    }
                    Text(stringResource(R.string.system_hooks_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.toggleHookModal() }) {
                    Text(stringResource(R.string.system_confirm_cancel))
                }
            },
        )
    }
}

@Composable
private fun HookCard(
    hook: HookEntry,
    spacing: com.m57.hermescontrol.theme.Spacing,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Icon(
                        Icons.Filled.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = hook.command ?: "?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(
                        text = hook.event ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    hook.matcher?.let { matcher ->
                        Text(
                            text = stringResource(R.string.system_hooks_matcher, matcher),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    StatusBadge(
                        text =
                            if (hook.executable != false) {
                                stringResource(R.string.system_hooks_allowed)
                            } else {
                                stringResource(R.string.system_hooks_not_executable)
                            },
                        status =
                            if (hook.executable != false) StatusBadgeType.SUCCESS else StatusBadgeType.ERROR,
                    )
                    StatusBadge(
                        text =
                            if (hook.allowed == true) {
                                stringResource(R.string.system_hooks_allowed)
                            } else {
                                stringResource(R.string.system_hooks_not_approved)
                            },
                        status =
                            if (hook.allowed == true) StatusBadgeType.SUCCESS else StatusBadgeType.WARNING,
                    )
                    hook.timeout?.let { t ->
                        StatusBadge(
                            text = "${t}s",
                            status = StatusBadgeType.NEUTRAL,
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionLogSection(
    state: SystemUiState,
    viewModel: SystemViewModel,
) {
    val spacing = LocalSpacing.current

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
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (lines.size > 20) {
                                    Text(
                                        text = "... ${lines.size - 20} more line(s)",
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
