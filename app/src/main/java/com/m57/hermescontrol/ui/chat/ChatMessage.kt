package com.m57.hermescontrol.ui.chat

import java.util.UUID

/**
 * Metadata for a [ChatMessage] that represents an approval request.
 * When present, the UI renders Approve/Deny buttons inline.
 * Transient — not persisted to SQLite.
 */
data class ApprovalInfo(
    val command: String?,
    val description: String?,
    val patternKeys: List<String>?,
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val toolName: String? = null,
    val toolStatus: ToolStatus? = null,
    val approvalInfo: ApprovalInfo? = null,
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
