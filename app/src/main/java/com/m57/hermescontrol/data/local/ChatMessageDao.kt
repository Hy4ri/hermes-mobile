package com.m57.hermescontrol.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp ASC")
    fun observeMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<ChatMessageEntity>)

    @Query("UPDATE chat_messages SET content = :content, is_streaming = 0 WHERE id = :id")
    suspend fun finalizeMessage(
        id: String,
        content: String,
    )

    @Query("UPDATE chat_messages SET content = :content WHERE id = :id")
    suspend fun updateContent(
        id: String,
        content: String,
    )

    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query(
        "DELETE FROM chat_messages WHERE session_id NOT IN " +
            "(SELECT DISTINCT session_id FROM chat_messages " +
            "ORDER BY timestamp DESC LIMIT :keepSessions)",
    )
    suspend fun pruneOldSessions(keepSessions: Int)

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun count(): Int
}
