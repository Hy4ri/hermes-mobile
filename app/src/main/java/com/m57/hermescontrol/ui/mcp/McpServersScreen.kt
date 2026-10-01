package com.m57.hermescontrol.ui.mcp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.ui.mcp.components.AddServerSection
import com.m57.hermescontrol.ui.mcp.components.CatalogView
import com.m57.hermescontrol.ui.mcp.components.McpDialogs
import com.m57.hermescontrol.ui.mcp.components.ServerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: McpServersViewModel = viewModel { McpServersViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dataScope by AuthManager.dataScopeFlow.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var query by remember { mutableStateOf("") }
    var showDetail by remember { mutableStateOf<McpServer?>(null) }

    val filteredServers =
        remember(query, state.servers) {
            state.servers.filter { server ->
                server.name.contains(query, ignoreCase = true) ||
                    server.command?.contains(query, ignoreCase = true) == true ||
                    server.transport?.contains(query, ignoreCase = true) == true
            }
        }

    val filteredCatalog =
        remember(state.catalogQuery, state.catalogEntries) {
            val q = state.catalogQuery.trim().lowercase()
            if (q.isEmpty()) {
                state.catalogEntries
            } else {
                state.catalogEntries.filter {
                    it.name.lowercase().contains(q) ||
                        it.description?.lowercase()?.contains(q) == true
                }
            }
        }

    LaunchedEffect(dataScope) {
        viewModel.clearScopeOwnedState()
        viewModel.loadServers()
    }
    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_mcp_servers)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = if (state.selectedTab == McpTab.INSTALLED) state.isLoading else state.catalogLoading,
        onRefresh = {
            if (state.selectedTab == McpTab.INSTALLED) {
                viewModel.loadServers(forceRefresh = true)
            } else {
                viewModel.loadCatalog()
            }
        },
        actions = {
            if (state.selectedTab == McpTab.INSTALLED) {
                IconButton(onClick = { viewModel.toggleAddForm() }) {
                    Icon(
                        imageVector = if (state.showAddForm) Icons.Filled.Close else Icons.Filled.Add,
                        contentDescription = stringResource(R.string.mcp_servers_add_server),
                    )
                }
                IconButton(onClick = { viewModel.toggleImportDialog() }) {
                    Icon(
                        imageVector = Icons.Filled.UploadFile,
                        contentDescription = stringResource(R.string.mcp_servers_action_import_json),
                    )
                }
                IconButton(
                    onClick = { viewModel.testAllServers() },
                    enabled = !state.isTestingAll && state.servers.any { it.enabled },
                ) {
                    if (state.isTestingAll) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Science,
                            contentDescription = stringResource(R.string.mcp_servers_action_test_all),
                        )
                    }
                }
            }
        },
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SegmentedButton(
                    selected = state.selectedTab == McpTab.INSTALLED,
                    onClick = { viewModel.setTab(McpTab.INSTALLED) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.mcp_tab_installed))
                }
                SegmentedButton(
                    selected = state.selectedTab == McpTab.CATALOG,
                    onClick = { viewModel.setTab(McpTab.CATALOG) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.mcp_tab_catalog))
                }
            }

            when (state.selectedTab) {
                McpTab.INSTALLED -> {
                    when {
                        state.isLoading && state.servers.isEmpty() -> {
                            SkeletonListState()
                        }

                        state.errorMessage != null && state.servers.isEmpty() -> {
                            ErrorState(
                                message = state.errorMessage ?: "",
                                onRetry = { viewModel.loadServers() },
                            )
                        }

                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = listContentPadding,
                                verticalArrangement = listItemSpacing,
                            ) {
                                item(key = "add-form") {
                                    AddServerSection(state, viewModel, spacing)
                                }

                                if (state.servers.isNotEmpty()) {
                                    item(key = "search") {
                                        SearchBar(
                                            query = query,
                                            onQueryChange = { query = it },
                                            placeholder = stringResource(R.string.mcp_search_placeholder),
                                        )
                                    }
                                }

                                if (state.servers.isEmpty()) {
                                    item(key = "empty") {
                                        EmptyState(
                                            title = stringResource(R.string.mcp_servers_empty_title),
                                            subtitle = stringResource(R.string.mcp_servers_empty_desc),
                                            actionLabel = stringResource(R.string.mcp_tab_catalog),
                                            onAction = { viewModel.setTab(McpTab.CATALOG) },
                                            modifier = Modifier.height(320.dp),
                                        )
                                    }
                                } else if (filteredServers.isEmpty()) {
                                    item(key = "no-match") {
                                        Text(
                                            text = stringResource(R.string.mcp_no_match, query),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(spacing.md),
                                        )
                                    }
                                }

                                items(filteredServers, key = { "server:${it.name}" }) { server ->
                                    ServerCard(
                                        server = server,
                                        state = state,
                                        viewModel = viewModel,
                                        spacing = spacing,
                                        onClick = { showDetail = server },
                                        onOpenBrowser = { url ->
                                            try {
                                                context.startActivity(
                                                    android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(url),
                                                    ),
                                                )
                                            } catch (_: Exception) {
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                McpTab.CATALOG -> {
                    CatalogView(
                        state = state,
                        viewModel = viewModel,
                        spacing = spacing,
                        filteredCatalog = filteredCatalog,
                    )
                }
            }
        }
    }

    McpDialogs(
        state = state,
        viewModel = viewModel,
        spacing = spacing,
        selectedServerDetail = showDetail,
        onDismissDetail = { showDetail = null },
        context = context,
        clipboardManager = clipboardManager,
    )
}
