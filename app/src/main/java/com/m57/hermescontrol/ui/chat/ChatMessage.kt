package com.m57.hermescontrol.ui.chat

import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val toolName: String? = null,
    val toolStatus: ToolStatus? = null,
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
}

enum class ToolStatus {
    RUNNING,
    COMPLETED,
    FAILED,
}
