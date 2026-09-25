package com.m57.hermescontrol.data.local

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Persisted chat message. Maps to the [com.m57.hermescontrol.ui.chat.ChatMessage]
 * UI model but is stored independently so it survives process death.
 *
 * Messages are scoped by [sessionId] so switching sessions loads the right
 * thread. [sortOrder] preserves numeric server order or local insertion order;
 * [timestamp] is display metadata only.
 */
@Entity(
    tableName = "chat_messages",
    indices = [
        Index(value = ["session_id", "sort_group", "sort_order", "id"]),
    ],
)
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "role")
    val role: String,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "reasoning_text")
    val reasoningText: String = "",
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "tool_name")
    val toolName: String? = null,
    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String = "",
    @ColumnInfo(name = "tool_status")
    val toolStatus: String? = null,
    @ColumnInfo(name = "is_streaming")
    val isStreaming: Boolean = false,
    @ColumnInfo(name = "display_kind")
    val displayKind: String? = null,
    @ColumnInfo(name = "token_count")
    val tokenCount: Int? = null,
    @ColumnInfo(name = "tps")
    val tps: Double? = null,
    @ColumnInfo(name = "completion_id")
    val completionId: String? = null,
    @ColumnInfo(name = "rest_id")
    val restId: String? = null,
    @ColumnInfo(name = "sort_group", defaultValue = "1")
    val sortGroup: Int = 1,
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Long = 0,
    @ColumnInfo(name = "message_provenance", defaultValue = "'UNKNOWN'")
    val messageProvenance: String = "UNKNOWN",
)

internal fun ChatMessageEntity.isSessionStartMarker(): Boolean =
    role == "SYSTEM" && (content == "Session created" || content == "Session branched")

/** Only the exact session prefix and a nonnegative decimal suffix identify a canonical row. */
internal fun canonicalMessageOrder(
    id: String,
    sessionId: String,
): Long? {
    val prefix = "rest-$sessionId-"
    if (!id.startsWith(prefix)) return null
    val suffix = id.removePrefix(prefix)
    return suffix.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toLongOrNull()
}
