package com.m57.hermescontrol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * High-level live status for a session in History.
 */
enum class SessionLiveStatus {
    WORKING,
    WAITING,
}

/**
 * RPC response from `session.active_list`.
 */
@Serializable
data class ActiveSessionsResponse(
    val sessions: List<ActiveSessionItem> = emptyList(),
)

/**
 * Individual item in `session.active_list`.
 */
@Serializable
data class ActiveSessionItem(
    val id: String? = null,
    @SerialName("session_key")
    val sessionKey: String? = null,
    val status: String? = null,
    @SerialName("last_active")
    val lastActive: Double? = null,
)

/**
 * Parsed authoritative snapshot of live sessions.
 */
data class LiveSessionSnapshot(
    val statusByStoredId: Map<String, SessionLiveStatus> = emptyMap(),
    val storedIdByRuntimeId: Map<String, String> = emptyMap(),
)
