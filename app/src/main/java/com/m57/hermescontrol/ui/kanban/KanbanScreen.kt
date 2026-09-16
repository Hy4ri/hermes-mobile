package com.m57.hermescontrol.ui.kanban

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.KanbanTaskDetailKey
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.kanban.components.KanbanCreateTaskDialog
import com.m57.hermescontrol.ui.kanban.components.KanbanFilterSheet
import com.m57.hermescontrol.ui.kanban.components.KanbanTaskCard

private const val DEFAULT_COLUMN = "todo"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: KanbanViewModel = viewModel { KanbanViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var selectedAssignee by remember { mutableStateOf<String?>(null) }
    var selectedTenant by remember { mutableStateOf<String?>(null) }
    var includeArchived by remember { mutableStateOf(false) }
    var groupRunning by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }

    val filteredTasks =
        remember(query, state.tasks, selectedAssignee, selectedTenant) {
            state.tasks.filter { task ->
                val matchesQuery =
                    query.isBlank() ||
                        task.title.contains(query, ignoreCase = true) ||
                        task.description?.contains(query, ignoreCase = true) == true ||
                        task.status.contains(query, ignoreCase = true) ||
                        task.assignedTo?.contains(query, ignoreCase = true) == true ||
                        task.id.contains(query, ignoreCase = true)
                val matchesAssignee =
                    selectedAssignee == null || task.assignee.equals(selectedAssignee, ignoreCase = true)
                val matchesTenant = selectedTenant == null || task.tenant.equals(selectedTenant, ignoreCase = true)
                matchesQuery && matchesAssignee && matchesTenant
            }
        }

    val tasksByColumn =
        remember(filteredTasks) {
            filteredTasks.groupBy { it.status.lowercase() }
        }
    var showAddTaskDialog by remember { mutableStateOf(false) }
    var taskForActions by remember { mutableStateOf<KanbanTask?>(null) }
    var confirmTarget by remember { mutableStateOf<Pair<KanbanTask, KanbanTaskAction>?>(null) }
    var summaryTarget by remember { mutableStateOf<Pair<KanbanTask, KanbanTaskAction>?>(null) }

    // Multi-select & Bulk operations state
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedTaskIds by remember { mutableStateOf(setOf<String>()) }
    var showBulkMoveDialog by remember { mutableStateOf(false) }

    // Board management dialogs
    var showBoardMenu by remember { mutableStateOf(false) }
    var showCreateBoardDialog by remember { mutableStateOf(false) }
    var showRenameBoardDialog by remember { mutableStateOf(false) }
    var showDeleteBoardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadBoards()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.kanban_board_title)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        actions = {
            IconButton(onClick = { showFilterSheet = true }) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = "Filter",
                )
            }
            Box {
                IconButton(onClick = { showBoardMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Board options",
                    )
                }
                DropdownMenu(
                    expanded = showBoardMenu,
                    onDismissRequest = { showBoardMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("New Board") },
                        onClick = {
                            showBoardMenu = false
                            showCreateBoardDialog = true
                        },
                    )
                    if (state.selectedBoard != null) {
                        DropdownMenuItem(
                            text = { Text("Rename Board") },
                            onClick = {
                                showBoardMenu = false
                                showRenameBoardDialog = true
                            },
                        )
                        if (state.boards.size > 1) {
                            DropdownMenuItem(
                                text = { Text("Delete Board") },
                                onClick = {
                                    showBoardMenu = false
                                    showDeleteBoardDialog = true
                                },
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text(if (isMultiSelectMode) "Exit Multi-Select" else "Select Multiple") },
                        onClick = {
                            showBoardMenu = false
                            isMultiSelectMode = !isMultiSelectMode
                            if (!isMultiSelectMode) selectedTaskIds = emptySet()
                        },
                    )
                }
            }
        },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadBoards() },
    ) { paddingValues ->
        when {
            state.isLoading && state.boards.isEmpty() -> {
                SkeletonListState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadBoards() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                Box(Modifier.fillMaxSize()) {
                    if (state.isLoading) {
                        CircularProgressIndicator()
                    } else if (state.errorMessage != null) {
                        Text(
                            text = state.errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(paddingValues),
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            SearchBar(
                                query = query,
                                onQueryChange = { query = it },
                                placeholder = stringResource(R.string.kanban_filter_placeholder),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            // Board selector tab row
                            if (state.boards.isNotEmpty()) {
                                PrimaryScrollableTabRow(
                                    selectedTabIndex = state.boards.indexOf(state.selectedBoard).coerceAtLeast(0),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    state.boards.forEach { board ->
                                        Tab(
                                            selected = board == state.selectedBoard,
                                            onClick = { viewModel.selectBoard(board) },
                                            text = { Text(board.displayName) },
                                        )
                                    }
                                }
                            }

                            if (state.selectedBoard == null) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.kanban_no_boards))
                                }
                            } else if (state.columns.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.kanban_no_columns))
                                }
                            } else {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    LiveStatusPill(isLive = state.isLive)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(onClick = { showFilterSheet = true }) {
                                        Icon(
                                            imageVector = Icons.Filled.FilterList,
                                            contentDescription = "Filters",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(onClick = { showAddTaskDialog = true }) {
                                        Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = stringResource(R.string.kanban_add_task),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                LazyRow(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding =
                                        PaddingValues(
                                            start = 16.dp,
                                            top = 16.dp,
                                            end = 16.dp,
                                            bottom = 16.dp,
                                        ),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(state.columns.size, key = { index ->
                                        state.columns[index].name
                                    }) { columnIndex ->
                                        val column = state.columns[columnIndex]
                                        val colName = column.name
                                        val colTasks = tasksByColumn[colName.lowercase()] ?: emptyList()

                                        Column(
                                            modifier =
                                                Modifier
                                                    .width(280.dp)
                                                    .fillMaxSize(),
                                        ) {
                                            Text(
                                                text = "${colName.replaceFirstChar { it.uppercase() }} (${
                                                    colTasks.size
                                                })",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(bottom = 8.dp),
                                            )

                                            if (colTasks.isEmpty()) {
                                                Box(
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 16.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.kanban_no_tasks),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            } else {
                                                LazyColumn(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    items(colTasks, key = { it.id }) { task ->
                                                        KanbanTaskCard(
                                                            task = task,
                                                            isSelected =
                                                                isMultiSelectMode && task.id in selectedTaskIds,
                                                            onTaskClick = { clickedTask ->
                                                                if (isMultiSelectMode) {
                                                                    selectedTaskIds =
                                                                        if (clickedTask.id in selectedTaskIds) {
                                                                            selectedTaskIds - clickedTask.id
                                                                        } else {
                                                                            selectedTaskIds + clickedTask.id
                                                                        }
                                                                } else {
                                                                    state.selectedBoard?.let { board ->
                                                                        NavigationController.navigateTo(
                                                                            KanbanTaskDetailKey(
                                                                                boardSlug = board.id,
                                                                                taskId = clickedTask.id,
                                                                            ),
                                                                        )
                                                                    }
                                                                }
                                                            },
                                                            onActionClick = { clickedTask ->
                                                                if (isMultiSelectMode) {
                                                                    selectedTaskIds =
                                                                        if (clickedTask.id in selectedTaskIds) {
                                                                            selectedTaskIds - clickedTask.id
                                                                        } else {
                                                                            selectedTaskIds + clickedTask.id
                                                                        }
                                                                } else {
                                                                    taskForActions = clickedTask
                                                                }
                                                            },
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

                    if (showAddTaskDialog) {
                        KanbanCreateTaskDialog(
                            columns = state.columns,
                            defaultColumn = state.columns.firstOrNull()?.name ?: DEFAULT_COLUMN,
                            profiles = state.profiles,
                            existingTasks = state.tasks,
                            isCreating = state.isCreatingTask,
                            onEstimate = viewModel::estimateNewTask,
                            onDismiss = { showAddTaskDialog = false },
                            onConfirm = { body, targetStatus ->
                                viewModel.createTask(body, targetStatus)
                                showAddTaskDialog = false
                            },
                        )
                    }

                    taskForActions?.let { task ->
                        val actions = kanbanActionsForStatus(task.status)
                        if (actions.isNotEmpty()) {
                            TaskActionSheet(
                                task = task,
                                actions = actions,
                                onAction = { action ->
                                    taskForActions = null
                                    when {
                                        action.needsSummary -> summaryTarget = task to action
                                        action.needsConfirm -> confirmTarget = task to action
                                        else -> viewModel.moveTask(task, action)
                                    }
                                },
                                onDismiss = { taskForActions = null },
                            )
                        }
                    }

                    confirmTarget?.let { (task, action) ->
                        ConfirmActionDialog(
                            message = stringResource(action.confirmRes()),
                            onConfirm = {
                                confirmTarget = null
                                if (action.needsSummary) {
                                    summaryTarget = task to action
                                } else {
                                    viewModel.moveTask(task, action)
                                }
                            },
                            onDismiss = { confirmTarget = null },
                        )
                    }

                    summaryTarget?.let { (task, action) ->
                        CompleteTaskDialog(
                            onConfirm = { summary ->
                                summaryTarget = null
                                viewModel.moveTask(task, action, summary)
                            },
                            onDismiss = { summaryTarget = null },
                        )
                    }

                    if (showFilterSheet) {
                        val assignees =
                            remember(state.tasks) {
                                state.tasks
                                    .mapNotNull { it.assignee }
                                    .distinct()
                                    .sorted()
                            }
                        val tenants =
                            remember(state.tasks) {
                                state.tasks
                                    .mapNotNull { it.tenant }
                                    .distinct()
                                    .sorted()
                            }
                        KanbanFilterSheet(
                            assignees = assignees,
                            tenants = tenants,
                            selectedAssignee = selectedAssignee,
                            selectedTenant = selectedTenant,
                            includeArchived = includeArchived,
                            groupRunning = groupRunning,
                            onSelectAssignee = { selectedAssignee = it },
                            onSelectTenant = { selectedTenant = it },
                            onToggleIncludeArchived = { includeArchived = it },
                            onToggleGroupRunning = { groupRunning = it },
                            onClearFilters = {
                                selectedAssignee = null
                                selectedTenant = null
                                includeArchived = false
                                groupRunning = false
                            },
                            onDismiss = { showFilterSheet = false },
                        )
                    }

                    if (isMultiSelectMode) {
                        Card(
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth(),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                            ) {
                                Text(
                                    text = "${selectedTaskIds.size} selected",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = {
                                            selectedTaskIds =
                                                if (selectedTaskIds.size == filteredTasks.size) {
                                                    emptySet()
                                                } else {
                                                    filteredTasks.map { it.id }.toSet()
                                                }
                                        },
                                    ) {
                                        Text(if (selectedTaskIds.size == filteredTasks.size) "Clear" else "All")
                                    }
                                    Button(
                                        onClick = { showBulkMoveDialog = true },
                                        enabled = selectedTaskIds.isNotEmpty(),
                                    ) {
                                        Text("Move")
                                    }
                                    Button(
                                        onClick = {
                                            viewModel.bulkArchive(selectedTaskIds.toList())
                                            isMultiSelectMode = false
                                            selectedTaskIds = emptySet()
                                        },
                                        enabled = selectedTaskIds.isNotEmpty(),
                                    ) {
                                        Text("Archive")
                                    }
                                    IconButton(
                                        onClick = {
                                            isMultiSelectMode = false
                                            selectedTaskIds = emptySet()
                                        },
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Close multi-select")
                                    }
                                }
                            }
                        }
                    }

                    if (showBulkMoveDialog) {
                        AlertDialog(
                            onDismissRequest = { showBulkMoveDialog = false },
                            title = { Text("Move ${selectedTaskIds.size} tasks") },
                            text = {
                                Column {
                                    state.columns.forEach { col ->
                                        TextButton(
                                            onClick = {
                                                viewModel.bulkMove(selectedTaskIds.toList(), col.name)
                                                showBulkMoveDialog = false
                                                isMultiSelectMode = false
                                                selectedTaskIds = emptySet()
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(col.name.uppercase(), modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { showBulkMoveDialog = false }) {
                                    Text("Cancel")
                                }
                            },
                        )
                    }

                    if (showCreateBoardDialog) {
                        var boardSlug by remember { mutableStateOf("") }
                        var boardName by remember { mutableStateOf("") }
                        var boardDesc by remember { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = { showCreateBoardDialog = false },
                            title = { Text("Create New Board") },
                            text = {
                                Column {
                                    OutlinedTextField(
                                        value = boardSlug,
                                        onValueChange = {
                                            boardSlug =
                                                it.lowercase().filter { c ->
                                                    c.isLetterOrDigit() || c == '-' || c == '_'
                                                }
                                        },
                                        label = { Text("Board ID / Slug") },
                                        placeholder = { Text("e.g. sprint-3") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = boardName,
                                        onValueChange = { boardName = it },
                                        label = { Text("Display Name") },
                                        placeholder = { Text("e.g. Sprint 3") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = boardDesc,
                                        onValueChange = { boardDesc = it },
                                        label = { Text("Description") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (boardSlug.isNotBlank()) {
                                            viewModel.createBoard(
                                                slug = boardSlug.trim(),
                                                name = boardName.trim().ifBlank { null },
                                                description = boardDesc.trim().ifBlank { null },
                                            )
                                            showCreateBoardDialog = false
                                        }
                                    },
                                    enabled = boardSlug.isNotBlank(),
                                ) {
                                    Text("Create")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCreateBoardDialog = false }) {
                                    Text("Cancel")
                                }
                            },
                        )
                    }

                    if (showRenameBoardDialog && state.selectedBoard != null) {
                        var newName by remember { mutableStateOf(state.selectedBoard?.name ?: "") }
                        AlertDialog(
                            onDismissRequest = { showRenameBoardDialog = false },
                            title = { Text("Rename Board") },
                            text = {
                                OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    label = { Text("Board Name") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (newName.isNotBlank()) {
                                            state.selectedBoard?.let { board ->
                                                viewModel.renameBoard(board.id, newName.trim())
                                            }
                                            showRenameBoardDialog = false
                                        }
                                    },
                                    enabled = newName.isNotBlank(),
                                ) {
                                    Text("Save")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRenameBoardDialog = false }) {
                                    Text("Cancel")
                                }
                            },
                        )
                    }

                    if (showDeleteBoardDialog && state.selectedBoard != null) {
                        AlertDialog(
                            onDismissRequest = { showDeleteBoardDialog = false },
                            title = { Text("Delete Board") },
                            text = {
                                Text(
                                    "Are you sure you want to delete board '${state.selectedBoard?.displayName}'? This action cannot be undone.",
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        state.selectedBoard?.let { board ->
                                            viewModel.deleteBoard(board.id)
                                        }
                                        showDeleteBoardDialog = false
                                    },
                                    colors =
                                        ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                        ),
                                ) {
                                    Text("Delete")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteBoardDialog = false }) {
                                    Text("Cancel")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveStatusPill(isLive: Boolean) {
    val color =
        if (isLive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .background(color = color, shape = CircleShape),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(if (isLive) R.string.kanban_live else R.string.kanban_offline),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

private fun KanbanTaskAction.labelRes(): Int =
    when (this) {
        KanbanTaskAction.TRIAGE -> R.string.kanban_action_triage
        KanbanTaskAction.READY -> R.string.kanban_action_ready
        KanbanTaskAction.UNBLOCK -> R.string.kanban_action_unblock
        KanbanTaskAction.BLOCK -> R.string.kanban_action_block
        KanbanTaskAction.COMPLETE -> R.string.kanban_action_complete
        KanbanTaskAction.ARCHIVE -> R.string.kanban_action_archive
    }

private fun KanbanTaskAction.confirmRes(): Int =
    when (this) {
        KanbanTaskAction.BLOCK -> R.string.kanban_confirm_blocked
        KanbanTaskAction.COMPLETE -> R.string.kanban_confirm_done
        KanbanTaskAction.ARCHIVE -> R.string.kanban_confirm_archive
        else -> error("Action $this has no confirm message")
    }

@Composable
private fun TaskActionSheet(
    task: KanbanTask,
    actions: List<KanbanTaskAction>,
    onAction: (KanbanTaskAction) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                actions.forEach { action ->
                    Button(
                        onClick = { onAction(action) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                    ) {
                        Text(stringResource(action.labelRes()))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ConfirmActionDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun CompleteTaskDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var summary by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kanban_complete_title)) },
        text = {
            OutlinedTextField(
                value = summary,
                onValueChange = { summary = it },
                label = { Text(stringResource(R.string.kanban_complete_summary_label)) },
                supportingText = { Text(stringResource(R.string.kanban_complete_summary_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(summary.trim()) },
                enabled = summary.isNotBlank(),
            ) {
                Text(stringResource(R.string.kanban_action_complete))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
