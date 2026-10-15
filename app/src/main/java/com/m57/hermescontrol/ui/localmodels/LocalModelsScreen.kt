package com.m57.hermescontrol.ui.localmodels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.LocalModelJob
import com.m57.hermescontrol.data.model.LocalModelsHardware
import com.m57.hermescontrol.data.model.LocalModelsStatus
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.InfoRow
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.util.formatBytes
import com.m57.hermescontrol.util.formatDuration

@Composable
fun LocalModelsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: LocalModelsViewModel = viewModel { LocalModelsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dataScope by AuthManager.dataScopeFlow.collectAsStateWithLifecycle()

    LaunchedEffect(dataScope) {
        viewModel.clearScopeOwnedState()
        viewModel.load()
    }
    LifecycleStartEffect(viewModel) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }
    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    val status = state.status
    val activeJobs = state.jobs.filter { it.isActive }
    val recentJobs = state.jobs.filterNot { it.isActive }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_local_models)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = viewModel::load,
    ) {
        when {
            state.isLoading && status == null -> {
                SkeletonListState()
            }

            state.unavailable -> {
                EmptyState(
                    title = stringResource(R.string.local_models_unavailable_title),
                    subtitle = stringResource(R.string.local_models_unavailable_desc),
                    icon = Icons.Filled.Memory,
                )
            }

            state.errorMessage != null && status == null -> {
                ErrorState(message = state.errorMessage.orEmpty(), onRetry = viewModel::load)
            }

            status != null -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    item { SectionHeader(stringResource(R.string.local_models_section_engine)) }
                    item { EngineCard(status) }

                    state.hardware?.let { hw ->
                        item { SectionHeader(stringResource(R.string.local_models_section_hardware)) }
                        item { HardwareCard(hw) }
                    }

                    item { SectionHeader(stringResource(R.string.local_models_section_downloads)) }
                    if (activeJobs.isEmpty()) {
                        item { MutedCard(stringResource(R.string.local_models_no_downloads)) }
                    } else {
                        items(activeJobs, key = { it.jobId }) { job ->
                            JobCard(
                                job = job,
                                busy = job.jobId in state.busyJobIds,
                                onPause = { viewModel.pause(job) },
                                onResume = { viewModel.resume(job) },
                            )
                        }
                    }

                    item { SectionHeader(stringResource(R.string.local_models_section_models)) }
                    if (status.models.isEmpty()) {
                        item { MutedCard(stringResource(R.string.local_models_no_models)) }
                    } else {
                        items(status.models, key = { it.id }) { model ->
                            ModelRow(
                                name = model.id,
                                size = formatBytes(model.sizeBytes),
                                loadedState = status.loadedModels[model.id],
                                isActive = model.id == status.activeModelId,
                            )
                        }
                    }

                    if (recentJobs.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.local_models_section_recent)) }
                        items(recentJobs, key = { it.jobId }) { job ->
                            JobCard(job = job, busy = false, onPause = {}, onResume = {})
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SurfaceCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { content() }
}

@Composable
private fun MutedCard(text: String) {
    val spacing = LocalSpacing.current
    SurfaceCard {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(spacing.md),
        )
    }
}

@Composable
private fun EngineCard(status: LocalModelsStatus) {
    val spacing = LocalSpacing.current
    val (label, type) =
        when {
            !status.runtimeInstalled -> {
                stringResource(R.string.local_models_engine_not_installed) to
                    StatusBadgeType.WARNING
            }

            status.serverRunning -> {
                stringResource(R.string.local_models_engine_running) to StatusBadgeType.SUCCESS
            }

            else -> {
                stringResource(R.string.local_models_engine_stopped) to StatusBadgeType.NEUTRAL
            }
        }
    SurfaceCard {
        Column(modifier = Modifier.padding(vertical = spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.local_models_engine_llamacpp),
                    style = MaterialTheme.typography.titleMedium,
                )
                StatusBadge(text = label, status = type)
            }
            status.runtimeBackend?.let { InfoRow(stringResource(R.string.local_models_backend), it) }
            status.tag?.let { InfoRow(stringResource(R.string.local_models_version), it) }
            status.activeModelId?.let { InfoRow(stringResource(R.string.local_models_active_model), it) }
            if (status.updateAvailable) {
                Row(modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs)) {
                    StatusBadge(stringResource(R.string.local_models_update_available), StatusBadgeType.INFO)
                }
            }
        }
    }
}

