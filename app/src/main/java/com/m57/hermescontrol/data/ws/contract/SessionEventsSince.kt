package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SessionEventsSinceParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("last_seen") val lastSeen: Int? = null,
) // NO profile field: profile is injected by WsProfileParams only.

// Tolerant reader: every field nullable/defaulted, mirrors what the old untyped parser tolerated.
@Serializable
data class SessionEventsSinceResult(
    val events: List<JsonElement>? = null,
    @SerialName("latest_seq") val latestSeq: Int? = null,
    val truncated: Boolean? = null,
    val epoch: String? = null,
)
