package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.ChatMessageDao
import com.m57.hermescontrol.data.local.toEntity
import com.m57.hermescontrol.data.local.toUiModel

/**
 * Wraps Room DAO operations for chat message persistence.
 *
 * Extracted from ChatViewModel to separate persistence concerns from
 * UI state management and WebSocket event handling.
 */
class ChatPersistenceRepository(
    private val dao: ChatMessageDao,
) {
    /** Persist a single message for the given session. */
    suspend fun persistMessage(
        message: ChatMessage,
        sessionId: String,
    ) {
        dao.upsert(message.toEntity(sessionId))
    }

    /** Persist multiple messages in one transaction. */
    suspend fun persistMessages(
        messages: List<ChatMessage>,
        sessionId: String,
    ) {
        val entities = messages.map { it.toEntity(sessionId) }
        dao.upsertAll(entities)
    }

    /** Load cached messages for a session from Room. */
    suspend fun loadMessages(sessionId: String): List<ChatMessage> =
        dao.getMessagesForSession(sessionId).map { it.toUiModel() }
}
