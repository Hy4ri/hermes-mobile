package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.AchievementsResponse
import com.m57.hermescontrol.data.model.ActionResponse
import com.m57.hermescontrol.data.model.ActionStatusResponse
import com.m57.hermescontrol.data.model.ActiveProfileResponse
import com.m57.hermescontrol.data.model.AddMcpServerRequest
import com.m57.hermescontrol.data.model.AgentPluginInstallBody
import com.m57.hermescontrol.data.model.AnalyticsResponse
import com.m57.hermescontrol.data.model.AuxiliaryModelsResponse
import com.m57.hermescontrol.data.model.BulkDeleteRequest
import com.m57.hermescontrol.data.model.BulkDeleteResponse
import com.m57.hermescontrol.data.model.CheckpointsResponse
import com.m57.hermescontrol.data.model.CloneProfileRequest
import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.data.model.ConfigUpdateRequest
import com.m57.hermescontrol.data.model.CreateCronJobRequest
import com.m57.hermescontrol.data.model.CreateProfileRequest
import com.m57.hermescontrol.data.model.CreateTaskBody
import com.m57.hermescontrol.data.model.CreateWebhookRequest
import com.m57.hermescontrol.data.model.CredentialPoolResponse
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.model.CuratorResponse
import com.m57.hermescontrol.data.model.DebugShareResponse
import com.m57.hermescontrol.data.model.DeleteWebhookResponse
import com.m57.hermescontrol.data.model.DoctorResponse
import com.m57.hermescontrol.data.model.EnvVarConfig
import com.m57.hermescontrol.data.model.EnvVarDeleteRequest
import com.m57.hermescontrol.data.model.EnvVarRevealRequest
import com.m57.hermescontrol.data.model.EnvVarRevealResponse
import com.m57.hermescontrol.data.model.EnvVarUpdate
import com.m57.hermescontrol.data.model.HookResponse
import com.m57.hermescontrol.data.model.KanbanBoardResponse
import com.m57.hermescontrol.data.model.KanbanBoardsResponse
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.model.LogResponse
import com.m57.hermescontrol.data.model.McpCatalogInstallRequest
import com.m57.hermescontrol.data.model.McpCatalogResponse
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.data.model.McpServerToggleRequest
import com.m57.hermescontrol.data.model.McpServersResponse
import com.m57.hermescontrol.data.model.MemoryResponse
import com.m57.hermescontrol.data.model.MessagingPlatformResponse
import com.m57.hermescontrol.data.model.MessagingPlatformTestResult
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.data.model.MoaConfigResponse
import com.m57.hermescontrol.data.model.ModelAssignmentRequest
import com.m57.hermescontrol.data.model.ModelAssignmentResponse
import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.model.ModelsAnalyticsResponse
import com.m57.hermescontrol.data.model.OAuthCancelResponse
import com.m57.hermescontrol.data.model.OAuthPollResponse
import com.m57.hermescontrol.data.model.OAuthProvidersResponse
import com.m57.hermescontrol.data.model.OAuthStartResponse
import com.m57.hermescontrol.data.model.OAuthSubmitRequest
import com.m57.hermescontrol.data.model.OAuthSubmitResponse
import com.m57.hermescontrol.data.model.PluginProvidersPutRequest
import com.m57.hermescontrol.data.model.PluginsHubResponse
import com.m57.hermescontrol.data.model.PortalResponse
import com.m57.hermescontrol.data.model.ProfileSoulResponse
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.model.PruneRequest
import com.m57.hermescontrol.data.model.RawConfigResponse
import com.m57.hermescontrol.data.model.RecentUnlock
import com.m57.hermescontrol.data.model.SaveSkillContentRequest
import com.m57.hermescontrol.data.model.ScanStatus
import com.m57.hermescontrol.data.model.SessionListResponse
import com.m57.hermescontrol.data.model.SessionMessagesResponse
import com.m57.hermescontrol.data.model.SessionPromptResponse
import com.m57.hermescontrol.data.model.SessionRenameRequest
import com.m57.hermescontrol.data.model.SessionSearchResponse
import com.m57.hermescontrol.data.model.SessionStatsResponse
import com.m57.hermescontrol.data.model.SetActiveProfileRequest
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.SkillContentResponse
import com.m57.hermescontrol.data.model.SkillHubInstallRequest
import com.m57.hermescontrol.data.model.SkillHubSearchResponse
import com.m57.hermescontrol.data.model.SkillHubUninstallRequest
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.model.SystemStatsResponse
import com.m57.hermescontrol.data.model.TelegramOnboardingApplyRequest
import com.m57.hermescontrol.data.model.TelegramOnboardingApplyResponse
import com.m57.hermescontrol.data.model.TelegramOnboardingStartRequest
import com.m57.hermescontrol.data.model.TelegramOnboardingStartResponse
import com.m57.hermescontrol.data.model.TelegramOnboardingStatusResponse
import com.m57.hermescontrol.data.model.ToggleSkillRequest
import com.m57.hermescontrol.data.model.Toolset
import com.m57.hermescontrol.data.model.ToolsetToggleRequest
import com.m57.hermescontrol.data.model.UpdateCheckResponse
import com.m57.hermescontrol.data.model.UpdateCronJobRequest
import com.m57.hermescontrol.data.model.UpdateProfileDescriptionRequest
import com.m57.hermescontrol.data.model.UpdateProfileModelRequest
import com.m57.hermescontrol.data.model.UpdateProfileSoulRequest
import com.m57.hermescontrol.data.model.UpdateRawConfigRequest
import com.m57.hermescontrol.data.model.WebhookSubscription
import com.m57.hermescontrol.data.model.WebhookToggleSubscriptionRequest
import com.m57.hermescontrol.data.model.WebhooksResponse
import com.m57.hermescontrol.data.model.WebhooksToggleRequest
import kotlinx.serialization.json.JsonElement
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
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
    suspend fun getSessions(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("order") order: String = "recent",
    ): Response<SessionListResponse>

    @GET("api/sessions/search")
    suspend fun searchSessions(
        @Query("q") q: String,
        @Query("profile") profile: String? = null,
    ): Response<SessionSearchResponse>

    @GET("api/sessions/{id}/messages")
    suspend fun getSessionMessages(
        // Preserve slashes in session IDs — backend generates IDs containing '/' characters (issue #468).
        // Contract: The server-generated sessionId must only contain URL-safe characters (no ?, #, or spaces).
        @Path("id", encoded = true) sessionId: String,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int = 0,
    ): Response<SessionMessagesResponse>

    @GET("api/sessions/stats")
    suspend fun getSessionStats(): Response<SessionStatsResponse>

    @PUT("api/sessions/{id}/rename")
    suspend fun renameSession(
        // Preserve slashes in session IDs — backend generates IDs containing '/' characters (issue #468).
        // Contract: The server-generated sessionId must only contain URL-safe characters (no ?, #, or spaces).
        @Path("id", encoded = true) sessionId: String,
        @Body body: SessionRenameRequest,
    ): Response<Unit>

    @POST("api/sessions/bulk-delete")
    suspend fun bulkDeleteSessions(
        @Body body: BulkDeleteRequest,
    ): Response<BulkDeleteResponse>

    @DELETE("api/sessions/{id}")
    suspend fun deleteSession(
        // Preserve slashes in session IDs — backend generates IDs containing '/' characters (issue #468).
        // Contract: The server-generated sessionId must only contain URL-safe characters (no ?, #, or spaces).
        @Path("id", encoded = true) sessionId: String,
    ): Response<Unit>

    @POST("api/sessions/prune")
    suspend fun pruneSessions(
        @Body body: PruneRequest,
    ): Response<Unit>

    @GET("api/sessions/{id}/prompt")
    suspend fun getSessionPrompt(
        // Preserve slashes in session IDs — backend generates IDs containing '/' characters (issue #468).
        // Contract: The server-generated sessionId must only contain URL-safe characters (no ?, #, or spaces).
        @Path("id", encoded = true) sessionId: String,
    ): Response<SessionPromptResponse>

    @GET("api/system/stats")
    suspend fun getSystemStats(): Response<SystemStatsResponse>

    @GET("api/analytics/usage")
    suspend fun getAnalytics(
        @Query("days") days: Int,
        @Query("profile") profile: String? = null,
    ): Response<AnalyticsResponse>

    @GET("api/analytics/models")
    suspend fun getModelsAnalytics(
        @Query("days") days: Int,
        @Query("profile") profile: String? = null,
    ): Response<ModelsAnalyticsResponse>

    @GET("api/skills")
    suspend fun getSkills(): Response<List<Skill>>

    @GET("api/skills/hub/search")
    suspend fun searchSkillsHub(
        @Query("q") q: String,
        @Query("source") source: String? = null,
        @Query("limit") limit: Int? = null,
    ): Response<SkillHubSearchResponse>

    @GET("api/skills/hub/preview")
    suspend fun previewHubSkill(
        @Query("identifier") identifier: String,
    ): Response<SkillContentResponse>

    @POST("api/skills/hub/install")
    suspend fun installHubSkill(
        @Body body: SkillHubInstallRequest,
    ): Response<ActionResponse>

    @POST("api/skills/hub/uninstall")
    suspend fun uninstallHubSkill(
        @Body body: SkillHubUninstallRequest,
    ): Response<ActionResponse>

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

    @GET("api/cron/jobs/{id}")
    suspend fun getCronJob(
        @Path("id") id: String,
    ): Response<CronJob>

    @POST("api/cron/jobs")
    suspend fun createCronJob(
        @Body body: CreateCronJobRequest,
    ): Response<CronJob>

    @PUT("api/cron/jobs/{id}")
    suspend fun updateCronJob(
        @Path("id") id: String,
        @Body body: UpdateCronJobRequest,
    ): Response<CronJob>

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

    @POST("api/profiles")
    suspend fun createProfile(
        @Body body: CreateProfileRequest,
    ): Response<Unit>

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

    @POST("api/profiles/{name}/clone")
    suspend fun cloneProfile(
        @Path("name") name: String,
        @Body body: CloneProfileRequest,
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

    @GET("api/plugins/hermes-achievements/scan-status")
    suspend fun getAchievementScanStatus(): Response<ScanStatus>

    @POST("api/plugins/hermes-achievements/rescan")
    suspend fun rescanAchievements(): Response<AchievementsResponse>

    @POST("api/plugins/hermes-achievements/reset-state")
    suspend fun resetAchievementState(): Response<Unit>

    @GET("api/plugins/hermes-achievements/recent-unlocks")
    suspend fun getRecentUnlocks(): Response<List<RecentUnlock>>

    @GET("api/config/raw")
    suspend fun getRawConfig(): Response<RawConfigResponse>

    @PUT("api/config/raw")
    suspend fun updateRawConfig(
        @Body body: UpdateRawConfigRequest,
    ): Response<Unit>

    @GET("api/config")
    suspend fun getConfig(): Response<Map<String, JsonElement>>

    @GET("api/config/schema")
    suspend fun getConfigSchema(): Response<ConfigSchemaResponse>

    @GET("api/config/defaults")
    suspend fun getConfigDefaults(): Response<Map<String, JsonElement>>

    @PUT("api/config")
    suspend fun updateConfig(
        @Body body: ConfigUpdateRequest,
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

    @POST("api/mcp/servers")
    suspend fun addMcpServer(
        @Body body: AddMcpServerRequest,
    ): Response<McpServer>

    @PUT("api/mcp/servers/{name}")
    suspend fun updateMcpServer(
        @Path("name") name: String,
        @Body body: Map<String, Any>,
    ): Response<McpServer>

    @POST("api/mcp/servers/{name}/restart")
    suspend fun restartMcpServer(
        @Path("name") name: String,
    ): Response<Unit>

    @GET("api/mcp/catalog")
    suspend fun getMcpCatalog(): Response<McpCatalogResponse>

    @POST("api/mcp/catalog/install")
    suspend fun installMcpCatalogEntry(
        @Body body: McpCatalogInstallRequest,
    ): Response<Map<String, Any>>

    @GET("api/webhooks")
    suspend fun getWebhooks(): Response<WebhooksResponse>

    @POST("api/webhooks/enable")
    suspend fun toggleWebhooks(
        @Body body: WebhooksToggleRequest,
    ): Response<Unit>

    @POST("api/webhooks")
    suspend fun createWebhook(
        @Body body: CreateWebhookRequest,
    ): Response<WebhookSubscription>

    @DELETE("api/webhooks/{name}")
    suspend fun deleteWebhook(
        @Path("name") name: String,
    ): Response<DeleteWebhookResponse>

    @PUT("api/webhooks/{name}/enabled")
    suspend fun setWebhookEnabled(
        @Path("name") name: String,
        @Body body: WebhookToggleSubscriptionRequest,
    ): Response<Unit>

    @GET("api/model/options")
    suspend fun getModelOptions(
        @Query("refresh") refresh: Boolean = false,
        @Query("include_unconfigured") includeUnconfigured: Boolean = false,
    ): Response<ModelOptionsResponse>

    @GET("api/model/auxiliary")
    suspend fun getAuxiliaryModels(): Response<AuxiliaryModelsResponse>

    @GET("api/model/moa")
    suspend fun getMoaModels(): Response<MoaConfigResponse>

    @PUT("api/model/moa")
    suspend fun saveMoaModels(
        @Body body: MoaConfigResponse,
    ): Response<MoaConfigResponse>

    @POST("api/model/set")
    suspend fun setModelAssignment(
        @Body body: ModelAssignmentRequest,
    ): Response<ModelAssignmentResponse>

    @GET("api/logs")
    suspend fun getLogs(
        @Query("limit") limit: Int? = null,
        @Query("lines") lines: Int? = null,
    ): Response<LogResponse>

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

    @POST("api/dashboard/plugins/rescan")
    suspend fun rescanPlugins(): Response<Unit>

    @PUT("api/dashboard/plugin-providers")
    suspend fun savePluginProviders(
        @Body body: PluginProvidersPutRequest,
    ): Response<Unit>

    @POST("api/dashboard/plugins/{name}/visibility")
    suspend fun setPluginVisibility(
        @Path("name", encoded = true) name: String,
        @Body body: Map<String, Boolean>,
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

    @POST("api/messaging/platforms/{platform_id}/test")
    suspend fun testMessagingPlatform(
        @Path("platform_id") platformId: String,
    ): Response<MessagingPlatformTestResult>

    @DELETE("api/messaging/platforms/{platform_id}")
    suspend fun removeMessagingPlatform(
        @Path("platform_id") platformId: String,
    ): Response<Unit>

    @POST("api/messaging/telegram/onboarding/start")
    suspend fun startTelegramOnboarding(
        @Body body: TelegramOnboardingStartRequest,
    ): Response<TelegramOnboardingStartResponse>

    @GET("api/messaging/telegram/onboarding/{pairing_id}")
    suspend fun getTelegramOnboardingStatus(
        @Path("pairing_id") pairingId: String,
    ): Response<TelegramOnboardingStatusResponse>

    @POST("api/messaging/telegram/onboarding/{pairing_id}/apply")
    suspend fun applyTelegramOnboarding(
        @Path("pairing_id") pairingId: String,
        @Body body: TelegramOnboardingApplyRequest,
    ): Response<TelegramOnboardingApplyResponse>

    @HTTP(method = "DELETE", path = "api/messaging/telegram/onboarding/{pairing_id}", hasBody = false)
    suspend fun cancelTelegramOnboarding(
        @Path("pairing_id") pairingId: String,
    ): Response<Unit>

    // ── OAuth provider management (issue #534) ──────────────────────────
    // Auth: the dashboard gates /start, /submit, /disconnect, /cancel via
    // `_require_token`, which accepts either `X-Hermes-Session-Token` OR the
    // legacy `Authorization: Bearer <token>`. ApiClient already injects the
    // Bearer token on every request, so no extra header code is needed here.
    // (poll is tokenless — read-only session state.)

    @GET("api/providers/oauth")
    suspend fun getOAuthProviders(): Response<OAuthProvidersResponse>

    @DELETE("api/providers/oauth/{provider_id}")
    suspend fun disconnectOAuthProvider(
        @Path("provider_id") providerId: String,
    ): Response<Unit>

    @POST("api/providers/oauth/{provider_id}/start")
    suspend fun startOAuthLogin(
        @Path("provider_id") providerId: String,
    ): Response<OAuthStartResponse>

    @POST("api/providers/oauth/{provider_id}/submit")
    suspend fun submitOAuthCode(
        @Path("provider_id") providerId: String,
        @Body body: OAuthSubmitRequest,
    ): Response<OAuthSubmitResponse>

    @GET("api/providers/oauth/{provider_id}/poll/{session_id}")
    suspend fun pollOAuthSession(
        @Path("provider_id") providerId: String,
        @Path("session_id") sessionId: String,
    ): Response<OAuthPollResponse>

    @HTTP(method = "DELETE", path = "api/providers/oauth/sessions/{session_id}", hasBody = false)
    suspend fun cancelOAuthSession(
        @Path("session_id") sessionId: String,
    ): Response<OAuthCancelResponse>

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

    @HTTP(method = "DELETE", path = "api/env", hasBody = true)
    suspend fun deleteEnvVar(
        @Body request: EnvVarDeleteRequest,
    ): Response<Unit>

    @POST("api/ops/backup")
    suspend fun triggerBackup(): Response<ActionResponse>

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

    // ── Admin: Hermes update ──────────────────────────────────────────
    @GET("api/hermes/update/check")
    suspend fun checkHermesUpdate(
        @Query("force") force: Boolean = false,
    ): Response<UpdateCheckResponse>

    @POST("api/hermes/update")
    suspend fun updateHermes(): Response<ActionResponse>

    // ── Admin: Portal ─────────────────────────────────────────────────
    @GET("api/portal")
    suspend fun getPortal(): Response<PortalResponse>

    // ── Admin: Curator ────────────────────────────────────────────────
    @GET("api/curator")
    suspend fun getCurator(): Response<CuratorResponse>

    @PUT("api/curator/paused")
    suspend fun setCuratorPaused(
        @Body body: Map<String, Boolean>,
    ): Response<Map<String, Any>>

    @POST("api/curator/run")
    suspend fun runCurator(): Response<ActionResponse>

    // ── Admin: Memory ─────────────────────────────────────────────────
    @GET("api/memory")
    suspend fun getMemory(): Response<MemoryResponse>

    @POST("api/memory/reset")
    suspend fun resetMemory(
        @Body body: Map<String, String>,
    ): Response<Map<String, Any>>

    // ── Admin: Credential pool ────────────────────────────────────────
    @GET("api/credentials/pool")
    suspend fun getCredentialPool(): Response<CredentialPoolResponse>

    @POST("api/credentials/pool")
    suspend fun addCredentialPoolEntry(
        @Body body: Map<String, String>,
    ): Response<Map<String, Any>>

    @DELETE("api/credentials/pool/{provider}/{index}")
    suspend fun removeCredentialPoolEntry(
        @Path("provider") provider: String,
        @Path("index") index: Int,
    ): Response<Map<String, Any>>

    // ── Admin: Operations ─────────────────────────────────────────────
    @POST("api/ops/security-audit")
    suspend fun runSecurityAudit(): Response<ActionResponse>

    @POST("api/ops/prompt-size")
    suspend fun runPromptSize(): Response<ActionResponse>

    @POST("api/ops/dump")
    suspend fun runDump(): Response<ActionResponse>

    @POST("api/ops/config-migrate")
    suspend fun runConfigMigrate(): Response<ActionResponse>

    @POST("api/skills/hub/update")
    suspend fun updateSkillsFromHub(
        @Body body: Map<String, String> = emptyMap(),
    ): Response<ActionResponse>

    // ── Admin: Backup download ────────────────────────────────────────
    @GET("api/ops/backup/download")
    suspend fun downloadBackup(
        @Query("archive") archive: String,
    ): Response<ResponseBody>

    // ── Admin: Backup import ──────────────────────────────────────────
    @POST("api/ops/import")
    suspend fun runImport(
        @Body body: Map<String, Any>,
    ): Response<ActionResponse>

    // ── Admin: Debug share ────────────────────────────────────────────
    @POST("api/ops/debug-share")
    suspend fun runDebugShare(
        @Body body: Map<String, Any>,
    ): Response<DebugShareResponse>

    // ── Admin: Checkpoints ────────────────────────────────────────────
    @GET("api/ops/checkpoints")
    suspend fun getCheckpoints(): Response<CheckpointsResponse>

    @POST("api/ops/checkpoints/prune")
    suspend fun pruneCheckpoints(): Response<ActionResponse>

    // ── Admin: Shell hooks ────────────────────────────────────────────
    @GET("api/ops/hooks")
    suspend fun getHooks(): Response<HookResponse>

    @POST("api/ops/hooks")
    suspend fun createHook(
        @Body body: Map<String, Any>,
    ): Response<Map<String, Any>>

    @DELETE("api/ops/hooks")
    suspend fun deleteHook(
        @Body body: Map<String, String>,
    ): Response<Map<String, Any>>

    // ── Admin: Action status (log viewer) ────────────────────────────
    @GET("api/actions/{name}/status")
    suspend fun getActionStatus(
        @Path("name") name: String,
        @Query("lines") lines: Int = 200,
    ): Response<ActionStatusResponse>
}
