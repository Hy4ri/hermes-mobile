package com.m57.hermescontrol.ui.chat.fakes

import com.m57.hermescontrol.data.local.ChatMessageDao
import com.m57.hermescontrol.data.local.ChatMessageEntity
import com.m57.hermescontrol.data.local.canonicalMessageOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * In-memory [ChatMessageDao] for use in tests.
 * Paging mirrors the DAO's numeric cursor; writes use its default order allocation methods.
 */
class FakeChatMessageDao : ChatMessageDao {
    private val messages: ConcurrentMap<String, ChatMessageEntity> = ConcurrentHashMap()

    private val ordering = compareBy<ChatMessageEntity> { it.sortGroup }.thenBy { it.sortOrder }.thenBy { it.id }

    private var insertionSequence = 0L

    var fullSessionReads = 0
        private set

    var beforeRead: suspend () -> Unit = {}

    override suspend fun sessionExists(sessionId: String): Boolean = messages.values.any { it.sessionId == sessionId }

    override suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity> {
        fullSessionReads++
        beforeRead()
        return messages.values
            .filter { it.sessionId == sessionId }
            .sortedWith(ordering)
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
            .sortedWith(ordering.reversed())
            .take(limit)
    }

    override suspend fun getMessagePage(
        sessionId: String,
        beforeGroup: Int,
        beforeOrder: Long,
        beforeId: String,
        limit: Int,
    ): List<ChatMessageEntity> {
        pageLimits += limit
        beforeRead()
        return messages.values
            .filter {
                it.sessionId == sessionId &&
                    (
                        it.sortGroup < beforeGroup ||
                            (
                                it.sortGroup == beforeGroup &&
                                    (it.sortOrder < beforeOrder || (it.sortOrder == beforeOrder && it.id < beforeId))
                            )
                    )
            }.sortedWith(ordering.reversed())
            .take(limit)
    }

    override suspend fun getMessage(id: String): ChatMessageEntity? = messages[id]

    override suspend fun nextLocalOrder(): Long = insertionSequence + 1

    override suspend fun writeMessage(message: ChatMessageEntity) {
        if (message.id !in messages) insertionSequence++
        messages[message.id] = message
    }

    override suspend fun deleteMessagesForSession(sessionId: String) {
        messages.values.removeAll { it.sessionId == sessionId }
    }

    /** Direct access for test setup — bypasses the suspend modifier. */
    fun addMessageDirect(message: ChatMessageEntity) {
        val existing = messages[message.id]
        if (existing == null) insertionSequence++
        val order = canonicalMessageOrder(message.restId ?: message.id, message.sessionId)
        messages[message.id] =
            message.copy(
                sortGroup = if (order != null) 0 else 1,
                sortOrder = order ?: existing?.sortOrder ?: insertionSequence,
            )
    }

    /** Reset all stored messages. */
    fun clear() {
        messages.clear()
        insertionSequence = 0L
    }

    fun idsForSession(sessionId: String): Set<String> =
        messages.values.filter { it.sessionId == sessionId }.mapTo(mutableSetOf()) { it.id }

    /** Returns the number of stored messages. */
    fun count(): Int = messages.size
}
