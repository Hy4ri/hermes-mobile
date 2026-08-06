@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.m57.hermescontrol.data.model.MemoryProviderConfigResponse
import com.m57.hermescontrol.data.model.MemoryProviderField
import com.m57.hermescontrol.data.model.MemoryProviderSetupResult
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.ExposedDropdownField
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

/**
 * Per-memory-provider management page (issue #783) — the mobile counterpart
 * of the desktop provider-config-panel: schema fields with existing values,
 * write-only secrets, a synchronous setup run with inline per-step results,
 * and an honest status pill refreshed after each mutation.
 */
@Composable
fun MemoryProviderDetailScreen(
    name: String,
    label: String?,
    onBack: () -> Unit,
    viewModel: MemoryProviderDetailViewModel = viewModel { MemoryProviderDetailViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val config = state.config

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    // The VM is shared across all provider-detail entries (activity-level
    // ViewModelStoreOwner) — drive it with the CURRENT entry's provider.
    LaunchedEffect(name) {
        viewModel.setProvider(name)
        viewModel.load()
    }

    HermesScaffold(
        title = { Text(label ?: name) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.load() },
    ) { paddingValues ->
        when {
            state.isLoading && config == null -> {
                SkeletonListState(modifier = Modifier)
            }

            state.errorMessage != null && config == null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.load() },
                    modifier = Modifier,
                )
            }

            config == null -> {
                EmptyState(
                    title = stringResource(R.string.memory_provider_empty_title),
                    subtitle = stringResource(R.string.memory_provider_empty_desc),
                    onAction = { viewModel.load() },
                    actionLabel = stringResource(R.string.content_desc_refresh),
                    modifier = Modifier,
                )
            }

            else -> {
                MemoryProviderConfigContent(
                    config = config,
                    state = state,
                    onFieldValue = viewModel::setFieldValue,
                    onSave = viewModel::saveConfig,
                    onRunSetup = viewModel::runSetup,
                    modifier = Modifier,
                )
            }
        }
    }
}

@Composable
private fun MemoryProviderConfigContent(
    config: MemoryProviderConfigResponse,
    state: MemoryProviderDetailUiState,
    onFieldValue: (String, String) -> Unit,
    onSave: () -> Unit,
    onRunSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = listContentPadding,
        verticalArrangement = listItemSpacing,
    ) {
        // Status header
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = config.label.ifEmpty { config.name },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        state.status?.let { status ->
                            StatusBadge(
                                text = memoryProviderStatusLabel(status.status),
                                status = memoryProviderStatusType(status.status),
                            )
                        }
                    }
                    if (config.docs_url.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = config.docs_url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    config.setup?.let { setup ->
                        if (setup.pip_dependencies.isNotEmpty() || setup.required_env.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text =
                                    stringResource(
                                        R.string.memory_provider_setup_summary,
                                        setup.pip_dependencies.joinToString(", "),
                                        setup.required_env.joinToString(", "),
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Config fields
        items(config.fields, key = { it.key }) { field ->
            ProviderFieldRow(
                field = field,
                value = state.edits[field.key] ?: "",
                enabled = !state.saving,
                onChange = { onFieldValue(field.key, it) },
            )
        }

        // Save
        item {
            Button(
                onClick = onSave,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.memory_provider_save))
            }
        }

        // Setup
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.memory_provider_setup_heading),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.memory_provider_setup_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRunSetup,
                        enabled = !state.runningSetup,
                    ) {
                        if (state.runningSetup) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.memory_provider_run_setup))
                    }
                    state.setupResult?.let { result ->
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text =
                                if (result.ok) {
                                    stringResource(R.string.memory_provider_setup_ok)
                                } else {
                                    stringResource(R.string.memory_provider_setup_failed)
                                },
                            style = MaterialTheme.typography.labelMedium,
                            color =
                                if (result.ok) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                        )
                        result.results.forEach { step ->
                            Spacer(modifier = Modifier.height(8.dp))
                            SetupStepRow(step)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderFieldRow(
    field: MemoryProviderField,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = field.label.ifEmpty { field.key },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (field.kind == "secret") {
                    StatusBadge(
                        text =
                            stringResource(
                                if (field.is_set) {
                                    R.string.memory_provider_secret_set
                                } else {
                                    R.string.memory_provider_secret_not_set
                                },
                            ),
                        status =
                            if (field.is_set) {
                                StatusBadgeType.SUCCESS
                            } else {
                                StatusBadgeType.WARNING
                            },
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
            Spacer(modifier = Modifier.height(8.dp))

            when (field.kind) {
                "select" ->
                    ExposedDropdownField(
                        label = field.label.ifEmpty { field.key },
                        options = field.options.map { it.value }.ifEmpty { listOf(value) },
                        selectedValue = value,
                        onOptionSelected = onChange,
                    )

                "bool" ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = value == "true",
                            onCheckedChange = { onChange(it.toString()) },
                            enabled = enabled,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text =
                                stringResource(
                                    if (value == "true") {
                                        R.string.memory_provider_bool_on
                                    } else {
                                        R.string.memory_provider_bool_off
                                    },
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                else ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = onChange,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                if (field.kind == "secret") {
                                    stringResource(R.string.memory_provider_secret_label)
                                } else {
                                    field.label.ifEmpty { field.key }
                                },
                            )
                        },
                        placeholder = {
                            if (field.placeholder.isNotBlank()) {
                                Text(field.placeholder)
                            }
                        },
                        singleLine = field.kind != "json",
                        visualTransformation =
                            if (field.kind == "secret") {
                                PasswordVisualTransformation()
                            } else {
                                androidx.compose.ui.text.input.VisualTransformation.None
                            },
                        keyboardOptions =
                            if (field.kind == "number") {
                                KeyboardOptions(keyboardType = KeyboardType.Number)
                            } else {
                                KeyboardOptions.Default
                            },
                    )
            }
        }
    }
}

@Composable
private fun SetupStepRow(step: MemoryProviderSetupResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = step.name.ifEmpty { step.kind },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(
                text = step.status.replace('_', ' '),
                status =
                    when (step.status) {
                        "failed" -> StatusBadgeType.ERROR
                        "skipped" -> StatusBadgeType.NEUTRAL
                        else -> StatusBadgeType.SUCCESS
                    },
            )
        }
        if (step.command.isNotBlank()) {
            Text(
                text = step.command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (step.stdout.isNotBlank()) {
            Text(
                text = step.stdout.trim(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (step.stderr.isNotBlank()) {
            Text(
                text = step.stderr.trim(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun memoryProviderStatusType(status: String): StatusBadgeType =
    when (status) {
        "ready" -> StatusBadgeType.SUCCESS
        "needs_config" -> StatusBadgeType.WARNING
        "unavailable", "missing" -> StatusBadgeType.ERROR
        else -> StatusBadgeType.NEUTRAL
    }
