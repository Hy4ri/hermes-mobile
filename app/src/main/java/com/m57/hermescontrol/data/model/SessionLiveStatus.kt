package com.m57.hermescontrol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * High-level status for a session in History (issues #1101, #1642).
 *
 * [WORKING] and the `WAITING*` values are live gateway state. [DONE] and [FAILED] are local
 * "finished, not opened yet" marks from [com.m57.hermescontrol.data.session.SessionAttentionStore];
 * a live status always wins over them.
 */
enum class SessionLiveStatus {
    WORKING,

    /** Blocked on the user, reason unknown (snapshot `waiting`, sudo, secret or vault prompts). */
    WAITING,

    /** Blocked on a command approval. */
    WAITING_APPROVAL,

    /** Blocked on a clarify question. */
    WAITING_CLARIFY,

    /** The turn completed after the user last viewed the session. */
    DONE,

    /** The turn ended with an error after the user last viewed the session. */
    FAILED,
    ;

    val isWaiting: Boolean get() = this == WAITING || this == WAITING_APPROVAL || this == WAITING_CLARIFY
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
    val title: String? = null,
)

/**
 * Parsed authoritative snapshot of live sessions.
 */
data class LiveSessionSnapshot(
    val statusByStoredId: Map<String, SessionLiveStatus> = emptyMap(),
    val storedIdByRuntimeId: Map<String, String> = emptyMap(),
)
