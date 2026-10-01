package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApprovalPendingParams(
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class ApprovalReceivedParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("request_id") val requestId: String,
)

@Serializable
data class ApprovalRespondParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("choice") val choice: String? = null,
    @SerialName("all") val all: Boolean? = null,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
data class SessionActiveListParams(
    @SerialName("current_session_id") val currentSessionId: String? = null,
)
