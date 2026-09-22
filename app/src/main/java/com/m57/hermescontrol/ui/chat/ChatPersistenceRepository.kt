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
open class ChatPersistenceRepository(
    private val daoProvider: suspend () -> ChatMessageDao,
) {
    constructor(dao: ChatMessageDao) : this({ dao })

    /** Persist a single message for the given session. */
    suspend fun persistMessage(
        message: ChatMessage,
        sessionId: String,
    ) {
        daoProvider().upsert(message.toEntity(sessionId))
    }

    /** Persist multiple messages in one transaction. */
    suspend fun persistMessages(
        messages: List<ChatMessage>,
        sessionId: String,
    ) {
        val entities = messages.map { it.toEntity(sessionId) }
        daoProvider().upsertAll(entities)
    }

    /** Load cached messages for a session from Room. */
    suspend fun loadMessages(sessionId: String): List<ChatMessage> =
        daoProvider().getMessagesForSession(sessionId).map { it.toUiModel() }

    data class Cursor(
        val group: Int,
        val order: Long,
        val id: String,
    )

    data class Page(
        val messages: List<ChatMessage>,
        val cursor: Cursor?,
        val hasOlder: Boolean,
    )

    /** Read at most one page plus a lookahead row; never trim a full-session query. */
    suspend fun loadPage(
        sessionId: String,
        before: Cursor?,
        limit: Int,
    ): Page {
        require(limit in 1..1_000)
        val dao = daoProvider()
        val rows =
            if (before == null) {
                dao.getLatestMessagePage(sessionId, limit + 1)
            } else {
                dao.getMessagePage(sessionId, before.group, before.order, before.id, limit + 1)
            }
        val page = rows.take(limit)
        val oldest = page.lastOrNull()
        return Page(
            messages = page.asReversed().map { it.toUiModel() },
            cursor = oldest?.let { Cursor(it.sortGroup, it.sortOrder, it.id) } ?: before,
            hasOlder = rows.size > limit,
        )
    }

    /** Record confirmed UUID aliases without replacing their newer locally persisted content. */
    suspend fun confirmIdentities(
        messages: List<ChatMessage>,
        sessionId: String,
    ) {
        val dao = daoProvider()
        messages.forEach { dao.confirmIdentity(it.toEntity(sessionId)) }
    }

    /** Clear all cached messages for a session (e.g. after /undo rewind). */
    suspend fun clearMessagesForSession(sessionId: String) {
        daoProvider().deleteMessagesForSession(sessionId)
    }
}