@Composable
private fun HardwareCard(hw: LocalModelsHardware) {
    val spacing = LocalSpacing.current
    SurfaceCard {
        Column(modifier = Modifier.padding(vertical = spacing.sm)) {
            hw.gpuName?.let { InfoRow(stringResource(R.string.local_models_gpu), it) }
            if (hw.vramTotalBytes > 0) {
                val vram =
                    if (hw.vramUsedBytes != null) {
                        "${formatBytes(hw.vramUsedBytes)} / ${formatBytes(hw.vramTotalBytes)}"
                    } else {
                        formatBytes(hw.vramTotalBytes)
                    }
                InfoRow(
                    stringResource(if (hw.uma) R.string.local_models_unified_memory else R.string.local_models_vram),
                    vram,
                )
                InfoRow(stringResource(R.string.local_models_vram_usable), formatBytes(hw.vramUsableBytes))
            }
            hw.gpuUtilPercent?.let { InfoRow(stringResource(R.string.local_models_gpu_util), "$it%") }
            InfoRow(
                stringResource(R.string.local_models_ram),
                "${formatBytes(hw.ramAvailableBytes)} / ${formatBytes(hw.ramTotalBytes)}",
            )
        }
    }
}

@Composable
private fun ModelRow(
    name: String,
    size: String,
    loadedState: String?,
    isActive: Boolean,
) {
    val spacing = LocalSpacing.current
    SurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    size,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isActive) StatusBadge(stringResource(R.string.local_models_badge_default), StatusBadgeType.INFO)
            when (loadedState) {
                null -> {}

                "loading" -> {
                    StatusBadge(stringResource(R.string.local_models_badge_loading), StatusBadgeType.WARNING)
                }

                else -> {
                    StatusBadge(stringResource(R.string.local_models_badge_loaded), StatusBadgeType.SUCCESS)
                }
            }
        }
    }
}

@Composable
private fun JobCard(
    job: LocalModelJob,
    busy: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val (label, type) =
        when (job.status) {
            "running" -> stringResource(R.string.local_models_job_running) to StatusBadgeType.INFO
            "paused" -> stringResource(R.string.local_models_job_paused) to StatusBadgeType.WARNING
            "done" -> stringResource(R.string.local_models_job_done) to StatusBadgeType.SUCCESS
            else -> stringResource(R.string.local_models_job_failed) to StatusBadgeType.ERROR
        }
    val total = job.totalBytes?.takeIf { it > 0 }
    val progress = if (total != null) (job.doneBytes.toFloat() / total).coerceIn(0f, 1f) else null
    SurfaceCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        job.target,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = job.error?.takeIf { job.status == "error" } ?: job.detail.ifBlank { job.phase }
                    if (sub.isNotBlank()) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                StatusBadge(label, type)
                if (job.status == "running" && job.canPause) {
                    IconButton(onClick = onPause, enabled = !busy) {
                        Icon(
                            Icons.Filled.Pause,
                            contentDescription = stringResource(R.string.local_models_action_pause),
                        )
                    }
                }
                if (job.canResume) {
                    IconButton(onClick = onResume, enabled = !busy) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.local_models_action_resume),
                        )
                    }
                }
            }
            if (job.isActive) {
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else if (job.status == "running") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (total != null) {
                    val speed =
                        job.bytesPerSec
                            ?.takeIf { it > 0 }
                            ?.let { " · ${formatBytes(it.toLong())}/s" }
                            .orEmpty()
                    val eta =
                        job.etaSeconds
                            ?.takeIf { it > 0 }
                            ?.let { " · ${formatDuration(it)}" }
                            .orEmpty()
                    Text(
                        text = "${formatBytes(job.doneBytes)} / ${formatBytes(total)}$speed$eta",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
