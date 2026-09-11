package com.m57.hermescontrol.ui.mcp.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.theme.Spacing
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.mcp.McpHealthStatus
import com.m57.hermescontrol.ui.mcp.McpServersUiState
import com.m57.hermescontrol.ui.mcp.McpServersViewModel
import com.m57.hermescontrol.ui.mcp.McpTokenEstimator

@Composable
fun ServerCard(
    server: McpServer,
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: Spacing,
    onClick: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEnv by remember { mutableStateOf(false) }
    val isTesting = server.name in state.testingServers
    val testResult = state.serverTestResults[server.name]
    val healthStatus = McpHealthStatus.resolve(isTesting, testResult, server.status, server.error)

    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
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
                        when (healthStatus) {
                            McpHealthStatus.HEALTHY -> {
                                StatusBadge(
                                    text = stringResource(R.string.mcp_servers_status_healthy),
                                    status = StatusBadgeType.SUCCESS,
                                )
                            }

                            McpHealthStatus.AUTH_REQUIRED -> {
                                StatusBadge(
                                    text = stringResource(R.string.mcp_servers_status_needs_auth),
                                    status = StatusBadgeType.WARNING,
                                )
                            }

                            McpHealthStatus.ERROR -> {
                                StatusBadge(
                                    text = stringResource(R.string.mcp_servers_status_error),
                                    status = StatusBadgeType.ERROR,
                                )
                            }

                            McpHealthStatus.TESTING -> {
                                StatusBadge(
                                    text = stringResource(R.string.mcp_servers_status_testing),
                                    status = StatusBadgeType.INFO,
                                )
                            }

                            McpHealthStatus.UNKNOWN -> {
                                ServerStatusBadge(server.status)
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.mcp_servers_label_transport, server.transport ?: "stdio"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Tool count & schema token overhead badge (issue #1029)
                    val toolInfos = testResult?.tools
                    val toolCount = toolInfos?.size ?: server.tools?.size
                    if (toolCount != null && toolCount > 0) {
                        val tokenEst = toolInfos?.let { McpTokenEstimator.estimateTokens(it) }
                        val label = McpTokenEstimator.formatTokenOverhead(toolCount, tokenEst)
                        Spacer(modifier = Modifier.height(spacing.xs))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = spacing.xs + 2.dp, vertical = 2.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Build,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Spacer(modifier = Modifier.width(spacing.xs))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
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
                if (server.auth == "oauth") {
                    FilledTonalButton(
                        onClick = { viewModel.startMcpOAuthFlow(server, onOpenBrowser) },
                        modifier = Modifier.weight(1.2f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text(stringResource(R.string.mcp_servers_action_authorize), maxLines = 1)
                    }
                }
                FilledTonalButton(
                    onClick = { viewModel.testServer(server.name) },
                    enabled = !isTesting,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text(stringResource(R.string.mcp_servers_status_testing), maxLines = 1)
                    } else {
                        Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text(stringResource(R.string.mcp_servers_action_test), maxLines = 1)
                    }
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
fun EnvVarSection(
    server: McpServer,
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: Spacing,
    modifier: Modifier = Modifier,
) {
    val isEditing = state.editingEnvFor == server.name
    val env = server.env ?: emptyMap()

    Column(modifier = modifier) {
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
}

@Composable
fun ServerStatusBadge(status: String?) {
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
