package com.m57.hermescontrol.ui.toolsets

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ToolsetDetailKey
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SkeletonListState
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

    var query by remember { mutableStateOf("") }

    val filteredToolsets =
        remember(query, state.toolsets) {
            state.toolsets.filter { toolset ->
                toolset.name.contains(query, ignoreCase = true) ||
                    toolset.label?.contains(query, ignoreCase = true) == true ||
                    toolset.description?.contains(query, ignoreCase = true) == true
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadToolsets()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_toolsets)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadToolsets() },
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
                                SearchBar(
                                    query = query,
                                    onQueryChange = { query = it },
                                    placeholder = stringResource(R.string.toolsets_search_placeholder),
                                )
                            }
                            items(filteredToolsets, key = { it.name }) { toolset ->
                                Card(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable(
                                                onClick = {
                                                    NavigationController.navigateTo(
                                                        ToolsetDetailKey(
                                                            name = toolset.name,
                                                            label = toolset.label,
                                                        ),
                                                    )
                                                },
                                            ),
                                    colors =
                                        CardDefaults.cardColors(
                                            containerColor =
                                                if (toolset.enabled) {
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                },
                                        ),
                                ) {
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text =
                                                    toolset.label
                                                        ?: toolset.name.replaceFirstChar { it.uppercase() },
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            toolset.description?.let {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = it,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }

                                            toolset.tools?.let { tools ->
                                                if (tools.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.height(8.dp))
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
                                        }

                                        Switch(
                                            checked = toolset.enabled,
                                            onCheckedChange = { viewModel.toggleToolset(toolset) },
                                            modifier = Modifier.padding(start = 16.dp),
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
}
