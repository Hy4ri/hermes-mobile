package com.m57.hermescontrol.data.local

import android.content.Context
import androidx.room.Room
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatMessageDaoTest {
    private lateinit var db: HermesDatabase
    private lateinit var dao: ChatMessageDao
    private val mockContext = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0

        every { mockContext.applicationContext } returns mockContext
        every { mockContext.packageName } returns "com.m57.hermescontrol.test"

        db =
            Room
                .inMemoryDatabaseBuilder(mockContext, HermesDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.chatMessageDao()
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
    }

    @Test
    fun testUpsertAndRetrieve() =
        runTest {
            val message =
                ChatMessageEntity(
                    id = "msg-1",
                    sessionId = "session-a",
                    role = "user",
                    content = "Hello",
                    timestamp = 1000L,
                )
            dao.upsert(message)

            val messages = dao.getMessagesForSession("session-a")
            assertEquals(1, messages.size)
            assertEquals("msg-1", messages[0].id)
            assertEquals("user", messages[0].role)
            assertEquals("Hello", messages[0].content)
        }

    @Test
    fun testUpsertReplacesExisting() =
        runTest {
            val original =
                ChatMessageEntity(
                    id = "msg-1",
                    sessionId = "session-a",
                    role = "user",
                    content = "Hello",
                    timestamp = 1000L,
                )
            dao.upsert(original)

            val replacement =
                ChatMessageEntity(
                    id = "msg-1",
                    sessionId = "session-a",
                    role = "assistant",
                    content = "Hello World!",
                    timestamp = 2000L,
                )
            dao.upsert(replacement)

            val messages = dao.getMessagesForSession("session-a")
            assertEquals(1, messages.size)
            assertEquals("assistant", messages[0].role)
            assertEquals("Hello World!", messages[0].content)
        }

    @Test
    fun testEmptySessionReturnsEmptyList() =
        runTest {
            val messages = dao.getMessagesForSession("nonexistent-session")
            assertTrue(messages.isEmpty())
        }

    @Test
    fun testMessagesOrderedByTimestamp() =
        runTest {
            val msg1 =
                ChatMessageEntity(
                    id = "msg-1",
                    sessionId = "session-a",
                    role = "assistant",
                    content = "Third",
                    timestamp = 3000L,
                )
            val msg2 =
                ChatMessageEntity(
                    id = "msg-2",
                    sessionId = "session-a",
                    role = "user",
                    content = "First",
                    timestamp = 1000L,
                )
            val msg3 =
                ChatMessageEntity(
                    id = "msg-3",
                    sessionId = "session-a",
                    role = "assistant",
                    content = "Second",
                    timestamp = 2000L,
                )
            dao.upsertAll(listOf(msg1, msg2, msg3))

            val messages = dao.getMessagesForSession("session-a")
            assertEquals(3, messages.size)
            assertEquals("First", messages[0].content)
            assertEquals("Second", messages[1].content)
            assertEquals("Third", messages[2].content)
        }

    @Test
    fun testMultipleSessionsAreSeparate() =
        runTest {
            dao.upsert(
                ChatMessageEntity(
                    id = "session-a-msg-1",
                    sessionId = "session-a",
                    role = "user",
                    content = "Hello from A",
                    timestamp = 1000L,
                ),
            )
            dao.upsert(
                ChatMessageEntity(
                    id = "session-b-msg-1",
                    sessionId = "session-b",
                    role = "user",
                    content = "Hello from B",
                    timestamp = 2000L,
                ),
            )

            assertEquals(1, dao.getMessagesForSession("session-a").size)
            assertEquals("Hello from A", dao.getMessagesForSession("session-a")[0].content)
            assertEquals(1, dao.getMessagesForSession("session-b").size)
            assertEquals("Hello from B", dao.getMessagesForSession("session-b")[0].content)
        }

    @Test
    fun testNullableFields_storedAndRetrieved() =
        runTest {
            val msg =
                ChatMessageEntity(
                    id = "msg-tool-1",
                    sessionId = "session-a",
                    role = "tool",
                    content = "{\"result\": \"42\"}",
                    timestamp = 5000L,
                    toolName = "calculator",
                    toolStatus = "completed",
                )
            dao.upsert(msg)

            val messages = dao.getMessagesForSession("session-a")
            assertEquals(1, messages.size)
            assertEquals("calculator", messages[0].toolName)
            assertEquals("completed", messages[0].toolStatus)
        }

    @Test
    fun testNullableFields_defaultToNull() =
        runTest {
            dao.upsert(
                ChatMessageEntity(
                    id = "msg-no-tool",
                    sessionId = "session-a",
                    role = "user",
                    content = "plain message",
                    timestamp = 6000L,
                ),
            )

            val messages = dao.getMessagesForSession("session-a")
            assertEquals(1, messages.size)
            assertEquals(null, messages[0].toolName)
            assertEquals(null, messages[0].toolStatus)
        }

    @Test
    fun testIsStreamingField() =
        runTest {
            dao.upsert(
                ChatMessageEntity(
                    id = "msg-streaming",
                    sessionId = "session-a",
                    role = "assistant",
                    content = "partial...",
                    timestamp = 7000L,
                    isStreaming = true,
                ),
            )

            val messages = dao.getMessagesForSession("session-a")
            assertTrue(messages[0].isStreaming)
        }

    @Test
    fun testUpsertAll_batchInsert() =
        runTest {
            val messages =
                (1..10).map { i ->
                    ChatMessageEntity(
                        id = "batch-msg-$i",
                        sessionId = "session-batch",
                        role = if (i % 2 == 0) "assistant" else "user",
                        content = "Message $i",
                        timestamp = i * 1000L,
                    )
                }
            dao.upsertAll(messages)

            val retrieved = dao.getMessagesForSession("session-batch")
            assertEquals(10, retrieved.size)
            assertEquals("Message 1", retrieved[0].content)
            assertEquals("Message 10", retrieved[9].content)
        }
}
