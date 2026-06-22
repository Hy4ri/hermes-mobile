package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.AchievementsResponse
import com.m57.hermescontrol.data.model.ActiveProfileResponse
import com.m57.hermescontrol.data.model.AgentPluginInstallBody
import com.m57.hermescontrol.data.model.CreateTaskBody
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.model.DoctorResponse
import com.m57.hermescontrol.data.model.EnvVarConfig
import com.m57.hermescontrol.data.model.EnvVarRevealRequest
import com.m57.hermescontrol.data.model.EnvVarRevealResponse
import com.m57.hermescontrol.data.model.EnvVarUpdate
import com.m57.hermescontrol.data.model.KanbanBoardResponse
import com.m57.hermescontrol.data.model.KanbanBoardsResponse
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.model.LogResponse
import com.m57.hermescontrol.data.model.McpServerToggleRequest
import com.m57.hermescontrol.data.model.McpServersResponse
import com.m57.hermescontrol.data.model.MessagingPlatformResponse
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.model.PairingApproveRequest
import com.m57.hermescontrol.data.model.PairingResponse
import com.m57.hermescontrol.data.model.PairingRevokeRequest
import com.m57.hermescontrol.data.model.PluginsHubResponse
import com.m57.hermescontrol.data.model.ProfileSoulResponse
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.model.RawConfigResponse
import com.m57.hermescontrol.data.model.SaveSkillContentRequest
import com.m57.hermescontrol.data.model.SessionListResponse
import com.m57.hermescontrol.data.model.SessionMessagesResponse
import com.m57.hermescontrol.data.model.SetActiveProfileRequest
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.SkillContentResponse
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.model.SystemStatsResponse
import com.m57.hermescontrol.data.model.ToggleSkillRequest
import com.m57.hermescontrol.data.model.Toolset
import com.m57.hermescontrol.data.model.ToolsetToggleRequest
import com.m57.hermescontrol.data.model.UpdateProfileModelRequest
import com.m57.hermescontrol.data.model.UpdateProfileSoulRequest
import com.m57.hermescontrol.data.model.UpdateRawConfigRequest
import com.m57.hermescontrol.data.model.WebhooksResponse
import com.m57.hermescontrol.data.model.WebhooksToggleRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface HermesApiService {
    @GET("api/skills/content")
    suspend fun getSkillContent(
        @Query("name") name: String,
    ): Response<SkillContentResponse>

    @PUT("api/skills/content")
    suspend fun saveSkillContent(
        @Body body: SaveSkillContentRequest,
    ): Response<Unit>

    @GET("api/status")
    suspend fun getStatus(): Response<StatusResponse>

    @GET("api/sessions")
    suspend fun getSessions(): Response<SessionListResponse>

    @GET("api/sessions/{id}/messages")
    suspend fun getSessionMessages(
        @Path("id") sessionId: String,
    ): Response<SessionMessagesResponse>

    @GET("api/system/stats")
    suspend fun getSystemStats(): Response<SystemStatsResponse>

    @GET("api/skills")
    suspend fun getSkills(): Response<List<Skill>>

    @PUT("api/skills/toggle")
    suspend fun toggleSkill(
        @Body body: ToggleSkillRequest,
    ): Response<Unit>

    @GET("api/cron/jobs")
    suspend fun getCronJobs(): Response<List<CronJob>>

    @POST("api/cron/jobs/{id}/pause")
    suspend fun pauseCronJob(
        @Path("id") id: String,
    ): Response<Unit>

    @POST("api/cron/jobs/{id}/resume")
    suspend fun resumeCronJob(
        @Path("id") id: String,
    ): Response<Unit>

    @POST("api/cron/jobs/{id}/trigger")
    suspend fun triggerCronJob(
        @Path("id") id: String,
    ): Response<Unit>

    @DELETE("api/cron/jobs/{id}")
    suspend fun deleteCronJob(
        @Path("id") id: String,
    ): Response<Unit>

    @POST("api/gateway/start")
    suspend fun startGateway(): Response<Unit>

    @POST("api/gateway/stop")
    suspend fun stopGateway(): Response<Unit>

    @POST("api/gateway/restart")
    suspend fun restartGateway(): Response<Unit>

    @GET("api/profiles")
    suspend fun getProfiles(): Response<ProfilesResponse>

    @GET("api/profiles/active")
    suspend fun getActiveProfile(): Response<ActiveProfileResponse>

    @POST("api/profiles/active")
    suspend fun setActiveProfile(
        @Body body: SetActiveProfileRequest,
    ): Response<Unit>

    @GET("api/profiles/{name}/soul")
    suspend fun getProfileSoul(
        @Path("name") name: String,
    ): Response<ProfileSoulResponse>

    @PUT("api/profiles/{name}/soul")
    suspend fun updateProfileSoul(
        @Path("name") name: String,
        @Body body: UpdateProfileSoulRequest,
    ): Response<Unit>

    @PUT("api/profiles/{name}/model")
    suspend fun updateProfileModel(
        @Path("name") name: String,
        @Body body: UpdateProfileModelRequest,
    ): Response<Unit>

    @GET("api/tools/toolsets")
    suspend fun getToolsets(): Response<List<Toolset>>

    @PUT("api/tools/toolsets/{name}")
    suspend fun toggleToolset(
        @Path("name") name: String,
        @Body body: ToolsetToggleRequest,
    ): Response<Unit>

    @GET("api/plugins/hermes-achievements/achievements")
    suspend fun getAchievements(): Response<AchievementsResponse>

    @GET("api/pairing")
    suspend fun getPairing(): Response<PairingResponse>

    @POST("api/pairing/approve")
    suspend fun approvePairing(
        @Body body: PairingApproveRequest,
    ): Response<Unit>

    @POST("api/pairing/revoke")
    suspend fun revokePairing(
        @Body body: PairingRevokeRequest,
    ): Response<Unit>

    @POST("api/pairing/clear-pending")
    suspend fun clearPendingPairing(): Response<Unit>

    @GET("api/config/raw")
    suspend fun getRawConfig(): Response<RawConfigResponse>

    @PUT("api/config/raw")
    suspend fun updateRawConfig(
        @Body body: UpdateRawConfigRequest,
    ): Response<Unit>

    @GET("api/mcp/servers")
    suspend fun getMcpServers(): Response<McpServersResponse>

    @PUT("api/mcp/servers/{name}/enabled")
    suspend fun toggleMcpServer(
        @Path("name") name: String,
        @Body body: McpServerToggleRequest,
    ): Response<Unit>

    @POST("api/mcp/servers/{name}/test")
    suspend fun testMcpServer(
        @Path("name") name: String,
    ): Response<Unit>

    @DELETE("api/mcp/servers/{name}")
    suspend fun deleteMcpServer(
        @Path("name") name: String,
    ): Response<Unit>

    @GET("api/webhooks")
    suspend fun getWebhooks(): Response<WebhooksResponse>

    @POST("api/webhooks/enable")
    suspend fun toggleWebhooks(
        @Body body: WebhooksToggleRequest,
    ): Response<Unit>

    @GET("api/model/options")
    suspend fun getModelOptions(): Response<ModelOptionsResponse>

    @GET("api/logs")
    suspend fun getLogs(): Response<LogResponse>

    @GET("api/dashboard/plugins/hub")
    suspend fun getPlugins(): Response<PluginsHubResponse>

    @POST("api/dashboard/agent-plugins/install")
    suspend fun installPlugin(
        @Body body: AgentPluginInstallBody,
    ): Response<Unit>

    @DELETE("api/dashboard/agent-plugins/{name}")
    suspend fun uninstallPlugin(
        @Path("name", encoded = true) name: String,
    ): Response<Unit>

    @POST("api/dashboard/agent-plugins/{name}/update")
    suspend fun updatePlugin(
        @Path("name", encoded = true) name: String,
    ): Response<Unit>

    @POST("api/dashboard/agent-plugins/{name}/enable")
    suspend fun enablePlugin(
        @Path("name", encoded = true) name: String,
    ): Response<Unit>

    @POST("api/dashboard/agent-plugins/{name}/disable")
    suspend fun disablePlugin(
        @Path("name", encoded = true) name: String,
    ): Response<Unit>

    @GET("api/messaging/platforms")
    suspend fun getMessagingPlatforms(
        @Query("profile") profile: String? = null,
    ): Response<MessagingPlatformResponse>

    @PUT("api/messaging/platforms/{platform_id}")
    suspend fun configurePlatform(
        @Path("platform_id") platformId: String,
        @Body config: MessagingPlatformUpdate,
    ): Response<Unit>

    @GET("api/env")
    suspend fun getEnvVars(): Response<Map<String, EnvVarConfig>>

    @PUT("api/env")
    suspend fun updateEnvVar(
        @Body body: EnvVarUpdate,
    ): Response<Unit>

    @POST("api/env/reveal")
    suspend fun revealEnvVar(
        @Body request: EnvVarRevealRequest,
    ): Response<EnvVarRevealResponse>

    @POST("api/ops/backup")
    suspend fun triggerBackup(): Response<Unit>

    @POST("api/ops/doctor")
    suspend fun runDoctor(): Response<DoctorResponse>

    @GET("api/plugins/kanban/boards")
    suspend fun getKanbanBoards(): Response<KanbanBoardsResponse>

    @GET("api/plugins/kanban/board")
    suspend fun getKanbanBoard(): Response<KanbanBoardResponse>

    @POST("api/plugins/kanban/boards/{slug}/switch")
    suspend fun switchKanbanBoard(
        @Path("slug") slug: String,
    ): Response<Unit>

    @PATCH("api/plugins/kanban/tasks/{id}")
    suspend fun updateKanbanTask(
        @Path("id") taskId: String,
        @Body body: Map<String, String?>,
    ): Response<Unit>

    @POST("api/plugins/kanban/tasks")
    suspend fun createKanbanTask(
        @Query("board") board: String?,
        @Body task: CreateTaskBody,
    ): Response<KanbanTask>
}
