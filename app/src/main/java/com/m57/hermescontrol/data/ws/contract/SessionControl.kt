package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionInterruptParams(
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class SessionCorrectionParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("text") val text: String,
)

@Serializable
data class SessionInterruptResult(
    val status: String? = null,
)

@Serializable
data class SessionCorrectionResult(
    val status: String? = null,
    val text: String? = null,
)
