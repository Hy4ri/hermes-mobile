package com.m57.hermescontrol.ui.chat.fakes

import com.m57.hermescontrol.data.local.ChatMessageDao
import com.m57.hermescontrol.data.local.ChatMessageEntity

/**
 * In-memory [ChatMessageDao] for use in tests.
 * Replaces Room's generated implementation with a thread-safe hash map.
 */
class FakeChatMessageDao : ChatMessageDao {
    private val messages = mutableMapOf<String, ChatMessageEntity>()
    private val lock = Any()

    override suspend fun sessionExists(sessionId: String): Boolean {
        synchronized(lock) {
            return messages.values.any { it.sessionId == sessionId }
        }
    }

    override suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity> {
        synchronized(lock) {
            return messages.values
                .filter { it.sessionId == sessionId }
                .sortedBy { it.timestamp }
        }
    }

    override suspend fun upsert(message: ChatMessageEntity) {
        synchronized(lock) {
            messages[message.id] = message
        }
    }

    override suspend fun upsertAll(messages: List<ChatMessageEntity>) {
        synchronized(lock) {
            messages.forEach { this.messages[it.id] = it }
        }
    }

    /** Direct access for test setup — bypasses the suspend modifier. */
    fun addMessageDirect(message: ChatMessageEntity) {
        synchronized(lock) {
            messages[message.id] = message
        }
    }

    /** Reset all stored messages. */
    fun clear() {
        synchronized(lock) {
            messages.clear()
        }
    }

    /** Returns the number of stored messages. */
    fun count(): Int {
        synchronized(lock) {
            return messages.size
        }
    }
}
