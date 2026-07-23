package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class SessionMessagesResponse(
    val messages: List<SessionMessage>,
    val offset: Int? = null,
    val total: Int? = null,
)

@Serializable
data class SessionMessage(
    val role: String? = null,
    val content: JsonElement? = null,
    val timestamp: JsonElement? = null,
    val type: String? = null,
    val reasoning: JsonElement? = null,
    val reasoning_text: JsonElement? = null,
) {
    val timestampText: String?
        get() = (timestamp as? JsonPrimitive)?.content

    val contentText: String
        get() =
            when (content) {
                is JsonPrimitive -> content.content
                null -> ""
                else -> content.toString()
            }

    val reasoningText: String
        get() =
            when (val r = reasoning ?: reasoning_text) {
                is JsonPrimitive -> r.content
                null -> ""
                else -> r.toString()
            }
}

