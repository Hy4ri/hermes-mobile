package com.m57.hermescontrol.ui.kanban

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.KanbanTaskDetailKey
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.model.ManagedFileDelete
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.data.remote.GatewayFileResult
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SearchBar
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.kanban.components.KanbanBoardSettingsDialog
import com.m57.hermescontrol.ui.kanban.components.KanbanCreateTaskDialog
import com.m57.hermescontrol.ui.kanban.components.KanbanFilterSheet
import com.m57.hermescontrol.ui.kanban.components.KanbanOrchestrationDialog
import com.m57.hermescontrol.ui.kanban.components.KanbanTaskCard
import com.m57.hermescontrol.util.StreamingUriRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

private const val DEFAULT_COLUMN = "todo"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: KanbanViewModel = viewModel { KanbanViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nowSeconds = rememberKanbanNowSeconds(state.tasks.any { it.status.equals("running", ignoreCase = true) })
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var selectedAssignee by remember { mutableStateOf<String?>(null) }
    var selectedTenant by remember { mutableStateOf<String?>(null) }
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
    var createTaskDefaultColumn by remember { mutableStateOf(DEFAULT_COLUMN) }
    var taskForActions by remember { mutableStateOf<KanbanTask?>(null) }
    var confirmTarget by remember { mutableStateOf<Pair<KanbanTask, KanbanTaskAction>?>(null) }
    var summaryTarget by remember { mutableStateOf<Pair<KanbanTask, KanbanTaskAction>?>(null) }

    // Multi-select & Bulk operations state
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedTaskIds by remember { mutableStateOf(setOf<String>()) }
    var showBulkMoveDialog by remember { mutableStateOf(false) }
    var showBulkAssignDialog by remember { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }

    // Board management dialogs
    var showBoardMenu by remember { mutableStateOf(false) }
    var showCreateBoardDialog by remember { mutableStateOf(false) }
    var showBoardSettingsDialog by remember { mutableStateOf(false) }
    var showDeleteBoardDialog by remember { mutableStateOf(false) }
    var showOrchestrationDialog by remember { mutableStateOf(false) }

    // Board Transfer (Export / Import)
    var pendingExportPath by remember { mutableStateOf<String?>(null) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/gzip"),
        ) { uri ->
            if (uri != null && pendingExportPath != null) {
                coroutineScope.launch {
                    try {
                        val cachedFile = File(pendingExportPath!!)
                        if (cachedFile.exists()) {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openOutputStream(uri)?.use { output ->
                                    cachedFile.inputStream().use { input ->
                                        input.copyTo(output)
                                    }
                                } ?: error("Unable to open selected destination")
                            }
                            viewModel.showToast("Board exported successfully")
                        }
                    } catch (e: Exception) {
                        viewModel.showToast("Failed to save export: ${e.message}")
                    } finally {
                        pendingExportPath = null
                    }
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) {
                coroutineScope.launch {
                    try {
                        var fileName = "board.tar.gz"
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1 && cursor.moveToFirst()) {
                                fileName = cursor.getString(nameIndex) ?: "board.tar.gz"
                            }
                        }

                        val contentLength =
                            context.contentResolver
                                .query(
                                    uri,
                                    arrayOf(OpenableColumns.SIZE),
                                    null,
                                    null,
                                    null,
                                )?.use { cursor ->
                                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                    if (sizeIndex != -1 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                                        cursor.getLong(sizeIndex).takeIf { it >= 0L } ?: -1L
                                    } else {
                                        -1L
                                    }
                                } ?: -1L
                        val targetPath = "kanban-imports/$fileName"
                        val pathBody = targetPath.toRequestBody("text/plain".toMediaTypeOrNull())
                        val overwriteBody = "true".toRequestBody("text/plain".toMediaTypeOrNull())
                        val requestBody =
                            StreamingUriRequestBody(
                                contentResolver = context.contentResolver,
                                uri = uri,
                                contentType = "application/gzip".toMediaTypeOrNull(),
                                contentLength = contentLength,
                            )
                        val filePart = MultipartBody.Part.createFormData("file", fileName, requestBody)
                        val uploadResult =
                            withContext(Dispatchers.IO) {
                                safeApiCall {
                                    ApiClient.hermesApi.uploadManagedFileStream(pathBody, overwriteBody, filePart)
                                }
                            }
                        when (uploadResult) {
                            is NetworkResult.Success -> {
                                val serverPath =
                                    uploadResult.data.entry?.path ?: uploadResult.data.path ?: targetPath
                                val importResult = viewModel.importBoard(serverPath)
                                when (importResult) {
                                    is NetworkResult.Success -> {
                                        val res = importResult.data
                                        var msg = "Board '${res.name}' imported"
                                        if (res.renamed) msg += " (as ${res.board})"
                                        viewModel.showToast(msg)
                                        viewModel.loadBoards()
                                    }

                                    is NetworkResult.Failure -> {
                                        viewModel.showToast("Import failed: ${importResult.error.message}")
                                    }
                                }
                                runCatching {
                                    ApiClient.hermesApi.deleteManagedFile(ManagedFileDelete(serverPath))
                                }
                            }

                            is NetworkResult.Failure -> {
                                viewModel.showToast("Failed to upload archive: ${uploadResult.error.message}")
                            }
                        }
                    } catch (e: Exception) {
                        viewModel.showToast("Import failed: ${e.message}")
                    }
                }
            }
        }

    LaunchedEffect(state.tasks) {
        val existingIds = state.tasks.map { it.id }.toSet()
        selectedTaskIds = selectedTaskIds.intersect(existingIds)
    }

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
                    contentDescription = stringResource(R.string.kanban_filter),
                )
            }
            Box {
                IconButton(onClick = { showBoardMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.kanban_board_options),
                    )
                }
                DropdownMenu(
                    expanded = showBoardMenu,
                    onDismissRequest = { showBoardMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.kanban_new_board)) },
                        onClick = {
                            showBoardMenu = false
                            showCreateBoardDialog = true
                        },
                    )
                    if (state.selectedBoard != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.kanban_board_settings)) },
                            onClick = {
                                showBoardMenu = false
                                showBoardSettingsDialog = true
                            },
                        )
                        if (state.boards.size > 1 && !state.selectedBoard?.id.equals("default", ignoreCase = true)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.kanban_archive_board)) },
                                onClick = {
                                    showBoardMenu = false
                                    showDeleteBoardDialog = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.kanban_export_board)) },
                            onClick = {
                                showBoardMenu = false
                                state.selectedBoard?.let { b ->
                                    coroutineScope.launch {
                                        when (val res = viewModel.exportBoard(b.id)) {
                                            is NetworkResult.Success -> {
                                                val download =
                                                    GatewayFileClient.fetch(
                                                        res.data.archive,
                                                        context.cacheDir,
                                                    )
                                                if (download is GatewayFileResult.Success) {
                                                    pendingExportPath = download.file.cacheFile.absolutePath
                                                    exportLauncher.launch("${b.slug}.tar.gz")
                                                } else {
                                                    viewModel.showToast("Failed to download board export")
                                                }
                                            }

                                            is NetworkResult.Failure -> {
                                                viewModel.showToast("Export failed: ${res.error.message}")
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.kanban_import_board)) },
                        onClick = {
                            showBoardMenu = false
                            importLauncher.launch(
                                arrayOf("application/gzip", "application/x-gzip", "application/octet-stream", "*/*"),
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.kanban_orchestration_settings)) },
                        onClick = {
                            showBoardMenu = false
                            showOrchestrationDialog = true
                        },
                    )
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
                                            imageVector = Icons.Default.FilterList,
                                            contentDescription = stringResource(R.string.kanban_filters),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = {
                                            createTaskDefaultColumn = DEFAULT_COLUMN
                                            showAddTaskDialog = true
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = stringResource(R.string.kanban_add_task),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                val boardHasWork = state.tasks.isNotEmpty()
                                val statusColors = LocalHermesStatusColors.current

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
                                        val isCollapsed =
                                            KanbanColumnCollapseHelper.isColumnCollapsed(
                                                columnName = colName,
                                                columnTaskCount = colTasks.size,
                                                boardHasWork = boardHasWork,
                                                overrides = state.columnCollapseOverrides,
                                            )
                                        val statusTone =
                                            when (colName.lowercase()) {
                                                "ready" -> statusColors.info
                                                "running" -> statusColors.success
                                                "blocked" -> statusColors.error
                                                "review" -> statusColors.warning
                                                "done" -> statusColors.success
                                                else -> MaterialTheme.colorScheme.outline
                                            }

                                        if (isCollapsed) {
                                            Card(
                                                modifier =
                                                    Modifier
                                                        .width(56.dp)
                                                        .fillMaxHeight()
                                                        .clickable {
                                                            viewModel.toggleColumnCollapse(
                                                                colName,
                                                                boardHasWork,
                                                                colTasks.size,
                                                            )
                                                        },
                                            ) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
                                                ) {
                                                    Box(
                                                        modifier =
                                                            Modifier
                                                                .size(8.dp)
                                                                .background(statusTone, shape = CircleShape),
                                                    )
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(
                                                        text = colTasks.size.toString(),
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                    Spacer(modifier = Modifier.height(16.dp))
                                                    Text(
                                                        text = colName.take(3).uppercase(),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    Spacer(modifier = Modifier.weight(1f))
                                                    if (KanbanStatusConstraints.isUserWritableTarget(colName)) {
                                                        IconButton(
                                                            onClick = {
                                                                createTaskDefaultColumn = colName
                                                                showAddTaskDialog = true
                                                            },
                                                            modifier = Modifier.size(32.dp),
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Add,
                                                                contentDescription =
                                                                    stringResource(
                                                                        R.string.kanban_add_to_column,
                                                                        colName,
                                                                    ),
                                                                modifier = Modifier.size(16.dp),
                                                            )
                                                        }
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            viewModel.toggleColumnCollapse(
                                                                colName,
                                                                boardHasWork,
                                                                colTasks.size,
                                                            )
                                                        },
                                                        modifier = Modifier.size(32.dp),
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                            contentDescription =
                                                                stringResource(
                                                                    R.string.kanban_expand_column,
                                                                    colName,
                                                                ),
                                                            modifier = Modifier.size(16.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            Column(
                                                modifier =
                                                    Modifier
                                                        .width(280.dp)
                                                        .fillMaxSize(),
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .padding(bottom = 8.dp),
                                                ) {
                                                    Text(
                                                        text = "${colName.replaceFirstChar {
                                                            it.uppercase()
                                                        }} (${colTasks.size})",
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        if (KanbanStatusConstraints.isUserWritableTarget(colName)) {
                                                            IconButton(
                                                                onClick = {
                                                                    createTaskDefaultColumn = colName
                                                                    showAddTaskDialog = true
                                                                },
                                                                modifier = Modifier.size(28.dp),
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Add,
                                                                    contentDescription =
                                                                        stringResource(
                                                                            R.string.kanban_add_to_column,
                                                                            colName,
                                                                        ),
                                                                    modifier = Modifier.size(18.dp),
                                                                )
                                                            }
                                                        }
                                                        IconButton(
                                                            onClick = {
                                                                viewModel.toggleColumnCollapse(
                                                                    colName,
                                                                    boardHasWork,
                                                                    colTasks.size,
                                                                )
                                                            },
                                                            modifier = Modifier.size(28.dp),
                                                        ) {
                                                            Icon(
                                                                imageVector =
                                                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                                                contentDescription =
                                                                    stringResource(
                                                                        R.string.kanban_collapse_column,
                                                                        colName,
                                                                    ),
                                                                modifier = Modifier.size(18.dp),
                                                            )
                                                        }
                                                    }
                                                }

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
                                                } else if (colName.equals("running", ignoreCase = true) &&
                                                    state.groupRunning
                                                ) {
                                                    val runningGroups =
                                                        remember(colTasks) {
                                                            KanbanLaneGrouping.groupRunningTasks(colTasks)
                                                        }
                                                    LazyColumn(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                                    ) {
                                                        runningGroups.forEach { group ->
                                                            item(key = "group_${group.assignee ?: "unassigned"}") {
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    modifier =
                                                                        Modifier.fillMaxWidth().padding(
                                                                            vertical = 4.dp,
                                                                        ),
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.Person,
                                                                        contentDescription = null,
                                                                        modifier = Modifier.size(14.dp),
                                                                        tint = MaterialTheme.colorScheme.primary,
                                                                    )
                                                                    Spacer(modifier = Modifier.width(4.dp))
                                                                    Text(
                                                                        text =
                                                                            "${group.assignee ?: stringResource(
                                                                                R.string.kanban_unassigned_group,
                                                                            )} (${group.tasks.size})",
                                                                        style = MaterialTheme.typography.labelMedium,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = MaterialTheme.colorScheme.primary,
                                                                    )
                                                                }
                                                            }
                                                            items(group.tasks, key = { it.id }) { task ->
                                                                KanbanTaskCard(
                                                                    task = task,
                                                                    nowSeconds = nowSeconds,
                                                                    defaultAssignee =
                                                                        state.orchestration
                                                                            ?.resolvedDefaultAssignee,
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
                                                } else {
                                                    LazyColumn(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                                    ) {
                                                        items(colTasks, key = { it.id }) { task ->
                                                            KanbanTaskCard(
                                                                task = task,
                                                                nowSeconds = nowSeconds,
                                                                defaultAssignee =
                                                                    state.orchestration
                                                                        ?.resolvedDefaultAssignee,
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
                    }

                    if (showAddTaskDialog) {
                        KanbanCreateTaskDialog(
                            columns = state.columns,
                            defaultColumn = createTaskDefaultColumn,
                            profiles = state.profiles,
                            existingTasks = state.tasks,
                            isCreating = state.isCreatingTask,
                            defaultAssignee = state.orchestration?.resolvedDefaultAssignee,
                            boardDefaultWorkspaceKind = state.selectedBoard?.defaultWorkspaceKind,
                            boardDefaultWorkdir = state.selectedBoard?.defaultWorkdir,
                            modelProviders = state.modelProviders,
                            pinnedModels = state.pinnedModels,
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
                            includeArchived = state.includeArchived,
                            groupRunning = state.groupRunning,
                            onSelectAssignee = { selectedAssignee = it },
                            onSelectTenant = { selectedTenant = it },
                            onToggleIncludeArchived = viewModel::setIncludeArchived,
                            onToggleGroupRunning = viewModel::setGroupRunning,
                            onClearFilters = {
                                selectedAssignee = null
                                selectedTenant = null
                                viewModel.setIncludeArchived(false)
                                viewModel.setGroupRunning(false)
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
                                modifier =
                                    Modifier
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .fillMaxWidth(),
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
                                        Text(
                                            if (selectedTaskIds.size == filteredTasks.size) {
                                                stringResource(R.string.kanban_clear_selection)
                                            } else {
                                                stringResource(R.string.kanban_select_all)
                                            },
                                        )
                                    }
                                    Button(
                                        onClick = { showBulkMoveDialog = true },
                                        enabled = selectedTaskIds.isNotEmpty(),
                                    ) {
                                        Text(stringResource(R.string.kanban_move))
                                    }
                                    Button(
                                        onClick = { showBulkAssignDialog = true },
                                        enabled = selectedTaskIds.isNotEmpty(),
                                    ) {
                                        Text(stringResource(R.string.kanban_bulk_assign))
                                    }
                                    Button(
                                        onClick = {
                                            viewModel.bulkAssign(selectedTaskIds.toList(), null) { failed ->
                                                selectedTaskIds = failed
                                                if (failed.isEmpty()) isMultiSelectMode = false
                                            }
                                        },
                                        enabled = selectedTaskIds.isNotEmpty(),
                                    ) {
                                        Text(stringResource(R.string.kanban_bulk_unassign))
                                    }
                                    Button(
                                        onClick = {
                                            viewModel.bulkArchive(selectedTaskIds.toList()) { failed ->
                                                selectedTaskIds = failed
                                                if (failed.isEmpty()) isMultiSelectMode = false
                                            }
                                        },
                                        enabled = selectedTaskIds.isNotEmpty(),
                                    ) {
                                        Text(stringResource(R.string.kanban_action_archive))
                                    }
                                    Button(
                                        onClick = { showBulkDeleteDialog = true },
                                        enabled = selectedTaskIds.isNotEmpty(),
                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error,
                                            ),
                                    ) {
                                        Text(stringResource(R.string.kanban_bulk_delete))
                                    }
                                    IconButton(
                                        onClick = {
                                            isMultiSelectMode = false
                                            selectedTaskIds = emptySet()
                                        },
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.kanban_close_multi_select),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (showBulkMoveDialog) {
                        val writableMoveColumns =
                            remember(state.columns) {
                                state.columns.filter { KanbanStatusConstraints.isUserWritableTarget(it.name) }
                            }
                        AlertDialog(
                            onDismissRequest = { showBulkMoveDialog = false },
                            title = { Text(stringResource(R.string.kanban_move_tasks, selectedTaskIds.size)) },
                            text = {
                                Column {
                                    writableMoveColumns.forEach { col ->
                                        TextButton(
                                            onClick = {
                                                viewModel.bulkMove(selectedTaskIds.toList(), col.name) { failed ->
                                                    selectedTaskIds = failed
                                                    if (failed.isEmpty()) isMultiSelectMode = false
                                                }
                                                showBulkMoveDialog = false
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
                                    Text(stringResource(R.string.action_cancel))
                                }
                            },
                        )
                    }

                    if (showBulkAssignDialog) {
                        AlertDialog(
                            onDismissRequest = { showBulkAssignDialog = false },
                            title = { Text(stringResource(R.string.kanban_bulk_assign)) },
                            text = {
                                Column {
                                    state.profiles.forEach { profile ->
                                        TextButton(
                                            onClick = {
                                                viewModel.bulkAssign(selectedTaskIds.toList(), profile.name) { failed ->
                                                    selectedTaskIds = failed
                                                    if (failed.isEmpty()) isMultiSelectMode = false
                                                }
                                                showBulkAssignDialog = false
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(profile.name, modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { showBulkAssignDialog = false }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            },
                        )
                    }

                    if (showBulkDeleteDialog) {
                        AlertDialog(
                            onDismissRequest = { showBulkDeleteDialog = false },
                            title = { Text(stringResource(R.string.kanban_bulk_delete)) },
                            text = {
                                Text(stringResource(R.string.kanban_bulk_delete_confirm, selectedTaskIds.size))
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        viewModel.bulkDelete(selectedTaskIds.toList()) { failed ->
                                            selectedTaskIds = failed
                                            if (failed.isEmpty()) isMultiSelectMode = false
                                        }
                                        showBulkDeleteDialog = false
                                    },
                                    colors =
                                        ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                        ),
                                ) {
                                    Text(stringResource(R.string.kanban_bulk_delete))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showBulkDeleteDialog = false }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            },
                        )
                    }

                    if (showCreateBoardDialog) {
                        var boardSlug by remember { mutableStateOf("") }
                        var boardName by remember { mutableStateOf("") }
                        var boardDesc by remember { mutableStateOf("") }
                        var selectedProjId by remember { mutableStateOf<String?>(null) }
                        var projectDropdownExpanded by remember { mutableStateOf(false) }

                        AlertDialog(
                            onDismissRequest = { showCreateBoardDialog = false },
                            title = { Text(stringResource(R.string.kanban_create_new_board)) },
                            text = {
                                Column {
                                    OutlinedTextField(
                                        value = boardSlug,
                                        onValueChange = { boardSlug = it },
                                        label = { Text(stringResource(R.string.kanban_slug)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = boardName,
                                        onValueChange = { boardName = it },
                                        label = { Text(stringResource(R.string.kanban_display_name)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = boardDesc,
                                        onValueChange = { boardDesc = it },
                                        label = { Text(stringResource(R.string.kanban_description)) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    ExposedDropdownMenuBox(
                                        expanded = projectDropdownExpanded,
                                        onExpandedChange = { projectDropdownExpanded = it },
                                    ) {
                                        val displayProj =
                                            state.projects.find { it.id == selectedProjId }?.name
                                                ?: stringResource(R.string.kanban_no_project)
                                        OutlinedTextField(
                                            value = displayProj,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text(stringResource(R.string.kanban_project)) },
                                            trailingIcon = {
                                                ExposedDropdownMenuDefaults.TrailingIcon(
                                                    expanded = projectDropdownExpanded,
                                                )
                                            },
                                            modifier =
                                                Modifier
                                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                                    .fillMaxWidth(),
                                        )
                                        ExposedDropdownMenu(
                                            expanded = projectDropdownExpanded,
                                            onDismissRequest = { projectDropdownExpanded = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.kanban_no_project)) },
                                                onClick = {
                                                    selectedProjId = null
                                                    projectDropdownExpanded = false
                                                },
                                            )
                                            state.projects.forEach { proj ->
                                                DropdownMenuItem(
                                                    text = { Text(proj.name) },
                                                    onClick = {
                                                        selectedProjId = proj.id
                                                        projectDropdownExpanded = false
                                                    },
                                                )
                                            }
                                        }
                                    }
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
                                                projectId = selectedProjId,
                                            )
                                            showCreateBoardDialog = false
                                        }
                                    },
                                    enabled = boardSlug.isNotBlank(),
                                ) {
                                    Text(stringResource(R.string.action_add))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCreateBoardDialog = false }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            },
                        )
                    }

                    if (showBoardSettingsDialog && state.selectedBoard != null) {
                        KanbanBoardSettingsDialog(
                            board = state.selectedBoard!!,
                            projects = state.projects,
                            onSave = { body ->
                                state.selectedBoard?.let { b ->
                                    viewModel.updateBoard(b.id, body)
                                }
                            },
                            onDismiss = { showBoardSettingsDialog = false },
                        )
                    }

                    if (showOrchestrationDialog) {
                        KanbanOrchestrationDialog(
                            settings = state.orchestration,
                            profiles = state.profiles,
                            onSaveSettings = viewModel::updateOrchestration,
                            onSaveProfileDescription = viewModel::updateProfileDescription,
                            onAutoDescribe = viewModel::autoDescribeProfile,
                            onDismiss = { showOrchestrationDialog = false },
                        )
                    }

                    if (showDeleteBoardDialog && state.selectedBoard != null) {
                        AlertDialog(
                            onDismissRequest = { showDeleteBoardDialog = false },
                            title = { Text(stringResource(R.string.kanban_archive_board)) },
                            text = {
                                Text(
                                    stringResource(
                                        R.string.kanban_archive_board_confirm,
                                        state.selectedBoard?.displayName.orEmpty(),
                                    ),
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
                                    Text(stringResource(R.string.kanban_action_archive))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteBoardDialog = false }) {
                                    Text(stringResource(R.string.action_cancel))
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
                    .size(6.dp)
                    .background(color, CircleShape),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (isLive) stringResource(R.string.kanban_live) else stringResource(R.string.kanban_offline),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskActionSheet(
    task: KanbanTask,
    actions: List<KanbanTaskAction>,
    onAction: (KanbanTaskAction) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            actions.forEach { action ->
                TextButton(
                    onClick = { onAction(action) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(action.labelRes()),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmActionDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kanban_confirm_action)) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun CompleteTaskDialog(
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var summary by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kanban_complete_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text(stringResource(R.string.kanban_complete_summary_label)) },
                    placeholder = { Text(stringResource(R.string.kanban_complete_summary_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(summary.trim().ifBlank { null }) }) {
                Text(stringResource(R.string.kanban_action_complete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun KanbanTaskAction.labelRes(): Int =
    when (this) {
        KanbanTaskAction.TRIAGE -> R.string.kanban_action_triage
        KanbanTaskAction.READY -> R.string.kanban_action_ready
        KanbanTaskAction.BLOCK -> R.string.kanban_action_block
        KanbanTaskAction.UNBLOCK -> R.string.kanban_action_unblock
        KanbanTaskAction.COMPLETE -> R.string.kanban_action_complete
        KanbanTaskAction.ARCHIVE -> R.string.kanban_action_archive
    }

private fun KanbanTaskAction.confirmRes(): Int =
    when (this) {
        KanbanTaskAction.BLOCK -> R.string.kanban_confirm_blocked
        KanbanTaskAction.COMPLETE -> R.string.kanban_confirm_done
        KanbanTaskAction.ARCHIVE -> R.string.kanban_confirm_archive
        else -> R.string.kanban_confirm_blocked
    }
