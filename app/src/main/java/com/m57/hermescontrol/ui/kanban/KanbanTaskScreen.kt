package com.m57.hermescontrol.ui.kanban

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.KanbanTaskDetailKey
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.KanbanProfile
import com.m57.hermescontrol.data.model.KanbanTaskFull
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.model.TaskEstimate
import com.m57.hermescontrol.data.model.TaskLinks
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.MarkdownText
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.kanban.components.KanbanModelOverrideEditor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanTaskScreen(
    boardSlug: String,
    taskId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KanbanTaskViewModel = viewModel { KanbanTaskViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs =
        listOf(
            stringResource(R.string.kanban_tab_overview),
            stringResource(R.string.kanban_tab_discussion),
            stringResource(R.string.kanban_tab_runs),
            stringResource(R.string.kanban_tab_activity),
            stringResource(R.string.kanban_tab_files),
        )
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(boardSlug, taskId) {
        viewModel.loadTask(boardSlug, taskId)
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = {
            Column {
                Text(
                    text = state.detail?.task?.title ?: stringResource(R.string.kanban_task_details),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = "#${taskId.removePrefix("t_").take(8)} • $boardSlug",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        navigationIcon = NavIcon.Back(onBack),
        actions = {
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.kanban_delete_task),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        drawerGesturesEnabled = false,
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadTask(boardSlug, taskId) },
    ) { paddingValues ->
        when {
            state.isLoading && state.detail == null -> {
                SkeletonListState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null && state.detail == null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadTask(boardSlug, taskId) },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            state.detail != null -> {
                val detail = state.detail!!
                val task = detail.task

                Column(modifier = Modifier.fillMaxSize()) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = selectedTabIndex,
                        edgePadding = 16.dp,
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabIndex = index },
                                text = { Text(title) },
                            )
                        }
                    }

                    when (selectedTabIndex) {
                        0 -> {
                            TaskOverviewTab(
                                task = task,
                                links = detail.links,
                                profiles = state.profiles,
                                modelProviders = state.modelProviders,
                                pinnedModels = state.pinnedModels,
                                estimate = state.estimate,
                                isEstimating = state.isEstimating,
                                onReassign = { newProfile ->
                                    viewModel.reassign(boardSlug, taskId, newProfile)
                                },
                                onUpdateModel = { override ->
                                    viewModel.updateModelOverride(boardSlug, taskId, override)
                                },
                                onEstimate = {
                                    viewModel.estimateTask(boardSlug, taskId)
                                },
                                onNavigateToTask = { targetId ->
                                    NavigationController.navigateTo(
                                        KanbanTaskDetailKey(boardSlug = boardSlug, taskId = targetId),
                                    )
                                },
                                onSaveDescription = { newBody ->
                                    viewModel.updateDescription(boardSlug, taskId, newBody)
                                },
                                onReclaim = {
                                    viewModel.reclaim(boardSlug, taskId)
                                },
                            )
                        }

                        1 -> {
                            TaskDiscussionTab(
                                comments = detail.comments,
                                isRunning = task.status.equals("running", ignoreCase = true),
                                isSubmitting = state.isSubmittingComment,
                                onAddComment = { body ->
                                    viewModel.addComment(boardSlug, taskId, body)
                                },
                                onNoteAndRequeue = { body ->
                                    viewModel.postNoteAndRequeue(boardSlug, taskId, body)
                                },
                            )
                        }

                        2 -> {
                            TaskRunsTab(
                                runs = detail.runs,
                                workerLog = state.workerLog,
                            )
                        }

                        3 -> {
                            TaskActivityTab(
                                events = detail.events,
                            )
                        }

                        4 -> {
                            TaskFilesTab(
                                attachments = detail.attachments ?: emptyList(),
                                isUploading = state.isUploadingAttachment,
                                onUpload = { filename, mimeType, bytes ->
                                    viewModel.uploadAttachment(boardSlug, taskId, filename, mimeType, bytes)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.kanban_delete_task)) },
            text = { Text(stringResource(R.string.kanban_delete_task_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteTask(boardSlug, taskId, onSuccess = onBack)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.kanban_delete_task))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun TaskOverviewTab(
    task: KanbanTaskFull,
    links: TaskLinks,
    profiles: List<KanbanProfile>,
    modelProviders: List<ModelProvider>,
    pinnedModels: List<PinnedModel>,
    estimate: TaskEstimate?,
    isEstimating: Boolean,
    onReassign: (String?) -> Unit,
    onUpdateModel: (KanbanModelOverride) -> Unit,
    onEstimate: () -> Unit,
    onNavigateToTask: (String) -> Unit,
    onSaveDescription: (String) -> Unit,
    onReclaim: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isEditingDescription by remember { mutableStateOf(false) }
    var descriptionDraft by remember(task.body) { mutableStateOf(task.body ?: "") }
    var assigneeMenuExpanded by remember { mutableStateOf(false) }
    val statusColors = LocalHermesStatusColors.current

    val currentModelOverride =
        remember(task.modelOverride, task.providerOverride, task.reasoningEffort) {
            KanbanModelOverride.fromTask(task.modelOverride, task.providerOverride, task.reasoningEffort)
        }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // Diagnostics alerts
            if (task.diagnostics.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    task.diagnostics.forEach { diag ->
                        Card(
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = statusColors.error.copy(alpha = 0.15f),
                                ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = statusColors.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = diag.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = statusColors.error,
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = diag.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (diag.actions.any { it.kind == "reclaim" }) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = onReclaim,
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Text(stringResource(R.string.kanban_reclaim_task))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Metadata / Attributes card
            Card(
                shape = RoundedCornerShape(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Attributes",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    MetaRow(label = "Status", value = task.status.uppercase())

                    // Editable Assignee
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.kanban_assignee),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box {
                            TextButton(
                                onClick = { assigneeMenuExpanded = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                            ) {
                                Text(
                                    text = task.assignee ?: "Unassigned",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            DropdownMenu(
                                expanded = assigneeMenuExpanded,
                                onDismissRequest = { assigneeMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.kanban_assignee_unassigned)) },
                                    onClick = {
                                        onReassign(null)
                                        assigneeMenuExpanded = false
                                    },
                                )
                                profiles.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name) },
                                        onClick = {
                                            onReassign(p.name)
                                            assigneeMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    task.priority?.let { MetaRow(label = "Priority", value = it.toString()) }
                    task.tenant?.let { MetaRow(label = "Tenant", value = it) }
                    task.workspaceKind?.let { MetaRow(label = "Workspace", value = "$it ${task.workspacePath ?: ""}") }
                    task.workerPid?.let { MetaRow(label = "Worker PID", value = it.toString()) }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // Editable Model & Reasoning Override
                    KanbanModelOverrideEditor(
                        override = currentModelOverride,
                        onOverrideChange = onUpdateModel,
                        modelProviders = modelProviders,
                        pinnedModels = pinnedModels,
                    )
                }
            }

            // Dependencies Section
            if (links.parents.isNotEmpty() || links.children.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.kanban_dependencies),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (links.parents.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.kanban_blocked_by),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            ) {
                                links.parents.forEach { parentId ->
                                    SuggestionChip(
                                        onClick = { onNavigateToTask(parentId) },
                                        label = { Text("#${parentId.removePrefix("t_").take(6)}") },
                                    )
                                }
                            }
                        }
                        if (links.children.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.kanban_blocks),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            ) {
                                links.children.forEach { childId ->
                                    SuggestionChip(
                                        onClick = { onNavigateToTask(childId) },
                                        label = { Text("#${childId.removePrefix("t_").take(6)}") },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Effort Estimation Section
            Card(
                shape = RoundedCornerShape(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.kanban_estimate),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (isEstimating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    val currentEst = estimate
                    if (currentEst != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        currentEst.tokens?.let { tokensCount ->
                            Text(
                                text = stringResource(R.string.kanban_estimated_tokens, tokensCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (!currentEst.complexity.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.kanban_complexity, currentEst.complexity),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (!currentEst.rationale.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = currentEst.rationale,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onEstimate,
                            enabled = !isEstimating,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.kanban_reestimate_action))
                        }
                    } else {
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = onEstimate,
                            enabled = !isEstimating,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.kanban_estimate_action))
                        }
                    }
                }
            }

            // Description Section
            Card(
                shape = RoundedCornerShape(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.kanban_description),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (!isEditingDescription) {
                            IconButton(onClick = { isEditingDescription = true }) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.kanban_edit_description),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    if (isEditingDescription) {
                        OutlinedTextField(
                            value = descriptionDraft,
                            onValueChange = { descriptionDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { isEditingDescription = false }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                onSaveDescription(descriptionDraft)
                                isEditingDescription = false
                            }) {
                                Text(stringResource(R.string.action_save))
                            }
                        }
                    } else {
                        SelectionContainer {
                            MarkdownText(
                                text = task.body?.ifBlank { "No description provided." } ?: "No description provided.",
                                textColor = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            // Result/Summary Section if completed
            val summary = task.result ?: task.latestSummary
            if (!summary.isNullOrBlank()) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Latest Outcome / Summary",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        MarkdownText(
                            text = summary,
                            textColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskDiscussionTab(
    comments: List<com.m57.hermescontrol.data.model.KanbanComment>,
    isRunning: Boolean,
    isSubmitting: Boolean,
    onAddComment: (String) -> Unit,
    onNoteAndRequeue: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newCommentText by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (comments.isEmpty()) {
                item {
                    Text(stringResource(R.string.kanban_no_comments), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                items(comments, key = { it.id }) { comment ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = comment.author,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            MarkdownText(
                                text = comment.body,
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = newCommentText,
            onValueChange = { newCommentText = it },
            placeholder = { Text(stringResource(R.string.kanban_write_comment)) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
            enabled = !isSubmitting,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (isRunning) {
                OutlinedButton(
                    onClick = {
                        if (newCommentText.isNotBlank()) {
                            onNoteAndRequeue(newCommentText.trim())
                            newCommentText = ""
                        }
                    },
                    enabled = newCommentText.isNotBlank() && !isSubmitting,
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.kanban_note_requeue))
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Button(
                onClick = {
                    if (newCommentText.isNotBlank()) {
                        onAddComment(newCommentText.trim())
                        newCommentText = ""
                    }
                },
                enabled = newCommentText.isNotBlank() && !isSubmitting,
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(stringResource(R.string.kanban_comment))
            }
        }
    }
}

private fun formatRunDuration(durationSeconds: Long): String =
    when {
        durationSeconds < 60L -> "${durationSeconds}s"
        durationSeconds < 3600L -> "${durationSeconds / 60L}m"
        else -> "${durationSeconds / 3600L}h"
    }

@Composable
private fun TaskRunsTab(
    runs: List<com.m57.hermescontrol.data.model.KanbanRun>,
    workerLog: com.m57.hermescontrol.data.model.WorkerLog?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.kanban_execution_runs),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        if (runs.isEmpty()) {
            item {
                Text(stringResource(R.string.kanban_no_run_history), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            items(runs, key = { it.id }) { run ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.kanban_run_number, run.id),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = run.status.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color =
                                    if (run.status.equals("done", ignoreCase = true)) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                            )
                        }
                        run.profile?.let {
                            Text(
                                stringResource(R.string.kanban_run_profile, it),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        run.outcome?.let {
                            Text(
                                stringResource(R.string.kanban_run_outcome, it),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (run.startedAt != null && run.endedAt != null && run.endedAt >= run.startedAt) {
                            Text(
                                stringResource(
                                    R.string.kanban_run_duration,
                                    formatRunDuration(run.endedAt - run.startedAt),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        run.error?.let {
                            Text(
                                stringResource(R.string.kanban_run_error, it),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        run.summary?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                stringResource(R.string.kanban_run_summary, it),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        if (workerLog != null && workerLog.exists && workerLog.content.isNotBlank()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(R.string.kanban_worker_log),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = workerLog.content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun localizedKanbanEventLabel(event: FormattedKanbanEvent): String =
    when (event.message) {
        KanbanEventMessage.CREATED -> {
            stringResource(R.string.kanban_event_created, event.arguments.getOrElse(0) { "Todo" })
        }

        KanbanEventMessage.CREATED_BY -> {
            stringResource(
                R.string.kanban_event_created_by,
                event.arguments.getOrElse(0) { "Todo" },
                event.arguments.getOrElse(1) { "" },
            )
        }

        KanbanEventMessage.MOVED -> {
            stringResource(R.string.kanban_event_moved, event.arguments.getOrElse(0) { "?" })
        }

        KanbanEventMessage.ASSIGNED -> {
            stringResource(R.string.kanban_event_assigned, event.arguments.getOrElse(0) { "" })
        }

        KanbanEventMessage.UNASSIGNED -> {
            stringResource(R.string.kanban_event_unassigned)
        }

        KanbanEventMessage.COMMENTED -> {
            stringResource(R.string.kanban_event_commented, event.arguments.getOrElse(0) { "someone" })
        }

        KanbanEventMessage.CLAIMED_REVIEW -> {
            stringResource(R.string.kanban_event_claimed_review)
        }

        KanbanEventMessage.CLAIMED_WORKER -> {
            stringResource(R.string.kanban_event_claimed_worker)
        }

        KanbanEventMessage.WORKER_STARTED -> {
            stringResource(R.string.kanban_event_worker_started)
        }

        KanbanEventMessage.COMPLETED -> {
            stringResource(R.string.kanban_event_completed)
        }

        KanbanEventMessage.BLOCKED -> {
            stringResource(R.string.kanban_event_blocked)
        }

        KanbanEventMessage.UNBLOCKED -> {
            if (event.arguments.isEmpty()) {
                stringResource(R.string.kanban_event_unblocked)
            } else {
                stringResource(R.string.kanban_event_unblocked_to, event.arguments[0])
            }
        }

        KanbanEventMessage.RECLAIMED -> {
            stringResource(R.string.kanban_event_reclaimed)
        }

        KanbanEventMessage.SPECIFIED -> {
            stringResource(R.string.kanban_event_specified)
        }

        KanbanEventMessage.PROMOTED -> {
            stringResource(R.string.kanban_event_promoted)
        }

        KanbanEventMessage.SCHEDULED -> {
            stringResource(R.string.kanban_event_scheduled)
        }

        KanbanEventMessage.ARCHIVED -> {
            stringResource(R.string.kanban_event_archived)
        }

        KanbanEventMessage.PRIORITY -> {
            stringResource(R.string.kanban_event_priority, event.arguments.getOrElse(0) { "?" })
        }

        KanbanEventMessage.UNKNOWN -> {
            event.label
        }
    }

@Composable
private fun TaskActivityTab(
    events: List<com.m57.hermescontrol.data.model.KanbanDetailEvent>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.kanban_event_timeline),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        if (events.isEmpty()) {
            item {
                Text(stringResource(R.string.kanban_no_activity), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            items(events, key = { it.id }) { event ->
                val formatted = remember(event) { KanbanEventFormatter.format(event) }
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .padding(top = 5.dp)
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.primary, shape = CircleShape),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = localizedKanbanEventLabel(formatted),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!formatted.detail.isNullOrBlank()) {
                            Text(
                                text = formatted.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaRow(
    label: String,
    value: String,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TaskFilesTab(
    attachments: List<com.m57.hermescontrol.data.model.KanbanAttachment>,
    isUploading: Boolean,
    onUpload: (filename: String, mimeType: String, bytes: ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: Uri? ->
            if (uri != null) {
                var filename = "attachment"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        filename = cursor.getString(nameIndex) ?: "attachment"
                    }
                }
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes =
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: byteArrayOf()
                if (bytes.isNotEmpty()) {
                    onUpload(filename, mimeType, bytes)
                }
            }
        }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.kanban_attachments),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Button(
                onClick = { launcher.launch("*/*") },
                enabled = !isUploading,
            ) {
                if (isUploading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(stringResource(R.string.kanban_upload))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (attachments.isEmpty()) {
                item {
                    Text(stringResource(R.string.kanban_no_attachments), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                items(attachments, key = { it.id }) { att ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Icon(
                                Icons.Filled.Description,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = att.filename.ifBlank { "File #${att.id}" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                att.size?.let {
                                    Text(
                                        text = "${it / 1024} KB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
