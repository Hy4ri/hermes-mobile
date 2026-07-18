package com.m57.hermescontrol.ui.mcp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.McpCatalogEntry
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.DetailDialog
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.ui.common.toDetailRows

@Composable
fun McpServersScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: McpServersViewModel = viewModel { McpServersViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
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

    LaunchedEffect(Unit) { viewModel.loadServers() }
    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_mcp_servers)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadServers() },
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
                            placeholder = "Search MCP servers...",
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
                                text = "No servers match \"$query\"",
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

    showDetail?.let { server ->
        DetailDialog(
            title = server.name,
            rows = server.toDetailRows(),
            onDismiss = { showDetail = null },
        )
    }
}

// ── Sections ──────────────────────────────────────────────────────

@Composable
private fun AddServerSection(
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: com.m57.hermescontrol.theme.Spacing,
) {
    SectionHeader(
        title = stringResource(R.string.mcp_servers_add_server),
        trailing = {
            TextButton(onClick = { viewModel.toggleAddForm() }) {
                Text(if (state.showAddForm) "Hide" else "New")
            }
        },
    )

    AnimatedVisibility(visible = state.showAddForm) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.padding(spacing.md)) {
                SecondaryTabRow(selectedTabIndex = if (state.addMode == AddServerMode.HTTP) 0 else 1) {
                    Tab(
                        selected = state.addMode == AddServerMode.HTTP,
                        onClick = { viewModel.setAddMode(AddServerMode.HTTP) },
                        text = { Text(stringResource(R.string.mcp_servers_add_http)) },
                    )
                    Tab(
                        selected = state.addMode == AddServerMode.Stdio,
                        onClick = { viewModel.setAddMode(AddServerMode.Stdio) },
                        text = { Text(stringResource(R.string.mcp_servers_add_stdio)) },
                    )
                }

                Spacer(modifier = Modifier.height(spacing.sm))

                OutlinedTextField(
                    value = state.addServerName,
                    onValueChange = viewModel::updateAddServerName,
                    label = { Text(stringResource(R.string.mcp_servers_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(spacing.sm))

                if (state.addMode == AddServerMode.HTTP) {
                    OutlinedTextField(
                        value = state.addServerUrl,
                        onValueChange = viewModel::updateAddServerUrl,
                        label = { Text(stringResource(R.string.mcp_servers_field_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Text(
                        text = stringResource(R.string.mcp_servers_auth_mode),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(spacing.xs))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        FilterChip(
                            selected = state.addServerAuth == "none",
                            onClick = { viewModel.updateAddServerAuth("none") },
                            label = { Text(stringResource(R.string.mcp_servers_auth_none)) },
                        )
                        FilterChip(
                            selected = state.addServerAuth == "header",
                            onClick = { viewModel.updateAddServerAuth("header") },
                            label = { Text(stringResource(R.string.mcp_servers_auth_header)) },
                        )
                        FilterChip(
                            selected = state.addServerAuth == "oauth",
                            onClick = { viewModel.updateAddServerAuth("oauth") },
                            label = { Text(stringResource(R.string.mcp_servers_auth_oauth)) },
                        )
                    }
                    if (state.addServerAuth == "header") {
                        Spacer(modifier = Modifier.height(spacing.sm))
                        OutlinedTextField(
                            value = state.addServerBearerToken,
                            onValueChange = viewModel::updateAddServerBearerToken,
                            label = { Text(stringResource(R.string.mcp_servers_field_bearer_token)) },
                            singleLine = true,
                            visualTransformation = remember { PasswordVisualTransformation() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = state.addServerCommand,
                        onValueChange = viewModel::updateAddServerCommand,
                        label = { Text(stringResource(R.string.mcp_servers_field_command)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(spacing.sm))
                    OutlinedTextField(
                        value = state.addServerArgs,
                        onValueChange = viewModel::updateAddServerArgs,
                        label = { Text(stringResource(R.string.mcp_servers_field_args)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(spacing.md))
                Button(
                    onClick = { viewModel.submitAddServer() },
                    enabled = !state.addingServer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.addingServer) {
                            stringResource(R.string.mcp_servers_action_adding)
                        } else {
                            stringResource(R.string.mcp_servers_action_submit)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: McpServer,
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: com.m57.hermescontrol.theme.Spacing,
    onClick: () -> Unit,
) {
    var showEnv by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (server.enabled) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.md)) {
            // Header row: name + status + toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = server.name.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.width(spacing.sm))
                        ServerStatusBadge(server.status)
                    }
                    Text(
                        text = stringResource(R.string.mcp_servers_label_transport, server.transport ?: "stdio"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = { viewModel.toggleServer(server) },
                )
            }

            // URL or Command
            if (server.url != null) {
                Spacer(modifier = Modifier.height(spacing.sm))
                Text(
                    text = server.url,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            } else if (server.command != null) {
                Spacer(modifier = Modifier.height(spacing.sm))
                Text(
                    text = stringResource(R.string.mcp_servers_label_command),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${server.command} ${server.args.orEmpty().joinToString(" ")}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }

            // Error diagnostics
            server.error?.let { error ->
                if (error.isNotBlank()) {
                    Spacer(modifier = Modifier.height(spacing.sm))
                    StatusBadge(
                        text = error,
                        status = StatusBadgeType.ERROR,
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.sm))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                FilledTonalButton(
                    onClick = { viewModel.testServer(server.name) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(spacing.xs))
                    Text(stringResource(R.string.mcp_servers_action_test), maxLines = 1)
                }
                FilledTonalButton(
                    onClick = { viewModel.restartServer(server.name) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(spacing.xs))
                    Text(stringResource(R.string.mcp_servers_action_restart), maxLines = 1)
                }
                IconButton(onClick = { viewModel.deleteServer(server.name) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                }
            }

            // Env vars toggle
            TextButton(
                onClick = { showEnv = !showEnv },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(spacing.xs))
                Text(stringResource(R.string.mcp_servers_env_vars))
            }

            AnimatedVisibility(visible = showEnv) {
                EnvVarSection(server = server, state = state, viewModel = viewModel, spacing = spacing)
            }
        }
    }
}

@Composable
private fun EnvVarSection(
    server: McpServer,
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: com.m57.hermescontrol.theme.Spacing,
) {
    val isEditing = state.editingEnvFor == server.name
    val env = server.env ?: emptyMap()

    if (env.isEmpty() && !isEditing) {
        Text(
            text = stringResource(R.string.mcp_servers_env_no_vars),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = spacing.xs),
        )
    } else {
        env.forEach { (key, value) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = key, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (value.length > 30) "${value.take(15)}…${value.takeLast(10)}" else value,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.removeEnvVar(server.name, key) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    if (isEditing) {
        HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.envKeyInput,
                onValueChange = viewModel::updateEnvKey,
                label = { Text(stringResource(R.string.mcp_servers_env_key)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.envValueInput,
                onValueChange = viewModel::updateEnvValue,
                label = { Text(stringResource(R.string.mcp_servers_env_value)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Button(onClick = { viewModel.addEnvVar(server.name) }) {
                Text(stringResource(R.string.mcp_servers_env_add))
            }
            OutlinedButton(onClick = { viewModel.stopEditingEnv() }) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    } else {
        TextButton(onClick = { viewModel.startEditingEnv(server) }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(spacing.xs))
            Text(stringResource(R.string.mcp_servers_env_add))
        }
    }
}

@Composable
private fun ServerStatusBadge(status: String?) {
    if (status == null) return
    val badgeType =
        when (status.lowercase()) {
            "running", "ok", "connected" -> StatusBadgeType.SUCCESS
            "error", "failed" -> StatusBadgeType.ERROR
            "stopped" -> StatusBadgeType.NEUTRAL
            else -> StatusBadgeType.NEUTRAL
        }
    StatusBadge(text = status, status = badgeType)
}

// ── Catalog ─────────────────────────────────────────────────────

@Composable
private fun CatalogSection(
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: com.m57.hermescontrol.theme.Spacing,
    filteredCatalog: List<McpCatalogEntry>,
) {
    val catalogExpanded = remember { mutableStateOf(false) }

    SectionHeader(
        title = stringResource(R.string.mcp_servers_section_catalog),
        trailing = {
            TextButton(onClick = {
                catalogExpanded.value = !catalogExpanded.value
                if (catalogExpanded.value && state.catalogEntries.isEmpty() && !state.catalogLoading) {
                    viewModel.loadCatalog()
                }
            }) {
                Text(if (catalogExpanded.value) "Hide" else "Browse")
            }
        },
    )

    AnimatedVisibility(visible = catalogExpanded.value) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Search
            OutlinedTextField(
                value = state.catalogQuery,
                onValueChange = viewModel::updateCatalogQuery,
                label = { Text(stringResource(R.string.mcp_servers_catalog_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = spacing.sm),
            )

            // Loading / error / content
            when {
                state.catalogLoading -> {
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.height(spacing.sm))
                }

                state.catalogError != null -> {
                    ErrorState(
                        message = state.catalogError,
                        onRetry = { viewModel.loadCatalog() },
                    )
                }

                filteredCatalog.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.mcp_servers_catalog_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = spacing.md),
                    )
                }

                else -> {
                    filteredCatalog.forEach { entry ->
                        CatalogEntryCard(
                            entry = entry,
                            state = state,
                            viewModel = viewModel,
                            spacing = spacing,
                        )
                        Spacer(modifier = Modifier.height(spacing.sm))
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogEntryCard(
    entry: McpCatalogEntry,
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: com.m57.hermescontrol.theme.Spacing,
) {
    var showInstallForm by remember { mutableStateOf(false) }
    val isInstalling = state.installingCatalogEntry == entry.name

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    entry.source?.let {
                        Text(
                            text = stringResource(R.string.mcp_servers_catalog_source, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    onClick = {
                        if (entry.env?.isNotEmpty() == true) {
                            showInstallForm = !showInstallForm
                        } else {
                            viewModel.installCatalogEntry(entry)
                        }
                    },
                    enabled = !isInstalling,
                ) {
                    Text(
                        if (isInstalling) {
                            stringResource(R.string.mcp_servers_catalog_installing)
                        } else {
                            stringResource(R.string.mcp_servers_catalog_install)
                        },
                    )
                }
            }

            entry.description?.let {
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Install form with env vars
            AnimatedVisibility(visible = showInstallForm) {
                Column(modifier = Modifier.padding(top = spacing.sm)) {
                    entry.env?.let { envVars ->
                        if (envVars.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.mcp_servers_catalog_required_env),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(spacing.sm))
                            envVars.forEach { envVar ->
                                val label = envVar.label ?: envVar.key
                                val currentValue = state.catalogInstallEnv[envVar.key] ?: ""
                                OutlinedTextField(
                                    value = currentValue,
                                    onValueChange = { viewModel.updateCatalogEnvVar(envVar.key, it) },
                                    label = { Text(label) },
                                    placeholder = envVar.description?.let { { Text(it) } },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(spacing.sm))
                            }
                        }
                    }
                    Button(
                        onClick = { viewModel.installCatalogEntry(entry) },
                        enabled = !isInstalling,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (isInstalling) {
                                stringResource(R.string.mcp_servers_catalog_installing)
                            } else {
                                stringResource(R.string.mcp_servers_catalog_install)
                            },
                        )
                    }
                }
            }
        }
    }
}
