package com.m57.hermescontrol.ui.chat.fakes

import com.m57.hermescontrol.data.local.ChatMessageDao
import com.m57.hermescontrol.data.local.ChatMessageEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * In-memory [ChatMessageDao] for use in tests.
 * Uses [ConcurrentHashMap] for thread safety — no manual synchronization needed.
 */
class FakeChatMessageDao : ChatMessageDao {
    private val messages: ConcurrentMap<String, ChatMessageEntity> = ConcurrentHashMap()

    var fullSessionReads = 0
        private set

    var beforeRead: suspend () -> Unit = {}

    override suspend fun sessionExists(sessionId: String): Boolean = messages.values.any { it.sessionId == sessionId }

    override suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity> {
        fullSessionReads++
        beforeRead()
        return messages.values
            .filter { it.sessionId == sessionId }
            .sortedBy { it.timestamp }
    }

    val pageLimits = mutableListOf<Int>()

    override suspend fun getLatestMessagePage(
        sessionId: String,
        limit: Int,
    ): List<ChatMessageEntity> {
        pageLimits += limit
        beforeRead()
        return messages.values
            .filter { it.sessionId == sessionId }
            .sortedWith(compareByDescending<ChatMessageEntity> { it.timestamp }.thenByDescending { it.id })
            .take(limit)
    }

    override suspend fun getMessagePage(
        sessionId: String,
        beforeTimestamp: Long,
        beforeId: String,
        limit: Int,
    ): List<ChatMessageEntity> {
        pageLimits += limit
        beforeRead()
        return messages.values
            .filter {
                it.sessionId == sessionId &&
                    it.timestamp <= beforeTimestamp &&
                    (it.timestamp < beforeTimestamp || it.id < beforeId)
            }.sortedWith(compareByDescending<ChatMessageEntity> { it.timestamp }.thenByDescending { it.id })
            .take(limit)
    }

    override suspend fun upsert(message: ChatMessageEntity) {
        messages[message.id] = message
    }

    override suspend fun upsertAll(messageList: List<ChatMessageEntity>) {
        messageList.forEach { messages[it.id] = it }
    }

    override suspend fun deleteMessagesForSession(sessionId: String) {
        messages.values.removeAll { it.sessionId == sessionId }
    }

    /** Direct access for test setup — bypasses the suspend modifier. */
    fun addMessageDirect(message: ChatMessageEntity) {
        messages[message.id] = message
    }

    /** Reset all stored messages. */
    fun clear() {
        messages.clear()
    }

    fun idsForSession(sessionId: String): Set<String> =
        messages.values.filter { it.sessionId == sessionId }.mapTo(mutableSetOf()) { it.id }

    /** Returns the number of stored messages. */
    fun count(): Int = messages.size
}
