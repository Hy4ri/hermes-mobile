package com.m57.hermescontrol.ui.mcp.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.Spacing
import com.m57.hermescontrol.ui.mcp.AddServerMode
import com.m57.hermescontrol.ui.mcp.McpServersUiState
import com.m57.hermescontrol.ui.mcp.McpServersViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddServerSection(
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: Spacing,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        McpSectionHeader(
            icon = Icons.Filled.Add,
            title = stringResource(R.string.mcp_servers_add_server),
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    TextButton(
                        onClick = { viewModel.toggleImportDialog() },
                        colors =
                            ButtonDefaults.textButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text(stringResource(R.string.mcp_servers_action_import_json))
                    }
                    TextButton(
                        onClick = { viewModel.toggleAddForm() },
                        colors =
                            ButtonDefaults.textButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                    ) {
                        Text(if (state.showAddForm) "Hide" else "New")
                    }
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
}
