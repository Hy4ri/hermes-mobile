package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class SessionMessagesResponse(
    val messages: List<SessionMessage> = emptyList(),
    // Present only when the backend paginates (limit/offset supplied).
    // Absent on the un-paginated full-history call, so default to null.
    val pagination: Pagination? = null,
)

@Serializable
data class Pagination(
    val limit: Int? = null,
    val offset: Int = 0,
    val returned: Int = 0,
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
