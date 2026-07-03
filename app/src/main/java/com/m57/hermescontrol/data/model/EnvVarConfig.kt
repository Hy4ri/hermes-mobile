package com.m57.hermescontrol.data.model

import com.google.gson.annotations.SerializedName

data class EnvVarConfig(
    @SerializedName("is_set") val isSet: Boolean,
    @SerializedName("redacted_value") val redactedValue: String?,
    val description: String?,
    val url: String?,
    val category: String?,
    @SerializedName("is_password") val isPassword: Boolean,
)

data class EnvVarRevealRequest(
    val key: String,
    val profile: String? = null,
)

data class EnvVarRevealResponse(
    val key: String,
    val value: String,
)

data class EnvVarUpdate(
    val key: String,
    val value: String,
    val profile: String? = null,
)

data class EnvVarDeleteRequest(
    val key: String,
    val profile: String? = null,
)
