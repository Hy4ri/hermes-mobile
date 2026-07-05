package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class SessionMessagesResponse(
    val messages: List<SessionMessage>,
)

@Serializable
data class SessionMessage(
    val role: String? = null,
    val content: String? = null,
    val timestamp: JsonElement? = null,
    val type: String? = null,
) {
    val timestampText: String?
        get() = (timestamp as? JsonPrimitive)?.content
}
