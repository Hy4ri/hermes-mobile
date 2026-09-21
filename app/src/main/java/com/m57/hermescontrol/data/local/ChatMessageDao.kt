package com.m57.hermescontrol.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface ChatMessageDao {
    @Query("SELECT EXISTS(SELECT 1 FROM chat_messages WHERE session_id = :sessionId)")
    suspend fun sessionExists(sessionId: String): Boolean

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity>

    @Query(
        "SELECT * FROM chat_messages WHERE session_id = :sessionId " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    suspend fun getLatestMessagePage(
        sessionId: String,
        limit: Int,
    ): List<ChatMessageEntity>

    // The explicit upper bound allows the existing session/timestamp index to seek.
    @Query(
        "SELECT * FROM chat_messages WHERE session_id = :sessionId " +
            "AND timestamp <= :beforeTimestamp " +
            "AND (timestamp < :beforeTimestamp OR id < :beforeId) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    suspend fun getMessagePage(
        sessionId: String,
        beforeTimestamp: Long,
        beforeId: String,
        limit: Int,
    ): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)
}
