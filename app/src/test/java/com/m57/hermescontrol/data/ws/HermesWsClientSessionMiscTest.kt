package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import com.m57.hermescontrol.data.ws.contract.PromptBtwParams
import com.m57.hermescontrol.data.ws.contract.PromptBtwResult
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.SessionBranchParams
import com.m57.hermescontrol.data.ws.contract.SessionBranchWholeParams
import com.m57.hermescontrol.data.ws.contract.SessionCompressParams
import com.m57.hermescontrol.data.ws.contract.SessionIdParams
import com.m57.hermescontrol.data.ws.contract.SessionListParams
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HermesWsClientSessionMiscTest {
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        mockWebServer = MockWebServer()
        mockWebServer.start()
        mockkObject(AuthManager)
        every { AuthManager.wsUrl() } returns mockWebServer.url("/").toString().replace("http://", "ws://")
        every { AuthManager.isAutoReconnect() } returns false
        every { AuthManager.getSessionCookie() } returns null
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config
                        .ServerStoreState()
            }
        CookieManager.setJarForTest(buildFakePersistentCookieJar())
        mockkObject(DashboardSessionTokenRefresher)
        coEvery { DashboardSessionTokenRefresher.refreshAsync() } returns null
        every { DashboardSessionTokenRefresher.refresh() } returns null

        HermesWsClient.disconnect(clearPendingMessages = true)
        HermesWsClient.releaseExternalActivityConnectionLease()
        HermesWsClient.releaseBackgroundConnectionLease()
        HermesWsClient.setAppForeground(true)
        AuthManager.resetAuthStateForTest()
    }

    @After
    fun tearDown() {
        AuthManager.resetAuthStateForTest()
        HermesWsClient.releaseExternalActivityConnectionLease()
        HermesWsClient.releaseBackgroundConnectionLease()
        HermesWsClient.disconnect(clearPendingMessages = true)
        runBlocking {
            withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.DISCONNECTED } }
        }
        runCatching { mockWebServer.shutdown() }
        unmockkAll()
    }

    private fun connectClient() {
        HermesWsClient.connect()
        runBlocking {
            withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } }
        }
    }

    @Test
    fun typedSendSessionListPutsEmptyParamsOnWireWithoutActiveProfile() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) = opened.countDown()

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val json = OkHttpProvider.json.parseToJsonElement(text) as? JsonObject ?: return
                        val name = (json["method"] as? JsonPrimitive)?.content
                        if (name == "session.list") {
                            frameRef.set(json)
                            captured.countDown()
                        }
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        var onSentId: String? = null
        val returnedId =
            HermesWsClient.send(
                method = RpcMethods.SESSION_LIST,
                params = SessionListParams,
                onSent = { id -> onSentId = id },
            )

        assertTrue(returnedId.isNotBlank())
        assertEquals(returnedId, onSentId)

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertFalse("Outgoing frames have NO jsonrpc field", frame.containsKey("jsonrpc"))
        assertEquals("session.list", (frame["method"] as? JsonPrimitive)?.content)
        assertEquals(returnedId, (frame["id"] as? JsonPrimitive)?.content)

        // An empty params object is omitted on the wire (encodeDefaults=false), same as before the migration.
        val params = frame["params"] as? JsonObject
        assertTrue("session.list carries no params", params == null || params.isEmpty())
    }

    @Test
    fun typedSendSessionListWithActiveProfileInjectsProfileOnWire() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) = opened.countDown()

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val json = OkHttpProvider.json.parseToJsonElement(text) as? JsonObject ?: return
                        val name = (json["method"] as? JsonPrimitive)?.content
                        if (name == "session.list") {
                            frameRef.set(json)
                            captured.countDown()
                        }
                    }
                },
            ),
        )

        AuthManager.setActiveProfileId("active-profile-sessions")
        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        HermesWsClient.send(
            method = RpcMethods.SESSION_LIST,
            params = SessionListParams,
        )

        assertTrue(captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        val expectedParams =
            buildJsonObject {
                put("profile", "active-profile-sessions")
            }
        assertEquals(expectedParams, params)
    }

    @Test
    fun typedCallPromptBtwRoundTripsTaskIdAndReturnsPromptBtwResult() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) = opened.countDown()

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val json = OkHttpProvider.json.parseToJsonElement(text) as? JsonObject ?: return
                        val name = (json["method"] as? JsonPrimitive)?.content
                        if (name == "prompt.btw") {
                            frameRef.set(json)
                            captured.countDown()
                            val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                            webSocket.send(
                                """{"jsonrpc":"2.0","id":"$id","result":{"task_id":"task-async-123","status":"queued"}}""",
                            )
                        }
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        val result: PromptBtwResult =
            runBlocking {
                HermesWsClient.call(
                    RpcMethods.PROMPT_BTW,
                    PromptBtwParams(sessionId = "sess-btw-call", text = "remember this detail"),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        assertEquals("prompt.btw", (frame["method"] as? JsonPrimitive)?.content)

        val expectedParams =
            buildJsonObject {
                put("session_id", "sess-btw-call")
                put("text", "remember this detail")
            }
        assertEquals(expectedParams, frame["params"])
        assertEquals("task-async-123", result.taskId)
    }

    @Test
    fun typedCallSessionUsageReturnsRawResultJsonElementUntouched() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put("prompt_tokens", 1024)
                put("completion_tokens", 256)
                put("total_tokens", 1280)
                put("cost_usd", 0.015)
                put(
                    "nested_stats",
                    buildJsonObject {
                        put("cache_read_tokens", 512)
                        put("reasoning_tokens", 64)
                    },
                )
            }

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) = opened.countDown()

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val json = OkHttpProvider.json.parseToJsonElement(text) as? JsonObject ?: return
                        val name = (json["method"] as? JsonPrimitive)?.content
                        if (name == "session.usage") {
                            frameRef.set(json)
                            captured.countDown()
                            val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                            webSocket.send(
                                """{"jsonrpc":"2.0","id":"$id","result":$serverResultJson}""",
                            )
                        }
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        val result: JsonElement =
            runBlocking {
                HermesWsClient.call(
                    RpcMethods.SESSION_USAGE,
                    SessionIdParams(sessionId = "s1"),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        assertEquals("session.usage", (frame["method"] as? JsonPrimitive)?.content)

        val expectedParams =
            buildJsonObject {
                put("session_id", "s1")
            }
        assertEquals(expectedParams, frame["params"])
        assertEquals(serverResultJson, result)
    }

    @Test
    fun typedCallSessionCompressWithCustomTimeoutPutsExactWirePayload() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) = opened.countDown()

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val json = OkHttpProvider.json.parseToJsonElement(text) as? JsonObject ?: return
                        val name = (json["method"] as? JsonPrimitive)?.content
                        if (name == "session.compress") {
                            frameRef.set(json)
                            captured.countDown()
                            val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                            webSocket.send(
                                """{"jsonrpc":"2.0","id":"$id","result":{"status":"compressed"}}""",
                            )
                        }
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        val result: JsonElement =
            runBlocking {
                HermesWsClient.call(
                    method = RpcMethods.SESSION_COMPRESS,
                    params = SessionCompressParams(sessionId = "sess-long-comp", focusTopic = "deep-dive"),
                    timeoutMs = 300_000L,
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        assertEquals("session.compress", (frame["method"] as? JsonPrimitive)?.content)

        val expectedParams =
            buildJsonObject {
                put("session_id", "sess-long-comp")
                put("focus_topic", "deep-dive")
            }
        assertEquals(expectedParams, frame["params"])

        val expectedResult =
            buildJsonObject {
                put("status", "compressed")
            }
        assertEquals(expectedResult, result)
    }

    @Test
    fun typedCallSessionBranchPutsExactWirePayloadAndDecodesPassthroughResult() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResult =
            buildJsonObject {
                put("new_session_id", "sess-branched-99")
            }

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) = opened.countDown()

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val json = OkHttpProvider.json.parseToJsonElement(text) as? JsonObject ?: return
                        val name = (json["method"] as? JsonPrimitive)?.content
                        if (name == "session.branch") {
                            frameRef.set(json)
                            captured.countDown()
                            val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                            webSocket.send(
                                """{"jsonrpc":"2.0","id":"$id","result":$serverResult}""",
                            )
                        }
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        val result: JsonElement =
            runBlocking {
                HermesWsClient.call(
                    RpcMethods.SESSION_BRANCH,
                    SessionBranchParams(sessionId = "sess-orig-1", name = "branch-name"),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        assertEquals("session.branch", (frame["method"] as? JsonPrimitive)?.content)

        val expectedParams =
            buildJsonObject {
                put("session_id", "sess-orig-1")
                put("name", "branch-name")
            }
        assertEquals(expectedParams, frame["params"])
        assertEquals(serverResult, result)
    }

    @Test
    fun typedCallSessionBranchWholePutsExactWirePayloadAndDecodesPassthroughResult() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResult =
            buildJsonObject {
                put("whole_branch_session_id", "sess-whole-branched-101")
            }

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) = opened.countDown()

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val json = OkHttpProvider.json.parseToJsonElement(text) as? JsonObject ?: return
                        val name = (json["method"] as? JsonPrimitive)?.content
                        if (name == "session.branch_whole") {
                            frameRef.set(json)
                            captured.countDown()
                            val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                            webSocket.send(
                                """{"jsonrpc":"2.0","id":"$id","result":$serverResult}""",
                            )
                        }
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        val result: JsonElement =
            runBlocking {
                HermesWsClient.call(
                    RpcMethods.SESSION_BRANCH_WHOLE,
                    SessionBranchWholeParams(sessionId = "sess-whole-orig-1", name = null),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        assertEquals("session.branch_whole", (frame["method"] as? JsonPrimitive)?.content)

        val expectedParams =
            buildJsonObject {
                put("session_id", "sess-whole-orig-1")
            }
        assertEquals(expectedParams, frame["params"])
        assertEquals(serverResult, result)
    }

    @Test
    fun typedCallSessionContextBreakdownPutsExactWirePayloadAndDecodesPassthroughResult() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResult =
            buildJsonObject {
                put("system_prompt", 450)
                put("messages", 2300)
                put("tools", 800)
            }

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) = opened.countDown()

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val json = OkHttpProvider.json.parseToJsonElement(text) as? JsonObject ?: return
                        val name = (json["method"] as? JsonPrimitive)?.content
                        if (name == "session.context_breakdown") {
                            frameRef.set(json)
                            captured.countDown()
                            val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                            webSocket.send(
                                """{"jsonrpc":"2.0","id":"$id","result":$serverResult}""",
                            )
                        }
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        val result: JsonElement =
            runBlocking {
                HermesWsClient.call(
                    RpcMethods.SESSION_CONTEXT_BREAKDOWN,
                    SessionIdParams(sessionId = "sess-bd-1"),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        assertEquals("session.context_breakdown", (frame["method"] as? JsonPrimitive)?.content)

        val expectedParams =
            buildJsonObject {
                put("session_id", "sess-bd-1")
            }
        assertEquals(expectedParams, frame["params"])
        assertEquals(serverResult, result)
    }
}
