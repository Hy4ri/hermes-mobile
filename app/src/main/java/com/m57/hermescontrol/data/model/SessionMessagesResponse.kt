package com.m57.hermescontrol.data.model

data class SessionMessagesResponse(
    val messages: List<SessionMessage>,
)

data class SessionMessage(
    val role: String?,
    val content: String?,
    val timestamp: String?,
    val type: String?,
)
