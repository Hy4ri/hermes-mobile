package com.m57.hermescontrol.ui.mcp.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.McpCatalogEntry
import com.m57.hermescontrol.theme.Spacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.ui.mcp.McpServersUiState
import com.m57.hermescontrol.ui.mcp.McpServersViewModel

@Composable
fun CatalogView(
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: Spacing,
    filteredCatalog: List<McpCatalogEntry>,
    modifier: Modifier = Modifier,
) {
    val installedNames = remember(state.servers) { state.servers.map { it.name }.toSet() }
    Column(modifier = modifier.fillMaxSize()) {
        SearchBar(
            query = state.catalogQuery,
            onQueryChange = viewModel::updateCatalogQuery,
            placeholder = stringResource(R.string.mcp_servers_catalog_search),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        when {
            state.catalogLoading && state.catalogEntries.isEmpty() -> {
                SkeletonListState()
            }

            state.catalogError != null && state.catalogEntries.isEmpty() -> {
                ErrorState(
                    message = state.catalogError,
                    onRetry = { viewModel.loadCatalog() },
                )
            }

            filteredCatalog.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.mcp_servers_catalog_empty_title),
                    subtitle = stringResource(R.string.mcp_servers_catalog_empty),
                    actionLabel = stringResource(R.string.content_desc_refresh),
                    onAction = { viewModel.loadCatalog() },
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    items(filteredCatalog, key = { "catalog:${it.name}" }) { entry ->
                        CatalogEntryCard(
                            entry = entry,
                            state = state,
                            viewModel = viewModel,
                            spacing = spacing,
                            isInstalled = entry.name in installedNames,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CatalogEntryCard(
    entry: McpCatalogEntry,
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: Spacing,
    modifier: Modifier = Modifier,
    isInstalled: Boolean = false,
) {
    var showInstallForm by remember { mutableStateOf(false) }
    val isInstalling = state.installingCatalogEntry == entry.name

    Card(
        modifier = modifier.fillMaxWidth(),
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
                if (isInstalled) {
                    FilledTonalButton(onClick = {}, enabled = false) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text(stringResource(R.string.mcp_servers_catalog_installed))
                    }
                } else {
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
            AnimatedVisibility(visible = showInstallForm && !isInstalled) {
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
