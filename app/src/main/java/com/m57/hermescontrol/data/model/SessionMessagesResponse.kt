package com.m57.hermescontrol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class SessionMessagesResponse(
    @SerialName("session_id") val sessionId: String? = null,
    val messages: List<SessionMessage>,
)

@Serializable
data class SessionMessage(
    val id: Long? = null,
    val role: String? = null,
    val content: JsonElement? = null,
    val timestamp: JsonElement? = null,
    val type: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: JsonElement? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    val reasoning: JsonElement? = null,
    @SerialName("reasoning_content") val reasoningContent: JsonElement? = null,
    @SerialName("reasoning_details") val reasoningDetails: JsonElement? = null,
) {
    val timestampText: String?
        get() = (timestamp as? JsonPrimitive)?.content
}
