package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class SessionMessagesResponse(
    val messages: List<SessionMessage>,
    val offset: Int? = null,
    val total: Int? = null,
    val pagination: PaginationInfo? = null,
)

/**
 * Echo of the backend's pagination state for GET /api/sessions/{id}/messages
 * (hermes_cli/web_routers/sessions.py). Absent on legacy backends that
 * predate the `order` param — its presence also proves `order=latest` was
 * honored (issue #859).
 */
@Serializable
data class PaginationInfo(
    val limit: Int? = null,
    val offset: Int? = null,
    val order: String? = null,
    val returned: Int? = null,
)

@Serializable
data class SessionMessage(
    // The gateway's AUTOINCREMENT row id (hermes_state_common.py
    // messages.id) — stable, unique and never reused. Used as the chat-list
    // stable key under newest-anchored paging (issue #859).
    val id: Int? = null,
    val role: String? = null,
    val content: JsonElement? = null,
    val timestamp: JsonElement? = null,
    val type: String? = null,
    val reasoning: JsonElement? = null,
    val reasoning_text: JsonElement? = null,
    val tool_call_id: String? = null,
    /**
     * Timeline-marker tag (backend NS-656 lineage, issue #904): markers like
     * `model_switch` / `personality_switch` / `auto_continue` ride as
     * role=user rows so strict providers accept them mid-conversation, but
     * they are NOT user turns. Nullable/additive — old backends omit it.
     */
    val display_kind: String? = null,
    /** Display-only metadata for the marker (e.g. delegation result counts). */
    val display_metadata: JsonElement? = null,
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

    val toolCallId: String
        get() = tool_call_id.orEmpty()
}
