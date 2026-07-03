package com.m57.hermescontrol.ui.model

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.MoaConfigPreset
import com.m57.hermescontrol.data.model.MoaModelSlot
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.ToastEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ModelViewModel = viewModel { ModelViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var expandedProviderSlug by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    val filteredProviders =
        remember(query, state.providers) {
            state.providers.filter { provider ->
                provider.name.contains(query, ignoreCase = true) ||
                    provider.slug.contains(query, ignoreCase = true) ||
                    provider.models.orEmpty().any { model ->
                        model.contains(query, ignoreCase = true)
                    }
            }
        }

    LaunchedEffect(Unit) {
        viewModel.loadAll()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_models)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadAll() },
    ) { paddingValues ->
        when {
            state.isLoading && state.providers.isEmpty() -> {
                LoadingState(
                    subtitle = stringResource(R.string.loading_state_subtitle_models),
                    modifier = Modifier.padding(paddingValues),
                )
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadAll() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            state.providers.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.model_empty_title),
                    subtitle = stringResource(R.string.model_empty_desc),
                    onAction = { viewModel.loadAll() },
                    actionLabel = stringResource(R.string.content_desc_refresh),
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // ── Model Settings Card ──
                    item {
                        ModelSettingsSection(
                            mainProvider = state.mainModelProvider,
                            mainModel = state.mainModelModel,
                            auxTasks = state.auxTasks,
                            moaEnabled = state.moaConfig != null,
                            moaSummary =
                                if (state.moaConfig != null) {
                                    val mc = state.moaConfig!!
                                    "${mc.reference_models.size} ref · ${mc.aggregator.provider}/${shortModelName(
                                        mc.aggregator.model,
                                    )}"
                                } else {
                                    ""
                                },
                            onSetMainModel = { viewModel.openMainModelPicker() },
                            onConfigureAux = { viewModel.openAuxDialog() },
                            onConfigureMoa = { viewModel.openMoaDialog() },
                        )
                    }

                    // ── Search Bar ──
                    item {
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search models...",
                        )
                    }

                    // ── Pinned Models ──
                    if (state.pinnedModels.isNotEmpty()) {
                        item {
                            var isPinnedSectionExpanded by rememberSaveable { mutableStateOf(true) }
                            val filteredPinned =
                                remember(query, state.pinnedModels) {
                                    if (query.isBlank()) {
                                        state.pinnedModels
                                    } else {
                                        state.pinnedModels.filter {
                                            it.modelName.contains(query, ignoreCase = true) ||
                                                it.providerSlug.contains(query, ignoreCase = true)
                                        }
                                    }
                                }

                            if (filteredPinned.isNotEmpty()) {
                                PinnedSectionCard(
                                    pinnedModels = filteredPinned,
                                    isExpanded = isPinnedSectionExpanded,
                                    activeProfile = state.activeProfile,
                                    onToggleExpanded = { isPinnedSectionExpanded = !isPinnedSectionExpanded },
                                    onModelClick = { slug, model -> viewModel.selectModel(slug, model) },
                                    onUnpin = { slug, model -> viewModel.unpinModel(slug, model) },
                                )
                            }
                        }
                    }

                    // ── Provider List ──
                    items(filteredProviders, key = { it.slug }) { provider ->
                        val isExpanded = expandedProviderSlug == provider.slug
                        val isCurrent = provider.is_current == true

                        ProviderCard(
                            provider = provider,
                            isExpanded = isExpanded,
                            isCurrent = isCurrent,
                            query = query,
                            activeProfile = state.activeProfile,
                            pinnedModels = state.pinnedModels,
                            onToggleExpand = {
                                expandedProviderSlug = if (isExpanded) null else provider.slug
                            },
                            onModelClick = { slug, model -> viewModel.selectModel(slug, model) },
                            onPin = { slug, model -> viewModel.pinModel(slug, model) },
                            onUnpin = { slug, model -> viewModel.unpinModel(slug, model) },
                        )
                    }
                }
            }
        }
    }

    // ── Dialogs ──

    // Expensive model confirmation
    if (state.modelPickerConfirmMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissModelPickerConfirm() },
            title = { Text("Expensive Model") },
            text = { Text(state.modelPickerConfirmMessage!!) },
            confirmButton = {
                Button(onClick = { viewModel.confirmModelPickerExpensive() }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissModelPickerConfirm() }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Main model picker dialog
    if (state.showMainModelPicker) {
        ModelPickerDialog(
            providers = state.providers,
            title = "Set Main Model",
            onSelect = { provider, model ->
                viewModel.setMainModel(provider, model)
            },
            onDismiss = { viewModel.closeMainModelPicker() },
        )
    }

    // Auxiliary tasks dialog
    if (state.showAuxDialog) {
        AuxTasksDialog(
            auxTasks = state.auxTasks,
            busy = state.modelPickerBusy,
            onSetTask = { task -> viewModel.openAuxModelPicker(task) },
            onResetAll = { viewModel.resetAllAuxTasks() },
            onDismiss = { viewModel.closeAuxDialog() },
        )
    }

    // Auxiliary model picker dialog
    if (state.showAuxModelPicker) {
        ModelPickerDialog(
            providers = state.providers,
            title = "Set Aux: ${state.auxPickerTask}",
            onSelect = { provider, model ->
                viewModel.setAuxTask(state.auxPickerTask, provider, model)
            },
            onDismiss = { viewModel.closeAuxModelPicker() },
        )
    }

    // MOA config dialog
    if (state.showMoaDialog && state.moaConfig != null) {
        MoaConfigDialog(
            config = state.moaConfig!!,
            providers = state.providers,
            busy = state.modelPickerBusy,
            onSave = { viewModel.saveMoaConfig(it) },
            onDismiss = { viewModel.closeMoaDialog() },
        )
    }
}

