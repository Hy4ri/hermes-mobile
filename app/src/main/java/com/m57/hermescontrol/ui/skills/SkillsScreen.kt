package com.m57.hermescontrol.ui.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.HubSkill
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.FilterChipRow
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

private const val CATEGORY_ALL = "All"

@Composable
fun SkillsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
    viewModel: SkillsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf(SkillFilter.ALL_STATUSES) }
    var selectedCategory by remember { mutableStateOf(CATEGORY_ALL) }

    LaunchedEffect(Unit) {
        viewModel.loadSkills()
    }

    HermesScaffold(
        modifier = modifier,
        title = { Text(stringResource(R.string.skills_screen_title)) },
        navigationIcon = NavIcon.Menu(onOpen = onOpenDrawer),
        actions = {
            if (state.viewMode == SkillsViewMode.INSTALLED) {
                IconButton(onClick = { viewModel.updateSkillsFromHub() }) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.content_desc_update_skills_hub),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // ── View mode tabs ──────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.viewMode == SkillsViewMode.INSTALLED,
                    onClick = { viewModel.setViewMode(SkillsViewMode.INSTALLED) },
                    label = { Text(stringResource(R.string.skills_mode_installed)) },
                )
                FilterChip(
                    selected = state.viewMode == SkillsViewMode.HUB,
                    onClick = { viewModel.setViewMode(SkillsViewMode.HUB) },
                    label = { Text(stringResource(R.string.skills_mode_hub)) },
                )
            }

            when (state.viewMode) {
                SkillsViewMode.INSTALLED ->
                    InstalledSkillsView(
                        state = state,
                        query = query,
                        onQueryChange = { query = it },
                        selectedStatus = selectedStatus,
                        onStatusChange = { selectedStatus = it },
                        selectedCategory = selectedCategory,
                        onCategoryChange = { selectedCategory = it },
                        onToggle = viewModel::toggleSkill,
                        onEdit = viewModel::loadSkillContent,
                        onUninstall = viewModel::uninstallSkill,
                        onSetSourceFilter = viewModel::setSourceFilter,
                        sourceFilter = state.sourceFilter,
                        isUninstalling = state.isUninstalling,
                        uninstallingSkillName = state.uninstallingSkillName,
                        onRefresh = viewModel::loadSkills,
                    )
                SkillsViewMode.HUB ->
                    HubBrowseView(
                        state = state,
                        hubQuery = state.hubQuery,
                        onHubQueryChange = viewModel::setHubQuery,
                        onSearch = viewModel::searchHub,
                        onClearSearch = viewModel::clearHubSearch,
                        onInstall = viewModel::installSkill,
                        isInstalling = state.isInstalling,
                        installingSkillName = state.installingSkillName,
                    )
            }
        }
    }

    // ── Dialogs ────────────────────────────────────────────────────

    state.editingSkillName?.let { skillName ->
        SkillEditorDialog(
            skillName = skillName,
            isLoading = state.isLoadingContent,
            initialContent = state.skillContent,
            isSaving = state.isSavingContent,
            saveSuccess = state.saveContentSuccess,
            onSave = { content -> viewModel.saveSkillContent(skillName, content) },
            onDismiss = { viewModel.clearEditor() },
            onClearSaveSuccess = { viewModel.clearSaveSuccess() },
        )
    }

    state.previewSkillName?.let { skillName ->
        SkillPreviewDialog(
            skillName = skillName,
            isLoading = state.isLoadingPreview,
            content = state.previewSkillContent,
            onDismiss = { viewModel.clearPreview() },
        )
    }

    ToastEffect(
        toastMessage = state.toastMessage,
        onClearToast = viewModel::clearToast,
    )
}

// ── Installed Skills View ───────────────────────────────────────────────

