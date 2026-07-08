package com.m57.hermescontrol.ui.skills.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.DetailDialog
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.FilterChipRow
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.ui.skills.CATEGORY_ALL
import com.m57.hermescontrol.ui.skills.SkillFilter
import com.m57.hermescontrol.ui.skills.SkillsUiState

@Composable
internal fun InstalledSkillsView(
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
    var showDetail by remember { mutableStateOf<Skill?>(null) }

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
            state.isLoading -> {
                LoadingState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage,
                    onRetry = onRefresh,
                )
            }

            filteredSkills.isEmpty() -> {
                EmptyState(
                    icon = Icons.Filled.Extension,
                    title = stringResource(R.string.skills_empty_message),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.padding(listContentPadding),
                    verticalArrangement = listItemSpacing,
                ) {
                    itemsIndexed(
                        filteredSkills,
                        key = { index, skill -> "${skill.name}:${skill.source ?: "unknown"}:$index" },
                    ) { _, skill ->
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
                            onClick = {
                                showDetail = skill
                            },
                        )
                    }
                }
            }
        }
    }

    showDetail?.let { skill ->
        DetailDialog(
            title = skill.name,
            rows =
                listOf(
                    stringResource(R.string.detail_dialog_category) to (skill.category ?: ""),
                    stringResource(R.string.detail_dialog_source) to (skill.source ?: ""),
                    stringResource(R.string.detail_dialog_description) to (skill.description ?: ""),
                ),
            onDismiss = { showDetail = null },
        )
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    onToggle: () -> Unit,
    onAction: () -> Unit,
    onUninstall: (() -> Unit)?,
    isUninstalling: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                            .fillMaxSize(),
                ) {
                    when {
                        isLoading -> {
                            LoadingState()
                        }

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

                        else -> {
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
}
