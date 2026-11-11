package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import com.m57.hermescontrol.data.remote.mockSystemServerTrust
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Sends the client-owned RPCs through the real [HermesWsClient] and checks the
 * exact frame on the wire against the vendored gateway contract (#1374).
 */
class GatewayContractFramesTest {
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        mockSystemServerTrust()

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
    }

    @After
    fun tearDown() {
        try {
            HermesWsClient.releaseExternalActivityConnectionLease()
            HermesWsClient.releaseBackgroundConnectionLease()
            HermesWsClient.disconnect(clearPendingMessages = true)
            HermesWsClient.awaitOpenSocketIdleForTest()
            runBlocking {
                withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.DISCONNECTED } }
            }
        } finally {
            try {
                mockWebServer.shutdown()
            } finally {
                unmockkAll()
            }
        }
    }

    /** Connect, run [trigger], and return the first frame the server sees for [method]. */
    private fun captureFrame(
        method: String,
        beforeConnect: () -> Unit = {},
        readyPayload: String? = null,
        capabilityResult: String = """{"canonical_clarify_batch":true}""",
        trigger: () -> Unit = {},
    ): JsonObject {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val capabilitiesReplied = CountDownLatch(1)
        val frame = AtomicReference<JsonObject?>(null)
        mockWebServer.enqueue(
            MockResponse().withClosingWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        if (readyPayload != null) {
                            webSocket.send(
                                """{"method":"event","params":{"type":"gateway.ready","payload":$readyPayload}}""",
                            )
                        }
                        opened.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        val json = OkHttpProvider.json.parseToJsonElement(text) as? JsonObject ?: return
                        val name = (json["method"] as? JsonPrimitive)?.content
                        if (name == WsMethods.CLIENT_CAPABILITIES) {
                            webSocket.send("""{"id":${json["id"]},"result":$capabilityResult}""")
                            capabilitiesReplied.countDown()
                        } else if (name == WsMethods.GATEWAY_PING) {
                            webSocket.send("""{"id":${json["id"]},"result":{}}""")
                        }
                        if (frame.get() == null && name == method) {
                            frame.set(json)
                            captured.countDown()
                        }
                    }
                },
            ),
        )
        beforeConnect()
        HermesWsClient.connect()
        runBlocking {
            withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } }
        }
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        if (readyPayload != null) {
            assertTrue("No capability negotiation", capabilitiesReplied.await(5, TimeUnit.SECONDS))
            // The ping reply follows the capability reply on the same socket.
            runBlocking { withTimeout(5000) { HermesWsClient.request(WsMethods.GATEWAY_PING).await() } }
        }
        trigger()
        assertTrue("No '$method' frame reached the server", captured.await(5, TimeUnit.SECONDS))
        return requireNotNull(frame.get())
    }

    private fun assertMatchesContract(frame: JsonObject) {
        val method = (frame["method"] as JsonPrimitive).content
        val params = frame["params"] as JsonObject
        val problems = GatewayContract.paramKeyProblems(method, params.keys)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun promptSubmitFrameMatchesContract() {
        assertMatchesContract(
            captureFrame(WsMethods.PROMPT_SUBMIT) { HermesWsClient.sendMessage("s1", "hello", queued = true) },
        )
    }

    @Test
    fun legacyPromptOmitsClarifyBatchDespiteAdvertisedCapability() {
        val frame =
            captureFrame(WsMethods.PROMPT_SUBMIT, readyPayload = "{}") {
                HermesWsClient.sendMessage("s1", "hello", queued = true)
            }
        assertTrue(HermesWsClient.canonicalClarifyBatch)
        val params = frame["params"] as JsonObject
        assertFalse("Legacy prompt.submit must omit clarify_batch", params.containsKey("clarify_batch"))
    }

    @Test
    fun promptClarifyBatchRequiresBothRouteAuthorityAndCapability() {
        for (route in listOf("true", "false", null)) {
            for (capability in listOf("true", "false", null, "\"true\"")) {
                HermesWsClient.disconnect(clearPendingMessages = true)
                val frame =
                    captureFrame(
                        WsMethods.PROMPT_SUBMIT,
                        readyPayload = route?.let { """{"session_authority":$it}""" } ?: "{}",
                        capabilityResult = capability?.let { """{"canonical_clarify_batch":$it}""" } ?: "{}",
                    ) { HermesWsClient.sendMessage("s1", "hello", queued = true) }
                val params = frame["params"] as JsonObject
                assertEquals(
                    "route=$route capability=$capability",
                    route == "true" && capability == "true",
                    params.containsKey("clarify_batch"),
                )
                if (params.containsKey("clarify_batch")) assertEquals(JsonPrimitive(true), params["clarify_batch"])
                assertEquals(JsonPrimitive("s1"), params["session_id"])
                assertEquals(JsonPrimitive("hello"), params["text"])
                assertEquals(JsonPrimitive(true), params["queued"])
                assertEquals(capability == "true", HermesWsClient.canonicalClarifyBatch)
                assertMatchesContract(frame)
            }
        }
    }

    @Test
    fun sessionAuthorityPromptCarriesSubmissionId() {
        val frame =
            captureFrame(WsMethods.PROMPT_SUBMIT, readyPayload = """{"session_authority":true}""") {
                HermesWsClient.sendMessage("s1", "hello")
            }
        val params = frame["params"] as JsonObject
        val id = (params["submission_id"] as? JsonPrimitive)?.content
        assertTrue("session-authority prompt.submit needs a submission_id", !id.isNullOrEmpty())
        assertEquals(id, UUID.fromString(id).toString())
        assertMatchesContract(frame)
    }

    @Test
    fun legacyPromptOmitsSubmissionId() {
        for (ready in listOf("{}", """{"session_authority":false}""")) {
            HermesWsClient.disconnect(clearPendingMessages = true)
            val frame =
                captureFrame(WsMethods.PROMPT_SUBMIT, readyPayload = ready) {
                    HermesWsClient.sendMessage("s1", "hello")
                }
            assertFalse(ready, (frame["params"] as JsonObject).containsKey("submission_id"))
        }
    }

    @Test
    fun usageIsRequestedAsSessionStatsOnlyOnTheAuthorityRoute() {
        val stats =
            captureFrame(WsMethods.SESSION_STATS, readyPayload = """{"session_authority":true}""") {
                HermesWsClient.request(WsMethods.SESSION_USAGE, mapOf("session_id" to "s1"))
            }
        assertEquals(WsMethods.SESSION_STATS, (stats["method"] as JsonPrimitive).content)
        HermesWsClient.disconnect(clearPendingMessages = true)
        val legacy =
            captureFrame(WsMethods.SESSION_USAGE, readyPayload = "{}") {
                HermesWsClient.request(WsMethods.SESSION_USAGE, mapOf("session_id" to "s1"))
            }
        assertEquals(WsMethods.SESSION_USAGE, (legacy["method"] as JsonPrimitive).content)
    }

    @Test
    fun eventsSinceReplayFrameMatchesContract() {
        assertMatchesContract(
            captureFrame(WsMethods.SESSION_EVENTS_SINCE, beforeConnect = { HermesWsClient.setSeqWatermark("s1", 5) }),
        )
    }
}
