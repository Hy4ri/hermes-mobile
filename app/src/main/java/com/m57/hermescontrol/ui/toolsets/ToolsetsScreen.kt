package com.m57.hermescontrol.ui.toolsets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
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
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ToolsetDetailKey
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.ManageableFilterRow
import com.m57.hermescontrol.ui.common.ManageableItemCard
import com.m57.hermescontrol.ui.common.ManageableToggle
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusFilter
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsetsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ToolsetsViewModel = viewModel { ToolsetsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dataScope by AuthManager.dataScopeFlow.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(StatusFilter.ALL) }

    LaunchedEffect(dataScope) {
        viewModel.clearScopeOwnedState()
        viewModel.loadToolsets()
    }

    val filteredToolsets =
        remember(query, statusFilter, state.toolsets) {
            state.toolsets.filter { toolset ->
                statusFilter.matches(toolset.enabled) &&
                    (
                        toolset.name.contains(query, ignoreCase = true) ||
                            toolset.label?.contains(query, ignoreCase = true) == true ||
                            toolset.description?.contains(query, ignoreCase = true) == true
                    )
            }
        }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_toolsets)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadToolsets(forceRefresh = true) },
    ) { paddingValues ->
        when {
            state.isLoading && state.toolsets.isEmpty() -> {
                SkeletonListState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadToolsets() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            state.toolsets.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.toolsets_empty_title),
                    subtitle = stringResource(R.string.toolsets_empty_desc),
                    onAction = { viewModel.loadToolsets() },
                    actionLabel = stringResource(R.string.content_desc_refresh),
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                Box(Modifier.fillMaxSize()) {
                    if (state.isLoading && state.toolsets.isEmpty()) {
                        CircularProgressIndicator()
                    } else if (state.errorMessage != null && state.toolsets.isEmpty()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(text = state.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(16.dp))
                            IconButton(onClick = { viewModel.loadToolsets() }) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.action_retry),
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = listContentPadding,
                            verticalArrangement = listItemSpacing,
                        ) {
                            item {
                                ManageableFilterRow(
                                    query = query,
                                    onQueryChange = { query = it },
                                    placeholder = stringResource(R.string.toolsets_search_placeholder),
                                    status = statusFilter,
                                    onStatusChange = { statusFilter = it },
                                )
                            }
                            items(filteredToolsets, key = { it.name }) { toolset ->
                                ManageableItemCard(
                                    title = toolset.label ?: toolset.name.replaceFirstChar { it.uppercase() },
                                    toggle =
                                        ManageableToggle(
                                            checked = toolset.enabled,
                                            onCheckedChange = { viewModel.toggleToolset(toolset) },
                                        ),
                                    onClick = {
                                        NavigationController.navigateTo(
                                            ToolsetDetailKey(
                                                name = toolset.name,
                                                label = toolset.label,
                                            ),
                                        )
                                    },
                                    body = {
                                        toolset.description?.let {
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        toolset.tools?.let { tools ->
                                            if (tools.isNotEmpty()) {
                                                // Single-line chip strip — toolsets can carry a dozen+
                                                // tools, and a wrapping FlowRow stacks chips vertically
                                                // (huge cards on large font scales). Scroll instead.
                                                Row(
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    tools.forEach { tool ->
                                                        SuggestionChip(
                                                            onClick = {},
                                                            label = {
                                                                Text(
                                                                    text = tool,
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    maxLines = 1,
                                                                )
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
