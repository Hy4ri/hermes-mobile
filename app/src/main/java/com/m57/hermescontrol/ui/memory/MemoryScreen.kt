@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.m57.hermescontrol.ui.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.MemoryProviderDetailKey
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.LearningGraphResponse
import com.m57.hermescontrol.data.model.MemoryProviderStatusRow
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatCard
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
                SkeletonListState(modifier = Modifier)
            }

            state.errorMessage != null && state.memory == null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.load() },
                    modifier = Modifier,
                )
            }

            state.memory == null -> {
                EmptyState(
                    title = stringResource(R.string.memory_empty_title),
                    subtitle = stringResource(R.string.memory_empty_desc),
                    onAction = { viewModel.load() },
                    actionLabel = stringResource(R.string.content_desc_refresh),
                    modifier = Modifier,
                )
            }

            else -> {
                MemoryContent(
                    memory = state.memory!!,
                    learningGraph = state.learningGraph,
                    resetting = state.resetting,
                    onResetRequest = { resetTarget = it },
                    modifier = Modifier,
                )
            }
        }
    }
}

@Composable
private fun MemoryContent(
    memory: com.m57.hermescontrol.data.model.MemoryResponse,
    learningGraph: LearningGraphResponse?,
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { onResetRequest("memory") },
                            enabled = resetting == null,
                        ) {
                            Text(
                                text = stringResource(R.string.memory_reset_memory),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        FilledTonalButton(
                            onClick = { onResetRequest("user") },
                            enabled = resetting == null,
                        ) {
                            Text(
                                text = stringResource(R.string.memory_reset_user),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        FilledTonalButton(
                            onClick = { onResetRequest("all") },
                            enabled = resetting == null,
                        ) {
                            Text(
                                text = stringResource(R.string.memory_reset_all),
                                maxLines = 1,
                                softWrap = false,
                            )
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
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
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

        // Self-Improvement & learning activity (moved from System tab)
        item {
            SelfImprovementSection(graph = learningGraph)
        }
    }
}

@Composable
private fun SelfImprovementSection(graph: LearningGraphResponse?) {
    SectionHeader(title = stringResource(R.string.memory_sec_self_improvement))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.memory_self_improvement_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (graph == null || graph.nodes.isEmpty()) {
                Text(
                    text = stringResource(R.string.memory_self_improvement_no_activity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            } else {
                val skillNodes = graph.nodes.filter { it.kind == "skill" }
                val memoryNodes = graph.nodes.filter { it.kind == "memory" }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatCard(
                        label = "Learned Skills",
                        value = "${skillNodes.size}",
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "Memories & Facts",
                        value = "${memoryNodes.size}",
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Recent Agent Self-Improvement Events",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))

                val sortedNodes =
                    graph.nodes
                        .filter { it.timestamp != null || it.kind == "skill" }
                        .sortedByDescending { it.timestamp ?: 0L }
                        .take(8)

                sortedNodes.forEach { node ->
                    Surface(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val icon = if (node.kind == "skill") "🛠" else "🧠"
                            Text(text = icon, fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = node.label,
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val cat = node.category ?: node.kind
                                val creator = node.createdBy?.let { " • by $it" } ?: ""
                                Text(
                                    text = "[$cat]$creator",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            node.useCount.takeIf { it > 0 }?.let { uses ->
                                StatusBadge(
                                    text = "$uses uses",
                                    status = StatusBadgeType.INFO,
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
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