@Composable
private fun InstalledSkillsView(
    state: SkillsUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedStatus: SkillFilter,
    onStatusChange: (SkillFilter) -> Unit,
    selectedCategory: String?,
    onCategoryChange: (String) -> Unit,
    onToggle: (Skill) -> Unit,
    onEdit: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onSetSourceFilter: (String?) -> Unit,
    sourceFilter: String?,
    isUninstalling: Boolean,
    uninstallingSkillName: String?,
    onRefresh: () -> Unit,
) {
    val categories =
        remember(state.skills) {
            state.skills
                .mapNotNull { it.category }
                .distinct()
                .sorted()
        }
    val sources =
        remember(state.skills) {
            state.skills
                .mapNotNull { it.source }
                .distinct()
                .sorted()
        }

    val filteredSkills =
        remember(state.skills, query, selectedStatus, selectedCategory, sourceFilter) {
            state.skills.filter { skill ->
                (
                    query.isBlank() || skill.name.contains(query, ignoreCase = true) ||
                        skill.description?.contains(query, ignoreCase = true) == true
                ) &&
                    (
                        selectedStatus == SkillFilter.ALL_STATUSES ||
                            (selectedStatus == SkillFilter.ENABLED && skill.enabled) ||
                            (selectedStatus == SkillFilter.DISABLED && !skill.enabled)
                    ) &&
                    (selectedCategory == CATEGORY_ALL || skill.category == selectedCategory) &&
                    (sourceFilter == null || skill.source == sourceFilter)
            }
        }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.skills_search_placeholder),
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChipRow(
                chips =
                    listOf(
                        SkillFilter.ALL_STATUSES,
                        SkillFilter.ENABLED,
                        SkillFilter.DISABLED,
                    ),
                selectedChip = selectedStatus,
                onChipSelected = onStatusChange,
                chipLabel = { chip -> Text(stringResource(chip.labelRes)) },
            )
        }

        if (categories.isNotEmpty() || sources.isNotEmpty()) {
            FilterChipRow(
                chips = listOf(stringResource(R.string.skills_category_all)) + categories,
                selectedChip = selectedCategory,
                onChipSelected = onCategoryChange,
            )
            if (sources.isNotEmpty()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = sourceFilter == null,
                        onClick = { onSetSourceFilter(null) },
                        label = {
                            Text(
                                stringResource(R.string.skills_source_all),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                    sources.forEach { source ->
                        FilterChip(
                            selected = sourceFilter == source,
                            onClick = { onSetSourceFilter(if (sourceFilter == source) null else source) },
                            label = { Text(source, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }

        when {
            state.isLoading -> LoadingState()
            state.errorMessage != null ->
                ErrorState(
                    message = state.errorMessage,
                    onRetry = onRefresh,
                )
            filteredSkills.isEmpty() ->
                EmptyState(
                    icon = Icons.Filled.Extension,
                    title = stringResource(R.string.skills_empty_message),
                )
            else ->
                LazyColumn(
                    modifier = Modifier.padding(listContentPadding),
                    verticalArrangement = listItemSpacing,
                ) {
                    items(filteredSkills, key = { it.name }) { skill ->
                        SkillCard(
                            skill = skill,
                            onToggle = { onToggle(skill) },
                            onAction = { onEdit(skill.name) },
                            onUninstall =
                                if (skill.source == "hub") {
                                    { onUninstall(skill.name) }
                                } else {
                                    null
                                },
                            isUninstalling = isUninstalling && uninstallingSkillName == skill.name,
                        )
                    }
                }
        }
    }
}

// ── Skill Card ─────────────────────────────────────────────────────────

@Composable
private fun SkillCard(
    skill: Skill,
    onToggle: () -> Unit,
    onAction: () -> Unit,
    onUninstall: (() -> Unit)?,
    isUninstalling: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp).padding(start = 12.dp),
            )
            Column(
                modifier = Modifier.weight(1f).padding(16.dp),
            ) {
                // ── Top row: name + source badge + toggle ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = skill.name,
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    skill.source?.let { source ->
                        Spacer(modifier = Modifier.width(6.dp))
                        SourceBadge(source = source)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { onToggle() },
                    )
                }

                // ── Category ──
                skill.category?.let { cat ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = cat,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── Description ──
                skill.description?.let { desc ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // ── Action buttons row ──
                if (onUninstall != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onAction) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.content_desc_edit_skill),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = onUninstall,
                            enabled = !isUninstalling,
                        ) {
                            if (isUninstalling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                val statusColors = LocalHermesStatusColors.current
                                Icon(
                                    imageVector = Icons.Filled.CloudOff,
                                    contentDescription = stringResource(R.string.content_desc_uninstall_skill),
                                    tint = statusColors.error,
                                )
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IconButton(onClick = onAction) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.content_desc_edit_skill),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
// ── Source Badge ───────────────────────────────────────────────────────

@Composable
private fun SourceBadge(source: String) {
    val (color, label) =
        when (source) {
            "hub" -> MaterialTheme.colorScheme.tertiary to "hub"
            "built-in" -> MaterialTheme.colorScheme.secondary to "built-in"
            "optional" -> MaterialTheme.colorScheme.outline to "opt"
            else -> MaterialTheme.colorScheme.outline to source
        }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ── Hub Browse View ─────────────────────────────────────────────────────

@Composable
private fun HubBrowseView(
    state: SkillsUiState,
    hubQuery: String,
    onHubQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onInstall: (String) -> Unit,
    isInstalling: Boolean,
    installingSkillName: String?,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = hubQuery,
            onValueChange = onHubQueryChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            placeholder = { Text(stringResource(R.string.skills_hub_search_placeholder)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                if (hubQuery.isNotEmpty()) {
                    IconButton(onClick = {
                        onHubQueryChange("")
                        onClearSearch()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_clear),
                        )
                    }
                }
            },
            singleLine = true,
        )

        Button(
            onClick = { onSearch(hubQuery) },
            modifier =
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 8.dp),
            enabled = hubQuery.isNotBlank(),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.skills_hub_search_button))
        }

        when {
            state.isHubSearching -> LoadingState()
            state.hubSearchError != null ->
                ErrorState(
                    message = state.hubSearchError,
                    onRetry = { onSearch(hubQuery) },
                )
            state.hubResults.isEmpty() && hubQuery.isNotBlank() && !state.isHubSearching -> {
                EmptyState(
                    icon = Icons.Filled.Extension,
                    title = stringResource(R.string.skills_hub_empty_results),
                )
            }
            state.hubResults.isNotEmpty() ->
                LazyColumn(
                    modifier = Modifier.padding(listContentPadding),
                    verticalArrangement = listItemSpacing,
                ) {
                    items(state.hubResults, key = { it.name }) { hubSkill ->
                        HubSkillCard(
                            hubSkill = hubSkill,
                            onInstall = { onInstall(hubSkill.name) },
                            isInstalling = isInstalling && installingSkillName == hubSkill.name,
                        )
                    }
                }
            else -> {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.skills_hub_search_hint),
                )
            }
        }
    }
}

