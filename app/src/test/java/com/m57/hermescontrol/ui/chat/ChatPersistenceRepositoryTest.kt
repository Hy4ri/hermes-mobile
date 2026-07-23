package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ChatPersistenceRepositoryTest {
    private lateinit var dao: FakeChatMessageDao
    private lateinit var repository: ChatPersistenceRepository

    @Before
    fun setup() {
        dao = FakeChatMessageDao()
        repository = ChatPersistenceRepository(dao)
    }

    @Test
    fun persistMessage_upsertsToDao() =
        runTest {
            val sessionId = "session-1"
            val message =
                ChatMessage(
                    id = "msg-1",
                    role = MessageRole.USER,
                    content = "Hello, world!",
                    timestamp = 1000L,
                )

            repository.persistMessage(message, sessionId)

            val daoMessages = dao.getMessagesForSession(sessionId)
            assertEquals(1, daoMessages.size)
            val entity = daoMessages.first()
            assertEquals("msg-1", entity.id)
            assertEquals(sessionId, entity.sessionId)
            assertEquals("USER", entity.role)
            assertEquals("Hello, world!", entity.content)
            assertEquals(1000L, entity.timestamp)
        }

    @Test
    fun persistMessages_upsertsAllToDao() =
        runTest {
            val sessionId = "session-2"
            val messages =
                listOf(
                    ChatMessage(id = "msg-2", role = MessageRole.SYSTEM, content = "System msg", timestamp = 2000L),
                    ChatMessage(id = "msg-3", role = MessageRole.ASSISTANT, content = "Response", timestamp = 3000L),
                )

            repository.persistMessages(messages, sessionId)

            val daoMessages = dao.getMessagesForSession(sessionId)
            assertEquals(2, daoMessages.size)
            assertEquals("msg-2", daoMessages[0].id)
            assertEquals("msg-3", daoMessages[1].id)
        }

    @Test
    fun loadMessages_returnsMappedMessagesFromDao() =
        runTest {
            val sessionId = "session-3"
            val message1 = ChatMessage(id = "msg-4", role = MessageRole.USER, content = "Q", timestamp = 4000L)
            val message2 = ChatMessage(id = "msg-5", role = MessageRole.ASSISTANT, content = "A", timestamp = 5000L)

            repository.persistMessages(listOf(message1, message2), sessionId)

            val loadedMessages = repository.loadMessages(sessionId)
            assertEquals(2, loadedMessages.size)
            assertEquals("msg-4", loadedMessages[0].id)
            assertEquals(MessageRole.USER, loadedMessages[0].role)
            assertEquals("Q", loadedMessages[0].content)

            assertEquals("msg-5", loadedMessages[1].id)
            assertEquals(MessageRole.ASSISTANT, loadedMessages[1].role)
            assertEquals("A", loadedMessages[1].content)
        }
}
