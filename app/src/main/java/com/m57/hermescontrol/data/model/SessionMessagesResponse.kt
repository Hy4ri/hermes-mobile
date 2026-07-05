package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class SessionMessagesResponse(
    val messages: List<SessionMessage>,
)

@Serializable
data class SessionMessage(
    val role: String?,
    val content: String?,
    val timestamp: String?,
    val type: String?,
)
