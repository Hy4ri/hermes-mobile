package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import com.m57.hermescontrol.data.ws.contract.EmptyParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.SubscriptionChangeParams
import com.m57.hermescontrol.data.ws.contract.VaultLockParams
import com.m57.hermescontrol.data.ws.contract.VaultUnlockParams
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

class HermesWsClientBillingVaultTest {
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
    fun typedCallSubscriptionChangeEncodesOnlyNonNullFieldsAndReturnsRawServerJsonUntouched() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put("status", "ok")
                put("subscription_id", "sub-12345")
                put("scheduled_cancellation", false)
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
                        if (name == "subscription.change") {
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
                    RpcMethods.SUBSCRIPTION_CHANGE,
                    SubscriptionChangeParams(
                        subscriptionTypeId = null,
                        cancel = false,
                    ),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertFalse("Outgoing frames have NO jsonrpc field", frame.containsKey("jsonrpc"))
        assertEquals("subscription.change", (frame["method"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        val expectedParams =
            buildJsonObject {
                put("cancel", false)
            }
        assertEquals(expectedParams, params)
        assertEquals(false, (params?.get("cancel") as? JsonPrimitive)?.booleanOrNull)
        assertFalse(params?.containsKey("subscription_type_id") ?: true)
        assertEquals(serverResultJson, result)
    }

    @Test
    fun typedCallVaultUnlockPutsNameAndPasswordOnWireAndReturnsRawServerJsonUntouched() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put("unlocked", true)
                put("expires_in", 3600)
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
                        if (name == "vault.unlock") {
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
                    RpcMethods.VAULT_UNLOCK,
                    VaultUnlockParams(
                        name = "op-work",
                        password = "super-secret-pass",
                    ),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertEquals("vault.unlock", (frame["method"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        val expectedParams =
            buildJsonObject {
                put("name", "op-work")
                put("password", "super-secret-pass")
            }
        assertEquals(expectedParams, params)
        assertEquals(serverResultJson, result)
    }

    @Test
    fun typedCallVaultLockWithNamePutsNameOnWireAndReturnsRawServerJsonUntouched() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put("locked", true)
                put("vault", "op-work")
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
                        if (name == "vault.lock") {
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
                    RpcMethods.VAULT_LOCK,
                    VaultLockParams(name = "op-work"),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertEquals("vault.lock", (frame["method"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        val expectedParams =
            buildJsonObject {
                put("name", "op-work")
            }
        assertEquals(expectedParams, params)
        assertEquals(serverResultJson, result)
    }

    @Test
    fun typedCallVaultLockWithoutNameLeavesParamsAbsentOrEmptyAndReturnsRawServerJsonUntouched() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put("locked_all", true)
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
                        if (name == "vault.lock") {
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
                    RpcMethods.VAULT_LOCK,
                    VaultLockParams(name = null),
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertEquals("vault.lock", (frame["method"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        assertTrue(
            "vault.lock with null name carries no params",
            params == null || params.isEmpty(),
        )
        assertEquals(serverResultJson, result)
    }

    @Test
    fun typedCallEmptyParamsMethodLeavesParamsAbsentOrEmptyAndReturnsRawServerJsonUntouched() {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frameRef = AtomicReference<JsonObject?>(null)

        val serverResultJson =
            buildJsonObject {
                put("status", "active")
                put("tier", "standard")
                put("valid_until", "2026-12-31T23:59:59Z")
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
                        if (name == "subscription.state") {
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
                    RpcMethods.SUBSCRIPTION_STATE,
                    EmptyParams,
                )
            }

        assertTrue("Frame must reach the server", captured.await(5, TimeUnit.SECONDS))
        val frame = requireNotNull(frameRef.get())

        assertEquals("subscription.state", (frame["method"] as? JsonPrimitive)?.content)

        val params = frame["params"] as? JsonObject
        assertTrue(
            "subscription.state with EmptyParams carries no params",
            params == null || params.isEmpty(),
        )
        assertEquals(serverResultJson, result)
    }
}
