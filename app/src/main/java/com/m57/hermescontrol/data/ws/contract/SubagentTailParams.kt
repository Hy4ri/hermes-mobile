package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubagentTailParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("subagent_id") val subagentId: String,
)
