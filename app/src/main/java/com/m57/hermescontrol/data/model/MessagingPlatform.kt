package com.m57.hermescontrol.data.model

import com.google.gson.annotations.SerializedName

data class MessagingPlatform(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerializedName("docs_url") val docsUrl: String? = null,
    val enabled: Boolean,
    val configured: Boolean,
    @SerializedName("gateway_running") val gatewayRunning: Boolean = false,
    val state: String = "disabled",
    @SerializedName("error_code") val errorCode: String? = null,
    @SerializedName("error_message") val errorMessage: String? = null,
    @SerializedName("env_vars") val envVars: List<EnvVarField>? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("home_channel") val homeChannel: HomeChannelInfo? = null,
)

data class HomeChannelInfo(
    val platform: String,
    @SerializedName("chat_id") val chatId: String,
    val name: String,
    @SerializedName("thread_id") val threadId: String? = null,
)

data class EnvVarField(
    val key: String,
    val required: Boolean,
    @SerializedName("is_set") val isSet: Boolean,
    @SerializedName("redacted_value") val redactedValue: String? = null,
    val description: String? = null,
    val prompt: String? = null,
    val help: String? = null,
    @SerializedName("is_password") val isPassword: Boolean,
    val advanced: Boolean = false,
    val url: String? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
)

data class MessagingPlatformResponse(
    @SerializedName("env_path") val envPath: String,
    @SerializedName("gateway_start_command") val gatewayStartCommand: String,
    val platforms: List<MessagingPlatform>,
)

data class MessagingPlatformUpdate(
    val enabled: Boolean? = null,
    val env: Map<String, String> = emptyMap(),
    @SerializedName("clear_env") val clearEnv: List<String> = emptyList(),
    val profile: String? = null,
)

data class MessagingPlatformTestResult(
    val ok: Boolean,
    val state: String,
    val message: String,
)

// ── Telegram onboarding ─────────────────────────────────────────────────

data class TelegramOnboardingStartRequest(
    @SerializedName("bot_name") val botName: String = "Hermes Agent",
)

data class TelegramOnboardingStartResponse(
    @SerializedName("pairing_id") val pairingId: String,
    @SerializedName("suggested_username") val suggestedUsername: String,
    @SerializedName("deep_link") val deepLink: String,
    @SerializedName("qr_payload") val qrPayload: String,
    @SerializedName("expires_at") val expiresAt: String,
)

data class TelegramOnboardingStatusResponse(
    val status: String,
    @SerializedName("bot_username") val botUsername: String? = null,
    @SerializedName("owner_user_id") val ownerUserId: String? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
)

data class TelegramOnboardingApplyRequest(
    @SerializedName("allowed_user_ids") val allowedUserIds: List<String>,
    val profile: String? = null,
)

data class TelegramOnboardingApplyResponse(
    val ok: Boolean,
    val platform: String,
    @SerializedName("bot_username") val botUsername: String? = null,
    @SerializedName("needs_restart") val needsRestart: Boolean,
    @SerializedName("restart_started") val restartStarted: Boolean? = null,
    @SerializedName("restart_action") val restartAction: String? = null,
    @SerializedName("restart_pid") val restartPid: Int? = null,
    @SerializedName("restart_error") val restartError: String? = null,
)
