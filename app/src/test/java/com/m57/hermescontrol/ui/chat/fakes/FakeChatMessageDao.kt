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

    override suspend fun sessionExists(sessionId: String): Boolean = messages.values.any { it.sessionId == sessionId }

    override suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity> =
        messages.values
            .filter { it.sessionId == sessionId }
            .sortedBy { it.timestamp }

    override suspend fun upsert(message: ChatMessageEntity) {
        messages[message.id] = message
    }

    override suspend fun upsertAll(messageList: List<ChatMessageEntity>) {
        messageList.forEach { messages[it.id] = it }
    }

    /** Direct access for test setup — bypasses the suspend modifier. */
    fun addMessageDirect(message: ChatMessageEntity) {
        messages[message.id] = message
    }

    /** Reset all stored messages. */
    fun clear() {
        messages.clear()
    }

    /** Returns the number of stored messages. */
    fun count(): Int = messages.size
}
