package com.m57.hermescontrol.ui.mcp.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.theme.Spacing
import com.m57.hermescontrol.ui.common.DetailDialog
import com.m57.hermescontrol.ui.common.toDetailRows
import com.m57.hermescontrol.ui.mcp.McpServersUiState
import com.m57.hermescontrol.ui.mcp.McpServersViewModel

@Composable
fun McpDialogs(
    state: McpServersUiState,
    viewModel: McpServersViewModel,
    spacing: Spacing,
    selectedServerDetail: McpServer?,
    onDismissDetail: () -> Unit,
    context: Context = LocalContext.current,
    clipboardManager: ClipboardManager = LocalClipboardManager.current,
) {
    selectedServerDetail?.let { server ->
        DetailDialog(
            title = server.name,
            rows = server.toDetailRows(),
            onDismiss = onDismissDetail,
        )
    }

    state.activeOAuthFlow?.let { flow ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissOAuthFlow() },
            confirmButton = {
                Button(
                    onClick = {
                        flow.authorizationUrl?.let { url ->
                            try {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(url),
                                    ),
                                )
                            } catch (_: Exception) {
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.mcp_servers_oauth_dialog_open_browser))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.dismissOAuthFlow() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            title = {
                Text(stringResource(R.string.mcp_servers_oauth_dialog_title))
            },
            text = {
                Column {
                    Text(stringResource(R.string.mcp_servers_oauth_dialog_desc))
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Text(
                        text = stringResource(R.string.mcp_servers_oauth_dialog_status, flow.status),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    flow.error?.let { err ->
                        Spacer(modifier = Modifier.height(spacing.xs))
                        Text(
                            text = stringResource(R.string.mcp_servers_oauth_dialog_error, err),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
        )
    }

    if (state.showImportDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.toggleImportDialog() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Text(stringResource(R.string.mcp_servers_import_dialog_title))
                }
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.mcp_servers_import_dialog_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    viewModel.updateImportJsonInput(clip)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(spacing.xs))
                            Text(
                                text = stringResource(R.string.mcp_servers_import_dialog_paste),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(spacing.xs))
                    OutlinedTextField(
                        value = state.importJsonInput,
                        onValueChange = viewModel::updateImportJsonInput,
                        placeholder = {
                            Text(
                                "{\n  \"mcpServers\": {\n    \"name\": {\n      \"command\": \"npx\",\n      \"args\": [\"-y\", \"@mcp/srv\"]\n    }\n  }\n}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                        maxLines = 10,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.submitImportJson() },
                    enabled = !state.isImportingJson && state.importJsonInput.isNotBlank(),
                ) {
                    if (state.isImportingJson) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(spacing.xs))
                        Text(stringResource(R.string.mcp_servers_import_dialog_importing))
                    } else {
                        Text(stringResource(R.string.mcp_servers_action_submit))
                    }
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.toggleImportDialog() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
