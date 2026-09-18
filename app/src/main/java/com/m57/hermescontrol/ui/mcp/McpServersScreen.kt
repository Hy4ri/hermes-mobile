package com.m57.hermescontrol.ui.mcp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.McpCatalogEntry
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.DetailDialog
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.ui.common.toDetailRows
import com.m57.hermescontrol.ui.mcp.components.AddServerSection
import com.m57.hermescontrol.ui.mcp.components.CatalogSection
import com.m57.hermescontrol.ui.mcp.components.McpDialogs
import com.m57.hermescontrol.ui.mcp.components.ServerCard

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
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadServers(forceRefresh = true) },
        actions = {
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
            IconButton(
                onClick = { viewModel.toggleImportDialog() },
            ) {
                Icon(
                    imageVector = Icons.Filled.UploadFile,
                    contentDescription = stringResource(R.string.mcp_servers_action_import_json),
                )
            }
        },
    ) { paddingValues ->
        when {
            state.isLoading && state.servers.isEmpty() -> {
                SkeletonListState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadServers() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    // ── 1. Add server ────────────────────────────────────
                    item(key = "add-header") {
                        AddServerSection(state, viewModel, spacing)
                    }

                    // ── 2. Search ────────────────────────────────────────
                    item(key = "search") {
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = stringResource(R.string.mcp_search_placeholder),
                        )
                    }

                    // ── 3. Server list ───────────────────────────────────
                    if (filteredServers.isEmpty() && query.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                title = stringResource(R.string.mcp_servers_empty_title),
                                subtitle = stringResource(R.string.mcp_servers_empty_desc),
                                actionLabel = stringResource(R.string.empty_action_add_server),
                                onAction = { viewModel.toggleAddForm() },
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

                    // ── 4. Catalog ──────────────────────────────────────
                    item(key = "catalog-header") {
                        CatalogSection(
                            state = state,
                            viewModel = viewModel,
                            spacing = spacing,
                            filteredCatalog = filteredCatalog,
                        )
                    }
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
