package com.m57.hermescontrol.data.model

data class SessionListResponse(
    val sessions: List<SessionInfo>,
)

data class SessionInfo(
    val id: String,
    val title: String?,
    val created_at: String?,
    val message_count: Int?,
    val status: String?,
)
