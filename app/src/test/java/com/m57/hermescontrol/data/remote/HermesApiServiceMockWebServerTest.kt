package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.model.StatusResponse
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * MockWebServer-based tests for [HermesApiService].
 *
 * Tests the Retrofit serialization + HTTP layer independently of the
 * real Hermes Gateway. Each test starts a local mock server, enqueues
 * canned JSON responses, and asserts the parsed response objects.
 */
class HermesApiServiceMockWebServerTest {
    private lateinit var mockServer: MockWebServer
    private lateinit var api: HermesApiService

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()

        api =
            Retrofit
                .Builder()
                .baseUrl(mockServer.url("/"))
                .addConverterFactory(OkHttpProvider.json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(HermesApiService::class.java)
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun getStatus_parsesResponse() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "version": "1.2.3",
                            "gateway_running": true,
                            "active_sessions": 4,
                            "auth_required": true,
                            "gateway_platforms": {
                                "telegram": { "state": "connected" },
                                "discord": { "state": "error", "error_code": "AUTH_FAILED" }
                            }
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.getStatus()
            assertTrue(response.isSuccessful)

            val body: StatusResponse? = response.body()
            assertNotNull(body)
            assertEquals("1.2.3", body!!.version)
            assertEquals(true, body.gateway_running)
            assertEquals(4, body.active_sessions)
            assertEquals(true, body.auth_required)

            // Platform statuses
            assertEquals(2, body.gateway_platforms?.size)
            assertEquals("connected", body.gateway_platforms?.get("telegram")?.state)
            assertEquals("error", body.gateway_platforms?.get("discord")?.state)
            assertEquals("AUTH_FAILED", body.gateway_platforms?.get("discord")?.error_code)
        }

    @Test
    fun getStatus_withNullFields_doesNotCrash() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "version": null,
                            "gateway_running": null,
                            "active_sessions": null,
                            "auth_required": null,
                            "gateway_platforms": null
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.getStatus()
            assertTrue(response.isSuccessful)

            val body = response.body()
            assertNotNull(body)
            assertNull(body!!.version)
            assertNull(body.gateway_running)
            assertNull(body.active_sessions)
            assertNull(body.auth_required)
            assertNull(body.gateway_platforms)
        }

    @Test
    fun getSessions_parsesResponse() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "sessions": [
                                {
                                    "id": "abc-123",
                                    "title": "Chat about APIs",
                                    "created_at": "2026-06-15T10:00:00Z",
                                    "message_count": 42,
                                    "status": "active",
                                    "preview": "Last message preview here",
                                    "source": "telegram"
                                },
                                {
                                    "id": "def-456",
                                    "title": "Debugging build",
                                    "created_at": "2026-06-20T14:30:00Z",
                                    "message_count": 7,
                                    "status": "idle",
                                    "source": "web"
                                }
                            ],
                            "total": 2,
                            "limit": 20,
                            "offset": 0
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.getSessions(limit = 20, offset = 0)
            assertTrue(response.isSuccessful)

            val body = response.body()
            assertNotNull(body)
            assertEquals(2, body!!.sessions.size)
            assertEquals(2, body.total)

            val first = body.sessions[0]
            assertEquals("abc-123", first.id)
            assertEquals("Chat about APIs", first.title)
            assertEquals(42, first.message_count)
            assertEquals("active", first.status)
            assertEquals("telegram", first.source)

            val second = body.sessions[1]
            assertEquals("def-456", second.id)
            assertEquals("Debugging build", second.title)
            assertEquals(7, second.message_count)
            assertEquals("idle", second.status)
            assertEquals("web", second.source)
        }

    @Test
    fun getSessions_withEmptyList_parsesCorrectly() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "sessions": [],
                            "total": 0,
                            "limit": 20,
                            "offset": 0
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.getSessions()
            assertTrue(response.isSuccessful)

            val body = response.body()
            assertNotNull(body)
            assertTrue(body!!.sessions.isEmpty())
            assertEquals(0, body.total)
        }

    @Test
    fun getSessionMessages_parsesResponse() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "messages": [
                                {
                                    "role": "user",
                                    "content": "Hello Hermes",
                                    "timestamp": "1718000000"
                                },
                                {
                                    "role": "assistant",
                                    "content": "Hi! How can I help?",
                                    "timestamp": "1718000010"
                                },
                                {
                                    "role": "tool",
                                    "content": "Result: 42",
                                    "timestamp": "1718000020",
                                    "type": "tool_execution"
                                }
                            ]
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.getSessionMessages("test-session-id")
            assertTrue(response.isSuccessful)

            val body = response.body()
            assertNotNull(body)

            val messages: List<SessionMessage> = body!!.messages
            assertEquals(3, messages.size)

            assertEquals("user", messages[0].role)
            assertEquals("Hello Hermes", messages[0].content)
            assertEquals("1718000000", messages[0].timestampText)

            assertEquals("assistant", messages[1].role)
            assertEquals("Hi! How can I help?", messages[1].content)

            assertEquals("tool", messages[2].role)
            assertEquals("tool_execution", messages[2].type)
        }

    @Test
    fun getSessionMessages_withNullRole_doesNotCrash() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "messages": [
                                {
                                    "role": null,
                                    "content": "Plain content",
                                    "timestamp": null
                                }
                            ]
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.getSessionMessages("any-id")
            assertTrue(response.isSuccessful)

            val body = response.body()
            assertNotNull(body)
            assertEquals(1, body!!.messages.size)
            assertNull(body.messages[0].role)
            assertEquals("Plain content", body.messages[0].content)
            assertNull(body.messages[0].timestamp)
        }

    @Test
    fun getSessionMessages_encodesSessionIdWithSlashes() =
        runBlocking {
            val sessionId = "session/with/slashes"
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "messages": [
                                {
                                    "role": "user",
                                    "content": "test",
                                    "timestamp": "1000"
                                }
                            ]
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.getSessionMessages(sessionId)
            assertTrue(response.isSuccessful)

            // Verify the request path preserves slashes
            val request = mockServer.takeRequest()
            assertTrue(
                "Slash-encoded session ID should appear in path",
                request.path!!.contains("session/with/slashes"),
            )
        }
}
