package com.m57.hermescontrol.data.local

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.m57.hermescontrol.ui.chat.isPermanentlyLocal

@Dao
interface ChatMessageDao {
    @Query("SELECT EXISTS(SELECT 1 FROM chat_messages WHERE session_id = :sessionId)")
    suspend fun sessionExists(sessionId: String): Boolean

    @Query(
        "SELECT * FROM chat_messages WHERE session_id = :sessionId " +
            "AND message_provenance != 'COMPRESSED_ALIAS' " +
            "ORDER BY sort_group, sort_order, id",
    )
    suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity>

    @Query(
        "SELECT * FROM chat_messages WHERE session_id = :sessionId " +
            "AND message_provenance != 'COMPRESSED_ALIAS' " +
            "ORDER BY sort_group DESC, sort_order DESC, id DESC LIMIT :limit",
    )
    suspend fun getLatestMessagePage(
        sessionId: String,
        limit: Int,
    ): List<ChatMessageEntity>

    @Query(
        "SELECT * FROM chat_messages WHERE session_id = :sessionId " +
            "AND message_provenance != 'COMPRESSED_ALIAS' " +
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

    @Query("SELECT MAX(sort_order) FROM chat_messages WHERE session_id = :sessionId AND sort_group = 0")
    suspend fun latestCanonicalOrder(sessionId: String): Long?

    @Upsert
    suspend fun writeMessage(message: ChatMessageEntity)

    // Allocate once, in the same transaction as the write. Content/clock updates never move a local row.
    @Transaction
    suspend fun upsert(message: ChatMessageEntity) {
        val existing = getMessage(message.id)
        val restId = message.restId ?: existing?.restId
        val canonicalOrder =
            if (message.isSessionStartMarker()) {
                -1L
            } else {
                canonicalMessageOrder(restId ?: message.id, message.sessionId)
            }
        // Capture once, transactionally. A later payload update cannot move an old local event to today's tail.
        val anchor =
            if (existing != null) {
                existing.localAnchorOrder ?: message.localAnchorOrder
            } else if (canonicalOrder == null && message.toUiModel().isPermanentlyLocal()) {
                message.localAnchorOrder ?: latestCanonicalOrder(message.sessionId) ?: -1L
            } else {
                message.localAnchorOrder
            }
        writeMessage(
            message.copy(
                restId = restId,
                messageProvenance =
                    if (existing?.messageProvenance ==
                        "COMPRESSED_ALIAS"
                    ) {
                        "COMPRESSED_ALIAS"
                    } else {
                        message.messageProvenance
                    },
                completionId = message.completionId ?: existing?.completionId,
                sortGroup = if (canonicalOrder != null) 0 else 1,
                sortOrder = canonicalOrder ?: existing?.sortOrder ?: nextLocalOrder(),
                localAnchorOrder = anchor,
                localPredecessorId = existing?.localPredecessorId ?: message.localPredecessorId,
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

    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId AND id IN (:ids)")
    suspend fun deleteCanonicalIds(
        sessionId: String,
        ids: List<String>,
    )

    /** Replace only exact server-keyed rows. UUID/local rows are user data, even when aliased. */
    @Transaction
    suspend fun replaceCanonicalHistory(
        sessionId: String,
        replacements: List<ChatMessageEntity>,
    ): List<ChatMessageEntity> {
        val existing = getMessagesForSession(sessionId)
        val retained = existing.filter { canonicalMessageOrder(it.id, sessionId) == null }
        val canonicalIds =
            existing.mapNotNull {
                it.id.takeIf { id -> canonicalMessageOrder(id, sessionId) != null }
            }
        if (canonicalIds.isNotEmpty()) deleteCanonicalIds(sessionId, canonicalIds)
        upsertAll(replacements)
        val tail = replacements.mapNotNull { canonicalMessageOrder(it.id, sessionId) }.maxOrNull() ?: -1L
        retained.forEach { row ->
            if (row.isSessionStartMarker()) {
                // Its original -1 placement predates the replaced history. Keep its identity,
                // but seat it at the replacement boundary ahead of later local events.
                writeMessage(row.copy(localAnchorOrder = tail, localPredecessorId = null))
            } else {
                if (row.restId != null && canonicalMessageOrder(row.restId, sessionId) != null) {
                    // Preserve identity and payload, but remove obsolete confirmed aliases from all cache pages.
                    writeMessage(row.copy(messageProvenance = "COMPRESSED_ALIAS"))
                } else {
                    writeMessage(
                        row.copy(
                            sortGroup = 1,
                            sortOrder = if (row.sortGroup == 0) nextLocalOrder() else row.sortOrder,
                            localAnchorOrder = tail,
                            localPredecessorId = null,
                        ),
                    )
                }
            }
        }
        return getMessagesForSession(sessionId).filter { canonicalMessageOrder(it.id, sessionId) == null }
    }

    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("DELETE FROM chat_messages WHERE id = :id AND rest_id IS NULL")
    suspend fun deleteUnconfirmedMessage(id: String)
}
