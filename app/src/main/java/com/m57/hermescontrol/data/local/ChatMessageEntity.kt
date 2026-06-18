package com.m57.hermescontrol.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted chat message. Maps to the [com.m57.hermescontrol.ui.chat.ChatMessage]
 * UI model but is stored independently so it survives process death.
 *
 * Messages are scoped by [sessionId] so switching sessions loads the right
 * thread. The [createdAt] field preserves original ordering even if Room
 * reorders internally.
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "role")
    val role: String,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "tool_name")
    val toolName: String? = null,
    @ColumnInfo(name = "tool_status")
    val toolStatus: String? = null,
    @ColumnInfo(name = "is_streaming")
    val isStreaming: Boolean = false,
)
