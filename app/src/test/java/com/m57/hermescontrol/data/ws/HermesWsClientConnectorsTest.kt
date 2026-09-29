package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import com.m57.hermescontrol.data.ws.contract.ConnectorOwner
import com.m57.hermescontrol.data.ws.contract.ConnectorsConnectParams
import com.m57.hermescontrol.data.ws.contract.ConnectorsListParams
import com.m57.hermescontrol.data.ws.contract.ConnectorsPolicySetParams
import com.m57.hermescontrol.data.ws.contract.HermesRpcCaller
import com.m57.hermescontrol.data.ws.contract.RpcMethods
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
import kotlinx.serialization.json.buildJsonArray
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

class HermesWsClientConnectorsTest {
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
    fun typedCallConnectorsConnectEncodesOwnerAccountReconnectTrueAndReturnsRawServerJsonUntouched() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put("op_id", "op-connect-123")
                put("status", "running")
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
                        if (name == "connectors.connect") {
                            frameRef.set(json)
                            captured.countDown()
                            val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                            webSocket.send(
                                """{"jsonrpc":"2.0","id":"$id","result":""" + serverResultJson + """}""",
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
                    RpcMethods.CONNECTORS_CONNECT,
                    ConnectorsConnectParams(
                        owner = ConnectorOwner.account(),
                        connectors = listOf("slack", "github"),
                        reconnect = true,
                    ),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertFalse("Outgoing frames have NO jsonrpc field", frame.containsKey("jsonrpc"))
        assertEquals("connectors.connect", (frame["method"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        val expectedParams =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "account")
                    },
                )
                put(
                    "connectors",
                    buildJsonArray {
                        add(JsonPrimitive("slack"))
                        add(JsonPrimitive("github"))
                    },
                )
                put("reconnect", true)
            }
        assertEquals(expectedParams, params)
        assertEquals(true, (params?.get("reconnect") as? JsonPrimitive)?.booleanOrNull)
        assertFalse(params?.containsKey("profile") ?: true)
        assertEquals(serverResultJson, result)
    }

    @Test
    fun typedCallConnectorsPolicySetEncodesNestedChangeWithArrayAndReturnsRawServerJsonUntouched() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put("revision", 5)
                put("applied", true)
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
                        if (name == "connectors.policy.set") {
                            frameRef.set(json)
                            captured.countDown()
                            val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                            webSocket.send(
                                """{"jsonrpc":"2.0","id":"$id","result":""" + serverResultJson + """}""",
                            )
                        }
                    }
                },
            ),
        )

        connectClient()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        val nestedChange =
            buildJsonObject {
                put("policy", "whitelist")
                put(
                    "allowed_connectors",
                    buildJsonArray {
                        add(JsonPrimitive("notion"))
                        add(JsonPrimitive("linear"))
                    },
                )
                put(
                    "rules",
                    buildJsonObject {
                        put("require_auth", true)
                    },
                )
            }

        val result: JsonElement =
            runBlocking {
                HermesWsClient.call(
                    RpcMethods.CONNECTORS_POLICY_SET,
                    ConnectorsPolicySetParams(
                        change = nestedChange,
                        expectedRevision = "4",
                        profile = "team-profile",
                    ),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertEquals("connectors.policy.set", (frame["method"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        val expectedParams =
            buildJsonObject {
                put("change", nestedChange)
                put("expected_revision", "4")
                put("profile", "team-profile")
            }
        assertEquals(expectedParams, params)
        assertEquals(nestedChange, params?.get("change"))
        assertEquals(serverResultJson, result)
    }

    @Test
    fun hermesRpcCallerCallDelegatesToHermesWsClientCallAndEncodesFrameParamsUntouched() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put(
                    "connectors",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "c1")
                                put("status", "connected")
                            },
                        )
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
                        if (name == "connectors.list") {
                            frameRef.set(json)
                            captured.countDown()
                            val id = (json["id"] as? JsonPrimitive)?.content ?: "1"
                            webSocket.send(
                                """{"jsonrpc":"2.0","id":"$id","result":""" + serverResultJson + """}""",
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
                HermesRpcCaller.call(
                    RpcMethods.CONNECTORS_LIST,
                    ConnectorsListParams(
                        owner = ConnectorOwner.session("s1"),
                    ),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertEquals("connectors.list", (frame["method"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        val expectedParams =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "session")
                        put("session_id", "s1")
                    },
                )
            }
        assertEquals(expectedParams, params)
        assertEquals(serverResultJson, result)
    }
}
