package com.m57.hermescontrol.ui.model.components

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.data.model.MoaConfigPreset
import com.m57.hermescontrol.data.model.MoaModelSlot
import com.m57.hermescontrol.ui.common.SearchBar

@Composable
internal fun MoaConfigDialog(
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
    data class Reference(
        val index: Int,
    ) : MoaPickerTarget()

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
                                        is MoaPickerTarget.Aggregator -> {
                                            preset.copy(aggregator = slot)
                                        }

                                        is MoaPickerTarget.Reference -> {
                                            preset.copy(
                                                reference_models =
                                                    preset.reference_models.toMutableList().also {
                                                        it[pickerTarget.index] = slot
                                                    },
                                            )
                                        }
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
