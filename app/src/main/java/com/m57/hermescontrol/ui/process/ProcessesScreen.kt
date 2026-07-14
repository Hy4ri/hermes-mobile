package com.m57.hermescontrol.ui.process

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ProcessInfo
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

@Composable
fun ProcessesScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ProcessesViewModel = viewModel { ProcessesViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var killTarget by remember { mutableStateOf<ProcessInfo?>(null) }

    LaunchedEffect(Unit) {
        if (state.sessionId != null) viewModel.load()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_processes)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.load() },
        modifier = modifier,
    ) {
        when {
            state.sessionId == null -> {
                EmptyState(
                    title = stringResource(R.string.processes_no_session_title),
                    subtitle = stringResource(R.string.processes_no_session_desc),
                    icon = Icons.Filled.Memory,
                )
            }

            state.isLoading && state.processes.isEmpty() -> {
                LoadingState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: stringResource(R.string.error_unknown),
                    onRetry = { viewModel.load() },
                )
            }

            state.processes.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.processes_empty_title),
                    subtitle = stringResource(R.string.processes_empty_desc),
                    icon = Icons.Filled.Memory,
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    items(state.processes, key = { it.sessionId }) { process ->
                        ProcessCard(
                            process = process,
                            isKilling = state.killingId == process.sessionId,
                            onKill = { killTarget = process },
                        )
                    }
                }
            }
        }
    }

    killTarget?.let { process ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text(stringResource(R.string.processes_kill_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.processes_kill_desc,
                        process.title,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.kill(process.sessionId)
                        killTarget = null
                    },
                ) { Text(stringResource(R.string.processes_kill_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { killTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ProcessCard(
    process: ProcessInfo,
    isKilling: Boolean,
    onKill: () -> Unit,
) {
    val statusColors = LocalHermesStatusColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = process.statusIcon(),
                contentDescription = null,
                tint = process.statusColor(statusColors),
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = process.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = process.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (process.isRunning) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.processes_running_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColors.success,
                    )
                }
            }
            if (process.isRunning) {
                Spacer(modifier = Modifier.width(8.dp))
                if (isKilling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(
                        onClick = onKill,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.processes_kill_cd),
                            tint = statusColors.error,
                        )
                    }
                }
            }
        }
    }
}

private val ProcessInfo.subtitle: String
    get() {
        val parts = mutableListOf<String>()
        pid?.let { parts += "pid $it" }
        uptimeSeconds?.let { parts += formatUptime(it) }
        if (!isRunning) {
            exitCode?.let { parts += "exit $it" }
        }
        cwd?.let { parts += it }
        return parts.joinToString(" · ")
    }

private fun formatUptime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return when {
        m <= 0 -> {
            "${s}s"
        }

        m < 60 -> {
            "${m}m ${s}s"
        }

        else -> {
            val h = m / 60
            "${h}h ${m % 60}m"
        }
    }
}

private fun ProcessInfo.statusIcon(): ImageVector = if (isRunning) Icons.Filled.PlayArrow else Icons.Filled.Memory

@Composable
private fun ProcessInfo.statusColor(statusColors: com.m57.hermescontrol.theme.HermesStatusColors): Color =
    if (isRunning) statusColors.success else statusColors.info
