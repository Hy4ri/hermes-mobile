package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ChatPersistenceRepositoryTest {
    private lateinit var dao: FakeChatMessageDao
    private lateinit var repository: ChatPersistenceRepository

    @Test
    fun providerIsLazyAndPendingWriteWaitsForDatabase() =
        runTest {
            val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
            var calls = 0
            val lazyRepository =
                ChatPersistenceRepository {
                    calls++
                    ready.await()
                    dao
                }
            assertEquals(0, calls)
            val pending =
                async {
                    lazyRepository.persistMessage(
                        ChatMessage(id = "pending", role = MessageRole.USER, content = "saved"),
                        "s",
                    )
                }
            kotlinx.coroutines.yield()
            assertEquals(1, calls)
            org.junit.Assert.assertFalse(pending.isCompleted)
            ready.complete(Unit)
            pending.await()
            assertEquals("pending", dao.getMessagesForSession("s").single().id)
        }

    @Test
    fun providerFailureIsNotReportedAsEmptyHistory() =
        runTest {
            val failure = IllegalStateException("database unavailable")
            val failing = ChatPersistenceRepository { throw failure }
            org.junit.Assert.assertSame(failure, runCatching { failing.loadMessages("s") }.exceptionOrNull())
        }

    @Test
    fun pagedReadWaitsForLazyProviderAndRetainsCompletionIdentity() =
        runTest {
            val ready = kotlinx.coroutines.CompletableDeferred<com.m57.hermescontrol.data.local.ChatMessageDao>()
            var calls = 0
            val lazyRepository =
                ChatPersistenceRepository {
                    calls++
                    ready.await()
                }
            assertEquals(0, calls)
            val pending = async { lazyRepository.loadPage("s", null, 150) }
            kotlinx.coroutines.yield()
            assertEquals(1, calls)
            org.junit.Assert.assertFalse(pending.isCompleted)
            repository.persistMessage(
                ChatMessage(id = "rest-s-1", role = MessageRole.ASSISTANT, content = "Done", completionId = "comp"),
                "s",
            )
            ready.complete(dao)
            val row = pending.await().messages.single()
            assertEquals("rest-s-1", row.canonicalRestId)
            assertEquals("comp", row.completionId)
            assertEquals(0, dao.fullSessionReads)
            assertEquals(listOf(151), dao.pageLimits)
            val failure = IllegalStateException("database unavailable")
            val failing = ChatPersistenceRepository { throw failure }
            org.junit.Assert.assertSame(failure, runCatching { failing.loadPage("s", null, 150) }.exceptionOrNull())
        }

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
    fun deleteMessageRemovesOnlyUnconfirmedOptimisticRows() =
        runTest {
            repository.persistMessage(ChatMessage(id = "local", role = MessageRole.USER, content = "draft"), "s")
            repository.persistMessage(
                ChatMessage(id = "confirmed", role = MessageRole.USER, content = "history", restId = "rest-s-7"),
                "s",
            )

            repository.deleteMessage("local")
            repository.deleteMessage("confirmed")

            val rows = repository.loadMessages("s")
            assertFalse(rows.any { it.id == "local" })
            assertEquals("confirmed", rows.single().id)
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
            assertEquals(sessionId, daoMessages[0].sessionId)
            assertEquals("SYSTEM", daoMessages[0].role)
            assertEquals("System msg", daoMessages[0].content)
            assertEquals(2000L, daoMessages[0].timestamp)

            assertEquals("msg-3", daoMessages[1].id)
            assertEquals(sessionId, daoMessages[1].sessionId)
            assertEquals("ASSISTANT", daoMessages[1].role)
            assertEquals("Response", daoMessages[1].content)
            assertEquals(3000L, daoMessages[1].timestamp)
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

    @Test
    fun loadMessages_filtersBySessionId() =
        runTest {
            val sessionA = "session-A"
            val sessionB = "session-B"
            val messageA1 = ChatMessage(id = "msg-A1", role = MessageRole.USER, content = "Hi A", timestamp = 1000L)
            val messageB1 = ChatMessage(id = "msg-B1", role = MessageRole.USER, content = "Hi B", timestamp = 2000L)
            val messageB2 =
                ChatMessage(id = "msg-B2", role = MessageRole.ASSISTANT, content = "Hello B", timestamp = 3000L)

            repository.persistMessage(messageA1, sessionA)
            repository.persistMessages(listOf(messageB1, messageB2), sessionB)

            val loadedA = repository.loadMessages(sessionA)
            assertEquals(1, loadedA.size)
            assertEquals("msg-A1", loadedA[0].id)

            val loadedB = repository.loadMessages(sessionB)
            assertEquals(2, loadedB.size)
            assertEquals("msg-B1", loadedB[0].id)
            assertEquals("msg-B2", loadedB[1].id)
        }

    @Test
    fun loadPage_usesSeparateInitialAndCursorQueries() =
        runTest {
            val tracedDao = io.mockk.spyk(dao)
            val pagedRepository = ChatPersistenceRepository(tracedDao)
            (1..3).forEach { index ->
                pagedRepository.persistMessage(
                    ChatMessage(id = "row-$index", role = MessageRole.USER, content = "$index", timestamp = 10L),
                    "session",
                )
            }

            val first = pagedRepository.loadPage("session", before = null, limit = 2)
            val second = pagedRepository.loadPage("session", before = first.cursor, limit = 2)

            assertEquals(listOf("row-2", "row-3"), first.messages.map { it.id })
            assertEquals(listOf("row-1"), second.messages.map { it.id })
            assertEquals(true, first.hasOlder)
            assertEquals(false, second.hasOlder)
            io.mockk.coVerify(exactly = 1) { tracedDao.getLatestMessagePage("session", 3) }
            io.mockk.coVerify(exactly = 1) { tracedDao.getMessagePage("session", 1, 2L, "row-2", 3) }
            io.mockk.coVerify(exactly = 0) { tracedDao.getMessagesForSession(any()) }
        }

    @Test
    fun loadPage_equalTimestampsAndLiveInsertKeepCursorDeterministicAndSessionsIsolated() =
        runTest {
            val ids = (1..350).map { "rest-session-$it" }
            repository.persistMessages(
                ids.map { ChatMessage(id = it, role = MessageRole.USER, content = it, timestamp = 10L) },
                "session",
            )
            val first = repository.loadPage("session", before = null, limit = 150)
            repository.persistMessage(
                ChatMessage(id = "new", role = MessageRole.USER, content = "new", timestamp = 20L),
                "session",
            )
            repository.persistMessage(
                ChatMessage(id = "other", role = MessageRole.USER, content = "other", timestamp = 10L),
                "other-session",
            )
            val second = repository.loadPage("session", before = first.cursor, limit = 150)
            val third = repository.loadPage("session", before = second.cursor, limit = 150)

            assertEquals(ids.takeLast(150), first.messages.map { it.id })
            assertEquals(ids, (third.messages + second.messages + first.messages).map { it.id })
            assertEquals(false, third.hasOlder)
            assertEquals(listOf(151, 151, 151), dao.pageLimits)
            assertEquals(0, dao.fullSessionReads)
            assertEquals(ids.toSet() + "new", dao.idsForSession("session"))
            assertEquals(setOf("other"), dao.idsForSession("other-session"))
        }

    @Test
    fun loadPage_nonMonotonicTimestampsFollowServerIdsInEitherArrivalOrder() =
        runTest {
            val rows =
                listOf(9, 10, 100, 200).mapIndexed { index, id ->
                    ChatMessage(
                        id = "rest-session-$id",
                        role = MessageRole.ASSISTANT,
                        content = "answer-$id",
                        timestamp = listOf(900L, 100L, 800L, 200L)[index],
                    )
                }
            for (pages in listOf(listOf(rows.take(2), rows.drop(2)), listOf(rows.drop(2), rows.take(2)))) {
                repository.clearMessagesForSession("session")
                pages.forEach { repository.persistMessages(it, "session") }
                val latest = repository.loadPage("session", null, 2)
                val older = repository.loadPage("session", latest.cursor, 2)
                assertEquals(rows.drop(2).map { it.id }, latest.messages.map { it.id })
                assertEquals(rows.take(2).map { it.id }, older.messages.map { it.id })
                assertEquals(true, latest.hasOlder)
                assertEquals(false, older.hasOlder)
            }
            assertEquals(0, dao.fullSessionReads)
            assertEquals(listOf(3, 3, 3, 3), dao.pageLimits)
        }

    @Test
    fun loadPage_uuidOnlyRowsKeepInsertionOrderAcrossUpdatesAndLiveAppend() =
        runTest {
            val rows =
                listOf("ffffffff", "00000000", "aaaaaaaa").mapIndexed { index, prefix ->
                    ChatMessage(
                        id = "$prefix-0000-4000-8000-000000000000",
                        role = MessageRole.USER,
                        content = "message-$index",
                        timestamp = listOf(900L, 100L, 800L)[index],
                    )
                }
            rows.forEach { repository.persistMessage(it, "local") }
            repository.persistMessage(rows.first().copy(content = "edited", timestamp = 999L), "local")
            val latest = repository.loadPage("local", null, 2)
            repository.persistMessage(
                ChatMessage(
                    id = "bbbbbbbb-0000-4000-8000-000000000000",
                    role = MessageRole.USER,
                    content = "appended after cursor",
                    timestamp = 1L,
                ),
                "local",
            )
            val older = repository.loadPage("local", latest.cursor, 2)
            assertEquals(rows.drop(1).map { it.id }, latest.messages.map { it.id })
            assertEquals(listOf(rows.first().id), older.messages.map { it.id })
            assertEquals("edited", older.messages.single().content)
            assertEquals(false, older.hasOlder)
            assertEquals(
                "appended after cursor",
                repository
                    .loadPage("local", null, 1)
                    .messages
                    .single()
                    .content,
            )
            assertEquals(0, dao.fullSessionReads)
        }
}
