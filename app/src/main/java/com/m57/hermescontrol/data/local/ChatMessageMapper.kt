package com.m57.hermescontrol.data.local

import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.ToolStatus

/**
 * Converts between the Room [ChatMessageEntity] and the UI [ChatMessage].
 *
 * Keep this as a pure mapping — no I/O, no side effects.
 */

fun ChatMessageEntity.toUiModel(): ChatMessage =
    ChatMessage(
        id = id,
        role =
            when (role) {
                "USER" -> MessageRole.USER
                "ASSISTANT" -> MessageRole.ASSISTANT
                "SYSTEM" -> MessageRole.SYSTEM
                "TOOL" -> MessageRole.TOOL
                else -> MessageRole.ASSISTANT
            },
        content = content,
        reasoningText = reasoningText,
        timestamp = timestamp,
        isStreaming = isStreaming,
        toolName = toolName,
        toolStatus =
            when (toolStatus) {
                "RUNNING" -> ToolStatus.RUNNING
                "COMPLETED" -> ToolStatus.COMPLETED
                "FAILED" -> ToolStatus.FAILED
                else -> null
            },
    )

fun ChatMessage.toEntity(sessionId: String): ChatMessageEntity =
    ChatMessageEntity(
        id = id,
        sessionId = sessionId,
        role = role.name,
        content = content,
        reasoningText = reasoningText,
        timestamp = timestamp,
        toolName = toolName,
        toolStatus = toolStatus?.name,
        isStreaming = isStreaming,
    )
