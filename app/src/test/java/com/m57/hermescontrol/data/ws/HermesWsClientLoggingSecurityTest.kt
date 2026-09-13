package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class HermesWsClientLoggingSecurityTest {
    private val capturedDebugLogs = Collections.synchronizedList(mutableListOf<String>())
    private val capturedInfoLogs = Collections.synchronizedList(mutableListOf<String>())
    private val capturedWarnLogs = Collections.synchronizedList(mutableListOf<String>())
    private val capturedErrorLogs = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        capturedDebugLogs.clear()
        capturedInfoLogs.clear()
        capturedWarnLogs.clear()
        capturedErrorLogs.clear()

        mockkStatic(Log::class)
        every { Log.v(any<String>(), any<String>()) } answers {
            capturedDebugLogs.add(secondArg())
            0
        }
        every { Log.d(any<String>(), any<String>()) } answers {
            capturedDebugLogs.add(secondArg())
            0
        }
        every { Log.i(any<String>(), any<String>()) } answers {
            capturedInfoLogs.add(secondArg())
            0
        }
        every { Log.w(any<String>(), any<String>()) } answers {
            capturedWarnLogs.add(secondArg())
            0
        }
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } answers {
            capturedWarnLogs.add(secondArg())
            0
        }
        every { Log.e(any<String>(), any<String>()) } answers {
            capturedErrorLogs.add(secondArg())
            0
        }
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } answers {
            capturedErrorLogs.add(secondArg())
            0
        }

        mockkObject(AuthManager)
        every { AuthManager.activeProfileId } returns MutableStateFlow<String?>(null)
        every { AuthManager.isAutoReconnect() } returns false
        every { AuthManager.getSessionCookie() } returns null
        every { AuthManager.wsUrl() } returns "ws://127.0.0.1:9119/"

        CookieManager.setJarForTest(buildFakePersistentCookieJar())

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as AtomicBoolean).set(false)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as AtomicBoolean).set(false)

        val generationField = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        generationField.isAccessible = true
        (generationField.get(HermesWsClient) as AtomicInteger).set(1)

        HermesWsClient.rejectAllPending()
        HermesWsClient.disconnect(clearPendingMessages = true)
        HermesWsClient.releaseExternalActivityConnectionLease()
        HermesWsClient.setAppForeground(true)
    }

    @After
    fun tearDown() {
        HermesWsClient.rejectAllPending()
        HermesWsClient.releaseExternalActivityConnectionLease()
        HermesWsClient.disconnect(clearPendingMessages = true)
        unmockkAll()
    }

    private fun createWsListener(generation: Int? = null): WebSocketListener {
        val targetGen =
            generation ?: run {
                val generationField = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
                generationField.isAccessible = true
                (generationField.get(HermesWsClient) as AtomicInteger).get()
            }
        val listenerClass = Class.forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
        val constructor = listenerClass.declaredConstructors.single()
        constructor.isAccessible = true
        return constructor.newInstance(targetGen) as WebSocketListener
    }

    private fun attachMockSocket(): WebSocket {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns true

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, socket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as AtomicBoolean).set(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as AtomicBoolean).set(false)

        return socket
    }

    @Suppress("UNCHECKED_CAST")
    private fun getPendingCalls(): ConcurrentHashMap<String, Any> {
        val field = HermesWsClient::class.java.getDeclaredField("pendingCalls")
        field.isAccessible = true
        return field.get(HermesWsClient) as ConcurrentHashMap<String, Any>
    }

    private fun getTimeoutJob(pendingCall: Any): Job? {
        val field = pendingCall.javaClass.getDeclaredField("timeoutJob")
        field.isAccessible = true
        return field.get(pendingCall) as? Job
    }

    // ── Formatter unit tests ──────────────────────────────────────────────────

    @Test
    fun `formatSafeOutgoingFrameLog does not leak payload parameters or urls`() {
        val sensitiveUrl = "https://example.com/oauth/authorize?token=secret123"
        val log =
            HermesWsClient.formatSafeOutgoingFrameLog(
                id = "req-42",
                method = WsMethods.CONNECTORS_CONNECT,
                paramsKeys = listOf("session_id", "connectors", "reconnect"),
                byteLength = 256,
                queued = false,
            )

        assertFalse("Log must not contain sensitive URL", log.contains(sensitiveUrl))
        assertFalse("Log must not contain 'secret'", log.contains("secret"))
        assertTrue("Log should contain id", log.contains("id=req-42"))
        assertTrue("Log should contain method", log.contains("method=connectors.connect"))
        assertTrue(
            "Log should contain parameter keys only",
            log.contains("paramsKeys=[session_id,connectors,reconnect]"),
        )
        assertTrue("Log should contain byte length", log.contains("bytes=256"))
    }

    @Test
    fun `formatSafeQueuedFrameLog does not leak sensitive payload`() {
        val sensitiveUrl = "https://auth.provider.com/login?key=supersecret"
        val rawJson =
            buildJsonObject {
                put("id", "q-1")
                put("method", "connectors.connect")
                put(
                    "params",
                    buildJsonObject {
                        put("session_id", "sid-1")
                        put("secret_url", sensitiveUrl)
                    },
                )
            }.toString()

        val log = HermesWsClient.formatSafeQueuedFrameLog(rawJson)

        assertFalse("Queued frame log must not leak sensitive url", log.contains(sensitiveUrl))
        assertFalse("Queued frame log must not contain 'supersecret'", log.contains("supersecret"))
        assertTrue("Queued frame log should mark as queued", log.contains("→ (queued)"))
        assertTrue("Queued frame log should show method", log.contains("method=connectors.connect"))
    }

    @Test
    fun `formatSafeIncomingFrameLog does not leak connect_url or token from response`() {
        val sensitiveAuthUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=123&token=abc_secret_token"
        val responseJson =
            """
            {
                "jsonrpc": "2.0",
                "id": "req-99",
                "result": {
                    "results": [
                        {
                            "connector": "google",
                            "status": "initiated",
                            "connect_url": "$sensitiveAuthUrl",
                            "instruction": "Open this link in your browser to sign in"
                        }
                    ],
                    "summary": {"total": 1}
                }
            }
            """.trimIndent()

        val log = HermesWsClient.formatSafeIncomingFrameLog(responseJson)

        assertFalse("Incoming frame log must not leak sensitive auth url", log.contains(sensitiveAuthUrl))
        assertFalse("Incoming frame log must not leak secret token", log.contains("abc_secret_token"))
        assertFalse("Incoming frame log must not leak instructions", log.contains("Open this link"))
        assertTrue("Incoming frame log should contain id", log.contains("id=req-99"))
        assertTrue("Incoming frame log should summarize result keys", log.contains("result=[results,summary]"))
    }

    @Test
    fun `formatSafeIncomingFrameLog handles malformed or non-object json safely`() {
        val log1 = HermesWsClient.formatSafeIncomingFrameLog("plain string not json")
        assertTrue(log1.startsWith("← frame bytes="))

        val log2 = HermesWsClient.formatSafeIncomingFrameLog("[1, 2, 3]")
        assertTrue(log2.startsWith("← non-object frame bytes="))
    }

    // ── Real inbound WsListenerImpl.onMessage path regression tests ───────────

    @Test
    fun `inbound onMessage delivers result with connect_url while Log_d redacts secrets`() {
        val socket = attachMockSocket()
        val listener = createWsListener()

        val deferred = HermesWsClient.request("connectors.connect", mapOf("connector" to "synthetic_provider"))
        val pendingCalls = getPendingCalls()
        val reqId = pendingCalls.keys.firstOrNull()
        assertNotNull("Pending call ID must exist", reqId)

        val sensitiveAuthUrl =
            "https://auth.synthetic-fixture.local/oauth/authorize?code=fixture-code-99&token=fixture-secret-token-xyz"
        val responseJson =
            """
            {
                "jsonrpc": "2.0",
                "id": "$reqId",
                "result": {
                    "status": "initiated",
                    "connect_url": "$sensitiveAuthUrl"
                }
            }
            """.trimIndent()

        listener.onMessage(socket, responseJson)

        assertTrue("Deferred request must be completed", deferred.isCompleted)
        val result = runBlocking { deferred.await() }
        assertTrue("Result must be delivered as JsonObject", result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(
            sensitiveAuthUrl,
            (resultObj["connect_url"] as? JsonPrimitive)?.content,
        )

        val allLogs = capturedDebugLogs + capturedInfoLogs + capturedWarnLogs + capturedErrorLogs
        for (log in allLogs) {
            assertFalse("Captured log must not leak sensitive auth URL: $log", log.contains(sensitiveAuthUrl))
            assertFalse("Captured log must not leak secret token: $log", log.contains("fixture-secret-token-xyz"))
            assertFalse("Captured log must not leak fixture code: $log", log.contains("fixture-code-99"))
            assertFalse(
                "Captured log must not leak synthetic auth host: $log",
                log.contains("https://auth.synthetic-fixture.local"),
            )
        }

        assertTrue(
            "Log.d must record safe sanitized incoming frame log with id and sanitized keys",
            capturedDebugLogs.any {
                it.contains("id=$reqId") && (
                    it.contains("result=[status,connect_url]") ||
                        it.contains("result=[connect_url,status]")
                )
            },
        )
    }

    @Test
    fun `inbound onMessage rpc error 4001 NOT_OWNER completes deferred exceptionally and preserves shared events`() {
        val socket = attachMockSocket()
        val listener = createWsListener()

        val capturedEvents = Collections.synchronizedList(mutableListOf<WsEvent>())
        val subscribedLatch = CountDownLatch(1)
        val errorEventLatch = CountDownLatch(1)
        var receivedErrorEvent: WsEvent.RpcError? = null

        val collectJob =
            CoroutineScope(Dispatchers.IO).launch {
                HermesWsClient.events
                    .onSubscription { subscribedLatch.countDown() }
                    .collect {
                        capturedEvents.add(it)
                        if (it is WsEvent.RpcError) {
                            receivedErrorEvent = it
                            errorEventLatch.countDown()
                        }
                    }
            }

        assertTrue("Event collector must subscribe", subscribedLatch.await(3, TimeUnit.SECONDS))

        val deferred = HermesWsClient.request("session.steer", mapOf("session_id" to "sess-ownership-4001"))
        val pendingCalls = getPendingCalls()
        val reqId = pendingCalls.keys.firstOrNull()
        assertNotNull("Pending call ID must exist", reqId)

        val errorJson =
            """
            {
                "jsonrpc": "2.0",
                "id": "$reqId",
                "error": {
                    "code": 4001,
                    "message": "Access denied: session ownership required",
                    "data": {
                        "reason": "NOT_OWNER",
                        "session_id": "sess-ownership-4001"
                    }
                }
            }
            """.trimIndent()

        listener.onMessage(socket, errorJson)

        assertTrue("Deferred must be completed", deferred.isCompleted)
        val thrown = runCatching { runBlocking { deferred.await() } }.exceptionOrNull()
        assertTrue(
            "Deferred must complete exceptionally with HermesRpcException",
            thrown is HermesWsClient.HermesRpcException,
        )
        val rpcException = thrown as HermesWsClient.HermesRpcException
        assertEquals(4001, rpcException.code)
        assertEquals("Access denied: session ownership required", rpcException.message)
        assertNotNull("Exception data must be preserved", rpcException.data)
        assertTrue("Exception data must be JsonObject", rpcException.data is JsonObject)
        val dataObj = rpcException.data as JsonObject
        assertEquals("NOT_OWNER", (dataObj["reason"] as? JsonPrimitive)?.content)
        assertEquals("sess-ownership-4001", (dataObj["session_id"] as? JsonPrimitive)?.content)

        assertTrue("Shared events flow must receive RpcError event", errorEventLatch.await(3, TimeUnit.SECONDS))
        collectJob.cancel()

        assertNotNull("Captured RpcError event must exist", receivedErrorEvent)
        assertEquals(reqId, receivedErrorEvent!!.id)
        assertEquals(4001, receivedErrorEvent!!.error.code)
        assertEquals("Access denied: session ownership required", receivedErrorEvent!!.error.message)
        val eventDataObj = receivedErrorEvent!!.error.data as? JsonObject
        assertNotNull("Event error data must be JsonObject", eventDataObj)
        assertEquals("NOT_OWNER", (eventDataObj!!["reason"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `inbound onMessage malformed frame logs safely without leaking payload or secrets`() {
        val socket = attachMockSocket()
        val listener = createWsListener()

        val subscribedLatch = CountDownLatch(1)
        val unknownEventLatch = CountDownLatch(1)
        var receivedUnknownEvent: WsEvent.Unknown? = null

        val collectJob =
            CoroutineScope(Dispatchers.IO).launch {
                HermesWsClient.events
                    .onSubscription { subscribedLatch.countDown() }
                    .collect {
                        if (it is WsEvent.Unknown) {
                            receivedUnknownEvent = it
                            unknownEventLatch.countDown()
                        }
                    }
            }

        assertTrue("Event collector must subscribe", subscribedLatch.await(3, TimeUnit.SECONDS))
        capturedDebugLogs.clear()
        capturedErrorLogs.clear()

        val sensitiveSecret = "synthetic-secret-token-malformed-frame-777"
        val sensitiveUrl = "https://auth.synthetic-trap.local/oauth/secret"
        val malformedPayload = """{"token": "$sensitiveSecret", "url": "$sensitiveUrl", [syntax-broken-json]}"""

        listener.onMessage(socket, malformedPayload)

        assertTrue("Unknown event must be emitted for malformed frame", unknownEventLatch.await(3, TimeUnit.SECONDS))
        collectJob.cancel()

        assertNotNull(receivedUnknownEvent)
        assertEquals(malformedPayload, receivedUnknownEvent!!.raw)

        val allLogs = capturedDebugLogs + capturedInfoLogs + capturedWarnLogs + capturedErrorLogs
        for (log in allLogs) {
            assertFalse("Logs must not contain sensitive secret token: $log", log.contains(sensitiveSecret))
            assertFalse("Logs must not contain sensitive auth URL: $log", log.contains(sensitiveUrl))
        }

        assertTrue(
            "Log.e must record parse failure without leaking secrets",
            capturedErrorLogs.any { it.startsWith("Failed to parse message:") },
        )
    }

    @Test
    fun `cancelling pending deferred cleans pending requests and cancels timeout job`() {
        attachMockSocket()
        val deferred = HermesWsClient.request("test.cancellation.method", mapOf("synthetic" to "fixture"))
        val pendingCalls = getPendingCalls()
        val entry =
            pendingCalls.entries.firstOrNull {
                it.value.javaClass.name
                    .contains("PendingCall")
            }
        assertNotNull("Pending call must be registered in pendingCalls map", entry)
        val reqId = entry!!.key
        val timeoutJob = getTimeoutJob(entry.value)
        assertNotNull("Timeout job must be initialized", timeoutJob)
        assertTrue("Timeout job must be active while request is pending", timeoutJob!!.isActive)

        deferred.cancel()

        assertTrue("Deferred must be cancelled", deferred.isCancelled)
        assertFalse(
            "Pending call must be removed from pendingCalls map on cancellation",
            pendingCalls.containsKey(reqId),
        )
        assertTrue("Timeout job must be cancelled on deferred cancellation", timeoutJob.isCancelled)
    }
}
