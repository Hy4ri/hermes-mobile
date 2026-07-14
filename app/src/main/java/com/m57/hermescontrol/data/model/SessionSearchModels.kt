package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionSearchResponse(
    val results: List<SessionSearchResult> = emptyList(),
)

@Serializable
data class SessionSearchResult(
    val session_id: String,
    val snippet: String? = null,
    val role: String? = null,
    val source: String? = null,
    val model: String? = null,
    val session_started: Double? = null,
)
