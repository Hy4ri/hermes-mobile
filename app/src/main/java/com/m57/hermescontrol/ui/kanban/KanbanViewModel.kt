package com.m57.hermescontrol.ui.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.CreateTaskRequest
import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.model.MoveTaskRequest
import com.m57.hermescontrol.data.remote.ApiClient
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
    val tasks: List<KanbanTask> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class KanbanViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(KanbanUiState())
    val uiState: StateFlow<KanbanUiState> = _uiState.asStateFlow()

    fun loadBoards() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getKanbanBoards()
                    }
                if (response.isSuccessful) {
                    val boards = response.body().orEmpty()
                    _uiState.update { it.copy(isLoading = false, boards = boards) }
                    if (boards.isNotEmpty()) {
                        selectBoard(boards.first())
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load Kanban boards: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load Kanban boards: ${e.message}",
                    )
                }
            }
        }
    }

    fun selectBoard(board: KanbanBoard) {
        _uiState.update { it.copy(selectedBoard = board, isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getKanbanTasks(board.id)
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(isLoading = false, tasks = response.body().orEmpty()) }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load Kanban tasks: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load Kanban tasks: ${e.message}",
                    )
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
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.createKanbanTask(
                            CreateTaskRequest(
                                boardId = board.id,
                                title = title,
                                description = description,
                                status = status,
                            ),
                        )
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(toastMessage = "Task created successfully") }
                    selectBoard(board)
                } else {
                    _uiState.update { it.copy(toastMessage = "Failed to create task: HTTP ${response.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Failed to create task: ${e.message}") }
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
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.moveKanbanTask(task.id, MoveTaskRequest(newStatus))
                    }
                if (!response.isSuccessful) {
                    revertTaskMove(task.id, originalStatus, "Failed to move task: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                revertTaskMove(task.id, originalStatus, "Failed to move task: ${e.message}")
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

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
