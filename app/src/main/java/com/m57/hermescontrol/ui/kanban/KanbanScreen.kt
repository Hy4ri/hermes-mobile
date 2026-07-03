package com.m57.hermescontrol.ui.kanban

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.ToastEffect

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

    val filteredTasks =
        remember(query, state.tasks) {
            state.tasks.filter { task ->
                task.title.contains(query, ignoreCase = true) ||
                    task.description?.contains(query, ignoreCase = true) == true ||
                    task.status.contains(query, ignoreCase = true) ||
                    task.assignedTo?.contains(query, ignoreCase = true) == true
            }
        }

    val tasksByColumn =
        remember(filteredTasks) {
            filteredTasks.groupBy { it.status.lowercase() }
        }
    var showAddTaskDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadBoards()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        title = { Text(stringResource(R.string.kanban_board_title)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadBoards() },
    ) { paddingValues ->
        when {
            state.isLoading && state.boards.isEmpty() -> {
                LoadingState(modifier = Modifier.padding(paddingValues))
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
                                placeholder = "Filter tasks by title, status, or assignee...",
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
                                            text = { Text(board.name) },
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
                                LazyRow(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(state.columns.size, key = { index ->
                                        state.columns[index].name
                                    }) { columnIndex ->
                                        val column = state.columns[columnIndex]
                                        val colName = column.name
                                        val colTasks = tasksByColumn[colName.lowercase()] ?: emptyList()
                                        val prevColumn = state.columns.getOrNull(columnIndex - 1)
                                        val nextColumn = state.columns.getOrNull(columnIndex + 1)

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

                                            LazyColumn(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                items(colTasks, key = { it.id }) { task ->
                                                    TaskCard(
                                                        task = task,
                                                        onMoveLeft =
                                                            prevColumn?.let {
                                                                { viewModel.moveTask(task, it.name) }
                                                            },
                                                        onMoveRight =
                                                            nextColumn?.let {
                                                                { viewModel.moveTask(task, it.name) }
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

                    if (showAddTaskDialog) {
                        AddTaskDialog(
                            onDismiss = { showAddTaskDialog = false },
                            onConfirm = { title, desc ->
                                viewModel.createTask(title, desc, state.columns.firstOrNull()?.name ?: DEFAULT_COLUMN)
                                showAddTaskDialog = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TaskCard(
    task: KanbanTask,
    onMoveLeft: (() -> Unit)?,
    onMoveRight: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            task.description?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onMoveLeft != null) {
                    IconButton(onClick = onMoveLeft) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_move_left),
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }

                if (onMoveRight != null) {
                    IconButton(onClick = onMoveRight) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = stringResource(R.string.content_desc_move_right),
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, desc: String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kanban_add_new_task)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.kanban_task_title)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text(stringResource(R.string.kanban_task_desc)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (title.isNotBlank()) onConfirm(title, desc.ifBlank { null }) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
