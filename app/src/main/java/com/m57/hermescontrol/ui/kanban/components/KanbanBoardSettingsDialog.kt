package com.m57.hermescontrol.ui.kanban.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanProject
import com.m57.hermescontrol.data.model.RenameBoardBody

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanBoardSettingsDialog(
    board: KanbanBoard,
    projects: List<KanbanProject>,
    onSave: (RenameBoardBody) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(board) { mutableStateOf(board.name ?: "") }
    var description by remember(board) { mutableStateOf(board.description ?: "") }
    var defaultWorkdir by remember(board) { mutableStateOf(board.defaultWorkdir ?: "") }
    var selectedProjectId by remember(board) { mutableStateOf(board.projectId) }

    var projectExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.kanban_board_settings)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.kanban_board_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.kanban_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                // Project Selector
                ExposedDropdownMenuBox(
                    expanded = projectExpanded,
                    onExpandedChange = { projectExpanded = it },
                ) {
                    val displayProject =
                        projects.find { it.id == selectedProjectId }?.name
                            ?: stringResource(R.string.kanban_no_project)

                    OutlinedTextField(
                        value = displayProject,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.kanban_project)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = projectExpanded) },
                        modifier =
                            Modifier
                                .menuAnchor(
                                    ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                ).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = projectExpanded,
                        onDismissRequest = { projectExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.kanban_no_project)) },
                            onClick = {
                                selectedProjectId = null
                                projectExpanded = false
                            },
                        )
                        projects.forEach { proj ->
                            DropdownMenuItem(
                                text = { Text(proj.name) },
                                onClick = {
                                    selectedProjectId = proj.id
                                    projectExpanded = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = defaultWorkdir,
                    onValueChange = { defaultWorkdir = it },
                    label = { Text(stringResource(R.string.kanban_default_workdir)) },
                    placeholder = { Text(stringResource(R.string.kanban_workdir_example)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        RenameBoardBody(
                            name = name.trim().ifBlank { null },
                            description = description.trim().ifBlank { null },
                            defaultWorkdir = defaultWorkdir.trim().ifBlank { null },
                            projectId = selectedProjectId ?: "",
                        ),
                    )
                    onDismiss()
                },
                enabled = name.isNotBlank(),
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
