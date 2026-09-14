package com.m57.hermescontrol.ui.kanban

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.MarkdownText
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect

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
    val tabs = listOf("Overview", "Discussion", "Runs", "Activity")

    LaunchedEffect(boardSlug, taskId) {
        viewModel.loadTask(boardSlug, taskId)
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = {
            Column {
                Text(
                    text = state.detail?.task?.title ?: "Task Details",
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
                    PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
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
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskOverviewTab(
    task: com.m57.hermescontrol.data.model.KanbanTaskFull,
    onSaveDescription: (String) -> Unit,
    onReclaim: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isEditingDescription by remember { mutableStateOf(false) }
    var descriptionDraft by remember(task.body) { mutableStateOf(task.body ?: "") }
    val statusColors = LocalHermesStatusColors.current

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
                                        Text("Reclaim task")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Metadata card
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
                    task.assignee?.let { MetaRow(label = "Assignee", value = it) }
                    task.priority?.let { MetaRow(label = "Priority", value = it.toString()) }
                    task.tenant?.let { MetaRow(label = "Tenant", value = it) }
                    task.modelOverride?.let { MetaRow(label = "Model Override", value = it) }
                    task.reasoningEffort?.let { MetaRow(label = "Reasoning Effort", value = it) }
                    task.workspaceKind?.let { MetaRow(label = "Workspace", value = "$it ${task.workspacePath ?: ""}") }
                    task.workerPid?.let { MetaRow(label = "Worker PID", value = it.toString()) }
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
                            text = "Description",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (!isEditingDescription) {
                            IconButton(onClick = { isEditingDescription = true }) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = "Edit Description",
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
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                onSaveDescription(descriptionDraft)
                                isEditingDescription = false
                            }) {
                                Text("Save")
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
                        SelectionContainer {
                            MarkdownText(
                                text = summary,
                                textColor = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
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
    var commentText by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (comments.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No comments yet. Post a note to steer the agent.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                items(comments, key = { it.id }) { comment ->
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = comment.author,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            SelectionContainer {
                                MarkdownText(
                                    text = comment.body,
                                    textColor = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            OutlinedTextField(
                value = commentText,
                onValueChange = { commentText = it },
                placeholder = { Text("Write a steering note...") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (isRunning) {
                    OutlinedButton(
                        onClick = {
                            onNoteAndRequeue(commentText)
                            commentText = ""
                        },
                        enabled = commentText.isNotBlank() && !isSubmitting,
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Note & Requeue")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Button(
                    onClick = {
                        onAddComment(commentText)
                        commentText = ""
                    },
                    enabled = commentText.isNotBlank() && !isSubmitting,
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Post")
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRunsTab(
    runs: List<com.m57.hermescontrol.data.model.KanbanRun>,
    workerLog: com.m57.hermescontrol.data.model.WorkerLog?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Attempt History", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }

        if (runs.isEmpty()) {
            item {
                Text("No worker attempts recorded yet.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            items(runs, key = { it.id }) { run ->
                Card(
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Run #${run.id}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = (run.outcome ?: run.status).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color =
                                    if (run.outcome == "completed") {
                                        LocalHermesStatusColors.current.success
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                        run.profile?.let {
                            Text("Profile: $it", style = MaterialTheme.typography.bodySmall)
                        }
                        val summary = run.error ?: run.summary
                        if (!summary.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = summary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Live Log Output
        if (workerLog != null && workerLog.exists && workerLog.content.isNotBlank()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Worker Log Output", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
private fun TaskActivityTab(
    events: List<com.m57.hermescontrol.data.model.KanbanDetailEvent>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Event Timeline", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }

        if (events.isEmpty()) {
            item {
                Text("No activity events.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            items(events, key = { it.id }) { event ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, shape = CircleShape),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = event.kind,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
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
