package com.m57.hermescontrol.data.remote

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
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface KanbanApiService {
    @GET("api/plugins/kanban/boards")
    suspend fun getBoards(
        @Query("include_archived") includeArchived: Boolean = false,
    ): Response<KanbanBoardsResponse>

    @GET("api/plugins/kanban/board")
    suspend fun getBoard(
        @Query("board") board: String? = null,
        @Query("include_archived") includeArchived: Boolean = false,
        @Query("tenant") tenant: String? = null,
    ): Response<KanbanBoardResponse>

    @POST("api/plugins/kanban/boards")
    suspend fun createBoard(
        @Body body: CreateBoardBody,
    ): Response<CreateBoardResponse>

    @PATCH("api/plugins/kanban/boards/{slug}")
    suspend fun updateBoard(
        @Path("slug") slug: String,
        @Body body: RenameBoardBody,
    ): Response<RenameBoardResponse>

    @DELETE("api/plugins/kanban/boards/{slug}")
    suspend fun deleteBoard(
        @Path("slug") slug: String,
        @Query("delete") delete: Boolean = false,
    ): Response<DeleteBoardResponse>

    @POST("api/plugins/kanban/boards/{slug}/switch")
    suspend fun switchBoard(
        @Path("slug") slug: String,
    ): Response<Unit>

    @GET("api/plugins/kanban/tasks/{id}")
    suspend fun getTask(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
    ): Response<KanbanTaskDetailResponse>

    @POST("api/plugins/kanban/tasks")
    suspend fun createTask(
        @Query("board") board: String? = null,
        @Body body: CreateTaskBody,
    ): Response<CreateTaskResponse>

    @PATCH("api/plugins/kanban/tasks/{id}")
    suspend fun updateTask(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
        @Body body: UpdateTaskBody,
    ): Response<UpdateTaskResponse>

    @DELETE("api/plugins/kanban/tasks/{id}")
    suspend fun deleteTask(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
    ): Response<Unit>

    @POST("api/plugins/kanban/tasks/bulk")
    suspend fun bulkTasks(
        @Query("board") board: String? = null,
        @Body body: BulkTasksBody,
    ): Response<BulkTasksResponse>

    @POST("api/plugins/kanban/tasks/{id}/comments")
    suspend fun addComment(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
        @Body body: Map<String, String>,
    ): Response<Unit>

    @POST("api/plugins/kanban/tasks/{id}/reassign")
    suspend fun reassignTask(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
        @Body body: ReassignTaskBody,
    ): Response<ReassignTaskResponse>

    @POST("api/plugins/kanban/tasks/{id}/reclaim")
    suspend fun reclaimTask(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
        @Body body: ReclaimTaskBody = ReclaimTaskBody(),
    ): Response<ReclaimTaskResponse>

    @GET("api/plugins/kanban/tasks/{id}/log")
    suspend fun getTaskLog(
        @Path("id") taskId: String,
        @Query("tail") tail: Int = 16384,
        @Query("board") board: String? = null,
    ): Response<WorkerLog>

    @POST("api/plugins/kanban/tasks/{id}/estimate")
    suspend fun estimateTask(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
    ): Response<TaskEstimate>

    @POST("api/plugins/kanban/estimate")
    suspend fun estimateNew(
        @Body body: Map<String, String>,
    ): Response<TaskEstimate>

    @Multipart
    @POST("api/plugins/kanban/tasks/{id}/attachments")
    suspend fun uploadAttachment(
        @Path("id") taskId: String,
        @Query("board") board: String? = null,
        @Part file: MultipartBody.Part,
    ): Response<AttachmentUploadResponse>

    @Streaming
    @GET("api/plugins/kanban/attachments/{id}")
    suspend fun downloadAttachment(
        @Path("id") attachmentId: Long,
        @Query("board") board: String? = null,
    ): Response<ResponseBody>

    @GET("api/plugins/kanban/profiles")
    suspend fun getProfiles(): Response<KanbanProfilesResponse>

    @PATCH("api/plugins/kanban/profiles/{name}")
    suspend fun updateProfileDescription(
        @Path("name") name: String,
        @Body body: Map<String, String>,
    ): Response<Unit>

    @POST("api/plugins/kanban/profiles/{name}/describe-auto")
    suspend fun autoDescribeProfile(
        @Path("name") name: String,
        @Body body: Map<String, Boolean> = mapOf("overwrite" to true),
    ): Response<AutoDescribeResponse>

    @GET("api/plugins/kanban/orchestration")
    suspend fun getOrchestration(): Response<OrchestrationSettings>

    @PUT("api/plugins/kanban/orchestration")
    suspend fun updateOrchestration(
        @Body body: OrchestrationSettingsUpdate,
    ): Response<OrchestrationSettings>

    @GET("api/plugins/kanban/projects")
    suspend fun getProjects(): Response<KanbanProjectsResponse>

    @POST("api/plugins/kanban/dispatch")
    suspend fun nudgeDispatcher(
        @Query("board") board: String? = null,
    ): Response<DispatchResult>
}
