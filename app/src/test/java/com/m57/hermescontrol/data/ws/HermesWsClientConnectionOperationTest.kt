package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import com.m57.hermescontrol.data.ws.contract.ConnectionAnswer
import com.m57.hermescontrol.data.ws.contract.ConnectionAnswerTarget
import com.m57.hermescontrol.data.ws.contract.ConnectionRespondParams
import com.m57.hermescontrol.data.ws.contract.ConnectorOwner
import com.m57.hermescontrol.data.ws.contract.ConnectorsOperationStatusParams
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

class HermesWsClientConnectionOperationTest {
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
    fun typedCallConnectionRespondWithApprovedEnvAndAccountOwnerEncodesFrameAndReturnsRawServerJsonUntouched() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put("status", "ok")
                put("settled", true)
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
                        if (name == "connection.respond") {
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
                    RpcMethods.CONNECTION_RESPOND,
                    ConnectionRespondParams(
                        owner = ConnectorOwner.account(),
                        opId = "op-approved-123",
                        result =
                            ConnectionAnswer(
                                targets =
                                    listOf(
                                        ConnectionAnswerTarget(
                                            name = "github",
                                            status = "approved",
                                            detail = "OAuth granted",
                                            env = mapOf("TOKEN" to "dummy_secret_token_abc"),
                                        ),
                                    ),
                                settledBy = null,
                            ),
                        profile = "prod",
                    ),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertFalse("Outgoing frames have NO jsonrpc field", frame.containsKey("jsonrpc"))
        assertEquals("connection.respond", (frame["method"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        val expectedParams =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "account")
                    },
                )
                put("op_id", "op-approved-123")
                put(
                    "result",
                    buildJsonObject {
                        put(
                            "targets",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("name", "github")
                                        put("status", "approved")
                                        put("detail", "OAuth granted")
                                        put(
                                            "env",
                                            buildJsonObject {
                                                put("TOKEN", "dummy_secret_token_abc")
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
                put("profile", "prod")
            }
        assertEquals(expectedParams, params)
        assertEquals(serverResultJson, result)
    }

    @Test
    fun typedCallConnectionRespondWithSkippedOmittedEnvAndSessionOwnerEncodesFrameAndReturnsRawServerJsonUntouched() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put("status", "ok")
                put("settled", false)
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
                        if (name == "connection.respond") {
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
                    RpcMethods.CONNECTION_RESPOND,
                    ConnectionRespondParams(
                        owner = ConnectorOwner.session("sess-skip-456"),
                        opId = "op-skip-456",
                        result =
                            ConnectionAnswer(
                                targets =
                                    listOf(
                                        ConnectionAnswerTarget(
                                            name = "slack",
                                            status = "skipped",
                                            detail = null,
                                            env = null,
                                        ),
                                    ),
                                settledBy = null,
                            ),
                        profile = null,
                    ),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertFalse("Outgoing frames have NO jsonrpc field", frame.containsKey("jsonrpc"))
        assertEquals("connection.respond", (frame["method"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        val expectedParams =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "session")
                        put("session_id", "sess-skip-456")
                    },
                )
                put("op_id", "op-skip-456")
                put(
                    "result",
                    buildJsonObject {
                        put(
                            "targets",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("name", "slack")
                                        put("status", "skipped")
                                    },
                                )
                            },
                        )
                    },
                )
            }
        assertEquals(expectedParams, params)
        assertFalse(params?.containsKey("profile") ?: true)
        assertEquals(serverResultJson, result)
    }

    @Test
    fun typedCallConnectionRespondWithContinueAndOmittedTargetsEncodesFrameAndReturnsRawServerJsonUntouched() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put("status", "ok")
                put("settled", true)
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
                        if (name == "connection.respond") {
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
                    RpcMethods.CONNECTION_RESPOND,
                    ConnectionRespondParams(
                        owner = ConnectorOwner.session("sess-continue-789"),
                        opId = "op-continue-789",
                        result =
                            ConnectionAnswer(
                                targets = null,
                                settledBy = "continue",
                            ),
                        profile = null,
                    ),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertFalse("Outgoing frames have NO jsonrpc field", frame.containsKey("jsonrpc"))
        assertEquals("connection.respond", (frame["method"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        val expectedParams =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "session")
                        put("session_id", "sess-continue-789")
                    },
                )
                put("op_id", "op-continue-789")
                put(
                    "result",
                    buildJsonObject {
                        put("settled_by", "continue")
                    },
                )
            }
        assertEquals(expectedParams, params)
        assertFalse(params?.containsKey("profile") ?: true)
        assertEquals(serverResultJson, result)
    }

    @Test
    fun typedCallConnectorsOperationWakeEncodesOwnerSessionOpIdAndProfileAndReturnsRawServerJsonUntouched() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put("op_id", "op-wake-777")
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
                        if (name == "connectors.operation.wake") {
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
                    RpcMethods.CONNECTORS_OPERATION_WAKE,
                    ConnectorsOperationStatusParams(
                        owner = ConnectorOwner.session("sess-wake-99"),
                        opId = "op-wake-777",
                        profile = "custom-profile",
                    ),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertFalse("Outgoing frames have NO jsonrpc field", frame.containsKey("jsonrpc"))
        assertEquals("connectors.operation.wake", (frame["method"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        val expectedParams =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "session")
                        put("session_id", "sess-wake-99")
                    },
                )
                put("op_id", "op-wake-777")
                put("profile", "custom-profile")
            }
        assertEquals(expectedParams, params)
        assertEquals(serverResultJson, result)
    }

    @Test
    fun hermesRpcCallerCallDelegatesForWakeWithAccountOwnerAndNullProfile() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put("op_id", "op-wake-888")
                put("status", "done")
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
                        if (name == "connectors.operation.wake") {
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
                    RpcMethods.CONNECTORS_OPERATION_WAKE,
                    ConnectorsOperationStatusParams(
                        owner = ConnectorOwner.account(),
                        opId = "op-wake-888",
                        profile = null,
                    ),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertEquals("connectors.operation.wake", (frame["method"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        val expectedParams =
            buildJsonObject {
                put(
                    "owner",
                    buildJsonObject {
                        put("type", "account")
                    },
                )
                put("op_id", "op-wake-888")
            }
        assertEquals(expectedParams, params)
        assertFalse(params?.containsKey("profile") ?: true)
        assertEquals(serverResultJson, result)
    }
}
