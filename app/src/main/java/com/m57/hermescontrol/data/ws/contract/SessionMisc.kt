package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data object SessionListParams

@Serializable
data class SessionIdParams(
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class SessionBranchParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("name") val name: String? = null,
)

@Serializable
data class SessionBranchWholeParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("name") val name: String? = null,
)

@Serializable
data class SessionCompressParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("focus_topic") val focusTopic: String? = null,
)

@Serializable
data class PromptBtwParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("text") val text: String,
)

@Serializable
data class PromptBtwResult(
    @SerialName("task_id") val taskId: String? = null,
)
