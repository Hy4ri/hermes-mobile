package com.m57.hermescontrol.ui.plugins

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.InstallDesktop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.PluginInfo
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: PluginsViewModel = viewModel { PluginsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }

    val filteredPlugins =
        remember(query, state.plugins) {
            state.plugins.filter { plugin ->
                plugin.name.contains(query, ignoreCase = true) ||
                    plugin.description?.contains(query, ignoreCase = true) == true
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadPlugins()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    // Remove confirmation dialog
    if (state.removeConfirmPlugin != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelRemovePlugin() },
            title = { Text(stringResource(R.string.plugins_remove_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.plugins_remove_confirm_text,
                        state.removeConfirmPlugin ?: "",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmRemovePlugin() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.plugins_action_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelRemovePlugin() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_plugins)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.loadPlugins() },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isLoading && state.plugins.isEmpty() -> {
                    LoadingState(modifier = Modifier.padding(paddingValues))
                }

                state.errorMessage != null -> {
                    ErrorState(
                        message = state.errorMessage ?: "",
                        onRetry = { viewModel.loadPlugins() },
                        modifier = Modifier.padding(paddingValues),
                    )
                }

                state.plugins.isEmpty() && state.orphanPlugins.isEmpty() -> {
                    EmptyState(
                        title = stringResource(R.string.plugins_empty_title),
                        subtitle = stringResource(R.string.plugins_empty_desc),
                        onAction = { viewModel.loadPlugins() },
                        actionLabel = stringResource(R.string.content_desc_refresh),
                        modifier = Modifier.padding(paddingValues),
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = listContentPadding,
                        verticalArrangement = listItemSpacing,
                    ) {
                        // Provider selection section
                        if (state.memoryOptions.isNotEmpty() || state.contextOptions.isNotEmpty()) {
                            item(key = "providers") {
                                ProviderSelectionSection(state, viewModel)
                            }
                        }

                        // Install section
                        item(key = "install") {
                            InstallSection(state, viewModel)
                        }

                        // Search bar
                        item(key = "search") {
                            SearchBar(
                                query = query,
                                onQueryChange = { query = it },
                                placeholder = "Search plugins...",
                            )
                        }

                        // Plugin list
                        items(filteredPlugins, key = { it.name }) { plugin ->
                            PluginCard(plugin = plugin, state = state, viewModel = viewModel)
                        }

                        // Orphan dashboard plugins section
                        if (state.orphanPlugins.isNotEmpty()) {
                            item(key = "orphan-header") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.plugins_orphan_heading),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            items(state.orphanPlugins, key = { "orphan-${it.name}" }) { plugin ->
                                OrphanPluginCard(plugin = plugin)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSelectionSection(
    state: PluginsUiState,
    viewModel: PluginsViewModel,
) {
    val providerDefaultsLabel = stringResource(R.string.plugins_provider_defaults)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.plugins_providers_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.plugins_providers_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Memory provider dropdown
            Text(
                text = stringResource(R.string.plugins_memory_provider_label),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            PluginProviderDropdown(
                label = stringResource(R.string.plugins_memory_provider_label),
                options =
                    listOf(
                        providerDefaultsLabel,
                    ) + state.memoryOptions.map { it.name },
                selectedValue =
                    if (state.isMemoryBuiltin) {
                        providerDefaultsLabel
                    } else {
                        state.memoryProvider
                    },
                onOptionSelected = { selected ->
                    if (selected == providerDefaultsLabel) {
                        viewModel.updateMemoryProvider(PluginsUiState.MEMORY_PROVIDER_BUILTIN)
                    } else {
                        viewModel.updateMemoryProvider(selected)
                    }
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Context engine dropdown
            Text(
                text = stringResource(R.string.plugins_context_engine_label),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            PluginProviderDropdown(
                label = stringResource(R.string.plugins_context_engine_label),
                options = listOf("compressor") + state.contextOptions.map { it.name },
                selectedValue = state.contextEngine,
                onOptionSelected = { viewModel.updateContextEngine(it) },
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { viewModel.savePluginProviders() },
                enabled = !state.providerBusy,
            ) {
                if (state.providerBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.common_save))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginProviderDropdown(
    label: String,
    options: List<String>,
    selectedValue: String,
    onOptionSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedValue.ifEmpty { options.firstOrNull() ?: "" },
            onValueChange = {},
            readOnly = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallSection(
    state: PluginsUiState,
    viewModel: PluginsViewModel,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.plugins_install_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.plugins_install_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = state.installUrl,
                onValueChange = { viewModel.updateInstallUrl(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("owner/repo, owner/repo/subdir, or https://...") },
                singleLine = true,
                enabled = !state.installBusy,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = state.installForce,
                        onCheckedChange = { viewModel.updateInstallForce(it) },
                        enabled = !state.installBusy,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.plugins_force_reinstall),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = state.installEnable,
                        onCheckedChange = { viewModel.updateInstallEnable(it) },
                        enabled = !state.installBusy,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.plugins_enable_after_install),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { viewModel.installPluginFromUrl() },
                enabled = !state.installBusy && state.installUrl.isNotBlank(),
            ) {
                if (state.installBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        Icons.Default.InstallDesktop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.plugins_action_install))
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: PluginInfo,
    state: PluginsUiState,
    viewModel: PluginsViewModel,
) {
    val busy = state.rowBusy == plugin.name
    val statusColors = LocalHermesStatusColors.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row: name + toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = plugin.name, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Version badge
                        plugin.version?.let {
                            Text(
                                text = "v$it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Source badge
                        plugin.source?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (plugin.installed) {
                    Switch(
                        checked = plugin.enabled,
                        onCheckedChange = { viewModel.togglePlugin(plugin) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Description
            Text(
                text = plugin.description ?: stringResource(R.string.plugins_no_desc),
                style = MaterialTheme.typography.bodyMedium,
            )

            // Status badges
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val statusColor =
                    when {
                        plugin.enabled -> statusColors.success
                        plugin.installed -> statusColors.warning
                        else -> statusColors.error
                    }
                Text(
                    text = plugin.runtimeStatus ?: "inactive",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
                if (plugin.authRequired) {
                    Text(
                        text = stringResource(R.string.plugins_auth_required),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColors.error,
                    )
                }
            }

            // Dashboard slots
            plugin.dashboardManifest?.slots?.let { slots ->
                if (slots.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.plugins_dashboard_slots, slots.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Auth command hint
            if (plugin.authRequired && plugin.authCommand != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.plugins_auth_command_hint, plugin.authCommand),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (plugin.installed) {
                    // Visibility toggle (only for plugins with dashboard manifest)
                    if (plugin.hasDashboardManifest) {
                        IconButton(
                            onClick = { viewModel.togglePluginVisibility(plugin) },
                            enabled = !busy,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                if (plugin.userHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription =
                                    if (plugin.userHidden) {
                                        stringResource(R.string.plugins_show_in_sidebar)
                                    } else {
                                        stringResource(R.string.plugins_hide_from_sidebar)
                                    },
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    // Update button
                    if (plugin.canUpdateGit) {
                        OutlinedButton(
                            onClick = { viewModel.updatePlugin(plugin.name) },
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.plugins_action_update))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    // Remove button with confirmation
                    if (plugin.canRemove) {
                        OutlinedButton(
                            onClick = { viewModel.requestRemovePlugin(plugin.name) },
                            enabled = !busy,
                            colors =
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.plugins_action_uninstall))
                        }
                    }
                } else {
                    Button(
                        onClick = { viewModel.activatePlugin(plugin) },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.plugins_action_install))
                    }
                }
            }
        }
    }
}

@Composable
private fun OrphanPluginCard(plugin: PluginInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                plugin.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
