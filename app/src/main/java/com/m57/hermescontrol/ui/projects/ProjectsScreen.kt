package com.m57.hermescontrol.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.applock.AlertDialog
import com.m57.hermescontrol.applock.ModalBottomSheet
import com.m57.hermescontrol.data.model.DiscoveredRepo
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.theme.parseProjectColor
import com.m57.hermescontrol.ui.common.ConfirmDialog
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.FilterChipRow
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.ManageableItemCard
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SectionHeader
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

/** Swatches matching the desktop project color picker: 12 evenly spaced hues at one saturation/lightness. */
internal val PROJECT_SWATCHES: List<String> = List(12) { "hsl(${it * 30} 68% 58%)" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ProjectsViewModel = viewModel { ProjectsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var addFolderTarget by remember { mutableStateOf<ProjectInfo?>(null) }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        modifier = modifier,
        title = { Text(stringResource(R.string.screen_projects)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading && state.projects.isNotEmpty(),
        onRefresh = viewModel::load,
        actions = {
            IconButton(onClick = { viewModel.startCreate() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.projects_action_new))
            }
        },
    ) {
        when {
            state.isLoading && state.projects.isEmpty() && state.errorMessage == null -> {
                SkeletonListState(modifier = Modifier.fillMaxSize())
            }

            state.errorMessage != null && state.projects.isEmpty() -> {
                ErrorState(
                    message = state.errorMessage.orEmpty(),
                    onRetry = viewModel::load,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                ProjectsList(state, viewModel, onAddFolder = { addFolderTarget = it })
            }
        }
    }

    state.editor?.let { draft ->
        ProjectEditorSheet(
            draft = draft,
            isSaving = state.isSaving,
            error =
                state.editorError?.let {
                    if (it ==
                        ProjectsViewModel.NAME_REQUIRED
                    ) {
                        stringResource(R.string.projects_error_name_required)
                    } else {
                        it
                    }
                },
            onSave = viewModel::save,
            onDismiss = viewModel::dismissEditor,
        )
    }

    addFolderTarget?.let { project ->
        AddFolderDialog(
            onAdd = { path ->
                viewModel.addFolder(project, path)
                addFolderTarget = null
            },
            onDismiss = { addFolderTarget = null },
        )
    }

    state.deleteTarget?.let { project ->
        ConfirmDialog(
            title = stringResource(R.string.projects_delete_title, project.displayName()),
            message = stringResource(R.string.projects_delete_message),
            confirmText = stringResource(R.string.action_delete),
            isDestructive = true,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

@Composable
private fun ProjectsList(
    state: ProjectsUiState,
    viewModel: ProjectsViewModel,
    onAddFolder: (ProjectInfo) -> Unit,
) {
    val visible = state.visibleProjects
    val showSuggestions = state.filter == ProjectsFilter.ACTIVE && state.query.isBlank()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = listContentPadding,
        verticalArrangement = listItemSpacing,
    ) {
        item(key = "filters") {
            Column {
                SearchBar(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    placeholder = stringResource(R.string.projects_search_placeholder),
                )
                FilterChipRow(
                    chips = ProjectsFilter.entries,
                    selectedChip = state.filter,
                    onChipSelected = viewModel::setFilter,
                    contentPadding = PaddingValues(vertical = LocalSpacing.current.xs),
                    chipLabel = { filter ->
                        Text(
                            when (filter) {
                                ProjectsFilter.ACTIVE -> {
                                    stringResource(R.string.projects_filter_active)
                                }

                                ProjectsFilter.ARCHIVED -> {
                                    stringResource(R.string.projects_filter_archived, state.archivedCount)
                                }
                            },
                        )
                    },
                )
            }
        }

        if (visible.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title =
                        stringResource(
                            if (state.filter == ProjectsFilter.ARCHIVED) {
                                R.string.projects_empty_archived
                            } else {
                                R.string.projects_empty_title
                            },
                        ),
                    subtitle =
                        stringResource(R.string.projects_empty_subtitle)
                            .takeIf { state.filter == ProjectsFilter.ACTIVE && state.query.isBlank() },
                    icon = Icons.Filled.Folder,
                    actionLabel =
                        stringResource(R.string.projects_action_new)
                            .takeIf { state.filter == ProjectsFilter.ACTIVE },
                    onAction = { viewModel.startCreate() },
                )
            }
        }

        items(visible, key = { it.id }) { project ->
            ProjectCard(
                project = project,
                busy = state.busyId == project.id,
                onEdit = { viewModel.startEdit(project) },
                onAddFolder = { onAddFolder(project) },
                onRemoveFolder = { viewModel.removeFolder(project, it) },
                onSetPrimary = { viewModel.setPrimaryFolder(project, it) },
                onArchive = { viewModel.setArchived(project, !project.isArchived) },
                onDelete = { viewModel.requestDelete(project) },
            )
        }

        if (showSuggestions && state.suggestions.isNotEmpty()) {
            item(key = "suggestions-header") {
                SectionHeader(title = stringResource(R.string.projects_suggestions_title))
            }
            items(state.suggestions, key = { "repo:${it.root}" }) { repo ->
                SuggestionCard(repo = repo, onCreate = { viewModel.startCreate(repo) })
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectInfo,
    busy: Boolean,
    onEdit: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onSetPrimary: (String) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = LocalSpacing.current
    ManageableItemCard(
        title = project.displayName(),
        subtitle = project.description?.takeIf(String::isNotBlank),
        modifier = Modifier.testTag("project_card_${project.id}"),
        onClick = onEdit,
        badges = {
            ColorDot(project.color)
            StatusBadge(
                text =
                    pluralStringResource(R.plurals.projects_folder_count, project.folders.size, project.folders.size),
            )
            if (project.isArchived) StatusBadge(text = stringResource(R.string.projects_badge_archived))
        },
        body = {
            if (project.folders.isEmpty()) {
                Text(
                    stringResource(R.string.projects_no_folders),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            project.folders.forEach { folder ->
                val primary = folder.isPrimary == true
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onSetPrimary(folder.path) },
                        enabled = !busy && !primary,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            if (primary) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription =
                                stringResource(
                                    if (primary) {
                                        R.string.projects_folder_primary
                                    } else {
                                        R.string.projects_folder_make_primary
                                    },
                                ),
                            tint =
                                if (primary) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        folder.path,
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = spacing.xs),
                    )
                    IconButton(
                        onClick = { onRemoveFolder(folder.path) },
                        enabled = !busy,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.projects_folder_remove, folder.path),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
        actions = {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            TextButton(onClick = onAddFolder, enabled = !busy) {
                Text(stringResource(R.string.projects_action_add_folder))
            }
            TextButton(onClick = onArchive, enabled = !busy) {
                Text(
                    stringResource(
                        if (project.isArchived) R.string.projects_action_restore else R.string.projects_action_archive,
                    ),
                )
            }
            TextButton(onClick = onDelete, enabled = !busy) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun SuggestionCard(
    repo: DiscoveredRepo,
    onCreate: () -> Unit,
) {
    ManageableItemCard(
        title = repo.label.ifBlank { repo.root },
        subtitle = repo.root,
        badges =
            if (repo.sessions > 0) {
                {
                    StatusBadge(
                        text = pluralStringResource(R.plurals.projects_session_count, repo.sessions, repo.sessions),
                    )
                }
            } else {
                null
            },
        actions = {
            TextButton(onClick = onCreate) { Text(stringResource(R.string.projects_action_create_from_repo)) }
        },
    )
}

@Composable
private fun ColorDot(
    color: String?,
    size: Int = 10,
) {
    val parsed = remember(color) { parseProjectColor(color) } ?: return
    Box(modifier = Modifier.size(size.dp).clip(CircleShape).background(parsed))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectEditorSheet(
    draft: ProjectDraft,
    isSaving: Boolean,
    error: String?,
    onSave: (ProjectDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val creating = draft.id == null
    var name by rememberSaveable(draft.id) { mutableStateOf(draft.name) }
    var description by rememberSaveable(draft.id) { mutableStateOf(draft.description) }
    var folder by rememberSaveable(draft.id) { mutableStateOf(draft.folder) }
    var color by rememberSaveable(draft.id) { mutableStateOf(draft.color) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg).padding(bottom = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                stringResource(if (creating) R.string.projects_action_new else R.string.projects_edit_title),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.projects_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("project_name_field"),
            )
            if (creating) {
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text(stringResource(R.string.projects_field_folder)) },
                    supportingText = { Text(stringResource(R.string.projects_field_folder_hint)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth().testTag("project_folder_field"),
                )
            } else {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.projects_field_description)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(stringResource(R.string.projects_field_color), style = MaterialTheme.typography.labelLarge)
            ColorSwatches(selected = color, onSelect = { color = it })
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = {
                        onSave(draft.copy(name = name, description = description, folder = folder, color = color))
                    },
                    enabled = !isSaving && name.isNotBlank(),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(if (creating) R.string.projects_action_create else R.string.action_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatches(
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val spacing = LocalSpacing.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        // "No color" first: a project without a color shows no accent anywhere.
        SwatchCell(
            selected = selected == null,
            label = stringResource(R.string.projects_color_none),
            onClick = { onSelect(null) },
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        PROJECT_SWATCHES.forEach { swatch ->
            val parsed = remember(swatch) { parseProjectColor(swatch) }
            SwatchCell(
                selected = selected == swatch,
                label = swatch,
                onClick = { onSelect(swatch) },
                fill = parsed,
            )
        }
    }
}

@Composable
private fun SwatchCell(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    fill: androidx.compose.ui.graphics.Color? = null,
    content: @Composable () -> Unit = {},
) {
    val ring = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier =
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .border(if (selected) 2.dp else 1.dp, ring, CircleShape)
                .let { if (fill != null) it.background(fill) else it }
                .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected && fill != null) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(18.dp),
            )
        } else {
            content()
        }
    }
}

@Composable
private fun AddFolderDialog(
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.projects_action_add_folder)) },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text(stringResource(R.string.projects_field_folder)) },
                supportingText = { Text(stringResource(R.string.projects_field_folder_hint)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(path) }, enabled = path.isNotBlank()) {
                Text(stringResource(R.string.projects_action_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun ProjectInfo.displayName(): String = name.trim().ifEmpty { slug ?: id }
