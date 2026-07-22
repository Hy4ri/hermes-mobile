package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.model.OAuthSubmitRequest
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
                                    "source": "web",
                                    "parent_session_id": "abc-123"
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
            assertEquals("abc-123", second.parent_session_id)
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
            assertEquals("Hello Hermes", messages[0].contentText)
            assertEquals("1718000000", messages[0].timestampText)

            assertEquals("assistant", messages[1].role)
            assertEquals("Hi! How can I help?", messages[1].contentText)

            assertEquals("tool", messages[2].role)
            assertEquals("tool_execution", messages[2].type)
        }

    @Test
    fun getSessionMessages_sendsPaginationQuery() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"messages\": []}"),
            )

            val response = api.getSessionMessages("desktop/session", limit = 150, offset = 300)

            assertTrue(response.isSuccessful)
            assertEquals(
                "/api/sessions/desktop/session/messages?limit=150&offset=300&include_compacted=true",
                mockServer.takeRequest().path,
            )
        }

    @Test
    fun getSessionMessages_withJsonObjectToolResult_deserializesSuccessfully() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "messages": [
                                {
                                    "role": "tool",
                                    "content": {"status": "ok", "items": [1, 2]},
                                    "type": "tool_result"
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
            assertEquals(1, body!!.messages.size)
            assertEquals("tool", body.messages[0].role)
            assertEquals("{\"status\":\"ok\",\"items\":[1,2]}", body.messages[0].contentText)
        }

    @Test
    fun getSessionMessages_withOffsetAndTotal_deserializesPaginationFields() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "messages": [],
                            "offset": 45,
                            "total": 120
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.getSessionMessages("test-session-id")
            assertTrue(response.isSuccessful)

            val body = response.body()
            assertNotNull(body)
            assertEquals(45, body!!.offset)
            assertEquals(120, body.total)
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
    fun searchSessions_parsesResponse() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "results": [
                                {
                                    "session_id": "sess-abc",
                                    "snippet": "…matched text…",
                                    "role": "user",
                                    "source": "telegram",
                                    "model": "gpt-4o",
                                    "session_started": 1718000000
                                }
                            ]
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.searchSessions(q = "matched", profile = null)
            assertTrue(response.isSuccessful)

            val body = response.body()
            assertNotNull(body)
            assertEquals(1, body!!.results.size)

            val first = body.results[0]
            assertEquals("sess-abc", first.session_id)
            assertEquals("…matched text…", first.snippet)
            assertEquals("telegram", first.source)
            assertEquals("gpt-4o", first.model)
            assertEquals(1718000000.0, first.session_started!!, 0.0)
        }

    @Test
    fun searchSessions_encodesQueryAndProfileParams() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{ "results": [] }"""),
            )

            api.searchSessions(q = "hello world", profile = "work")

            val request = mockServer.takeRequest()
            assertTrue(
                "q must be URL-encoded",
                request.path!!.contains("q=hello%20world"),
            )
            assertTrue(
                "profile param must be sent",
                request.path!!.contains("profile=work"),
            )
        }

    @Test
    fun getSessionMessages_preservesSessionIdSlashes() =
        runBlocking {
            val sessionId = "session/with/slashes"
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{ "messages": [] }"""),
            )

            api.getSessionMessages(sessionId)

            val request = mockServer.takeRequest()
            assertEquals(
                "path should contain the session ID with slashes preserved",
                "/api/sessions/session/with/slashes/messages?offset=0",
                request.path,
            )
        }

    @Test
    fun getOAuthProviders_parsesList() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "providers": [
                                {
                                    "id": "anthropic",
                                    "name": "Anthropic",
                                    "flow": "pkce",
                                    "cli_command": "",
                                    "docs_url": "https://docs.anthropic.com",
                                    "disconnectable": true,
                                    "status": {
                                        "logged_in": true,
                                        "source": "hermes_pkce",
                                        "source_label": "Hermes PKCE (~/.hermes/.anthropic_oauth.json)",
                                        "token_preview": "sk-ant-••••a1b2",
                                        "expires_at": "2026-08-01T12:00:00",
                                        "has_refresh_token": true,
                                        "last_refresh": "2026-07-10T12:00:00"
                                    }
                                },
                                {
                                    "id": "nous",
                                    "name": "Nous",
                                    "flow": "device_code",
                                    "cli_command": "hermes auth add nous",
                                    "docs_url": null,
                                    "disconnectable": false,
                                    "disconnect_hint": "Run `hermes auth remove nous` on the host.",
                                    "status": {
                                        "logged_in": false,
                                        "error": null
                                    }
                                }
                            ]
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.getOAuthProviders()
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(2, body!!.providers.size)

            val first = body.providers[0]
            assertEquals("anthropic", first.id)
            assertEquals("Anthropic", first.name)
            assertEquals("pkce", first.flow)
            assertEquals(true, first.status.loggedIn)
            assertEquals("sk-ant-••••a1b2", first.status.tokenPreview)
            assertEquals(true, first.status.hasRefreshToken)

            val second = body.providers[1]
            assertEquals("nous", second.id)
            assertEquals("device_code", second.flow)
            assertEquals(false, second.disconnectable)
            assertEquals("Run `hermes auth remove nous` on the host.", second.disconnectHint)
            assertEquals(false, second.status.loggedIn)
        }

    @Test
    fun startOAuthLogin_pkce_parsesResponse() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "session_id": "sess-abc",
                            "flow": "pkce",
                            "auth_url": "https://claude.ai/oauth/authorize?code=true",
                            "expires_in": 600
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.startOAuthLogin("anthropic")
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals("sess-abc", body!!.sessionId)
            assertEquals("pkce", body.flow)
            assertEquals("https://claude.ai/oauth/authorize?code=true", body.authUrl)
            assertEquals(600, body.expiresIn)
        }

    @Test
    fun startOAuthLogin_deviceCode_parsesResponse() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "session_id": "sess-xyz",
                            "flow": "device_code",
                            "user_code": "ABCD-EFGH",
                            "verification_url": "https://nous.ai/device",
                            "expires_in": 900,
                            "poll_interval": 5
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.startOAuthLogin("nous")
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals("device_code", body!!.flow)
            assertEquals("ABCD-EFGH", body.userCode)
            assertEquals("https://nous.ai/device", body.verificationUrl)
            assertEquals(5, body.pollInterval)
        }

    @Test
    fun submitOAuthCode_parsesResponse() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "ok": true,
                            "status": "approved",
                            "message": "Tokens saved"
                        }
                        """.trimIndent(),
                    ),
            )

            val response =
                api.submitOAuthCode(
                    "anthropic",
                    OAuthSubmitRequest(sessionId = "sess-abc", code = "auth-code-123"),
                )
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(true, body!!.ok)
            assertEquals("approved", body.status)
        }

    @Test
    fun pollOAuthSession_parsesResponse() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "session_id": "sess-xyz",
                            "status": "pending",
                            "error_message": null,
                            "expires_at": 1780583153.638556
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.pollOAuthSession("nous", "sess-xyz")
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals("pending", body!!.status)
            // Backend sends expires_at as a float epoch (time.time()).
            assertEquals(1780583153.638556, body.expiresAt ?: 0.0, 0.0001)
        }

    @Test
    fun cancelOAuthSession_parsesResponse() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "ok": true,
                            "session_id": "sess-xyz"
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.cancelOAuthSession("sess-xyz")
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(true, body!!.ok)
            assertEquals("sess-xyz", body.sessionId)
        }

    @Test
    fun disconnectOAuthProvider_sendsDeleteWithId() =
        runBlocking {
            mockServer.enqueue(
                MockResponse().setResponseCode(200).setBody(""),
            )

            val response = api.disconnectOAuthProvider("anthropic")
            assertTrue(response.isSuccessful)
            val request = mockServer.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/providers/oauth/anthropic", request.path)
        }

    @Test
    fun pollOAuthSession_sendsPathWithBothIds() =
        runBlocking {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                            "session_id": "sess-xyz",
                            "status": "approved"
                        }
                        """.trimIndent(),
                    ),
            )

            val response = api.pollOAuthSession("nous", "sess-xyz")
            assertTrue(response.isSuccessful)
            val request = mockServer.takeRequest()
            assertEquals("/api/providers/oauth/nous/poll/sess-xyz", request.path)
        }
}
