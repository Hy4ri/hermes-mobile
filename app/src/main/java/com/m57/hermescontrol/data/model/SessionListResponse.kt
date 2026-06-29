package com.m57.hermescontrol.data.model

data class SessionListResponse(
    val sessions: List<SessionInfo>,
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

data class SessionInfo(
    val id: String,
    val title: String?,
    val created_at: String?,
    val message_count: Int?,
    val status: String?,
    val preview: String? = null,
    val started_at: Double? = null,
)
