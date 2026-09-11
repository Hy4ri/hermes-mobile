package com.m57.hermescontrol.ui.skills.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.ui.common.DetailDialog
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.FilterChipRow
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.listItemSpacing
import com.m57.hermescontrol.ui.common.toDetailRows
import com.m57.hermescontrol.ui.skills.CATEGORY_ALL
import com.m57.hermescontrol.ui.skills.SkillFilter
import com.m57.hermescontrol.ui.skills.SkillListFilter
import com.m57.hermescontrol.ui.skills.SkillsUiState

@Composable
fun InstalledSkillsView(
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
    onBrowseHub: () -> Unit,
) {
    var showDetail by remember { mutableStateOf<Skill?>(null) }

    val categories =
        remember(state.skills) {
            SkillListFilter.extractCategories(state.skills)
        }
    val sources =
        remember(state.skills) {
            SkillListFilter.extractSources(state.skills)
        }

    val filteredSkills =
        remember(state.skills, query, selectedStatus, selectedCategory, sourceFilter) {
            SkillListFilter.filterSkills(
                skills = state.skills,
                query = query,
                selectedStatus = selectedStatus,
                selectedCategory = selectedCategory,
                sourceFilter = sourceFilter,
            )
        }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.skills_search_placeholder),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )

        FilterChipRow(
            chips = SkillFilter.entries.toList(),
            selectedChip = selectedStatus,
            onChipSelected = onStatusChange,
            chipLabel = { chip ->
                Text(
                    text =
                        if (chip == SkillFilter.ALL_STATUSES) {
                            stringResource(R.string.skills_category_all)
                        } else {
                            stringResource(chip.labelRes)
                        },
                )
            },
        )

        if (categories.isNotEmpty() || sources.isNotEmpty()) {
            FilterChipRow(
                chips = listOf(stringResource(R.string.skills_category_all)) + categories,
                selectedChip = selectedCategory,
                onChipSelected = onCategoryChange,
            )
            if (sources.isNotEmpty()) {
                FilterChipRow(
                    chips = listOf<String?>(null) + sources,
                    selectedChip = sourceFilter,
                    onChipSelected = onSetSourceFilter,
                    chipLabel = { source ->
                        Text(
                            text = source ?: stringResource(R.string.skills_source_all),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }

        when {
            state.isLoading -> {
                SkeletonListState()
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
                    actionLabel = stringResource(R.string.empty_action_browse_hub),
                    onAction = onBrowseHub,
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
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
                            onClick = { showDetail = skill },
                        )
                    }
                }
            }
        }
    }

    showDetail?.let { skill ->
        DetailDialog(
            title = skill.name,
            rows = skill.toDetailRows(),
            onDismiss = { showDetail = null },
        )
    }
}
