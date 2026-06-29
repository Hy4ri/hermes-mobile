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
