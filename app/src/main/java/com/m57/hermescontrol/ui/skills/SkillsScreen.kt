package com.m57.hermescontrol.ui.skills

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.skills.components.HubBrowseView
import com.m57.hermescontrol.ui.skills.components.InstalledSkillsView
import com.m57.hermescontrol.ui.skills.components.SkillEditorDialog
import com.m57.hermescontrol.ui.skills.components.SkillPreviewDialog

internal const val CATEGORY_ALL = "All"

@OptIn(ExperimentalMaterial3Api::class)
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
        isRefreshing = state.isLoading,
        onRefresh = {
            if (state.viewMode == SkillsViewMode.INSTALLED) {
                viewModel.loadSkills()
            } else {
                viewModel.loadHubSources()
            }
        },
        actions = {
            if (state.viewMode == SkillsViewMode.INSTALLED) {
                IconButton(onClick = { viewModel.updateSkillsFromHub() }) {
                    Icon(
                        imageVector = Icons.Filled.CloudDownload,
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
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                SegmentedButton(
                    selected = state.viewMode == SkillsViewMode.INSTALLED,
                    onClick = { viewModel.setViewMode(SkillsViewMode.INSTALLED) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.skills_mode_installed))
                }
                SegmentedButton(
                    selected = state.viewMode == SkillsViewMode.HUB,
                    onClick = { viewModel.setViewMode(SkillsViewMode.HUB) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.skills_mode_hub))
                }
            }

            when (state.viewMode) {
                SkillsViewMode.INSTALLED -> {
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
                        onBrowseHub = { viewModel.setViewMode(SkillsViewMode.HUB) },
                    )
                }

                SkillsViewMode.HUB -> {
                    HubBrowseView(
                        state = state,
                        hubQuery = state.hubQuery,
                        onHubQueryChange = viewModel::setHubQuery,
                        onSearch = viewModel::searchHub,
                        onClearSearch = viewModel::clearHubSearch,
                        onInstall = viewModel::installSkill,
                        onPreviewHubSkill = viewModel::previewHubSkill,
                        onScanHubSkill = viewModel::scanHubSkill,
                        onClearHubScan = viewModel::clearHubScan,
                        onLoadHubSources = viewModel::loadHubSources,
                        isInstalling = state.isInstalling,
                        installingSkillName = state.installingSkillName,
                    )
                }
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
