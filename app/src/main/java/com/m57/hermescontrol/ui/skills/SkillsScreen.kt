package com.m57.hermescontrol.ui.skills

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.FilterChipRow
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

@Composable
fun SkillsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: SkillsViewModel = viewModel { SkillsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    var query by remember { mutableStateOf("") }
    val statuses = listOf("All Statuses", "Enabled", "Disabled")
    var selectedStatus by remember { mutableStateOf("All Statuses") }
    val categories =
        remember(state.skills) {
            listOf("All") + state.skills.mapNotNull { it.category }.distinct().sorted()
        }
    var selectedCategory by remember { mutableStateOf("All") }

    LaunchedEffect(Unit) {
        viewModel.loadSkills()
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val filtered =
        remember(state.skills, query, selectedStatus, selectedCategory) {
            state.skills.filter { skill ->
                val matchesQuery =
                    if (query.isBlank()) {
                        true
                    } else {
                        skill.name.contains(query, ignoreCase = true) ||
                            skill.category?.contains(query, ignoreCase = true) == true ||
                            skill.description?.contains(query, ignoreCase = true) == true
                    }

                val matchesStatus =
                    when (selectedStatus) {
                        "Enabled" -> skill.enabled
                        "Disabled" -> !skill.enabled
                        else -> true
                    }

                val matchesCategory =
                    if (selectedCategory == "All") {
                        true
                    } else {
                        skill.category == selectedCategory
                    }

                matchesQuery && matchesStatus && matchesCategory
            }
        }

    HermesScaffold(
        title = { Text("Skills") },
        onOpenDrawer = onOpenDrawer,
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadSkills() },
    ) {
        when {
            state.isLoading && state.skills.isEmpty() -> {
                LoadingState()
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "Unknown error",
                    onRetry = { viewModel.loadSkills() },
                )
            }

            state.skills.isEmpty() -> {
                EmptyState(
                    title = "No skills found",
                    subtitle = "Skills loaded from Hermes will appear here.",
                    icon = Icons.Filled.Extension,
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    item {
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search skills…",
                        )
                    }
                    item {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = spacing.md),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            Text(
                                text = "Status",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                            ) {
                                statuses.forEach { status ->
                                    FilterChip(
                                        selected = selectedStatus == status,
                                        onClick = { selectedStatus = status },
                                        label = { Text(status) },
                                    )
                                }
                            }
                        }
                    }
                    if (categories.size > 2) {
                        item {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = spacing.xs),
                                verticalArrangement = Arrangement.spacedBy(spacing.xs),
                            ) {
                                Text(
                                    text = "Category",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = spacing.md),
                                )
                                FilterChipRow(
                                    chips = categories,
                                    selectedChip = selectedCategory,
                                    onChipSelected = { selectedCategory = it },
                                )
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = spacing.lg),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                                ) {
                                    Text(
                                        text = "No matching skills",
                                        style =
                                            MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                            ),
                                    )
                                    Text(
                                        text = "Try adjusting your search or filters.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else {
                        items(filtered) { skill ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    ),
                            ) {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(spacing.md),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
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
                                                text = skill.name,
                                                style =
                                                    MaterialTheme.typography.titleMedium.copy(
                                                        fontWeight = FontWeight.SemiBold,
                                                    ),
                                            )
                                            skill.category?.let { cat ->
                                                Text(
                                                    text = cat,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            skill.description?.let { desc ->
                                                Text(
                                                    text = desc,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                )
                                            }
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        IconButton(
                                            onClick = { viewModel.loadSkillContent(skill.name) },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Edit,
                                                contentDescription = "Edit Skill File",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Switch(
                                            checked = skill.enabled,
                                            onCheckedChange = { viewModel.toggleSkill(skill) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

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
}

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
                title = { Text("Edit: $skillName") },
                onBack = {
                    if (hasChanges) {
                        showDiscardConfirm = true
                    } else {
                        onDismiss()
                    }
                },
                showBack = true,
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
                                    contentDescription = "Save Changes",
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
                                    Text("Enter skill content...")
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
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved changes. Are you sure you want to discard them?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        onDismiss()
                    },
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
