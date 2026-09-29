package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.DashboardSessionTokenRefresher
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
    }

    @After
    fun tearDown() {
        HermesWsClient.releaseExternalActivityConnectionLease()
        HermesWsClient.releaseBackgroundConnectionLease()
        HermesWsClient.disconnect(clearPendingMessages = true)
        runBlocking {
            withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.DISCONNECTED } }
        }
        runCatching { mockWebServer.shutdown() }
        unmockkAll()
    }

    /** Connect, run [trigger], and return the first frame the server sees for [method]. */
    private fun captureFrame(
        method: String,
        beforeConnect: () -> Unit = {},
        trigger: () -> Unit = {},
    ): JsonObject {
        val opened = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val frame = AtomicReference<JsonObject?>(null)
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
    fun eventsSinceReplayFrameMatchesContract() {
        assertMatchesContract(
            captureFrame(WsMethods.SESSION_EVENTS_SINCE, beforeConnect = { HermesWsClient.setSeqWatermark("s1", 5) }),
        )
    }
}
