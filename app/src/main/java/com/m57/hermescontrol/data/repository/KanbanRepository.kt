package com.m57.hermescontrol.data.repository

import com.m57.hermescontrol.data.model.AttachmentUploadResponse
import com.m57.hermescontrol.data.model.AutoDescribeResponse
import com.m57.hermescontrol.data.model.BulkTasksBody
import com.m57.hermescontrol.data.model.BulkTasksResponse
import com.m57.hermescontrol.data.model.CreateBoardBody
import com.m57.hermescontrol.data.model.CreateBoardResponse
import com.m57.hermescontrol.data.model.CreateTaskBody
import com.m57.hermescontrol.data.model.CreateTaskResponse
import com.m57.hermescontrol.data.model.DeleteBoardResponse
import com.m57.hermescontrol.data.model.DispatchResult
import com.m57.hermescontrol.data.model.KanbanBoardResponse
import com.m57.hermescontrol.data.model.KanbanBoardsResponse
import com.m57.hermescontrol.data.model.KanbanProfilesResponse
import com.m57.hermescontrol.data.model.KanbanProjectsResponse
import com.m57.hermescontrol.data.model.KanbanTaskDetailResponse
import com.m57.hermescontrol.data.model.OrchestrationSettings
import com.m57.hermescontrol.data.model.OrchestrationSettingsUpdate
import com.m57.hermescontrol.data.model.ReassignTaskBody
import com.m57.hermescontrol.data.model.ReassignTaskResponse
import com.m57.hermescontrol.data.model.ReclaimTaskBody
import com.m57.hermescontrol.data.model.ReclaimTaskResponse
import com.m57.hermescontrol.data.model.RenameBoardBody
import com.m57.hermescontrol.data.model.RenameBoardResponse
import com.m57.hermescontrol.data.model.TaskEstimate
import com.m57.hermescontrol.data.model.UpdateTaskBody
import com.m57.hermescontrol.data.model.UpdateTaskResponse
import com.m57.hermescontrol.data.model.WorkerLog
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.KanbanApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.ResponseBody

interface KanbanRepository {
    suspend fun getBoards(includeArchived: Boolean = false): NetworkResult<KanbanBoardsResponse>

    suspend fun getBoard(
        board: String,
        includeArchived: Boolean = false,
        tenant: String? = null,
    ): NetworkResult<KanbanBoardResponse>

    suspend fun createBoard(body: CreateBoardBody): NetworkResult<CreateBoardResponse>

    suspend fun updateBoard(
        slug: String,
        body: RenameBoardBody,
    ): NetworkResult<RenameBoardResponse>

    suspend fun deleteBoard(
        slug: String,
        delete: Boolean = false,
    ): NetworkResult<DeleteBoardResponse>

    suspend fun getTask(
        taskId: String,
        board: String?,
    ): NetworkResult<KanbanTaskDetailResponse>

    suspend fun createTask(
        board: String?,
        body: CreateTaskBody,
    ): NetworkResult<CreateTaskResponse>

    suspend fun updateTask(
        taskId: String,
        board: String?,
        body: UpdateTaskBody,
    ): NetworkResult<UpdateTaskResponse>

    suspend fun deleteTask(
        taskId: String,
        board: String?,
    ): NetworkResult<Unit>

    suspend fun bulkTasks(
        board: String?,
        body: BulkTasksBody,
    ): NetworkResult<BulkTasksResponse>

    suspend fun addComment(
        taskId: String,
        board: String?,
        body: String,
    ): NetworkResult<Unit>

    suspend fun reassignTask(
        taskId: String,
        board: String?,
        profile: String?,
        reclaimFirst: Boolean = true,
    ): NetworkResult<ReassignTaskResponse>

    suspend fun reclaimTask(
        taskId: String,
        board: String?,
        reason: String? = null,
    ): NetworkResult<ReclaimTaskResponse>

    suspend fun getTaskLog(
        taskId: String,
        board: String?,
        tail: Int = 16384,
    ): NetworkResult<WorkerLog>

    suspend fun estimateTask(
        taskId: String,
        board: String?,
    ): NetworkResult<TaskEstimate>

    suspend fun estimateNew(
        title: String,
        body: String?,
    ): NetworkResult<TaskEstimate>

    suspend fun uploadAttachment(
        taskId: String,
        board: String?,
        file: MultipartBody.Part,
    ): NetworkResult<AttachmentUploadResponse>

    suspend fun downloadAttachment(
        attachmentId: Long,
        board: String?,
    ): NetworkResult<ResponseBody>

    suspend fun getProfiles(): NetworkResult<KanbanProfilesResponse>

    suspend fun updateProfileDescription(
        name: String,
        description: String,
    ): NetworkResult<Unit>

    suspend fun autoDescribeProfile(name: String): NetworkResult<AutoDescribeResponse>

    suspend fun getOrchestration(): NetworkResult<OrchestrationSettings>

    suspend fun updateOrchestration(body: OrchestrationSettingsUpdate): NetworkResult<OrchestrationSettings>

    suspend fun getProjects(): NetworkResult<KanbanProjectsResponse>

    suspend fun nudgeDispatcher(board: String?): NetworkResult<DispatchResult>
}

