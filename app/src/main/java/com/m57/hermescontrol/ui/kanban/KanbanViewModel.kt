package com.m57.hermescontrol.ui.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.InMemoryKanbanPreferencesStore
import com.m57.hermescontrol.data.local.KanbanPreferencesStore
import com.m57.hermescontrol.data.model.BoardExportResult
import com.m57.hermescontrol.data.model.BoardImportResult
import com.m57.hermescontrol.data.model.BulkTasksBody
import com.m57.hermescontrol.data.model.CreateBoardBody
import com.m57.hermescontrol.data.model.CreateTaskBody
import com.m57.hermescontrol.data.model.ExportBoardBody
import com.m57.hermescontrol.data.model.ImportBoardBody
import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanBoardResponse
import com.m57.hermescontrol.data.model.KanbanColumn
import com.m57.hermescontrol.data.model.KanbanProfile
import com.m57.hermescontrol.data.model.KanbanProject
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.OrchestrationSettings
import com.m57.hermescontrol.data.model.OrchestrationSettingsUpdate
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.model.RenameBoardBody
import com.m57.hermescontrol.data.model.TaskEstimate
import com.m57.hermescontrol.data.model.UpdateTaskBody
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.repository.KanbanRepository
import com.m57.hermescontrol.data.repository.KanbanRepositoryImpl
import com.m57.hermescontrol.data.ws.KanbanEventsClient
import com.m57.hermescontrol.data.ws.KanbanLiveStatus
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KanbanUiState(
    val isLoading: Boolean = false,
    val isCreatingTask: Boolean = false,
    val operatingTaskIds: Set<String> = emptySet(),
    val boards: List<KanbanBoard> = emptyList(),
    val selectedBoard: KanbanBoard? = null,
    val columns: List<KanbanColumn> = emptyList(),
    val tasks: List<KanbanTask> = emptyList(),
    val profiles: List<KanbanProfile> = emptyList(),
    val projects: List<KanbanProject> = emptyList(),
    val orchestration: OrchestrationSettings? = null,
    val modelProviders: List<ModelProvider> = emptyList(),
    val pinnedModels: List<PinnedModel> = emptyList(),
    val isLive: Boolean = false,
    val includeArchived: Boolean = false,
    val groupRunning: Boolean = false,
    val columnCollapseOverrides: Map<String, Boolean> = emptyMap(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

/**
 * Task actions mirroring the desktop kanban's transition-gated buttons.
 * Only moves the backend PATCH route accepts are offered: never `running`
 * (dispatcher-only, backend rejects with 400) and never `review` (not in
 * the dashboard whitelist).
 */
enum class KanbanTaskAction(
    val targetStatus: String,
    val needsConfirm: Boolean = false,
    val needsSummary: Boolean = false,
) {
    TRIAGE(targetStatus = "triage"),
    READY(targetStatus = "ready"),
    UNBLOCK(targetStatus = "ready"),
    BLOCK(targetStatus = "blocked", needsConfirm = true),
    COMPLETE(targetStatus = "done", needsConfirm = true, needsSummary = true),
    ARCHIVE(targetStatus = "archived", needsConfirm = true),
}

/** Desktop parity: which actions are valid from a given status. */
fun kanbanActionsForStatus(status: String): List<KanbanTaskAction> =
    when (status) {
        "triage" -> {
            listOf(KanbanTaskAction.READY, KanbanTaskAction.ARCHIVE)
        }

        "todo" -> {
            listOf(KanbanTaskAction.TRIAGE, KanbanTaskAction.READY, KanbanTaskAction.ARCHIVE)
        }

        "scheduled" -> {
            listOf(KanbanTaskAction.TRIAGE, KanbanTaskAction.READY, KanbanTaskAction.ARCHIVE)
        }

        "ready" -> {
            listOf(
                KanbanTaskAction.TRIAGE,
                KanbanTaskAction.BLOCK,
                KanbanTaskAction.COMPLETE,
                KanbanTaskAction.ARCHIVE,
            )
        }

        "running" -> {
            listOf(
                KanbanTaskAction.TRIAGE,
                KanbanTaskAction.READY,
                KanbanTaskAction.BLOCK,
                KanbanTaskAction.COMPLETE,
                KanbanTaskAction.ARCHIVE,
            )
        }

        "blocked" -> {
            listOf(
                KanbanTaskAction.TRIAGE,
                KanbanTaskAction.UNBLOCK,
                KanbanTaskAction.COMPLETE,
                KanbanTaskAction.ARCHIVE,
            )
        }

        "review" -> {
            listOf(KanbanTaskAction.TRIAGE, KanbanTaskAction.READY, KanbanTaskAction.ARCHIVE)
        }

        "done" -> {
            listOf(KanbanTaskAction.TRIAGE, KanbanTaskAction.READY, KanbanTaskAction.ARCHIVE)
        }

        "archived" -> {
            listOf(KanbanTaskAction.TRIAGE, KanbanTaskAction.READY)
        }

        else -> {
            emptyList()
        }
    }

class KanbanViewModel(
    private val repository: KanbanRepository = KanbanRepositoryImpl(),
    private val preferences: KanbanPreferencesStore = InMemoryKanbanPreferencesStore(),
    private val eventsClientProvider: () -> KanbanEventsClient = { KanbanEventsClient() },
    private val endpointProvider: () -> String = {
        runCatching { AuthManager.endpointForBuild().baseUrl.toString() }.getOrDefault("default")
    },
) : ViewModel(),
    ToastHost {
    private val _uiState =
        MutableStateFlow(
            KanbanUiState(
                includeArchived = preferences.getIncludeArchived(),
                groupRunning = preferences.getGroupRunning(),
            ),
        )
    val uiState: StateFlow<KanbanUiState> = _uiState.asStateFlow()

    private var eventsClient: KanbanEventsClient? = null
    private var eventsBoard: String? = null
    private var reloadJob: Job? = null
    private var currentLoadGen: Int = 0
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    fun loadBoards() {
        val endpoint = endpointProvider()
        val savedSlug = preferences.getSelectedBoard(endpoint)
        val previouslySelectedId = _uiState.value.selectedBoard?.id ?: savedSlug

        val hasExistingContent = _uiState.value.columns.isNotEmpty() || _uiState.value.tasks.isNotEmpty()
        if (!hasExistingContent) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        }
        loadProfiles()
        loadProjects()
        loadOrchestration()
        loadModelOptions()
        viewModelScope.launch {
            coroutineScope {
                val prefetchedBoardDeferred =
                    if (previouslySelectedId != null) {
                        async(ioDispatcher) {
                            repository.getBoard(
                                board = previouslySelectedId,
                                includeArchived = _uiState.value.includeArchived,
                            )
                        }
                    } else {
                        null
                    }

                when (val result = repository.getBoards(includeArchived = false)) {
                    is NetworkResult.Success -> {
                        val boards = result.data.boards
                        val targetBoard =
                            boards.find { it.id == previouslySelectedId }
                                ?: boards.find { it.id == result.data.current }
                                ?: boards.find { it.id.equals("default", ignoreCase = true) }
                                ?: boards.firstOrNull()

                        _uiState.update { it.copy(boards = boards) }

                        if (targetBoard != null) {
                            if (prefetchedBoardDeferred != null && targetBoard.id == previouslySelectedId) {
                                preferences.setSelectedBoard(endpoint, targetBoard.id)
                                val gen = ++currentLoadGen
                                _uiState.update { it.copy(selectedBoard = targetBoard) }
                                val prefetchedResult = prefetchedBoardDeferred.await()
                                applyBoardResult(targetBoard, prefetchedResult, gen)
                                connectEvents(targetBoard)
                            } else {
                                selectBoard(targetBoard)
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    selectedBoard = null,
                                    columns = emptyList(),
                                    tasks = emptyList(),
                                )
                            }
                        }
                    }

                    is NetworkResult.Failure -> {
                        if (!hasExistingContent) {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    errorMessage = "Failed to load Kanban boards: ${result.error.message}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun selectBoard(board: KanbanBoard) {
        val endpoint = endpointProvider()
        preferences.setSelectedBoard(endpoint, board.id)

        val gen = ++currentLoadGen
        _uiState.update { it.copy(selectedBoard = board, isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            loadBoardIntoState(board, gen)
            connectEvents(board)
        }
    }

    fun setIncludeArchived(include: Boolean) {
        preferences.setIncludeArchived(include)
        _uiState.update { it.copy(includeArchived = include) }
        val board = _uiState.value.selectedBoard ?: return
        val gen = ++currentLoadGen
        viewModelScope.launch {
            loadBoardIntoState(board, gen)
        }
    }

    fun setGroupRunning(group: Boolean) {
        preferences.setGroupRunning(group)
        _uiState.update { it.copy(groupRunning = group) }
    }

    fun toggleColumnCollapse(
        columnName: String,
        boardHasWork: Boolean,
        currentCount: Int,
    ) {
        _uiState.update { state ->
            val currentlyCollapsed =
                KanbanColumnCollapseHelper.isColumnCollapsed(
                    columnName = columnName,
                    columnTaskCount = currentCount,
                    boardHasWork = boardHasWork,
                    overrides = state.columnCollapseOverrides,
                )
            state.copy(
                columnCollapseOverrides = state.columnCollapseOverrides + (columnName to !currentlyCollapsed),
            )
        }
    }

    fun loadProfiles() {
        viewModelScope.launch {
            when (val result = repository.getProfiles()) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(profiles = result.data.profiles) }
                }

                is NetworkResult.Failure -> {
                    // Do not block UI if profiles fetch fails
                }
            }
        }
    }

    fun loadProjects() {
        viewModelScope.launch {
            when (val result = repository.getProjects()) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(projects = result.data.projects) }
                }

                is NetworkResult.Failure -> {
                    // Do not block UI if projects fetch fails
                }
            }
        }
    }

    fun loadOrchestration() {
        viewModelScope.launch {
            when (val result = repository.getOrchestration()) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(orchestration = result.data) }
                }

                is NetworkResult.Failure -> {
                    // Do not block UI if orchestration fetch fails
                }
            }
        }
    }

    fun loadModelOptions() {
        viewModelScope.launch {
            when (val res = safeApiCall { ApiClient.hermesApi.getModelOptions() }) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            modelProviders = res.data.providers,
                            pinnedModels = AuthManager.getPinnedModels(),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    // Do not block UI if model options fetch fails
                }
            }
        }
    }

    fun updateOrchestration(body: OrchestrationSettingsUpdate) {
        viewModelScope.launch {
            when (val res = repository.updateOrchestration(body)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            orchestration = res.data,
                            toastMessage = "Orchestration settings saved",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(toastMessage = "Failed to save orchestration: ${res.error.message}")
                    }
                }
            }
        }
    }

    fun updateProfileDescription(
        name: String,
        description: String,
    ) {
        viewModelScope.launch {
            when (val res = repository.updateProfileDescription(name, description)) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Profile description saved") }
                    loadProfiles()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(toastMessage = "Failed to save profile description: ${res.error.message}")
                    }
                }
            }
        }
    }

    fun autoDescribeProfile(
        name: String,
        onComplete: ((String?) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            when (val res = repository.autoDescribeProfile(name)) {
                is NetworkResult.Success -> {
                    if (res.data.ok) {
                        _uiState.update { it.copy(toastMessage = "Profile auto-described") }
                        loadProfiles()
                        onComplete?.invoke(res.data.description)
                    } else {
                        _uiState.update {
                            it.copy(toastMessage = res.data.reason ?: "Auto-describe failed")
                        }
                        onComplete?.invoke(null)
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(toastMessage = "Failed to auto-describe: ${res.error.message}")
                    }
                    onComplete?.invoke(null)
                }
            }
        }
    }

    fun createTask(
        body: CreateTaskBody,
        targetStatus: String = "todo",
    ) {
        val board = _uiState.value.selectedBoard ?: return
        if (_uiState.value.isCreatingTask) return
        _uiState.update { it.copy(isCreatingTask = true) }
        viewModelScope.launch {
            val result =
                repository.createTask(
                    board = board.id,
                    body = body,
                )
            when (result) {
                is NetworkResult.Success -> {
                    val createdTask = result.data.task
                    if (createdTask != null &&
                        targetStatus.isNotBlank() &&
                        !createdTask.status.equals(targetStatus, ignoreCase = true)
                    ) {
                        repository.updateTask(
                            taskId = createdTask.id,
                            board = board.id,
                            body = UpdateTaskBody(status = targetStatus),
                        )
                    }
                    val warning = result.data.warning
                    val msg =
                        if (!warning.isNullOrBlank()) {
                            "Task created: $warning"
                        } else {
                            "Task created successfully"
                        }
                    _uiState.update { it.copy(isCreatingTask = false, toastMessage = msg) }
                    reloadBoardSilently()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isCreatingTask = false,
                            toastMessage = "Failed to create task: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun createTask(
        title: String,
        description: String?,
        status: String = "todo",
    ) {
        val defaultAssignee =
            _uiState.value.orchestration
                ?.resolvedDefaultAssignee
                ?.ifBlank { null } ?: "default"
        createTask(
            body =
                CreateTaskBody(
                    title = title,
                    body = description,
                    assignee = defaultAssignee,
                ),
            targetStatus = status,
        )
    }

    suspend fun estimateNewTask(
        title: String,
        body: String?,
    ): TaskEstimate? =
        when (val result = repository.estimateNew(title, body)) {
            is NetworkResult.Success -> result.data
            is NetworkResult.Failure -> null
        }

    fun createBoard(
        slug: String,
        name: String? = null,
        description: String? = null,
        projectId: String? = null,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val body =
                CreateBoardBody(
                    slug = slug,
                    name = name,
                    description = description,
                    projectId = projectId?.ifBlank { null },
                )
            when (val res = repository.createBoard(body)) {
                is NetworkResult.Success -> {
                    val boardName = res.data.board?.name ?: slug
                    _uiState.update { it.copy(toastMessage = "Board created: $boardName") }
                    loadBoards()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Failed to create board: ${res.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun updateBoard(
        slug: String,
        body: RenameBoardBody,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = repository.updateBoard(slug, body)) {
                is NetworkResult.Success -> {
                    val boardName = res.data.board?.displayName ?: slug
                    _uiState.update { it.copy(toastMessage = "Board updated: $boardName") }
                    loadBoards()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Failed to update board: ${res.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun renameBoard(
        slug: String,
        newName: String,
    ) {
        updateBoard(slug, RenameBoardBody(name = newName))
    }

    fun deleteBoard(slug: String) {
        if (slug.equals("default", ignoreCase = true)) {
            _uiState.update { it.copy(toastMessage = "Cannot delete or archive the default board") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = repository.deleteBoard(slug, delete = false)) {
                is NetworkResult.Success -> {
                    val endpoint = endpointProvider()
                    preferences.clearSelectedBoard(endpoint)
                    _uiState.update { it.copy(selectedBoard = null, toastMessage = "Board archived") }
                    loadBoards()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Failed to archive board: ${res.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun deleteTask(taskId: String) {
        val board = _uiState.value.selectedBoard ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = repository.deleteTask(taskId = taskId, board = board.id)) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Task deleted") }
                    reloadBoardSilently()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Failed to delete task: ${res.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun bulkMove(
        taskIds: List<String>,
        targetStatus: String,
        onComplete: ((failedIds: Set<String>) -> Unit)? = null,
    ) {
        val board = _uiState.value.selectedBoard ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = repository.bulkTasks(board.id, BulkTasksBody(ids = taskIds, status = targetStatus))) {
                is NetworkResult.Success -> {
                    val results = res.data.results
                    val succeeded = results.filter { it.ok }.map { it.id }.toSet()
                    val failed = results.filter { !it.ok }.map { it.id }.toSet()
                    val firstError = results.firstOrNull { !it.ok }?.error
                    val msg =
                        when {
                            failed.isEmpty() -> "Moved ${succeeded.size} tasks to $targetStatus"
                            succeeded.isEmpty() -> "Failed to move tasks: ${firstError ?: "unknown error"}"
                            else -> "Moved ${succeeded.size} tasks, ${failed.size} failed: ${firstError ?: ""}"
                        }
                    _uiState.update { it.copy(toastMessage = msg) }
                    reloadBoardSilently()
                    onComplete?.invoke(failed)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Bulk move failed: ${res.error.message}",
                        )
                    }
                    onComplete?.invoke(taskIds.toSet())
                }
            }
        }
    }

    fun bulkArchive(
        taskIds: List<String>,
        onComplete: ((failedIds: Set<String>) -> Unit)? = null,
    ) {
        val board = _uiState.value.selectedBoard ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = repository.bulkTasks(board.id, BulkTasksBody(ids = taskIds, archive = true))) {
                is NetworkResult.Success -> {
                    val results = res.data.results
                    val succeeded = results.filter { it.ok }.map { it.id }.toSet()
                    val failed = results.filter { !it.ok }.map { it.id }.toSet()
                    val firstError = results.firstOrNull { !it.ok }?.error
                    val msg =
                        when {
                            failed.isEmpty() -> "Archived ${succeeded.size} tasks"
                            succeeded.isEmpty() -> "Failed to archive tasks: ${firstError ?: "unknown error"}"
                            else -> "Archived ${succeeded.size} tasks, ${failed.size} failed: ${firstError ?: ""}"
                        }
                    _uiState.update { it.copy(toastMessage = msg) }
                    reloadBoardSilently()
                    onComplete?.invoke(failed)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Bulk archive failed: ${res.error.message}",
                        )
                    }
                    onComplete?.invoke(taskIds.toSet())
                }
            }
        }
    }

    fun bulkAssign(
        taskIds: List<String>,
        assignee: String?,
        onComplete: ((failedIds: Set<String>) -> Unit)? = null,
    ) {
        val board = _uiState.value.selectedBoard ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val body =
                BulkTasksBody(
                    ids = taskIds,
                    assignee = assignee ?: "",
                    reclaimFirst = true,
                )
            when (val res = repository.bulkTasks(board.id, body)) {
                is NetworkResult.Success -> {
                    val results = res.data.results
                    val succeeded = results.filter { it.ok }.map { it.id }.toSet()
                    val failed = results.filter { !it.ok }.map { it.id }.toSet()
                    val firstError = results.firstOrNull { !it.ok }?.error
                    val actionName = if (!assignee.isNullOrBlank()) "Assigned" else "Unassigned"
                    val msg =
                        when {
                            failed.isEmpty() -> "$actionName ${succeeded.size} tasks"
                            succeeded.isEmpty() -> "Failed to update assignment: ${firstError ?: "unknown error"}"
                            else -> "$actionName ${succeeded.size} tasks, ${failed.size} failed: ${firstError ?: ""}"
                        }
                    _uiState.update { it.copy(toastMessage = msg) }
                    reloadBoardSilently()
                    onComplete?.invoke(failed)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Bulk assign failed: ${res.error.message}",
                        )
                    }
                    onComplete?.invoke(taskIds.toSet())
                }
            }
        }
    }

    fun bulkDelete(
        taskIds: List<String>,
        onComplete: ((failedIds: Set<String>) -> Unit)? = null,
    ) {
        val board = _uiState.value.selectedBoard ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val succeeded = mutableSetOf<String>()
            val failed = mutableSetOf<String>()
            var firstError: String? = null

            taskIds.forEach { taskId ->
                when (val res = repository.deleteTask(taskId = taskId, board = board.id)) {
                    is NetworkResult.Success -> {
                        succeeded.add(taskId)
                    }

                    is NetworkResult.Failure -> {
                        failed.add(taskId)
                        if (firstError == null) firstError = res.error.message
                    }
                }
            }

            val msg =
                when {
                    failed.isEmpty() -> "Deleted ${succeeded.size} tasks"
                    succeeded.isEmpty() -> "Failed to delete tasks: ${firstError ?: "unknown error"}"
                    else -> "Deleted ${succeeded.size} tasks, ${failed.size} failed: ${firstError ?: ""}"
                }
            _uiState.update { it.copy(toastMessage = msg) }
            reloadBoardSilently()
            onComplete?.invoke(failed)
        }
    }

    suspend fun exportBoard(slug: String): NetworkResult<BoardExportResult> =
        repository.exportBoard(slug, ExportBoardBody(output = ""))

    suspend fun importBoard(serverArchive: String): NetworkResult<BoardImportResult> =
        repository.importBoard(ImportBoardBody(archive = serverArchive))

    fun moveTask(
        task: KanbanTask,
        action: KanbanTaskAction,
        summary: String? = null,
    ) {
        val board = _uiState.value.selectedBoard ?: return
        if (task.id in _uiState.value.operatingTaskIds) return
        val originalStatus = task.status

        // Optimistically update both tasks and columns, desktop-style
        _uiState.update { state ->
            val updatedTasks =
                state.tasks.map {
                    if (it.id == task.id) it.copy(status = action.targetStatus) else it
                }
            val updatedColumns =
                state.columns.map { col ->
                    val filtered = col.tasks.filter { it.id != task.id }
                    val newTasks =
                        if (col.name.equals(action.targetStatus, ignoreCase = true)) {
                            val moved = updatedTasks.find { it.id == task.id }
                            if (moved != null) filtered + moved else filtered
                        } else {
                            filtered
                        }
                    col.copy(tasks = newTasks)
                }
            state.copy(
                tasks = updatedTasks,
                columns = updatedColumns,
                operatingTaskIds = state.operatingTaskIds + task.id,
            )
        }

        viewModelScope.launch {
            val body =
                UpdateTaskBody(
                    status = action.targetStatus,
                    summary = if (action.needsSummary && !summary.isNullOrBlank()) summary else null,
                    result = if (action.needsSummary && !summary.isNullOrBlank()) summary else null,
                )
            val result = repository.updateTask(task.id, board = board.id, body = body)
            when (result) {
                is NetworkResult.Success -> {
                    val returnedTask = result.data.task
                    _uiState.update { state ->
                        val finalStatus = returnedTask?.status ?: action.targetStatus
                        val updatedTasks =
                            state.tasks.map {
                                if (it.id == task.id) (returnedTask ?: it.copy(status = finalStatus)) else it
                            }
                        val updatedColumns =
                            state.columns.map { col ->
                                val filtered = col.tasks.filter { it.id != task.id }
                                val newTasks =
                                    if (col.name.equals(finalStatus, ignoreCase = true)) {
                                        val moved = updatedTasks.find { it.id == task.id }
                                        if (moved != null) filtered + moved else filtered
                                    } else {
                                        filtered
                                    }
                                col.copy(tasks = newTasks)
                            }
                        state.copy(
                            tasks = updatedTasks,
                            columns = updatedColumns,
                            operatingTaskIds = state.operatingTaskIds - task.id,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    revertTaskMove(task.id, originalStatus, "Move failed: ${result.error.message}")
                }
            }
        }
    }

    private fun revertTaskMove(
        taskId: String,
        originalStatus: String,
        errorMsg: String,
    ) {
        _uiState.update { state ->
            val updatedTasks =
                state.tasks.map {
                    if (it.id == taskId) it.copy(status = originalStatus) else it
                }
            val updatedColumns =
                state.columns.map { col ->
                    val filtered = col.tasks.filter { it.id != taskId }
                    val newTasks =
                        if (col.name.equals(originalStatus, ignoreCase = true)) {
                            val moved = updatedTasks.find { it.id == taskId }
                            if (moved != null) filtered + moved else filtered
                        } else {
                            filtered
                        }
                    col.copy(tasks = newTasks)
                }
            state.copy(
                tasks = updatedTasks,
                columns = updatedColumns,
                operatingTaskIds = state.operatingTaskIds - taskId,
                toastMessage = errorMsg,
            )
        }
    }

    override fun onCleared() {
        eventsClient?.disconnect()
        super.onCleared()
    }

    // ── Live events (issue #775) ─────────────────────────────────────────

    /**
     * Tail the kanban events WebSocket for [board]. The backend pins the board
     * at the WS handshake, so a board switch opens a fresh stream; re-selecting
     * the already-live board is a no-op (the REST load already refreshed it).
     */
    private fun connectEvents(board: KanbanBoard) {
        if (eventsBoard == board.id && _uiState.value.isLive) return
        eventsBoard = board.id
        val client = eventsClient ?: eventsClientProvider().also { eventsClient = it }
        client.connect(
            scope = viewModelScope,
            board = board.id,
            onEvents = { scheduleBoardReload() },
            onStatus = { status ->
                _uiState.update { it.copy(isLive = status == KanbanLiveStatus.CONNECTED) }
            },
        )
    }

    /** Debounced REST refresh after an events batch — mirrors the desktop pattern. */
    private fun scheduleBoardReload() {
        reloadJob?.cancel()
        reloadJob =
            viewModelScope.launch {
                delay(RELOAD_DEBOUNCE_MS)
                reloadBoardSilently()
            }
    }

    /** Re-fetch the current board without touching the loading spinner. */
    private fun reloadBoardSilently() {
        val board = _uiState.value.selectedBoard ?: return
        val gen = currentLoadGen
        viewModelScope.launch {
            val result =
                repository.getBoard(
                    board = board.id,
                    includeArchived = _uiState.value.includeArchived,
                )
            if (gen != currentLoadGen) return@launch
            if (result is NetworkResult.Success) {
                val body = result.data
                _uiState.update {
                    if (it.selectedBoard?.id != board.id) {
                        it
                    } else {
                        it.copy(
                            columns = body.columns,
                            tasks = body.columns.flatMap { col -> col.tasks },
                        )
                    }
                }
            }
        }
    }

    private fun applyBoardResult(
        board: KanbanBoard,
        result: NetworkResult<KanbanBoardResponse>,
        gen: Int,
    ) {
        if (gen != currentLoadGen) return
        when (result) {
            is NetworkResult.Success -> {
                val body = result.data
                val allTasks = body.columns.flatMap { it.tasks }
                _uiState.update {
                    if (it.selectedBoard?.id != board.id) {
                        it
                    } else {
                        it.copy(
                            isLoading = false,
                            columns = body.columns,
                            tasks = allTasks,
                            errorMessage = null,
                        )
                    }
                }
            }

            is NetworkResult.Failure -> {
                _uiState.update {
                    if (it.selectedBoard?.id != board.id) {
                        it
                    } else {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load Kanban tasks: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadBoardIntoState(
        board: KanbanBoard,
        gen: Int = currentLoadGen,
    ) {
        val result =
            repository.getBoard(
                board = board.id,
                includeArchived = _uiState.value.includeArchived,
            )
        applyBoardResult(board, result, gen)
    }

    fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 250L
    }
}
