package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import com.m57.hermescontrol.data.ws.contract.ApprovalRespondParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.SessionActiveListParams
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HermesWsClientApprovalsTest {
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
            withTimeoutOrNull(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.DISCONNECTED }
            }
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
    fun typedSendApprovalRespondPutsExactParamsOnWire() {
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
                        if (name == "approval.respond") {
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
                method = RpcMethods.APPROVAL_RESPOND,
                params =
                    ApprovalRespondParams(
                        sessionId = "sess-appr-wire-1",
                        choice = "deny",
                        all = false,
                        requestId = "req-appr-wire-42",
                    ),
                onSent = { id -> onSentId = id },
            )

        assertTrue(returnedId.isNotBlank())
        assertEquals(returnedId, onSentId)

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertFalse("Outgoing frames have NO jsonrpc field", frame.containsKey("jsonrpc"))
        assertEquals("approval.respond", (frame["method"] as? JsonPrimitive)?.content)
        assertEquals(returnedId, (frame["id"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        val expectedParams =
            buildJsonObject {
                put("session_id", "sess-appr-wire-1")
                put("choice", "deny")
                put("all", false)
                put("request_id", "req-appr-wire-42")
            }
        assertEquals(expectedParams, params)
        assertEquals(false, (params?.get("all") as? JsonPrimitive)?.booleanOrNull)
    }

    @Test
    fun typedCallSessionActiveListReturnsRawServerJsonUntouched() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put("active_count", 2)
                put(
                    "sessions",
                    buildJsonObject {
                        put("primary", "sess-alpha")
                        put("secondary", "sess-beta")
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
                        if (name == "session.active_list") {
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
                    RpcMethods.SESSION_ACTIVE_LIST,
                    SessionActiveListParams(),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())
        assertEquals("session.active_list", (frame["method"] as? JsonPrimitive)?.content)

        // An empty params object is omitted on wire or empty
        val params = frame["params"] as? JsonObject
        assertTrue(
            "session.active_list with null currentSessionId carries no params",
            params == null || params.isEmpty(),
        )
        assertEquals(serverResultJson, result)
    }
}