class KanbanRepositoryImpl(
    private val apiProvider: () -> KanbanApiService = { ApiClient.kanbanApi },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : KanbanRepository {
    override suspend fun getBoards(includeArchived: Boolean): NetworkResult<KanbanBoardsResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().getBoards(includeArchived) }
        }

    override suspend fun getBoard(
        board: String,
        includeArchived: Boolean,
        tenant: String?,
    ): NetworkResult<KanbanBoardResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().getBoard(board = board, includeArchived = includeArchived, tenant = tenant) }
        }

    override suspend fun createBoard(body: CreateBoardBody): NetworkResult<CreateBoardResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().createBoard(body) }
        }

    override suspend fun updateBoard(
        slug: String,
        body: RenameBoardBody,
    ): NetworkResult<RenameBoardResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().updateBoard(slug, body) }
        }

    override suspend fun deleteBoard(
        slug: String,
        delete: Boolean,
    ): NetworkResult<DeleteBoardResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().deleteBoard(slug, delete) }
        }

    override suspend fun getTask(
        taskId: String,
        board: String?,
    ): NetworkResult<KanbanTaskDetailResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().getTask(taskId = taskId, board = board) }
        }

    override suspend fun createTask(
        board: String?,
        body: CreateTaskBody,
    ): NetworkResult<CreateTaskResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().createTask(board = board, body = body) }
        }

    override suspend fun updateTask(
        taskId: String,
        board: String?,
        body: UpdateTaskBody,
    ): NetworkResult<UpdateTaskResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().updateTask(taskId = taskId, board = board, body = body) }
        }

    override suspend fun deleteTask(
        taskId: String,
        board: String?,
    ): NetworkResult<Unit> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().deleteTask(taskId = taskId, board = board) }
        }

    override suspend fun bulkTasks(
        board: String?,
        body: BulkTasksBody,
    ): NetworkResult<BulkTasksResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().bulkTasks(board = board, body = body) }
        }

    override suspend fun addComment(
        taskId: String,
        board: String?,
        body: String,
    ): NetworkResult<Unit> =
        withContext(ioDispatcher) {
            safeApiCall {
                apiProvider().addComment(
                    taskId = taskId,
                    board = board,
                    body = mapOf("body" to body, "author" to "mobile"),
                )
            }
        }

    override suspend fun reassignTask(
        taskId: String,
        board: String?,
        profile: String?,
        reclaimFirst: Boolean,
    ): NetworkResult<ReassignTaskResponse> =
        withContext(ioDispatcher) {
            safeApiCall {
                apiProvider().reassignTask(
                    taskId = taskId,
                    board = board,
                    body = ReassignTaskBody(profile = profile, reclaimFirst = reclaimFirst),
                )
            }
        }

    override suspend fun reclaimTask(
        taskId: String,
        board: String?,
        reason: String?,
    ): NetworkResult<ReclaimTaskResponse> =
        withContext(ioDispatcher) {
            safeApiCall {
                apiProvider().reclaimTask(
                    taskId = taskId,
                    board = board,
                    body = ReclaimTaskBody(reason = reason),
                )
            }
        }

    override suspend fun getTaskLog(
        taskId: String,
        board: String?,
        tail: Int,
    ): NetworkResult<WorkerLog> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().getTaskLog(taskId = taskId, tail = tail, board = board) }
        }

    override suspend fun estimateTask(
        taskId: String,
        board: String?,
    ): NetworkResult<TaskEstimate> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().estimateTask(taskId = taskId, board = board) }
        }

    override suspend fun estimateNew(
        title: String,
        body: String?,
    ): NetworkResult<TaskEstimate> =
        withContext(ioDispatcher) {
            val payload =
                buildMap {
                    put("title", title)
                    if (!body.isNullOrBlank()) put("body", body)
                }
            safeApiCall { apiProvider().estimateNew(payload) }
        }

    override suspend fun uploadAttachment(
        taskId: String,
        board: String?,
        file: MultipartBody.Part,
    ): NetworkResult<AttachmentUploadResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().uploadAttachment(taskId = taskId, board = board, file = file) }
        }

    override suspend fun downloadAttachment(
        attachmentId: Long,
        board: String?,
    ): NetworkResult<ResponseBody> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().downloadAttachment(attachmentId = attachmentId, board = board) }
        }

    override suspend fun getProfiles(): NetworkResult<KanbanProfilesResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().getProfiles() }
        }

    override suspend fun updateProfileDescription(
        name: String,
        description: String,
    ): NetworkResult<Unit> =
        withContext(ioDispatcher) {
            safeApiCall {
                apiProvider().updateProfileDescription(
                    name = name,
                    body = mapOf("description" to description),
                )
            }
        }

    override suspend fun autoDescribeProfile(name: String): NetworkResult<AutoDescribeResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().autoDescribeProfile(name) }
        }

    override suspend fun getOrchestration(): NetworkResult<OrchestrationSettings> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().getOrchestration() }
        }

    override suspend fun updateOrchestration(body: OrchestrationSettingsUpdate): NetworkResult<OrchestrationSettings> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().updateOrchestration(body) }
        }

    override suspend fun getProjects(): NetworkResult<KanbanProjectsResponse> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().getProjects() }
        }

    override suspend fun nudgeDispatcher(board: String?): NetworkResult<DispatchResult> =
        withContext(ioDispatcher) {
            safeApiCall { apiProvider().nudgeDispatcher(board) }
        }
}
