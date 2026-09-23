package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Lightweight prompt index returned by GET /api/sessions/{id}/timeline. */
@Serializable
data class SessionTimelineResponse(
    val session_id: String? = null,
    val profile: String? = null,
    val entries: List<SessionTimelineEntry> = emptyList(),
    val pagination: SessionTimelinePagination = SessionTimelinePagination(),
)

@Serializable
data class SessionTimelineEntry(
    /** Stable representative row id. Pass this exact value to /messages/around. */
    val row_id: Int,
    val preview: String = "",
    val timestamp: JsonElement? = null,
)

@Serializable
data class SessionTimelinePagination(
    val limit: Int? = null,
    val after_row_id: Int? = null,
    val returned: Int? = null,
    val total: Int? = null,
    val has_more: Boolean = false,
    /** Backend cursor. It is not interchangeable with the last returned row_id. */
    val next_cursor: Int? = null,
)

/** Bounded chronological display page returned by GET /api/sessions/{id}/messages/around. */
@Serializable
data class SessionMessagesAroundResponse(
    val session_id: String? = null,
    val profile: String? = null,
    val messages: List<SessionMessage> = emptyList(),
    val pagination: SessionMessagesAroundPagination = SessionMessagesAroundPagination(),
)

@Serializable
data class SessionMessagesAroundPagination(
    val row_id: Int? = null,
    val limit: Int? = null,
    val returned: Int? = null,
    val order: String? = null,
    val offset: Int = 0,
    val total: Int? = null,
    val has_older: Boolean = false,
    val has_newer: Boolean = false,
)
