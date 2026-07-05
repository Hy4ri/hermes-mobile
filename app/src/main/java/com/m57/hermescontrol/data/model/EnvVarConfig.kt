package com.m57.hermescontrol.data.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EnvVarConfig(
    @SerialName("is_set") val isSet: Boolean,
    @SerialName("redacted_value") val redactedValue: String?,
    val description: String?,
    val url: String?,
    val category: String?,
    @SerialName("is_password") val isPassword: Boolean,
)

@Serializable
data class EnvVarRevealRequest(
    val key: String,
    val profile: String? = null,
)

@Serializable
data class EnvVarRevealResponse(
    val key: String,
    val value: String,
)

@Serializable
data class EnvVarUpdate(
    val key: String,
    val value: String,
    val profile: String? = null,
)

@Serializable
data class EnvVarDeleteRequest(
    val key: String,
    val profile: String? = null,
)
