package com.m57.hermescontrol.data.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MessagingPlatform(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("docs_url") val docsUrl: String? = null,
    val enabled: Boolean,
    val configured: Boolean,
    @SerialName("gateway_running") val gatewayRunning: Boolean = false,
    val state: String = "disabled",
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("env_vars") val envVars: List<EnvVarField>? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("home_channel") val homeChannel: HomeChannelInfo? = null,
)

@Serializable
data class HomeChannelInfo(
    val platform: String,
    @SerialName("chat_id") val chatId: String,
    val name: String,
    @SerialName("thread_id") val threadId: String? = null,
)

@Serializable
data class EnvVarField(
    val key: String,
    val required: Boolean,
    @SerialName("is_set") val isSet: Boolean,
    @SerialName("redacted_value") val redactedValue: String? = null,
    val description: String? = null,
    val prompt: String? = null,
    val help: String? = null,
    @SerialName("is_password") val isPassword: Boolean,
    val advanced: Boolean = false,
    val url: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class MessagingPlatformResponse(
    @SerialName("env_path") val envPath: String,
    @SerialName("gateway_start_command") val gatewayStartCommand: String,
    val platforms: List<MessagingPlatform>,
)

@Serializable
data class MessagingPlatformUpdate(
    val enabled: Boolean? = null,
    val env: Map<String, String> = emptyMap(),
    @SerialName("clear_env") val clearEnv: List<String> = emptyList(),
    val profile: String? = null,
)

@Serializable
data class MessagingPlatformTestResult(
    val ok: Boolean,
    val state: String,
    val message: String,
)

// ── Telegram onboarding ─────────────────────────────────────────────────

@Serializable
data class TelegramOnboardingStartRequest(
    @SerialName("bot_name") val botName: String = "Hermes Agent",
)

@Serializable
data class TelegramOnboardingStartResponse(
    @SerialName("pairing_id") val pairingId: String,
    @SerialName("suggested_username") val suggestedUsername: String,
    @SerialName("deep_link") val deepLink: String,
    @SerialName("qr_payload") val qrPayload: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class TelegramOnboardingStatusResponse(
    val status: String,
    @SerialName("bot_username") val botUsername: String? = null,
    @SerialName("owner_user_id") val ownerUserId: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class TelegramOnboardingApplyRequest(
    @SerialName("allowed_user_ids") val allowedUserIds: List<String>,
    val profile: String? = null,
)

@Serializable
data class TelegramOnboardingApplyResponse(
    val ok: Boolean,
    val platform: String,
    @SerialName("bot_username") val botUsername: String? = null,
    @SerialName("needs_restart") val needsRestart: Boolean,
    @SerialName("restart_started") val restartStarted: Boolean? = null,
    @SerialName("restart_action") val restartAction: String? = null,
    @SerialName("restart_pid") val restartPid: Int? = null,
    @SerialName("restart_error") val restartError: String? = null,
)
