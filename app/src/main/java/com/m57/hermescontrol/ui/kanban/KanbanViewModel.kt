package com.m57.hermescontrol.ui.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanColumn
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class KanbanUiState(
    val isLoading: Boolean = false,
    val boards: List<KanbanBoard> = emptyList(),
    val selectedBoard: KanbanBoard? = null,
    val columns: List<KanbanColumn> = emptyList(),
    val tasks: List<KanbanTask> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class KanbanViewModel : ViewModel(), ToastHost {
    private val _uiState = MutableStateFlow(KanbanUiState())
    val uiState: StateFlow<KanbanUiState> = _uiState.asStateFlow()

    fun loadBoards() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getKanbanBoards() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                val boards = data.boards.orEmpty()
                _uiState.update { it.copy(isLoading = false, boards = boards) }
                val currentSlug = data.current
                val currentBoard = boards.find { it.id == currentSlug } ?: boards.firstOrNull()
                if (currentBoard != null) {
                    selectBoard(currentBoard)
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load Kanban boards: $errorMsg",
                    )
                }
            },
        )
    }

    fun selectBoard(board: KanbanBoard) {
        _uiState.update { it.copy(selectedBoard = board, isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val switchResult =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.switchKanbanBoard(board.id) }
                }
            if (switchResult is NetworkResult.Failure) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to switch Kanban board: ${switchResult.error.message}",
                    )
                }
                return@launch
            }

            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getKanbanBoard() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val body = result.data
                    val allTasks = body.columns.flatMap { it.tasks }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            columns = body.columns,
                            tasks = allTasks,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load Kanban tasks: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun createTask(
        title: String,
        description: String?,
        status: String,
    ) {
        val board = _uiState.value.selectedBoard ?: return
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.createKanbanTask(
                            board = board.id,
                            task =
                                com.m57.hermescontrol.data.model.CreateTaskBody(
                                    title = title,
                                    body = description,
                                ),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Task created successfully") }
                    selectBoard(board)
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to create task: ${result.error.message}") }
                }
            }
        }
    }

    fun moveTask(
        task: KanbanTask,
        newStatus: String,
    ) {
        val originalStatus = task.status
        val board = _uiState.value.selectedBoard ?: return

        // Optimistically update
        _uiState.update { state ->
            state.copy(
                tasks =
                    state.tasks.map {
                        if (it.id == task.id) it.copy(status = newStatus) else it
                    },
            )
        }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updateKanbanTask(task.id, mapOf("status" to newStatus)) }
                }
            if (result is NetworkResult.Failure) {
                revertTaskMove(task.id, originalStatus, "Failed to move task: ${result.error.message}")
            }
        }
    }

    private fun revertTaskMove(
        taskId: String,
        originalStatus: String,
        errorMsg: String,
    ) {
        _uiState.update { state ->
            state.copy(
                tasks =
                    state.tasks.map {
                        if (it.id == taskId) it.copy(status = originalStatus) else it
                    },
                toastMessage = errorMsg,
            )
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
