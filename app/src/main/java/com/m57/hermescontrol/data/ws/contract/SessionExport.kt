package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionExportParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("format") val format: String? = null,
    @SerialName("redact") val redact: Boolean? = null,
    @SerialName("profile") val profile: String? = null,
)

@Serializable
data class SessionExportResult(
    @SerialName("file") val file: String? = null,
    @SerialName("content") val content: String? = null,
    @SerialName("filename") val filename: String? = null,
)
