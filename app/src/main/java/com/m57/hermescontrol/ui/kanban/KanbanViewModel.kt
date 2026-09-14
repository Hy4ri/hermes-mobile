package com.m57.hermescontrol.ui.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.InMemoryKanbanPreferencesStore
import com.m57.hermescontrol.data.local.KanbanPreferencesStore
import com.m57.hermescontrol.data.model.BulkTasksBody
import com.m57.hermescontrol.data.model.CreateBoardBody
import com.m57.hermescontrol.data.model.CreateTaskBody
import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanColumn
import com.m57.hermescontrol.data.model.KanbanProfile
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.model.RenameBoardBody
import com.m57.hermescontrol.data.model.TaskEstimate
import com.m57.hermescontrol.data.model.UpdateTaskBody
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.repository.KanbanRepository
import com.m57.hermescontrol.data.repository.KanbanRepositoryImpl
import com.m57.hermescontrol.data.ws.KanbanEventsClient
import com.m57.hermescontrol.data.ws.KanbanLiveStatus
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.Job
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
    val isLive: Boolean = false,
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
    private val _uiState = MutableStateFlow(KanbanUiState())
    val uiState: StateFlow<KanbanUiState> = _uiState.asStateFlow()

    private var eventsClient: KanbanEventsClient? = null
    private var eventsBoard: String? = null
    private var reloadJob: Job? = null
    private var currentLoadGen: Int = 0

    fun loadBoards() {
        val endpoint = endpointProvider()
        val savedSlug = preferences.getSelectedBoard(endpoint)
        val previouslySelectedId = _uiState.value.selectedBoard?.id ?: savedSlug

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        loadProfiles()
        viewModelScope.launch {
            when (val result = repository.getBoards()) {
                is NetworkResult.Success -> {
                    val boards = result.data.boards
                    _uiState.update { it.copy(isLoading = false, boards = boards) }
                    val targetBoard =
                        boards.find { it.id == previouslySelectedId }
                            ?: boards.find { it.id == result.data.current }
                            ?: boards.firstOrNull()
                    if (targetBoard != null) {
                        selectBoard(targetBoard)
                    } else {
                        _uiState.update { it.copy(selectedBoard = null, columns = emptyList(), tasks = emptyList()) }
                    }
                }

                is NetworkResult.Failure -> {
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
        createTask(
            body =
                CreateTaskBody(
                    title = title,
                    body = description,
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
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val body =
                CreateBoardBody(
                    slug = slug,
                    name = name,
                    description = description,
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

    fun renameBoard(
        slug: String,
        newName: String,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = repository.updateBoard(slug, RenameBoardBody(name = newName))) {
                is NetworkResult.Success -> {
                    val boardName = res.data.board?.name ?: newName
                    _uiState.update { it.copy(toastMessage = "Board renamed to $boardName") }
                    loadBoards()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Failed to rename board: ${res.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun deleteBoard(slug: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = repository.deleteBoard(slug, delete = true)) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Board deleted") }
                    loadBoards()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Failed to delete board: ${res.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun bulkMove(
        taskIds: List<String>,
        targetStatus: String,
    ) {
        val board = _uiState.value.selectedBoard ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = repository.bulkTasks(board.id, BulkTasksBody(ids = taskIds, status = targetStatus))) {
                is NetworkResult.Success -> {
                    val count = res.data.results.count { it.ok }
                    _uiState.update { it.copy(toastMessage = "Moved $count tasks to $targetStatus") }
                    reloadBoardSilently()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Bulk move failed: ${res.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun bulkArchive(taskIds: List<String>) {
        val board = _uiState.value.selectedBoard ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = repository.bulkTasks(board.id, BulkTasksBody(ids = taskIds, archive = true))) {
                is NetworkResult.Success -> {
                    val count = res.data.results.count { it.ok }
                    _uiState.update { it.copy(toastMessage = "Archived $count tasks") }
                    reloadBoardSilently()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Bulk archive failed: ${res.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun bulkAssign(
        taskIds: List<String>,
        assignee: String?,
    ) {
        val board = _uiState.value.selectedBoard ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = repository.bulkTasks(board.id, BulkTasksBody(ids = taskIds, assignee = assignee))) {
                is NetworkResult.Success -> {
                    val count = res.data.results.count { it.ok }
                    _uiState.update { it.copy(toastMessage = "Assigned $count tasks") }
                    reloadBoardSilently()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Bulk assign failed: ${res.error.message}",
                        )
                    }
                }
            }
        }
    }

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
            val result = repository.getBoard(board = board.id)
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

    private suspend fun loadBoardIntoState(
        board: KanbanBoard,
        gen: Int = currentLoadGen,
    ) {
        val result = repository.getBoard(board = board.id)
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

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 250L
    }
}
