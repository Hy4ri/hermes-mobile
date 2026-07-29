package com.m57.hermescontrol.ui.starmap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon

@Composable
fun StarMapScreen(
    onOpenDrawer: () -> Unit,
    viewModel: StarMapViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    HermesScaffold(
        title = { Text(stringResource(R.string.starmap_screen_title)) },
        navigationIcon = NavIcon.Menu(onOpen = onOpenDrawer),
        actions = {
            IconButton(onClick = { viewModel.loadGraph() }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh graph",
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            when {
                uiState.isLoading -> {
                    LoadingState(modifier = Modifier.fillMaxSize())
                }

                uiState.errorMessage != null -> {
                    ErrorState(
                        message = uiState.errorMessage ?: "Failed to load StarMap",
                        onRetry = { viewModel.loadGraph() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                uiState.nodes.isEmpty() -> {
                    EmptyState(
                        title = stringResource(R.string.starmap_empty_title),
                        subtitle = stringResource(R.string.starmap_empty_subtitle),
                        icon = Icons.Filled.AutoAwesome,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    val filteredNodes =
                        uiState.nodes.filter { node ->
                            val matchesKind =
                                when (uiState.filter) {
                                    StarMapFilter.ALL -> true
                                    StarMapFilter.MEMORIES -> node.kind == "memory"
                                    StarMapFilter.SKILLS -> node.kind != "memory"
                                }
                            val matchesSearch =
                                uiState.searchQuery.isBlank() ||
                                    node.label.contains(uiState.searchQuery, ignoreCase = true) ||
                                    node.category.contains(uiState.searchQuery, ignoreCase = true)
                            matchesKind && matchesSearch
                        }

                    Column(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            OutlinedTextField(
                                value = uiState.searchQuery,
                                onValueChange = { viewModel.setSearchQuery(it) },
                                placeholder = { Text(stringResource(R.string.starmap_search_hint)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Search,
                                        contentDescription = null,
                                    )
                                },
                                trailingIcon = {
                                    if (uiState.searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                            Icon(
                                                imageVector = Icons.Filled.Clear,
                                                contentDescription = "Clear search",
                                            )
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                colors =
                                    OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ),
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilterChip(
                                    selected = uiState.filter == StarMapFilter.ALL,
                                    onClick = { viewModel.setFilter(StarMapFilter.ALL) },
                                    label = { Text(stringResource(R.string.starmap_filter_all)) },
                                    colors = FilterChipDefaults.filterChipColors(),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                FilterChip(
                                    selected = uiState.filter == StarMapFilter.MEMORIES,
                                    onClick = { viewModel.setFilter(StarMapFilter.MEMORIES) },
                                    label = { Text(stringResource(R.string.starmap_filter_memories)) },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                FilterChip(
                                    selected = uiState.filter == StarMapFilter.SKILLS,
                                    onClick = { viewModel.setFilter(StarMapFilter.SKILLS) },
                                    label = { Text(stringResource(R.string.starmap_filter_skills)) },
                                )
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            StarMapCanvas(
                                nodes = filteredNodes,
                                edges = uiState.edges,
                                selectedNodeId = uiState.selectedNodeId,
                                onNodeSelected = { nodeId -> viewModel.selectNode(nodeId) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    val selectedNode = uiState.nodes.find { it.id == uiState.selectedNodeId }
                    if (selectedNode != null) {
                        StarMapDetailSheet(
                            node = selectedNode,
                            nodeDetail = uiState.nodeDetail,
                            isLoadingDetail = uiState.isLoadingDetail,
                            allNodes = uiState.nodes,
                            onDismiss = { viewModel.selectNode(null) },
                            onSelectNode = { nodeId -> viewModel.selectNode(nodeId) },
                        )
                    }
                }
            }
        }
    }
}