// ────────────────────────────────────────────────────────────────────
// Model Settings Panel
// ────────────────────────────────────────────────────────────────────

@Composable
private fun ModelSettingsSection(
    mainProvider: String,
    mainModel: String,
    auxTasks: List<*>?,
    moaEnabled: Boolean,
    moaSummary: String,
    onSetMainModel: () -> Unit,
    onConfigureAux: () -> Unit,
    onConfigureMoa: () -> Unit,
) {
    val auxOverrideCount =
        auxTasks?.let { tasks ->
            tasks.filterIsInstance<com.m57.hermescontrol.data.model.AuxiliaryTaskAssignment>()
                .count { a -> a.provider.isNotBlank() && a.provider != "auto" }
        } ?: 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Model Settings",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "applies to new sessions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main model row
            SettingsRow(
                icon = {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = "Main model",
                value = if (mainProvider.isNotBlank()) "$mainProvider · $mainModel" else "(unset)",
                actionLabel = "Change",
                onAction = onSetMainModel,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Auxiliary tasks row
            SettingsRow(
                icon = {
                    Icon(
                        Icons.Filled.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = "Auxiliary tasks",
                value =
                    if (auxOverrideCount > 0) {
                        "$auxOverrideCount override · ${AUX_TASKS.size - auxOverrideCount} auto"
                    } else {
                        "${AUX_TASKS.size} tasks · all auto"
                    },
                actionLabel = "Configure",
                onAction = onConfigureAux,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // MOA row
            SettingsRow(
                icon = {
                    Icon(
                        Icons.Filled.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = "Mixture of Agents",
                value = if (moaEnabled) moaSummary else "not loaded",
                actionLabel = "Configure",
                onAction = onConfigureMoa,
                actionEnabled = moaEnabled,
            )
        }
    }
}

@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    actionLabel: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            icon()
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(
            onClick = onAction,
            enabled = actionEnabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(actionLabel, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// Auxiliary Tasks Dialog
// ────────────────────────────────────────────────────────────────────

@Composable
private fun AuxTasksDialog(
    auxTasks: List<com.m57.hermescontrol.data.model.AuxiliaryTaskAssignment>,
    busy: Boolean,
    onSetTask: (String) -> Unit,
    onResetAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auxiliary Tasks") },
        text = {
            Column {
                Text(
                    text =
                        "Auxiliary tasks handle side-jobs like vision, session" +
                            " search, and compression. \"auto\" means use the main model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))

                AUX_TASKS.forEach { info ->
                    val cur = auxTasks.find { it.task == info.key }
                    val isAuto = cur == null || cur.provider.isBlank() || cur.provider == "auto"

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = info.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = info.hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text =
                                    if (isAuto) {
                                        "auto (use main model)"
                                    } else {
                                        "${cur?.provider} · ${cur?.model ?: "(provider default)"}"
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { onSetTask(info.key) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("Change", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onResetAll,
                    enabled = !busy,
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset all to auto")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

// ────────────────────────────────────────────────────────────────────
// MOA Config Dialog
// ────────────────────────────────────────────────────────────────────

@Composable
private fun MoaConfigDialog(
    config: com.m57.hermescontrol.data.model.MoaConfigResponse,
    providers: List<com.m57.hermescontrol.data.model.ModelProvider>,
    busy: Boolean,
    onSave: (com.m57.hermescontrol.data.model.MoaConfigResponse) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(config) { mutableStateOf(config) }
    var selectedPreset by remember { mutableStateOf(config.default_preset.ifBlank { "default" }) }
    var newPresetName by remember { mutableStateOf("") }
    var pickerTarget by remember { mutableStateOf<MoaPickerTarget?>(null) }

    val presetNames = draft.presets.keys.toList()
    val effectivePreset = draft.presets[selectedPreset]

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mixture of Agents") },
        text = {
            if (pickerTarget != null && effectivePreset != null) {
                // Inline model picker within the dialog
                MoaSlotPicker(
                    providers = providers,
                    draft = draft,
                    selectedPreset = selectedPreset,
                    pickerTarget = pickerTarget!!,
                    onSlotSelected = { updatedDraft ->
                        draft = updatedDraft
                        pickerTarget = null
                    },
                    onCancel = { pickerTarget = null },
                )
            } else {
                MoaConfigEditor(
                    draft = draft,
                    presetNames = presetNames,
                    selectedPreset = selectedPreset,
                    effectivePreset = effectivePreset,
                    newPresetName = newPresetName,
                    busy = busy,
                    onSelectPreset = { selectedPreset = it },
                    onNewPresetNameChange = { newPresetName = it },
                    onAddPreset = {
                        val name = newPresetName.trim()
                        if (name.isNotBlank() && !draft.presets.containsKey(name)) {
                            val seed =
                                effectivePreset ?: MoaConfigPreset(
                                    reference_models = draft.reference_models,
                                    aggregator = draft.aggregator,
                                    reference_temperature = draft.reference_temperature,
                                    aggregator_temperature = draft.aggregator_temperature,
                                    max_tokens = draft.max_tokens,
                                )
                            draft =
                                draft.copy(
                                    default_preset = draft.default_preset.ifBlank { name },
                                    presets = draft.presets + (name to seed),
                                )
                            selectedPreset = name
                            newPresetName = ""
                        }
                    },
                    onDeletePreset = {
                        if (presetNames.size > 1) {
                            val remaining = presetNames - selectedPreset
                            val nextSelected = remaining.first()
                            draft =
                                draft.copy(
                                    presets = draft.presets - selectedPreset,
                                    default_preset =
                                        if (draft.default_preset == selectedPreset) {
                                            nextSelected
                                        } else {
                                            draft.default_preset
                                        },
                                )
                            selectedPreset = nextSelected
                        }
                    },
                    onUpdatePreset = { updater ->
                        if (effectivePreset != null) {
                            draft =
                                draft.copy(
                                    presets = draft.presets + (selectedPreset to updater(effectivePreset)),
                                )
                        }
                    },
                    onPickReference = { index ->
                        pickerTarget = MoaPickerTarget.Reference(index)
                    },
                    onPickAggregator = {
                        pickerTarget = MoaPickerTarget.Aggregator
                    },
                    onAddReference = {
                        if (effectivePreset != null) {
                            draft =
                                draft.copy(
                                    presets =
                                        draft.presets + (
                                            selectedPreset to
                                                effectivePreset.copy(
                                                    reference_models =
                                                        effectivePreset.reference_models +
                                                            effectivePreset.aggregator,
                                                )
                                        ),
                                )
                        }
                    },
                    onRemoveReference = { index ->
                        if (effectivePreset != null && effectivePreset.reference_models.size > 1) {
                            draft =
                                draft.copy(
                                    presets =
                                        draft.presets + (
                                            selectedPreset to
                                                effectivePreset.copy(
                                                    reference_models =
                                                        effectivePreset.reference_models.toMutableList().also {
                                                            it.removeAt(
                                                                index,
                                                            )
                                                        },
                                                )
                                        ),
                                )
                        }
                    },
                    onSetDefault = {
                        draft = draft.copy(default_preset = selectedPreset)
                    },
                )
            }
        },
        confirmButton = {
            if (pickerTarget == null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                    Button(
                        onClick = { onSave(draft) },
                        enabled = !busy,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Save")
                        }
                    }
                }
            }
        },
    )
}

private sealed class MoaPickerTarget {
    data class Reference(val index: Int) : MoaPickerTarget()

    data object Aggregator : MoaPickerTarget()
}

@Composable
private fun MoaConfigEditor(
    draft: com.m57.hermescontrol.data.model.MoaConfigResponse,
    presetNames: List<String>,
    selectedPreset: String,
    effectivePreset: com.m57.hermescontrol.data.model.MoaConfigPreset?,
    newPresetName: String,
    busy: Boolean,
    onSelectPreset: (String) -> Unit,
    onNewPresetNameChange: (String) -> Unit,
    onAddPreset: () -> Unit,
    onDeletePreset: () -> Unit,
    onUpdatePreset: (
        updater: (com.m57.hermescontrol.data.model.MoaConfigPreset) -> com.m57.hermescontrol.data.model.MoaConfigPreset,
    ) -> Unit,
    onPickReference: (Int) -> Unit,
    onPickAggregator: () -> Unit,
    onAddReference: () -> Unit,
    onRemoveReference: (Int) -> Unit,
    onSetDefault: () -> Unit,
) {
    Column {
        Text(
            text = "Presets appear as models under the Mixture of Agents provider.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (effectivePreset == null) {
            Text("No presets configured", style = MaterialTheme.typography.bodyMedium)
            return
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Preset selector
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Simplified preset dropdown with buttons
            if (presetNames.isNotEmpty()) {
                Text("Preset:", style = MaterialTheme.typography.bodySmall)
                presetNames.forEach { name ->
                    TextButton(
                        onClick = { onSelectPreset(name) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            name,
                            fontWeight = if (name == selectedPreset) FontWeight.Bold else FontWeight.Normal,
                            color =
                                if (name == selectedPreset) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // Default preset indicator
        Text(
            text = "Default: ${draft.default_preset.ifBlank { "(none)" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Preset actions
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onSetDefault,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                enabled = presetNames.size > 1,
            ) {
                Text("Set default", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = onDeletePreset,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                enabled = presetNames.size > 1,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Delete", style = MaterialTheme.typography.labelSmall)
            }
        }

        // New preset
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newPresetName,
                onValueChange = onNewPresetNameChange,
                placeholder = { Text("new preset name", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(48.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            IconButton(onClick = onAddPreset, enabled = newPresetName.isNotBlank()) {
                Icon(Icons.Outlined.Add, contentDescription = "Add preset")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Reference models
        Text(
            text = "Reference models",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))

        effectivePreset.reference_models.forEachIndexed { index, slot ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "${slot.provider} · ${slot.model}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { onPickReference(index) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text("Change", style = MaterialTheme.typography.labelSmall) }
                if (effectivePreset.reference_models.size > 1) {
                    IconButton(onClick = { onRemoveReference(index) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onAddReference,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add reference model", style = MaterialTheme.typography.labelSmall)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Aggregator
        Text(
            text = "Aggregator",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${effectivePreset.aggregator.provider} · ${effectivePreset.aggregator.model}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onPickAggregator,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) { Text("Change", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun MoaSlotPicker(
    providers: List<com.m57.hermescontrol.data.model.ModelProvider>,
    draft: com.m57.hermescontrol.data.model.MoaConfigResponse,
    selectedPreset: String,
    pickerTarget: MoaPickerTarget,
    onSlotSelected: (com.m57.hermescontrol.data.model.MoaConfigResponse) -> Unit,
    onCancel: () -> Unit,
) {
    var pickerQuery by remember { mutableStateOf("") }

    Column {
        Text(
            text =
                "Select model for ${
                    if (pickerTarget is MoaPickerTarget.Aggregator) {
                        "aggregator"
                    } else {
                        "reference model"
                    }
                }",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))

        SearchBar(
            query = pickerQuery,
            onQueryChange = { pickerQuery = it },
            placeholder = "Search providers...",
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.height(300.dp)) {
            items(
                providers.filter {
                    pickerQuery.isBlank() ||
                        it.name.contains(pickerQuery, ignoreCase = true) ||
                        it.slug.contains(pickerQuery, ignoreCase = true)
                },
                key = { it.slug },
            ) { provider ->
                val models =
                    provider.models.orEmpty().filter {
                        pickerQuery.isBlank() ||
                            it.contains(pickerQuery, ignoreCase = true)
                    }

                if (models.isNotEmpty()) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )

                    models.forEach { model ->
                        OutlinedButton(
                            onClick = {
                                val slot = MoaModelSlot(provider.slug, model)
                                val update: (
                                    com.m57.hermescontrol.data.model.MoaConfigPreset,
                                ) -> com.m57.hermescontrol.data.model.MoaConfigPreset = { preset ->
                                    when (pickerTarget) {
                                        is MoaPickerTarget.Aggregator -> preset.copy(aggregator = slot)
                                        is MoaPickerTarget.Reference ->
                                            preset.copy(
                                                reference_models =
                                                    preset.reference_models.toMutableList().also {
                                                        it[pickerTarget.index] = slot
                                                    },
                                            )
                                    }
                                }
                                val preset = draft.presets[selectedPreset]
                                if (preset != null) {
                                    onSlotSelected(
                                        draft.copy(presets = draft.presets + (selectedPreset to update(preset))),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(text = model, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        TextButton(onClick = onCancel) {
            Text("Back")
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// Model Picker Dialog
// ────────────────────────────────────────────────────────────────────

@Composable
private fun ModelPickerDialog(
    providers: List<com.m57.hermescontrol.data.model.ModelProvider>,
    title: String,
    onSelect: (provider: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickerQuery by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                SearchBar(
                    query = pickerQuery,
                    onQueryChange = { pickerQuery = it },
                    placeholder = "Search providers...",
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.height(400.dp)) {
                    items(
                        providers.filter {
                            pickerQuery.isBlank() ||
                                it.name.contains(pickerQuery, ignoreCase = true) ||
                                it.slug.contains(pickerQuery, ignoreCase = true)
                        },
                        key = { it.slug },
                    ) { provider ->
                        val models =
                            provider.models.orEmpty().filter {
                                pickerQuery.isBlank() ||
                                    it.contains(pickerQuery, ignoreCase = true)
                            }

                        if (models.isNotEmpty()) {
                            Text(
                                text = provider.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )

                            models.forEach { model ->
                                OutlinedButton(
                                    onClick = { onSelect(provider.slug, model) },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text(text = model, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ────────────────────────────────────────────────────────────────────
// Pinned Section Card — extracted from existing screen
// ────────────────────────────────────────────────────────────────────

@Composable
private fun PinnedSectionCard(
    pinnedModels: List<com.m57.hermescontrol.data.model.PinnedModel>,
    isExpanded: Boolean,
    activeProfile: com.m57.hermescontrol.data.model.ProfileInfo?,
    onToggleExpanded: () -> Unit,
    onModelClick: (String, String) -> Unit,
    onUnpin: (String, String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize().padding(bottom = 8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        onClick = onToggleExpanded,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.model_section_pinned),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                IconButton(onClick = onToggleExpanded) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.content_desc_pinned_section_toggle),
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    pinnedModels.forEach { pinned ->
                        val isActive =
                            activeProfile?.provider == pinned.providerSlug &&
                                activeProfile?.model == pinned.modelName

                        Card(
                            onClick = { onModelClick(pinned.providerSlug, pinned.modelName) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (isActive) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                ),
                            border =
                                BorderStroke(
                                    width = 1.dp,
                                    color =
                                        if (isActive) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                        },
                                ),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = pinned.modelName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        color =
                                            if (isActive) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                    )
                                    Text(
                                        text = pinned.providerSlug,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (isActive) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = stringResource(R.string.content_desc_active_model),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    IconButton(
                                        onClick = { onUnpin(pinned.providerSlug, pinned.modelName) },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PushPin,
                                            contentDescription = stringResource(R.string.content_desc_unpin_model),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// Provider Card — extracted from existing screen
// ────────────────────────────────────────────────────────────────────

@Composable
private fun ProviderCard(
    provider: com.m57.hermescontrol.data.model.ModelProvider,
    isExpanded: Boolean,
    isCurrent: Boolean,
    query: String,
    activeProfile: com.m57.hermescontrol.data.model.ProfileInfo?,
    pinnedModels: List<com.m57.hermescontrol.data.model.PinnedModel>,
    onToggleExpand: () -> Unit,
    onModelClick: (String, String) -> Unit,
    onPin: (String, String) -> Unit,
    onUnpin: (String, String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggleExpand,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isCurrent) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.model_label_slug, provider.slug),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isCurrent) {
                    Text(
                        text = stringResource(R.string.model_status_current),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            provider.warning?.let {
                if (it.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = stringResource(R.string.model_label_available),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    val models = provider.models.orEmpty()
                    val filteredModels =
                        if (query.isBlank()) {
                            models
                        } else {
                            models.filter { it.contains(query, ignoreCase = true) }
                        }

                    if (filteredModels.isEmpty()) {
                        Text(
                            text = stringResource(R.string.model_no_models),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        filteredModels.forEach { model ->
                            val isActive =
                                activeProfile?.provider == provider.slug &&
                                    activeProfile?.model == model
                            Card(
                                onClick = { onModelClick(provider.slug, model) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor =
                                            if (isActive) {
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                            } else {
                                                MaterialTheme.colorScheme.surface
                                            },
                                    ),
                                border =
                                    BorderStroke(
                                        width = 1.dp,
                                        color =
                                            if (isActive) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                            },
                                    ),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = model,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        color =
                                            if (isActive) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (isActive) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = stringResource(R.string.content_desc_active_model),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        val isPinned =
                                            pinnedModels.any {
                                                it.providerSlug == provider.slug && it.modelName == model
                                            }
                                        IconButton(
                                            onClick = {
                                                if (isPinned) {
                                                    onUnpin(provider.slug, model)
                                                } else {
                                                    onPin(provider.slug, model)
                                                }
                                            },
                                            modifier = Modifier.size(24.dp),
                                        ) {
                                            Icon(
                                                imageVector =
                                                    if (isPinned) {
                                                        Icons.Filled.PushPin
                                                    } else {
                                                        Icons.Outlined.PushPin
                                                    },
                                                contentDescription =
                                                    if (isPinned) {
                                                        stringResource(R.string.content_desc_unpin_model)
                                                    } else {
                                                        "Pin model"
                                                    },
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Shorten a model name for display. */
private fun shortModelName(model: String): String {
    val parts = model.split("/")
    return parts.lastOrNull() ?: model
}
