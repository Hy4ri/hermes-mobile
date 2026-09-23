package com.m57.hermescontrol.data.local

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

@Dao
interface ChatMessageDao {
    @Query("SELECT EXISTS(SELECT 1 FROM chat_messages WHERE session_id = :sessionId)")
    suspend fun sessionExists(sessionId: String): Boolean

    @Query(
        "SELECT * FROM chat_messages WHERE session_id = :sessionId " +
            "ORDER BY sort_group, sort_order, id",
    )
    suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity>

    @Query(
        "SELECT * FROM chat_messages WHERE session_id = :sessionId " +
            "ORDER BY sort_group DESC, sort_order DESC, id DESC LIMIT :limit",
    )
    suspend fun getLatestMessagePage(
        sessionId: String,
        limit: Int,
    ): List<ChatMessageEntity>

    @Query(
        "SELECT * FROM chat_messages WHERE session_id = :sessionId " +
            "AND (sort_group, sort_order, id) < (:beforeGroup, :beforeOrder, :beforeId) " +
            "ORDER BY sort_group DESC, sort_order DESC, id DESC LIMIT :limit",
    )
    suspend fun getMessagePage(
        sessionId: String,
        beforeGroup: Int,
        beforeOrder: Long,
        beforeId: String,
        limit: Int,
    ): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getMessage(id: String): ChatMessageEntity?

    // Physical insertion sequence includes confirmed aliases, so confirmation cannot reuse a local cursor key.
    @Query("SELECT COALESCE(MAX(rowid), 0) + 1 FROM chat_messages")
    suspend fun nextLocalOrder(): Long

    @Upsert
    suspend fun writeMessage(message: ChatMessageEntity)

    // Allocate once, in the same transaction as the write. Content/clock updates never move a local row.
    @Transaction
    suspend fun upsert(message: ChatMessageEntity) {
        val existing = getMessage(message.id)
        val restId = message.restId ?: existing?.restId
        val canonicalOrder = canonicalMessageOrder(restId ?: message.id, message.sessionId)
        writeMessage(
            message.copy(
                restId = restId,
                completionId = message.completionId ?: existing?.completionId,
                sortGroup = if (canonicalOrder != null) 0 else 1,
                sortOrder = canonicalOrder ?: existing?.sortOrder ?: nextLocalOrder(),
            ),
        )
    }

    @Transaction
    suspend fun upsertAll(messages: List<ChatMessageEntity>) {
        messages.forEach { upsert(it) }
    }

    // History confirms identity, but must not overwrite a newer WS-owned payload.
    @Transaction
    suspend fun confirmIdentity(message: ChatMessageEntity) {
        val existing = getMessage(message.id)
        upsert(
            existing?.copy(restId = message.restId, completionId = existing.completionId ?: message.completionId)
                ?: message,
        )
    }

    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)
}
