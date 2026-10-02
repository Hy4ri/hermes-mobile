package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.ChatMessageDao
import com.m57.hermescontrol.data.local.ChatMessageEntity
import com.m57.hermescontrol.data.local.canonicalMessageOrder
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
    suspend fun loadMessages(sessionId: String): List<ChatMessage> {
        val dao = daoProvider()
        return restoreLocalAnchors(dao.getMessagesForSession(sessionId), dao, sessionId)
    }

    /** Resolve a pending predecessor even when it falls outside the current cache page. */
    private suspend fun restoreLocalAnchors(
        rows: List<ChatMessageEntity>,
        dao: ChatMessageDao,
        sessionId: String,
    ): List<ChatMessage> {
        val predecessors = mutableMapOf<String, Long?>()
        return rows.map { row ->
            val message = row.toUiModel()
            val id = message.localPredecessorId
            if (!message.isPermanentlyLocal() || id == null) return@map message
            if (id !in predecessors) {
                val predecessor = dao.getMessage(id)?.takeIf { it.sessionId == sessionId }
                predecessors[id] =
                    predecessor?.let { canonicalMessageOrder(it.restId ?: it.id, sessionId) }
            }
            predecessors[id]?.let { message.copy(localAnchorOrder = it, localPredecessorId = null) } ?: message
        }
    }

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
            messages = restoreLocalAnchors(page.asReversed(), dao, sessionId),
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

    /** Atomic authoritative history swap; returns all retained local/UUID rows. */
    suspend fun replaceCanonicalHistory(
        sessionId: String,
        messages: List<ChatMessage>,
    ): List<ChatMessage> {
        val dao = daoProvider()
        val canonical =
            messages.mapNotNull { message ->
                message.canonicalRestId?.let { message.copy(id = it, restId = null).toEntity(sessionId) }
            }
        return restoreLocalAnchors(dao.replaceCanonicalHistory(sessionId, canonical), dao, sessionId)
    }

    /** Clear all cached messages for a session (e.g. after /undo rewind). */
    suspend fun clearMessagesForSession(sessionId: String) {
        daoProvider().deleteMessagesForSession(sessionId)
    }

    suspend fun deleteMessage(id: String) {
        daoProvider().deleteUnconfirmedMessage(id)
    }
}