// ── Hub Skill Card ─────────────────────────────────────────────────────

@Composable
private fun HubSkillCard(
    hubSkill: HubSkill,
    onInstall: () -> Unit,
    isInstalling: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hubSkill.name,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                hubSkill.source?.let { source ->
                    SourceBadge(source = source)
                }
                hubSkill.description?.let { desc ->
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Button(
                onClick = onInstall,
                enabled = !isInstalling,
            ) {
                if (isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.skills_hub_install))
                }
            }
        }
    }
}

// ── Skill Preview Dialog ───────────────────────────────────────────────

@Composable
fun SkillPreviewDialog(
    skillName: String,
    isLoading: Boolean,
    content: String?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
        ) {
            HermesScaffold(
                title = { Text(stringResource(R.string.skills_preview_title, skillName)) },
                navigationIcon = NavIcon.Back(onBack = onDismiss),
            ) { padding ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                ) {
                    when {
                        isLoading -> LoadingState()
                        content != null -> {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(16.dp)
                                        .verticalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    text = content,
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                )
                            }
                        }
                        else ->
                            EmptyState(
                                icon = Icons.Filled.Extension,
                                title = stringResource(R.string.skills_preview_empty),
                            )
                    }
                }
            }
        }
    }
}

// ── Skill Editor Dialog (existing, preserved unchanged) ────────────────

@Composable
fun SkillEditorDialog(
    skillName: String,
    isLoading: Boolean,
    initialContent: String?,
    isSaving: Boolean,
    saveSuccess: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    onClearSaveSuccess: () -> Unit,
) {
    var contentText by remember(initialContent) { mutableStateOf(initialContent.orEmpty()) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val hasChanges =
        remember(initialContent, contentText) {
            val original = initialContent.orEmpty()
            original != contentText
        }

    LaunchedEffect(saveSuccess) {
        if (saveSuccess) {
            onClearSaveSuccess()
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = {
            if (hasChanges) {
                showDiscardConfirm = true
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
        ) {
            HermesScaffold(
                title = { Text(stringResource(R.string.skills_edit_title, skillName)) },
                navigationIcon =
                    NavIcon.Back(
                        onBack = {
                            if (hasChanges) {
                                showDiscardConfirm = true
                            } else {
                                onDismiss()
                            }
                        },
                    ),
                actions = {
                    if (!isLoading) {
                        IconButton(
                            onClick = { onSave(contentText) },
                            enabled = !isSaving,
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.fillMaxSize(0.6f),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Save,
                                    contentDescription = stringResource(R.string.content_desc_save_changes),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                ) {
                    when {
                        isLoading -> {
                            LoadingState()
                        }

                        else -> {
                            OutlinedTextField(
                                value = contentText,
                                onValueChange = { contentText = it },
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                textStyle =
                                    TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                placeholder = {
                                    Text(stringResource(R.string.skills_content_placeholder))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.skills_discard_title)) },
            text = { Text(stringResource(R.string.skills_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.action_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
