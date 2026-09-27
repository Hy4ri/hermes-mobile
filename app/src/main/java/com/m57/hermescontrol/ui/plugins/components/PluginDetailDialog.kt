package com.m57.hermescontrol.ui.plugins.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.PluginInfo
import com.m57.hermescontrol.data.ws.AgentPluginRow
import com.m57.hermescontrol.data.ws.PluginServerRow
import com.m57.hermescontrol.data.ws.PluginServerState
import com.m57.hermescontrol.data.ws.PluginSettingField
import com.m57.hermescontrol.data.ws.PluginSettingFieldType
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.DetailRow
import com.m57.hermescontrol.ui.common.DetailRowTone
import com.m57.hermescontrol.ui.common.ExposedDropdownField
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.toDetailRows
import com.m57.hermescontrol.ui.common.toneForStatus

@Composable
fun PluginDetailDialog(
    plugin: PluginInfo,
    agentPluginRow: AgentPluginRow?,
    isLoading: Boolean,
    isSaving: Boolean,
    edits: Map<String, String>,
    onFieldChange: (String, String) -> Unit,
    onSaveSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val statusColors = LocalHermesStatusColors.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            HermesScaffold(
                title = { Text(plugin.name, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = NavIcon.Back(onDismiss),
                isRefreshing = false,
            ) { _ ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Standard metadata rows
                    val rows = plugin.toDetailRows().filter { !it.value.isNullOrBlank() }
                    rows.forEachIndexed { index, (label, value, tone) ->
                        if (index > 0) {
                            HorizontalDivider()
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = value ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color =
                                    when (tone) {
                                        DetailRowTone.SUCCESS -> statusColors.success
                                        DetailRowTone.WARNING -> statusColors.warning
                                        DetailRowTone.ERROR -> statusColors.error
                                        DetailRowTone.INFO -> statusColors.info
                                        DetailRowTone.NEUTRAL -> statusColors.neutral
                                        DetailRowTone.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                    }

                    if (isLoading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.plugins_settings_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Diagnostics section (only if servers present)
                    val servers = agentPluginRow?.servers.orEmpty()
                    if (servers.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            text = stringResource(R.string.plugins_diagnostics_heading),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        servers.forEach { server ->
                            PluginServerDiagnosticRow(server = server)
                        }
                    }

                    // Settings section (only if schema present)
                    val schema = agentPluginRow?.settingsSchema.orEmpty()
                    if (schema.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            text = stringResource(R.string.plugins_settings_heading),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        schema.forEach { field ->
                            PluginSettingFieldRow(
                                field = field,
                                value = edits[field.key] ?: "",
                                enabled = !isSaving,
                                onChange = { onFieldChange(field.key, it) },
                            )
                        }

                        Button(
                            onClick = onSaveSettings,
                            enabled = !isSaving,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.plugins_settings_save))
                        }
                    }

                    // Close action
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_close))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginServerDiagnosticRow(server: PluginServerRow) {
    val statusColors = LocalHermesStatusColors.current
    val (statusLabel, statusColor, badgeType) =
        when (server.state) {
            PluginServerState.CONNECTED -> {
                Triple(
                    stringResource(R.string.plugins_server_state_connected),
                    statusColors.success,
                    StatusBadgeType.SUCCESS,
                )
            }

            PluginServerState.APP_NOT_RUNNING -> {
                Triple(
                    stringResource(R.string.plugins_server_state_app_not_running),
                    statusColors.warning,
                    StatusBadgeType.WARNING,
                )
            }

            PluginServerState.ENDPOINT_UNAVAILABLE -> {
                Triple(
                    stringResource(R.string.plugins_server_state_endpoint_unavailable),
                    statusColors.warning,
                    StatusBadgeType.WARNING,
                )
            }

            PluginServerState.NO_INTERACTIVE_SESSION -> {
                Triple(
                    stringResource(R.string.plugins_server_state_no_interactive_session),
                    statusColors.warning,
                    StatusBadgeType.WARNING,
                )
            }

            PluginServerState.VERSION_TOO_OLD -> {
                Triple(
                    stringResource(R.string.plugins_server_state_version_too_old),
                    statusColors.error,
                    StatusBadgeType.ERROR,
                )
            }

            PluginServerState.MISSING_APP -> {
                Triple(
                    stringResource(R.string.plugins_server_state_missing_app),
                    statusColors.error,
                    StatusBadgeType.ERROR,
                )
            }

            PluginServerState.UNKNOWN -> {
                Triple(
                    stringResource(R.string.plugins_server_state_unknown),
                    statusColors.warning,
                    StatusBadgeType.WARNING,
                )
            }
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(text = statusLabel, status = badgeType)
            }
            if (server.sentence.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = server.sentence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PluginSettingFieldRow(
    field: PluginSettingField,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = field.label.ifEmpty { field.key },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (field.type == PluginSettingFieldType.SECRET) {
                    val isSet = field.hasValue == true
                    val badgeRes =
                        if (isSet) {
                            R.string.plugins_settings_secret_set
                        } else {
                            R.string.plugins_settings_secret_not_set
                        }
                    StatusBadge(
                        text = stringResource(badgeRes),
                        status = if (isSet) StatusBadgeType.SUCCESS else StatusBadgeType.WARNING,
                    )
                }
            }
            if (field.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = field.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (field.type == PluginSettingFieldType.SECRET && !field.env.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.plugins_settings_secret_hint, field.env),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            when (field.type) {
                PluginSettingFieldType.ENUM -> {
                    ExposedDropdownField(
                        label = field.label.ifEmpty { field.key },
                        options = field.choices.orEmpty().ifEmpty { listOf(value) },
                        selectedValue = value,
                        onOptionSelected = onChange,
                    )
                }

                PluginSettingFieldType.BOOLEAN -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = value.equals("true", ignoreCase = true),
                            onCheckedChange = { onChange(it.toString()) },
                            enabled = enabled,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text =
                                if (value.equals("true", ignoreCase = true)) {
                                    stringResource(R.string.memory_provider_bool_on)
                                } else {
                                    stringResource(R.string.memory_provider_bool_off)
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                else -> {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onChange,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                if (field.type == PluginSettingFieldType.SECRET) {
                                    stringResource(R.string.memory_provider_secret_label)
                                } else {
                                    field.label.ifEmpty { field.key }
                                },
                            )
                        },
                        singleLine = field.type != PluginSettingFieldType.JSON,
                        visualTransformation =
                            if (field.type == PluginSettingFieldType.SECRET) {
                                PasswordVisualTransformation()
                            } else {
                                androidx.compose.ui.text.input.VisualTransformation.None
                            },
                        keyboardOptions =
                            if (field.type == PluginSettingFieldType.NUMBER) {
                                KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            } else {
                                KeyboardOptions.Default
                            },
                    )
                }
            }
        }
    }
}
