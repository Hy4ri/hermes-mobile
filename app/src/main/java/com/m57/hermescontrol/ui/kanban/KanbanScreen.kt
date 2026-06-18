package com.m57.hermescontrol.ui.kanban

import android.widget.Toast
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: KanbanViewModel = viewModel { KanbanViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAddTaskDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadBoards()
    }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    HermesScaffold(
        title = "Kanban Board",
        onOpenDrawer = onOpenDrawer,
        onRefresh = { viewModel.loadBoard() },
        modifier = modifier,
    ) { paddingValues ->
        when {
            state.isLoading -> {
                LoadingState(modifier = Modifier.padding(paddingValues))
            }
            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadBoard() },
                    modifier = Modifier.padding(paddingValues),
                )
            }
            else -> {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (state.errorMessage != null) {
                    Text(
                        text = state.errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Board selector tab row
                        if (state.boards.isNotEmpty()) {
                            ScrollableTabRow(
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
                                Text("No boards found.")
                            }
                        } else if (state.columns.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No columns found for this board.")
                            }
                        } else {
                            LazyRow(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(state.columns) { column ->
                                    val colName = column.name
                                    val colTasks =
                                        state.tasks.filter {
                                            it.status.equals(colName, ignoreCase = true)
                                        }
                                    val columnIndex = state.columns.indexOf(column)
                                    val prevColumn = state.columns.getOrNull(columnIndex - 1)
                                    val nextColumn = state.columns.getOrNull(columnIndex + 1)

                                    Column(
                                        modifier =
                                            Modifier
                                                .width(280.dp)
                                                .fillMaxSize(),
                                    ) {
                                        Text(
                                            text = "${colName.replaceFirstChar { it.uppercase() }} (${colTasks.size})",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )

                                        LazyColumn(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            items(colTasks) { task ->
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
                            viewModel.createTask(title, desc, state.columns.firstOrNull()?.name ?: "todo")
                            showAddTaskDialog = false
                        },
                    )
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
                        Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Move Left")
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }

                if (onMoveRight != null) {
                    IconButton(onClick = onMoveRight) {
                        Icon(imageVector = Icons.Filled.ArrowForward, contentDescription = "Move Right")
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
        title = { Text("Add New Task") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Title") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Task Description") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (title.isNotBlank()) onConfirm(title, desc.ifBlank { null }) },
                enabled = title.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
