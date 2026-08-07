package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

/** GET /api/sessions/empty/count — count of empty, ended, non-archived sessions. */
@Serializable
data class EmptySessionsCountResponse(
    val count: Int = 0,
)

/** DELETE /api/sessions/empty — bulk cleanup result. */
@Serializable
data class EmptySessionsDeleteResponse(
    val ok: Boolean = false,
    val deleted: Int = 0,
)

/**
 * GET /api/sessions/{id}/latest-descendant — a branched session's "current"
 * row is its newest child leaf; resume THAT instead of the stale parent
 * (desktop parity, issue #787).
 */
@Serializable
data class LatestDescendantResponse(
    val requested_session_id: String? = null,
    val session_id: String? = null,
    val path: List<String> = emptyList(),
    val changed: Boolean = false,
)
