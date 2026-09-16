package com.m57.hermescontrol.ui.kanban.components

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.KanbanProfile
import com.m57.hermescontrol.data.model.OrchestrationSettings
import com.m57.hermescontrol.data.model.OrchestrationSettingsUpdate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanOrchestrationDialog(
    settings: OrchestrationSettings?,
    profiles: List<KanbanProfile>,
    onSaveSettings: (OrchestrationSettingsUpdate) -> Unit,
    onSaveProfileDescription: (profile: String, description: String) -> Unit,
    onAutoDescribe: (profile: String, onComplete: (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var orchestratorProfile by remember(settings) {
        mutableStateOf(settings?.orchestratorProfile ?: "")
    }
    var defaultAssignee by remember(settings) {
        mutableStateOf(settings?.defaultAssignee ?: "")
    }
    var autoDecompose by remember(settings) {
        mutableStateOf(settings?.autoDecompose ?: true)
    }

    var orchestratorExpanded by remember { mutableStateOf(false) }
    var defaultAssigneeExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.kanban_orchestration_settings)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Orchestrator Profile
                ExposedDropdownMenuBox(
                    expanded = orchestratorExpanded,
                    onExpandedChange = { orchestratorExpanded = it },
                ) {
                    val display =
                        if (orchestratorProfile.isBlank()) {
                            stringResource(R.string.kanban_assignee_unassigned)
                        } else {
                            orchestratorProfile
                        }
                    OutlinedTextField(
                        value = display,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.kanban_orchestrator_profile)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = orchestratorExpanded) },
                        modifier =
                            Modifier
                                .menuAnchor(
                                    ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                ).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = orchestratorExpanded,
                        onDismissRequest = { orchestratorExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.kanban_assignee_unassigned)) },
                            onClick = {
                                orchestratorProfile = ""
                                orchestratorExpanded = false
                            },
                        )
                        profiles.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = {
                                    orchestratorProfile = p.name
                                    orchestratorExpanded = false
                                },
                            )
                        }
                    }
                }

                // Default Assignee
                ExposedDropdownMenuBox(
                    expanded = defaultAssigneeExpanded,
                    onExpandedChange = { defaultAssigneeExpanded = it },
                ) {
                    val display =
                        if (defaultAssignee.isBlank()) {
                            stringResource(R.string.kanban_assignee_unassigned)
                        } else {
                            defaultAssignee
                        }
                    OutlinedTextField(
                        value = display,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.kanban_default_assignee)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = defaultAssigneeExpanded) },
                        modifier =
                            Modifier
                                .menuAnchor(
                                    ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                ).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = defaultAssigneeExpanded,
                        onDismissRequest = { defaultAssigneeExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.kanban_assignee_unassigned)) },
                            onClick = {
                                defaultAssignee = ""
                                defaultAssigneeExpanded = false
                            },
                        )
                        profiles.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = {
                                    defaultAssignee = p.name
                                    defaultAssigneeExpanded = false
                                },
                            )
                        }
                    }
                }

                // Auto-decompose Switch
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.kanban_auto_decompose),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = autoDecompose,
                        onCheckedChange = { autoDecompose = it },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Profile Descriptions
                Text(
                    text = stringResource(R.string.kanban_profile_descriptions),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                profiles.forEach { profile ->
                    ProfileDescriptionEditor(
                        profile = profile,
                        onSave = { onSaveProfileDescription(profile.name, it) },
                        onAutoDescribe = { cb -> onAutoDescribe(profile.name, cb) },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveSettings(
                        OrchestrationSettingsUpdate(
                            orchestratorProfile = orchestratorProfile,
                            defaultAssignee = defaultAssignee,
                            autoDecompose = autoDecompose,
                        ),
                    )
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ProfileDescriptionEditor(
    profile: KanbanProfile,
    onSave: (String) -> Unit,
    onAutoDescribe: ((String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(profile.description) { mutableStateOf(profile.description) }
    var isDescribing by remember { mutableStateOf(false) }

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (profile.isDefault) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(Default)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isDescribing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(
                            onClick = {
                                isDescribing = true
                                onAutoDescribe { desc ->
                                    isDescribing = false
                                    if (desc != null) draft = desc
                                }
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = stringResource(R.string.kanban_auto_describe),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    IconButton(
                        onClick = { onSave(draft.trim()) },
                        enabled = draft.trim() != profile.description.trim(),
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = stringResource(R.string.action_save),
                            modifier = Modifier.size(16.dp),
                            tint =
                                if (draft.trim() != profile.description.trim()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                placeholder = { Text(stringResource(R.string.kanban_profile_description_hint)) },
            )
        }
    }
}
