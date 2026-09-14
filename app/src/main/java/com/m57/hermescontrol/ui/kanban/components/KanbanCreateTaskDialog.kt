package com.m57.hermescontrol.ui.kanban.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.CreateTaskBody
import com.m57.hermescontrol.data.model.KanbanColumn
import com.m57.hermescontrol.data.model.KanbanProfile
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.model.TaskEstimate
import kotlinx.coroutines.launch

private const val PARKED_VALUE = "__parked__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanCreateTaskDialog(
    columns: List<KanbanColumn>,
    defaultColumn: String,
    profiles: List<KanbanProfile>,
    existingTasks: List<KanbanTask>,
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (body: CreateTaskBody, targetStatus: String) -> Unit,
    modifier: Modifier = Modifier,
    onEstimate: (suspend (title: String, body: String?) -> TaskEstimate?)? = null,
) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var selectedColumn by remember(defaultColumn) { mutableStateOf(defaultColumn) }
    var selectedAssignee by remember { mutableStateOf<String?>(null) }
    var priority by remember { mutableIntStateOf(0) }
    var goalMode by remember { mutableStateOf(false) }

    // Estimation state
    var estimate by remember { mutableStateOf<TaskEstimate?>(null) }
    var isEstimating by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Advanced options
    var showAdvanced by remember { mutableStateOf(false) }
    var parentTaskId by remember { mutableStateOf<String?>(null) }
    var modelOverride by remember { mutableStateOf("") }
    var providerOverride by remember { mutableStateOf("") }
    var reasoningEffort by remember { mutableStateOf<String?>(null) }
    var workspaceKind by remember { mutableStateOf<String?>(null) }
    var skillsText by remember { mutableStateOf("") }

    var assigneeExpanded by remember { mutableStateOf(false) }
    var columnExpanded by remember { mutableStateOf(false) }
    var parentExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        modifier = modifier,
        title = { Text(stringResource(R.string.kanban_add_new_task)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.kanban_task_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isCreating,
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text(stringResource(R.string.kanban_task_desc)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                    enabled = !isCreating,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Target Column selector
                if (columns.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = columnExpanded,
                        onExpandedChange = { if (!isCreating) columnExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedColumn.uppercase(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.kanban_target_column)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = columnExpanded) },
                            modifier =
                                Modifier
                                    .menuAnchor(
                                        ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                    ).fillMaxWidth(),
                            enabled = !isCreating,
                        )
                        ExposedDropdownMenu(
                            expanded = columnExpanded,
                            onDismissRequest = { columnExpanded = false },
                        ) {
                            columns.forEach { col ->
                                DropdownMenuItem(
                                    text = { Text(col.name.uppercase()) },
                                    onClick = {
                                        selectedColumn = col.name
                                        columnExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Assignee dropdown
                ExposedDropdownMenuBox(
                    expanded = assigneeExpanded,
                    onExpandedChange = { if (!isCreating) assigneeExpanded = it },
                ) {
                    val displayAssignee =
                        when (selectedAssignee) {
                            null -> stringResource(R.string.kanban_assignee_unassigned)
                            PARKED_VALUE -> stringResource(R.string.kanban_assignee_parked)
                            else -> selectedAssignee ?: ""
                        }
                    OutlinedTextField(
                        value = displayAssignee,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.kanban_assignee)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = assigneeExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        enabled = !isCreating,
                    )
                    ExposedDropdownMenu(
                        expanded = assigneeExpanded,
                        onDismissRequest = { assigneeExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.kanban_assignee_unassigned)) },
                            onClick = {
                                selectedAssignee = null
                                assigneeExpanded = false
                            },
                        )
                        profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(profile.name)
                                        if (profile.model != null) {
                                            Text(
                                                profile.model,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    selectedAssignee = profile.name
                                    assigneeExpanded = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.kanban_assignee_parked)) },
                            onClick = {
                                selectedAssignee = PARKED_VALUE
                                assigneeExpanded = false
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Priority chips
                Text(
                    text = stringResource(R.string.kanban_priority),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    listOf(
                        0 to stringResource(R.string.kanban_priority_normal),
                        1 to stringResource(R.string.kanban_priority_med),
                        2 to stringResource(R.string.kanban_priority_high),
                        3 to stringResource(R.string.kanban_priority_urgent),
                    ).forEach { (pVal, label) ->
                        FilterChip(
                            selected = priority == pVal,
                            onClick = { priority = pVal },
                            label = { Text(label) },
                            enabled = !isCreating,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Autonomous Goal Mode switch
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isCreating) { goalMode = !goalMode }
                            .padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.kanban_goal_mode),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.kanban_goal_mode_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = goalMode,
                        onCheckedChange = { goalMode = it },
                        enabled = !isCreating,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Advanced Options Toggle
                TextButton(
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.kanban_advanced_options))
                }

                AnimatedVisibility(visible = showAdvanced) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Parent Task
                        if (existingTasks.isNotEmpty()) {
                            ExposedDropdownMenuBox(
                                expanded = parentExpanded,
                                onExpandedChange = { if (!isCreating) parentExpanded = it },
                            ) {
                                val displayParent =
                                    existingTasks.find { it.id == parentTaskId }?.let {
                                        "#${it.id.removePrefix("t_").take(6)} • ${it.title}"
                                    } ?: stringResource(R.string.kanban_parent_none)

                                OutlinedTextField(
                                    value = displayParent,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.kanban_parent_task)) },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                            expanded = parentExpanded,
                                        )
                                    },
                                    modifier =
                                        Modifier
                                            .menuAnchor(
                                                ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                            ).fillMaxWidth(),
                                    enabled = !isCreating,
                                )
                                ExposedDropdownMenu(
                                    expanded = parentExpanded,
                                    onDismissRequest = { parentExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.kanban_parent_none)) },
                                        onClick = {
                                            parentTaskId = null
                                            parentExpanded = false
                                        },
                                    )
                                    existingTasks.forEach { task ->
                                        DropdownMenuItem(
                                            text = { Text("#${task.id.removePrefix("t_").take(6)} • ${task.title}") },
                                            onClick = {
                                                parentTaskId = task.id
                                                parentExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Model Override
                        OutlinedTextField(
                            value = modelOverride,
                            onValueChange = { modelOverride = it },
                            label = { Text(stringResource(R.string.kanban_model_override)) },
                            placeholder = { Text("e.g. claude-3-5-sonnet") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isCreating,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Provider Override
                        OutlinedTextField(
                            value = providerOverride,
                            onValueChange = { providerOverride = it },
                            label = { Text(stringResource(R.string.kanban_provider_override)) },
                            placeholder = { Text("e.g. anthropic") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isCreating,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Reasoning Effort
                        Text(
                            text = stringResource(R.string.kanban_reasoning_effort),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        ) {
                            listOf(
                                null to "Inherit",
                                "low" to "Low",
                                "medium" to "Medium",
                                "high" to "High",
                            ).forEach { (effort, label) ->
                                FilterChip(
                                    selected = reasoningEffort == effort,
                                    onClick = { reasoningEffort = effort },
                                    label = { Text(label) },
                                    enabled = !isCreating,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Workspace Kind
                        Text(
                            text = stringResource(R.string.kanban_workspace_kind),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        ) {
                            listOf(
                                null to "Scratch (Default)",
                                "worktree" to "Worktree",
                                "dir" to "Dir",
                            ).forEach { (kind, label) ->
                                FilterChip(
                                    selected = workspaceKind == kind,
                                    onClick = { workspaceKind = kind },
                                    label = { Text(label) },
                                    enabled = !isCreating,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Skills
                        OutlinedTextField(
                            value = skillsText,
                            onValueChange = { skillsText = it },
                            label = { Text(stringResource(R.string.kanban_skills)) },
                            placeholder = { Text("e.g. git, coding, tests") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isCreating,
                        )
                    }
                }

                if (onEstimate != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (estimate != null && estimate?.ok == true) {
                            Text(
                                text = "~${estimate?.estTokens ?: 0} tokens · ${estimate?.complexity ?: "normal"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        TextButton(
                            onClick = {
                                if (title.isNotBlank() && !isEstimating) {
                                    coroutineScope.launch {
                                        isEstimating = true
                                        estimate = onEstimate(title, desc.ifBlank { null })
                                        isEstimating = false
                                    }
                                }
                            },
                            enabled = title.isNotBlank() && !isEstimating && !isCreating,
                        ) {
                            if (isEstimating) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(if (estimate != null) "Re-estimate" else "Estimate tokens")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        val skillsList =
                            skillsText
                                .split(",")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .takeIf { it.isNotEmpty() }

                        val body =
                            CreateTaskBody(
                                title = title.trim(),
                                body = desc.trim().ifBlank { null },
                                assignee = if (selectedAssignee == PARKED_VALUE) null else selectedAssignee,
                                priority = priority,
                                goalMode = goalMode,
                                parents = parentTaskId?.let { listOf(it) } ?: emptyList(),
                                modelOverride = modelOverride.trim().ifBlank { null },
                                providerOverride = providerOverride.trim().ifBlank { null },
                                reasoningEffort = reasoningEffort,
                                workspaceKind = workspaceKind,
                                skills = skillsList,
                                triage = selectedColumn.equals("triage", ignoreCase = true),
                            )
                        onConfirm(body, selectedColumn)
                    }
                },
                enabled = title.isNotBlank() && !isCreating,
            ) {
                if (isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isCreating,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
