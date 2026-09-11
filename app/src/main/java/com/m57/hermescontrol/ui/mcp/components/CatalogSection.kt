package com.m57.hermescontrol.ui.mcp.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.mcp.McpServersUiState
import com.m57.hermescontrol.ui.mcp.McpServersViewModel

@Composable
fun CatalogSection(
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: Spacing,
    filteredCatalog: List<McpCatalogEntry>,
    modifier: Modifier = Modifier,
) {
    val catalogExpanded = remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        McpSectionHeader(
            icon = Icons.Filled.Storage,
            title = stringResource(R.string.mcp_servers_section_catalog),
            trailing = {
                TextButton(
                    onClick = {
                        catalogExpanded.value = !catalogExpanded.value
                        if (catalogExpanded.value && state.catalogEntries.isEmpty() && !state.catalogLoading) {
                            viewModel.loadCatalog()
                        }
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                ) {
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
}

@Composable
fun CatalogEntryCard(
    entry: McpCatalogEntry,
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: Spacing,
    modifier: Modifier = Modifier,
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
