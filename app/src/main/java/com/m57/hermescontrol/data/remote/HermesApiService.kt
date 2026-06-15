package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.AchievementsResponse
import com.m57.hermescontrol.data.model.ActiveProfileResponse
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.model.McpServerToggleRequest
import com.m57.hermescontrol.data.model.McpServersResponse
import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.model.PairingApproveRequest
import com.m57.hermescontrol.data.model.PairingResponse
import com.m57.hermescontrol.data.model.PairingRevokeRequest
import com.m57.hermescontrol.data.model.ProfileSoulResponse
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.model.RawConfigResponse
import com.m57.hermescontrol.data.model.SessionListResponse
import com.m57.hermescontrol.data.model.SessionMessagesResponse
import com.m57.hermescontrol.data.model.SetActiveProfileRequest
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.model.SystemStatsResponse
import com.m57.hermescontrol.data.model.ToggleSkillRequest
import com.m57.hermescontrol.data.model.Toolset
import com.m57.hermescontrol.data.model.ToolsetToggleRequest
import com.m57.hermescontrol.data.model.UpdateProfileDescriptionRequest
import com.m57.hermescontrol.data.model.UpdateProfileModelRequest
import com.m57.hermescontrol.data.model.UpdateProfileSoulRequest
import com.m57.hermescontrol.data.model.UpdateRawConfigRequest
import com.m57.hermescontrol.data.model.WebhooksResponse
import com.m57.hermescontrol.data.model.WebhooksToggleRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface HermesApiService {
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

    @GET("api/config")
    suspend fun getConfig(): Response<Map<String, Any?>>

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

    @PUT("api/profiles/{name}/description")
    suspend fun updateProfileDescription(
        @Path("name") name: String,
        @Body body: UpdateProfileDescriptionRequest,
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
}
