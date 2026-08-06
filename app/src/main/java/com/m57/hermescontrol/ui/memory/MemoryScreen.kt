@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.m57.hermescontrol.ui.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.MemoryProviderDetailKey
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.MemoryProviderStatusRow
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.ui.plugins.memoryProviderStatusLabel
import com.m57.hermescontrol.ui.plugins.memoryProviderStatusType

/**
 * Memory management home — owns the memory surface that used to live in the
 * System tab (active provider, builtin files + reset) plus the provider list
 * that drills into per-provider config/setup (issue #783).
 */
@Composable
fun MemoryScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: MemoryViewModel = viewModel { MemoryViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var resetTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    resetTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text(stringResource(R.string.memory_reset_confirm_title)) },
            text = { Text(stringResource(R.string.memory_reset_confirm_desc, target)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetTarget = null
                        viewModel.resetMemory(target)
                    },
                ) {
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

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_memory)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.load() },
        modifier = modifier,
    ) { paddingValues ->
        when {
            state.isLoading && state.memory == null -> {
                SkeletonListState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null && state.memory == null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.load() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            state.memory == null -> {
                EmptyState(
                    title = stringResource(R.string.memory_empty_title),
                    subtitle = stringResource(R.string.memory_empty_desc),
                    onAction = { viewModel.load() },
                    actionLabel = stringResource(R.string.content_desc_refresh),
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                MemoryContent(
                    memory = state.memory!!,
                    resetting = state.resetting,
                    onResetRequest = { resetTarget = it },
                    modifier = Modifier.padding(paddingValues),
                )
            }
        }
    }
}

@Composable
private fun MemoryContent(
    memory: com.m57.hermescontrol.data.model.MemoryResponse,
    resetting: String?,
    onResetRequest: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = listContentPadding,
        verticalArrangement = listItemSpacing,
    ) {
        // Active provider + builtin files
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (memory.active.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.memory_builtin),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.memory_active, memory.active),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    memory.builtin_files?.let { files ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text =
                                stringResource(
                                    R.string.memory_builtin_sizes,
                                    files.memory?.let { formatBytes(it) } ?: "?",
                                    files.user?.let { formatBytes(it) } ?: "?",
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { onResetRequest("memory") },
                            enabled = resetting == null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.memory_reset_memory))
                        }
                        FilledTonalButton(
                            onClick = { onResetRequest("user") },
                            enabled = resetting == null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.memory_reset_user))
                        }
                        FilledTonalButton(
                            onClick = { onResetRequest("all") },
                            enabled = resetting == null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.memory_reset_all))
                        }
                    }
                }
            }
        }

        // Providers
        if (memory.providers.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.memory_providers_heading).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
                )
            }
            items(memory.providers, key = { it.name }) { provider ->
                MemoryProviderRow(
                    provider = provider,
                    isActive = provider.name == memory.active,
                    onClick = {
                        NavigationController.navigateTo(
                            MemoryProviderDetailKey(
                                name = provider.name,
                                label = provider.name,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun MemoryProviderRow(
    provider: MemoryProviderStatusRow,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (isActive) {
                    StatusBadge(
                        text = stringResource(R.string.memory_provider_active),
                        status = StatusBadgeType.SUCCESS,
                    )
                }
                StatusBadge(
                    text = memoryProviderStatusLabel(provider.status),
                    status = memoryProviderStatusType(provider.status),
                )
            }
            if (provider.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = provider.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
