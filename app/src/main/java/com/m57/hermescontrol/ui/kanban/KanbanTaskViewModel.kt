package com.m57.hermescontrol.ui.kanban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.KanbanTaskDetailResponse
import com.m57.hermescontrol.data.model.ReassignTaskBody
import com.m57.hermescontrol.data.model.ReclaimTaskBody
import com.m57.hermescontrol.data.model.UpdateTaskBody
import com.m57.hermescontrol.data.model.WorkerLog
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.repository.KanbanRepository
import com.m57.hermescontrol.data.repository.KanbanRepositoryImpl
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

data class KanbanTaskUiState(
    val isLoading: Boolean = false,
    val detail: KanbanTaskDetailResponse? = null,
    val workerLog: WorkerLog? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val isSubmittingComment: Boolean = false,
    val isSavingDescription: Boolean = false,
    val isReclaiming: Boolean = false,
    val isUploadingAttachment: Boolean = false,
)

class KanbanTaskViewModel(
    private val repository: KanbanRepository = KanbanRepositoryImpl(),
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(KanbanTaskUiState())
    val uiState: StateFlow<KanbanTaskUiState> = _uiState.asStateFlow()

    fun loadTask(
        board: String,
        taskId: String,
    ) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = repository.getTask(taskId = taskId, board = board)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            detail = result.data,
                        )
                    }
                    if (result.data.task.status
                            .equals("running", ignoreCase = true)
                    ) {
                        loadLog(board, taskId)
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load task details: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun loadLog(
        board: String,
        taskId: String,
    ) {
        viewModelScope.launch {
            when (val result = repository.getTaskLog(taskId = taskId, board = board)) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(workerLog = result.data) }
                }

                is NetworkResult.Failure -> {
                    // Failures to read log don't blank the task screen
                }
            }
        }
    }

    fun updateDescription(
        board: String,
        taskId: String,
        newBody: String,
    ) {
        _uiState.update { it.copy(isSavingDescription = true) }
        viewModelScope.launch {
            val result =
                repository.updateTask(
                    taskId = taskId,
                    board = board,
                    body = UpdateTaskBody(body = newBody),
                )
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            isSavingDescription = false,
                            detail =
                                state.detail?.let { d ->
                                    d.copy(task = d.task.copy(body = newBody))
                                },
                            toastMessage = "Description saved",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isSavingDescription = false,
                            toastMessage = "Failed to save: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun addComment(
        board: String,
        taskId: String,
        body: String,
    ) {
        if (body.isBlank()) return
        _uiState.update { it.copy(isSubmittingComment = true) }
        viewModelScope.launch {
            when (val result = repository.addComment(taskId = taskId, board = board, body = body)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmittingComment = false,
                            toastMessage = "Comment added",
                        )
                    }
                    loadTask(board, taskId)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isSubmittingComment = false,
                            toastMessage = "Failed to add comment: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun postNoteAndRequeue(
        board: String,
        taskId: String,
        body: String,
    ) {
        if (body.isBlank()) return
        _uiState.update { it.copy(isSubmittingComment = true) }
        viewModelScope.launch {
            val commentRes = repository.addComment(taskId = taskId, board = board, body = body)
            if (commentRes is NetworkResult.Failure) {
                _uiState.update {
                    it.copy(
                        isSubmittingComment = false,
                        toastMessage = "Failed to post note: ${commentRes.error.message}",
                    )
                }
                return@launch
            }

            val reclaimRes = repository.reclaimTask(taskId = taskId, board = board, reason = "Requeued with note")
            when (reclaimRes) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmittingComment = false,
                            toastMessage = "Note posted & task requeued",
                        )
                    }
                    loadTask(board, taskId)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isSubmittingComment = false,
                            toastMessage = "Note posted, but reclaim failed: ${reclaimRes.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun reclaim(
        board: String,
        taskId: String,
        reason: String? = null,
    ) {
        _uiState.update { it.copy(isReclaiming = true) }
        viewModelScope.launch {
            when (val result = repository.reclaimTask(taskId = taskId, board = board, reason = reason)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isReclaiming = false,
                            toastMessage = "Task reclaimed",
                        )
                    }
                    loadTask(board, taskId)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isReclaiming = false,
                            toastMessage = "Failed to reclaim: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun reassign(
        board: String,
        taskId: String,
        profile: String,
    ) {
        viewModelScope.launch {
            when (val result = repository.reassignTask(taskId = taskId, board = board, profile = profile)) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Task reassigned to $profile") }
                    loadTask(board, taskId)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(toastMessage = "Failed to reassign: ${result.error.message}")
                    }
                }
            }
        }
    }

    fun uploadAttachment(
        board: String,
        taskId: String,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
    ) {
        val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("file", filename, requestBody)
        uploadAttachment(board, taskId, part)
    }

    fun uploadAttachment(
        board: String,
        taskId: String,
        part: MultipartBody.Part,
    ) {
        _uiState.update { it.copy(isUploadingAttachment = true) }
        viewModelScope.launch {
            when (val result = repository.uploadAttachment(taskId = taskId, board = board, file = part)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isUploadingAttachment = false,
                            toastMessage = "Attachment uploaded",
                        )
                    }
                    loadTask(board, taskId)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isUploadingAttachment = false,
                            toastMessage = "Failed to upload: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
