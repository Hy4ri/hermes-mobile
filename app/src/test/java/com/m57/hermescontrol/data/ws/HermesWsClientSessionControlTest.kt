package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.SessionCorrectionParams
import com.m57.hermescontrol.data.ws.contract.SessionInterruptParams
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

class HermesWsClientSessionControlTest {
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
    fun sendRedirectPutsExpectedFrameAndInvokesOnSent() {
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
                        if (name == "session.redirect") {
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
            HermesWsClient.sendRedirect(
                sessionId = "sess-red-1",
                text = "change topic to testing",
                onSent = { id -> onSentId = id },
            )

        assertTrue(returnedId.isNotBlank())
        assertEquals(returnedId, onSentId)

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertFalse("Outgoing frames have NO jsonrpc field", frame.containsKey("jsonrpc"))
        assertEquals("session.redirect", (frame["method"] as? JsonPrimitive)?.content)
        assertEquals(returnedId, (frame["id"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        val expectedParams =
            buildJsonObject {
                put("session_id", "sess-red-1")
                put("text", "change topic to testing")
            }
        assertEquals(expectedParams, params)
        assertEquals(setOf("session_id", "text"), params!!.keys)
    }

    @Test
    fun typedSendSessionInterruptPutsExpectedFrameAndInvokesOnSent() {
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
                        if (name == "session.interrupt") {
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
                method = RpcMethods.SESSION_INTERRUPT,
                params = SessionInterruptParams(sessionId = "s1"),
                onSent = { id -> onSentId = id },
            )

        assertTrue(returnedId.isNotBlank())
        assertEquals(returnedId, onSentId)

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertFalse("Outgoing frames have NO jsonrpc field", frame.containsKey("jsonrpc"))
        assertEquals("session.interrupt", (frame["method"] as? JsonPrimitive)?.content)
        assertEquals(returnedId, (frame["id"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        val expectedParams =
            buildJsonObject {
                put("session_id", "s1")
            }
        assertEquals(expectedParams, params)
        assertEquals(setOf("session_id"), params!!.keys)
    }

    @Test
    fun typedSendSessionSteerPutsExpectedFrameAndInvokesOnSent() {
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
                        if (name == "session.steer") {
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
                method = RpcMethods.SESSION_STEER,
                params = SessionCorrectionParams(sessionId = "s1", text = "steer towards plan B"),
                onSent = { id -> onSentId = id },
            )

        assertTrue(returnedId.isNotBlank())
        assertEquals(returnedId, onSentId)

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertFalse("Outgoing frames have NO jsonrpc field", frame.containsKey("jsonrpc"))
        assertEquals("session.steer", (frame["method"] as? JsonPrimitive)?.content)
        assertEquals(returnedId, (frame["id"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        assertNotNull(params)

        val expectedParams =
            buildJsonObject {
                put("session_id", "s1")
                put("text", "steer towards plan B")
            }
        assertEquals(expectedParams, params)
        assertEquals(setOf("session_id", "text"), params!!.keys)
    }
}
