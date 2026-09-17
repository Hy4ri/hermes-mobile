package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionSearchResponse(
    val results: List<SessionSearchResult> = emptyList(),
    val has_more: Boolean? = null,
    val next_offset: Int? = null,
) {
    val hasMore: Boolean get() = has_more == true
}

@Serializable
data class SessionSearchResult(
    val session_id: String,
    val snippet: String? = null,
    val role: String? = null,
    val source: String? = null,
    val model: String? = null,
    val session_started: Double? = null,
    val title: String? = null,
    val last_active: Double? = null,
    val message_count: Int? = null,
    val archived: Boolean? = null,
    val lineage_root: String? = null,
)
