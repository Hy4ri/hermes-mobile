package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubagentSteerParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("subagent_id") val subagentId: String,
    @SerialName("text") val text: String,
)

@Serializable
data class SubagentInterruptParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("subagent_id") val subagentId: String,
)

@Serializable
data class SubagentSteerResult(
    val status: String? = null,
    @SerialName("subagent_id") val subagentId: String? = null,
    val text: String? = null,
)

@Serializable
data class SubagentInterruptResult(
    val found: Boolean? = null,
    @SerialName("subagent_id") val subagentId: String? = null,
)
