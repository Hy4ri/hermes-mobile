package com.m57.hermescontrol.ui.kanban.components

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.ui.kanban.KanbanModelOverride
import com.m57.hermescontrol.ui.kanban.supportedKanbanReasoningEfforts
import com.m57.hermescontrol.ui.model.components.ModelPickerDialog

@Composable
fun KanbanModelOverrideEditor(
    override: KanbanModelOverride,
    onOverrideChange: (KanbanModelOverride) -> Unit,
    modelProviders: List<ModelProvider>,
    pinnedModels: List<PinnedModel> = emptyList(),
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var showPickerDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.kanban_model_override),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        OutlinedCard(
            onClick = { if (enabled) showPickerDialog = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint =
                            if (override.isInherited) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = override.displayLabel(stringResource(R.string.kanban_model_inherit)),
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (override.isInherited) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        maxLines = 1,
                    )
                }

                if (!override.isInherited && enabled) {
                    IconButton(
                        onClick = { onOverrideChange(override.copy(model = "", provider = "")) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.kanban_model_clear),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.kanban_reasoning_effort),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        val selectedCapabilities =
            modelProviders
                .firstOrNull { it.slug == override.provider }
                ?.capabilities
                ?.get(override.model)
        val reasoningSupported = selectedCapabilities?.reasoning != false
        val canDisableReasoning = selectedCapabilities?.can_disable_reasoning != false
        val supportedEfforts = supportedKanbanReasoningEfforts(reasoningSupported, canDisableReasoning)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
        ) {
            supportedEfforts
                .map { effortVal ->
                    effortVal to
                        when (effortVal) {
                            "" -> stringResource(R.string.kanban_effort_inherit)
                            "none" -> stringResource(R.string.kanban_effort_none)
                            "minimal" -> stringResource(R.string.kanban_effort_minimal)
                            "low" -> stringResource(R.string.kanban_effort_low)
                            "medium" -> stringResource(R.string.kanban_effort_medium)
                            "high" -> stringResource(R.string.kanban_effort_high)
                            "xhigh" -> stringResource(R.string.kanban_effort_xhigh)
                            "max" -> stringResource(R.string.kanban_effort_max)
                            "ultra" -> stringResource(R.string.kanban_effort_ultra)
                            else -> effortVal
                        }
                }.forEach { (effortVal, label) ->
                    FilterChip(
                        selected = override.effort == effortVal,
                        onClick = {
                            if (enabled) {
                                onOverrideChange(override.copy(effort = effortVal))
                            }
                        },
                        label = { Text(label) },
                        enabled = enabled,
                    )
                }
        }
    }

    if (showPickerDialog) {
        ModelPickerDialog(
            providers = modelProviders,
            title = stringResource(R.string.kanban_select_model),
            pinnedModels = pinnedModels,
            onSelect = { provider, model ->
                val capabilities = modelProviders.firstOrNull { it.slug == provider }?.capabilities?.get(model)
                onOverrideChange(
                    override.copy(
                        provider = provider,
                        model = model,
                        effort = if (capabilities?.reasoning == false) "" else override.effort,
                    ),
                )
                showPickerDialog = false
            },
            onDismiss = { showPickerDialog = false },
        )
    }
}
