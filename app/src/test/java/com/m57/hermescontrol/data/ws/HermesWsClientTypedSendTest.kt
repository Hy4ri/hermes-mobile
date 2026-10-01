package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import com.m57.hermescontrol.data.ws.contract.PromptSubmitParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.SessionCreateParams
import com.m57.hermescontrol.data.ws.contract.SessionResumeParams
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

class HermesWsClientTypedSendTest {
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
    fun typedSendSessionResumePutsExpectedFrameAndInvokesOnSent() {
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
                        if (name == "session.resume") {
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
                method = RpcMethods.SESSION_RESUME,
                params = SessionResumeParams(sessionId = "sess-1", omitMessages = true),
                onSent = { id -> onSentId = id },
            )

        assertTrue(returnedId.isNotBlank())
        assertEquals(returnedId, onSentId)

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertFalse("Outgoing frames have NO jsonrpc field", frame.containsKey("jsonrpc"))
        assertEquals("session.resume", (frame["method"] as? JsonPrimitive)?.content)
        assertEquals(returnedId, (frame["id"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        val expectedParams =
            buildJsonObject {
                put("session_id", "sess-1")
                put("omit_messages", true)
            }
        assertEquals(expectedParams, params)
    }

    @Test
    fun typedSendWithActiveProfileInjectsProfileIntoSessionResume() {
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
                        if (name == "session.resume") {
                            frameRef.set(json)
                            captured.countDown()
                        }
                    }
                },
            ),
        )

        AuthManager.setActiveProfileId("active-profile-1")
        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        HermesWsClient.send(
            method = RpcMethods.SESSION_RESUME,
            params = SessionResumeParams(sessionId = "sess-active", omitMessages = true),
        )

        assertTrue(captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        val expectedParams =
            buildJsonObject {
                put("session_id", "sess-active")
                put("omit_messages", true)
                put("profile", "active-profile-1")
            }
        assertEquals(expectedParams, params)
        assertEquals("active-profile-1", (params!!["profile"] as? JsonPrimitive)?.content)
    }

    @Test
    fun typedSendExplicitProfileWinsOverActiveProfileForSessionResume() {
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
                        if (name == "session.resume") {
                            frameRef.set(json)
                            captured.countDown()
                        }
                    }
                },
            ),
        )

        AuthManager.setActiveProfileId("active-profile-1")
        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        HermesWsClient.send(
            method = RpcMethods.SESSION_RESUME,
            params =
                SessionResumeParams(
                    sessionId = "sess-active",
                    omitMessages = true,
                    profile = "explicit-resume-profile",
                ),
        )

        assertTrue(captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        val expectedParams =
            buildJsonObject {
                put("session_id", "sess-active")
                put("omit_messages", true)
                put("profile", "explicit-resume-profile")
            }
        assertEquals(expectedParams, params)
        assertEquals("explicit-resume-profile", (params!!["profile"] as? JsonPrimitive)?.content)
    }

    @Test
    fun sessionCreateWithExplicitProfileKeepsExplicitWhenDifferentProfileActive() {
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
                        if (name == "session.create") {
                            frameRef.set(json)
                            captured.countDown()
                        }
                    }
                },
            ),
        )

        AuthManager.setActiveProfileId("active-profile-2")
        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        HermesWsClient.send(
            method = RpcMethods.SESSION_CREATE,
            params = SessionCreateParams(profile = "bot-1"),
        )

        assertTrue(captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        val expectedParams =
            buildJsonObject {
                put("profile", "bot-1")
            }
        assertEquals(expectedParams, params)
        assertEquals("bot-1", (params!!["profile"] as? JsonPrimitive)?.content)
    }

    @Test
    fun sendMessagePutsSessionIdAndTextOnTheWire() {
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
                        if (name == "prompt.submit") {
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
            HermesWsClient.sendMessage(
                sessionId = "sess-msg-1",
                text = "plain message",
                onSent = { id -> onSentId = id },
            )

        assertTrue(returnedId.isNotBlank())
        assertEquals(returnedId, onSentId)

        assertTrue(captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        val expectedParams =
            buildJsonObject {
                put("session_id", "sess-msg-1")
                put("text", "plain message")
            }
        assertEquals(expectedParams, params)
        assertFalse(params!!.containsKey("queued"))
    }

    @Test
    fun sendMessageWithQueuedTrueAddsQueuedTrue() {
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
                        if (name == "prompt.submit") {
                            frameRef.set(json)
                            captured.countDown()
                        }
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        HermesWsClient.sendMessage(
            sessionId = "sess-msg-2",
            text = "queued message",
            queued = true,
        )

        assertTrue(captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        val expectedParams =
            buildJsonObject {
                put("session_id", "sess-msg-2")
                put("text", "queued message")
                put("queued", true)
            }
        assertEquals(expectedParams, params)
        val queuedElement = params!!["queued"] as? JsonPrimitive
        assertTrue(queuedElement != null && queuedElement.isString.not())
        assertEquals("true", queuedElement!!.content)
    }

    @Test
    fun sendMessageWithQueuedFalseHasNoQueuedKey() {
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
                        if (name == "prompt.submit") {
                            frameRef.set(json)
                            captured.countDown()
                        }
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        HermesWsClient.sendMessage(
            sessionId = "sess-msg-3",
            text = "un为其queued message",
            queued = false,
        )

        assertTrue(captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        val expectedParams =
            buildJsonObject {
                put("session_id", "sess-msg-3")
                put("text", "un为其queued message")
            }
        assertEquals(expectedParams, params)
        assertFalse(params!!.containsKey("queued"))
    }
}
