package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import com.m57.hermescontrol.data.ws.contract.RpcMethod
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.SessionEventsSinceParams
import com.m57.hermescontrol.data.ws.contract.SessionEventsSinceResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HermesWsClientTypedCallTest {
    private lateinit var mockWebServer: MockWebServer

    @Serializable
    private data class ScopedDummyParams(
        val query: String,
        val profile: String? = null,
    )

    @Serializable
    private data class ScopedDummyResult(
        val status: String? = null,
    )

    private val sessionListMethod =
        RpcMethod(
            name = WsMethods.SESSION_LIST,
            params = ScopedDummyParams.serializer(),
            result = ScopedDummyResult.serializer(),
        )

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
    fun callPutsExactFrameOnTheWire() {
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
                        if (name == WsMethods.SESSION_EVENTS_SINCE) {
                            frameRef.set(json)
                            captured.countDown()
                            val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                            webSocket.send(
                                """{"jsonrpc":"2.0","id":"$id","result":{"latest_seq":7,"truncated":false}}""",
                            )
                        }
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        runBlocking {
            HermesWsClient.call(
                RpcMethods.SESSION_EVENTS_SINCE,
                SessionEventsSinceParams(sessionId = "s1", lastSeen = 7),
            )
        }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertEquals(WsMethods.SESSION_EVENTS_SINCE, (frame["method"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        val expectedParams =
            buildJsonObject {
                put("session_id", "s1")
                put("last_seen", 7)
            }
        assertEquals(expectedParams, params)

        val lastSeenElement = params!!["last_seen"] as? JsonPrimitive
        assertNotNull(lastSeenElement)
        assertTrue(lastSeenElement!!.isString.not())
        assertEquals(7, lastSeenElement.content.toInt())
    }

    @Test
    fun callDecodesTypedResultOnSuccess() {
        val opened = CountDownLatch(1)

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
                        val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                        webSocket.send(
                            """{"jsonrpc":"2.0","id":"$id","result":{"epoch":"ep-42","latest_seq":100,"truncated":true,"events":[{"type":"test"}]}}""",
                        )
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        val result =
            runBlocking {
                HermesWsClient.call(
                    RpcMethods.SESSION_EVENTS_SINCE,
                    SessionEventsSinceParams(sessionId = "s1", lastSeen = 5),
                )
            }

        assertEquals("ep-42", result.epoch)
        assertEquals(100, result.latestSeq)
        assertEquals(true, result.truncated)
        assertEquals(1, result.events?.size)
    }

    @Test
    fun callThrowsHermesRpcExceptionOnErrorResponse() {
        val opened = CountDownLatch(1)

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
                        val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                        webSocket.send(
                            """{"jsonrpc":"2.0","id":"$id","error":{"code":4001,"message":"Access denied: session ownership required","data":{"reason":"NOT_OWNER"}}}""",
                        )
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        try {
            runBlocking {
                HermesWsClient.call(
                    RpcMethods.SESSION_EVENTS_SINCE,
                    SessionEventsSinceParams(sessionId = "s1", lastSeen = 1),
                )
            }
            fail("Expected HermesRpcException to be thrown")
        } catch (e: HermesWsClient.HermesRpcException) {
            assertEquals(4001, e.code)
            assertEquals("Access denied: session ownership required", e.message)
            val dataObj = e.data as? JsonObject
            assertNotNull(dataObj)
            assertEquals("NOT_OWNER", (dataObj!!["reason"] as? JsonPrimitive)?.content)
        }
    }

    @Test
    fun profileScopingInjectsActiveProfileWhenSet() {
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
                        if (name == WsMethods.SESSION_LIST) {
                            frameRef.set(json)
                            captured.countDown()
                            val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                            webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":{"status":"ok"}}""")
                        }
                    }
                },
            ),
        )

        AuthManager.setActiveProfileId("work-profile")
        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        runBlocking {
            HermesWsClient.call(
                sessionListMethod,
                ScopedDummyParams(query = "active"),
            )
        }

        assertTrue(captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        assertEquals("work-profile", (params!!["profile"] as? JsonPrimitive)?.content)
        assertEquals("active", (params["query"] as? JsonPrimitive)?.content)
    }

    @Test
    fun profileScopingPreservesExplicitProfile() {
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
                        if (name == WsMethods.SESSION_LIST) {
                            frameRef.set(json)
                            captured.countDown()
                            val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                            webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":{"status":"ok"}}""")
                        }
                    }
                },
            ),
        )

        AuthManager.setActiveProfileId("work-profile")
        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        runBlocking {
            HermesWsClient.call(
                sessionListMethod,
                ScopedDummyParams(query = "active", profile = "explicit-override"),
            )
        }

        assertTrue(captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        assertEquals("explicit-override", (params!!["profile"] as? JsonPrimitive)?.content)
    }

    @Test
    fun profileScopingDoesNotInjectWhenNoActiveProfile() {
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
                        if (name == WsMethods.SESSION_LIST) {
                            frameRef.set(json)
                            captured.countDown()
                            val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                            webSocket.send("""{"jsonrpc":"2.0","id":"$id","result":{"status":"ok"}}""")
                        }
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        runBlocking {
            HermesWsClient.call(
                sessionListMethod,
                ScopedDummyParams(query = "active"),
            )
        }

        assertTrue(captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        assertFalse("Profile key must not be present when no active profile", params!!.containsKey("profile"))
    }

    @Test
    fun callFailsOnTimeoutWhenServerDoesNotReply() {
        val opened = CountDownLatch(1)

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
                        // Do not reply
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        val thrown =
            runCatching {
                runBlocking {
                    HermesWsClient.call(
                        RpcMethods.SESSION_EVENTS_SINCE,
                        SessionEventsSinceParams(sessionId = "s1", lastSeen = 1),
                        timeoutMs = 50L,
                    )
                }
            }.exceptionOrNull()

        assertTrue(
            "Timeout must surface as HermesRpcException, got: $thrown",
            thrown is HermesWsClient.HermesRpcException,
        )
        val rpc = thrown as HermesWsClient.HermesRpcException
        assertEquals(-1, rpc.code)
        assertTrue(rpc.message.orEmpty().contains("timed out"))
    }

    @Test
    fun cancellingCallingCoroutineCleansPendingCall() {
        val opened = CountDownLatch(1)
        val requestReceived = CountDownLatch(1)

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
                        requestReceived.countDown()
                        // Do not reply, keeping the call pending
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        runBlocking {
            val callJob =
                async(Dispatchers.IO) {
                    HermesWsClient.call(
                        RpcMethods.SESSION_EVENTS_SINCE,
                        SessionEventsSinceParams(sessionId = "s1", lastSeen = 1),
                        timeoutMs = 10_000L,
                    )
                }

            assertTrue("Server should receive request", requestReceived.await(5, TimeUnit.SECONDS))

            assertEquals(
                "Should have exactly 1 pending call while awaiting",
                1,
                HermesWsClient.pendingCallIdsForTest().size,
            )

            callJob.cancel()

            // Wait briefly for cancellation cleanup to execute
            withTimeout(2000) {
                while (HermesWsClient.pendingCallIdsForTest().isNotEmpty()) {
                    kotlinx.coroutines.delay(20)
                }
            }

            assertTrue(
                "Pending calls should be empty after caller coroutine is cancelled",
                HermesWsClient.pendingCallIdsForTest().isEmpty(),
            )
        }
    }
}
