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
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Characterization test suite capturing existing baseline behaviors and edge cases
 * of [HermesWsClient] before extracting components in issue #1376.
 */
class HermesWsClientCharacterizationTest {
    private lateinit var mockWebServer: MockWebServer
    private val serverSockets = Collections.synchronizedList(mutableListOf<WebSocket>())
    private lateinit var serverClosed: CountDownLatch

    @Before
    fun setUp() {
        serverSockets.clear()
        serverClosed = CountDownLatch(1)
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
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

        HermesWsClient.connectedForTest.set(false)
        HermesWsClient.connectionStatusForTest.value = ConnectionStatus.DISCONNECTED
        HermesWsClient.messageQueueForTest.clear()
        HermesWsClient.disconnect(clearPendingMessages = true)
        HermesWsClient.releaseExternalActivityConnectionLease()
        HermesWsClient.releaseBackgroundConnectionLease()
        HermesWsClient.setAppForeground(true)
        HermesWsClient.acceptQueuedMessagesForTest.set(true)
        HermesWsClient.clearCredentialWarning()
        HermesWsClient.clearSeqWatermarks()
        HermesWsClient.setLastLatencyForTest(null)
    }

    @After
    fun tearDown() {
        HermesWsClient.rejectAllPending()
        HermesWsClient.releaseExternalActivityConnectionLease()
        HermesWsClient.releaseBackgroundConnectionLease()
        HermesWsClient.disconnect(clearPendingMessages = true)
        runBlocking {
            withTimeout(3000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.DISCONNECTED }
            }
        }
        try {
            if (serverSockets.isNotEmpty()) {
                assertTrue("Server endpoint must finish closing", serverClosed.await(3, TimeUnit.SECONDS))
            }
            mockWebServer.shutdown()
        } finally {
            unmockkAll()
        }
    }

    private fun connectClientToServer(onServerOpen: ((WebSocket) -> Unit)? = null): WebSocket {
        val serverOpenLatch = CountDownLatch(1)
        var serverWs: WebSocket? = null

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        serverClosed.countDown()
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: okhttp3.Response?,
                    ) {
                        serverClosed.countDown()
                    }

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverSockets.add(webSocket)
                        serverWs = webSocket
                        onServerOpen?.invoke(webSocket)
                        serverOpenLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
        }
        assertTrue("Server WebSocket should open", serverOpenLatch.await(5, TimeUnit.SECONDS))
        return requireNotNull(serverWs)
    }

    @Test
    fun credentialWarning_updatedByGatewayReadyAndSessionInfo_retainedOnBlank_clearedExplicitly() {
        assertNull(HermesWsClient.credentialWarning.value)

        HermesWsClient.intentionalCloseForTest.set(false)
        HermesWsClient.connectedForTest.set(true)
        val dummySocket =
            mockk<WebSocket>(relaxed = true) {
                every { send(any<String>()) } returns true
            }
        HermesWsClient.webSocketForTest = dummySocket
        val generation = HermesWsClient.connectionGenerationForTest.get()
        val listener = HermesWsClient.createListenerForTest(generation)

        // 1. GatewayReady with non-blank credential_warning updates flow
        val gatewayReadyFrame =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"credential_warning":"Warning: Auth key expired"}}}"""
        listener.onMessage(dummySocket, gatewayReadyFrame)

        runBlocking {
            withTimeout(3000) {
                HermesWsClient.credentialWarning.first { it == "Warning: Auth key expired" }
            }
        }
        assertEquals("Warning: Auth key expired", HermesWsClient.credentialWarning.value)

        // 2. Blank or absent credential_warning does NOT clear the existing value.
        // Deliver follow-up gateway.ready (absent) after blank and check warning is retained,
        // then use session.info barrier to prove processing pipeline is drained.
        val blankFrame =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"credential_warning":""}}}"""
        listener.onMessage(dummySocket, blankFrame)
        assertEquals("Warning: Auth key expired", HermesWsClient.credentialWarning.value)

        val absentFrame =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0"}}}"""
        listener.onMessage(dummySocket, absentFrame)
        assertEquals("Warning: Auth key expired", HermesWsClient.credentialWarning.value)

        // Barrier: a distinct non-blank session.info proves all preceding frames were processed in order
        val barrierFrame =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"session.info","session_id":"s-barrier","payload":{"credential_warning":"Barrier reached"}}}"""
        listener.onMessage(dummySocket, barrierFrame)
        runBlocking {
            withTimeout(3000) {
                HermesWsClient.credentialWarning.first { it == "Barrier reached" }
            }
        }
        assertEquals("Barrier reached", HermesWsClient.credentialWarning.value)

        // 3. SessionInfo with non-blank updates warning
        val sessionInfoFrame =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"session.info","session_id":"s1","payload":{"credential_warning":"Session warning"}}}"""
        listener.onMessage(dummySocket, sessionInfoFrame)

        runBlocking {
            withTimeout(3000) {
                HermesWsClient.credentialWarning.first { it == "Session warning" }
            }
        }
        assertEquals("Session warning", HermesWsClient.credentialWarning.value)

        // 4. Explicit clear resets to null
        HermesWsClient.clearCredentialWarning()
        assertNull(HermesWsClient.credentialWarning.value)
    }

    @Test
    fun disconnect_resetsLastLatencyMsToNull() {
        HermesWsClient.setLastLatencyForTest(42L)
        assertEquals(42L, HermesWsClient.lastLatencyMs.value)

        HermesWsClient.disconnect()
        assertNull(HermesWsClient.lastLatencyMs.value)
    }

    @Test
    fun rpcTimeout_emitsHermesRpcExceptionCodeMinusOneExactMessage() {
        val serverWs = connectClientToServer()
        assertNotNull(serverWs)

        val shortTimeoutMs = 150L
        val deferred = HermesWsClient.request("custom.test_method", emptyMap(), timeoutMs = shortTimeoutMs)

        try {
            runBlocking {
                withTimeout(3000) {
                    deferred.await()
                }
            }
            fail("Expected deferred to fail with timeout exception")
        } catch (e: HermesWsClient.HermesRpcException) {
            assertEquals(-1, e.code)
            assertEquals("Request timed out: custom.test_method", e.message)
        }
    }

    @Test
    fun rejectAllPending_failsRequestsWithCustomException_cancelsTimeout_clearsPending() {
        connectClientToServer()

        val deferred = HermesWsClient.request("custom.hold", emptyMap(), timeoutMs = 10_000L)
        val pendingIds = HermesWsClient.pendingCallIdsForTest()
        assertEquals(1, pendingIds.size)
        val reqId = pendingIds.first()

        val timeoutJob = HermesWsClient.pendingCallTimeoutForTest(reqId)
        assertNotNull("Timeout job should be scheduled", timeoutJob)
        assertTrue("Timeout job should be active", timeoutJob?.isActive == true)

        val customException = HermesWsClient.HermesRpcException("Custom forced rejection", code = -99)
        HermesWsClient.rejectAllPending(customException)

        assertTrue(
            "Pending map should be empty after rejectAllPending",
            HermesWsClient.pendingCallIdsForTest().isEmpty(),
        )
        assertTrue("Timeout job should be cancelled", timeoutJob?.isCancelled == true)
        assertTrue("Deferred should be completed exceptionally", deferred.isCompleted)

        try {
            runBlocking { deferred.await() }
            fail("Expected custom exception")
        } catch (e: HermesWsClient.HermesRpcException) {
            assertEquals(-99, e.code)
            assertEquals("Custom forced rejection", e.message)
        }
    }

    @Test
    fun reconnectForNetworkChange_rejectsPendingCallsImmediately() {
        connectClientToServer()

        val deferred = HermesWsClient.request("custom.in_flight", emptyMap(), timeoutMs = 30_000L)
        assertEquals(1, HermesWsClient.pendingCallIdsForTest().size)

        // Network change triggers immediate rejectAllPending
        HermesWsClient.reconnectForNetworkChange(networkAvailable = false)

        assertTrue("Pending map should be empty after network change", HermesWsClient.pendingCallIdsForTest().isEmpty())
        assertTrue("Deferred must be completed", deferred.isCompleted)

        try {
            runBlocking { deferred.await() }
            fail("Expected pending call to be rejected on network change")
        } catch (e: HermesWsClient.HermesRpcException) {
            assertEquals("Connection lost — request cancelled", e.message)
        }
    }

    @Test
    fun disconnectAndListenerClosed_doNotRejectAllPending_deferredRemainsPendingUntilTimeoutOrReject() {
        val serverWs = connectClientToServer()

        val shortTimeoutMs = 300L
        val deferred = HermesWsClient.request("custom.deferred_call", emptyMap(), timeoutMs = shortTimeoutMs)
        val reqId = HermesWsClient.pendingCallIdsForTest().first()

        // Drive onClosed on the CURRENT active generation before disconnect.
        // In onClosed: isCurrent() is true (intentionalClose is still false, generation matches).
        // This exercises the active listener onClosed branch.
        val activeGeneration = HermesWsClient.connectionGenerationForTest.get()
        val currentListener = HermesWsClient.createListenerForTest(activeGeneration)
        val activeClientSocket = HermesWsClient.webSocketForTest ?: serverWs

        currentListener.onClosed(activeClientSocket, 1000, "Normal closure")
        activeClientSocket.cancel()

        // Deferred remains pending after active onClosed
        assertFalse("Deferred should still remain pending after onClosed", deferred.isCompleted)
        assertTrue("Request ID should still be in pendingCalls", HermesWsClient.pendingCallIdsForTest().contains(reqId))

        // Calling disconnect() does NOT invoke rejectAllPending() either
        HermesWsClient.disconnect()

        assertFalse("Deferred should NOT be completed immediately by disconnect() alone", deferred.isCompleted)
        assertTrue("Request ID should still be in pendingCalls", HermesWsClient.pendingCallIdsForTest().contains(reqId))

        // Eventually, the per-request timeout triggers and resolves it
        try {
            runBlocking {
                withTimeout(2000) {
                    deferred.await()
                }
            }
            fail("Deferred should fail with timeout")
        } catch (e: HermesWsClient.HermesRpcException) {
            assertEquals(-1, e.code)
            assertEquals("Request timed out: custom.deferred_call", e.message)
        }
    }

    @Test
    fun combinedExternalAndBackgroundLeases_releaseOneKeepsOther_releaseBothDisconnectsWhenBackgrounded() {
        connectClientToServer()
        assertTrue(HermesWsClient.isConnected)

        // Acquire both leases
        HermesWsClient.acquireExternalActivityConnectionLease()
        HermesWsClient.acquireBackgroundConnectionLease()
        assertTrue(HermesWsClient.hasBackgroundConnectionLease())

        // Move to background - should NOT disconnect because leases are active
        HermesWsClient.setAppForeground(false)
        assertTrue("Should stay connected while leases are active", HermesWsClient.isConnected)

        // Release external lease - background lease still holds connection open
        HermesWsClient.releaseExternalActivityConnectionLease()
        assertTrue("Should stay connected with background lease active", HermesWsClient.isConnected)

        // Release background lease - both leases released, app backgrounded, idle -> disconnects
        HermesWsClient.releaseBackgroundConnectionLease()
        assertFalse("Should disconnect when all leases released in background", HermesWsClient.isConnected)
    }

    @Test
    fun delayedReplayAndLiveRace_incomingSeqBufferedBeforeReplayResponseAndDeduped() {
        val sessionId = "session-characterization-race"
        val serverOpenLatch = CountDownLatch(1)
        val replayReceivedLatch = CountDownLatch(1)
        val allowReplayResponseLatch = CountDownLatch(1)
        val capturedReplayId = AtomicReference<String>()
        var serverSocketRef: WebSocket? = null

        // Enqueue MockResponse with WebSocketListener on MockWebServer
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        serverClosed.countDown()
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: okhttp3.Response?,
                    ) {
                        serverClosed.countDown()
                    }

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverSockets.add(webSocket)
                        serverSocketRef = webSocket
                        serverOpenLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (text.contains(WsMethods.SESSION_EVENTS_SINCE)) {
                            val request = OkHttpProvider.json.parseToJsonElement(text) as JsonObject
                            val id = (request["id"] as JsonPrimitive).content
                            capturedReplayId.set(id)
                            replayReceivedLatch.countDown()

                            if (allowReplayResponseLatch.await(5, TimeUnit.SECONDS)) {
                                val rpcId = requireNotNull(capturedReplayId.get())
                                val idJson = JsonPrimitive(rpcId).toString()
                                val replayResponse =
                                    """{"jsonrpc":"2.0","id":$idJson,"result":{"epoch":"epoch-1","latest_seq":12,"events":[{"type":"message.complete","session_id":"$sessionId","seq":11,"payload":{"text":"Replayed message 11"}},{"type":"message.complete","session_id":"$sessionId","seq":12,"payload":{"text":"Replayed message 12"}}]}}"""
                                webSocket.send(replayResponse)
                            }
                        }
                    }
                },
            ),
        )

        // Set watermark at seq 10 and epoch-1 prior to connect
        HermesWsClient.setSeqWatermark(sessionId, 10)
        HermesWsClient.setReplayEpochForTest("epoch-1")

        // Prepare subscription with onSubscription latch before stimulus
        val collectedEvents = Collections.synchronizedList(mutableListOf<WsEvent>())
        val subscriptionLatch = CountDownLatch(1)
        val collectLatch = CountDownLatch(2)
        val liveProcessedLatch = CountDownLatch(1)

        val collectorJob =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                HermesWsClient.events
                    .onSubscription {
                        subscriptionLatch.countDown()
                    }.collect { event ->
                        if (event is WsEvent.SessionInfo && event.data?.get("barrier") == "live-processed") {
                            liveProcessedLatch.countDown()
                        }
                        if (event is WsEvent.MessageComplete && event.sessionId == sessionId) {
                            collectedEvents.add(event)
                            collectLatch.countDown()
                        }
                    }
            }

        try {
            assertTrue("Collector subscription should be established", subscriptionLatch.await(3, TimeUnit.SECONDS))

            // Connect client - real onOpen automatically arms replay
            HermesWsClient.connect()
            runBlocking {
                withTimeout(5000) {
                    HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
                }
            }
            assertTrue("Server WebSocket should open", serverOpenLatch.await(5, TimeUnit.SECONDS))
            val serverSocket = requireNotNull(serverSocketRef)

            // Wait until the server receives session.events.since request
            assertTrue("Server should receive replay request", replayReceivedLatch.await(5, TimeUnit.SECONDS))
            assertTrue("Replay should be in flight", HermesWsClient.isReplayInFlight())

            // 1. Live frame arrived while replay is in flight (seq 12)
            val liveFrame =
                """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"$sessionId","seq":12,"payload":{"text":"Live message 12"}}}"""
            serverSocket.send(liveFrame)

            // A following unsequenced frame proves the live notification reached the client
            // before the replay response is released; unchanged watermark alone is not a barrier.
            serverSocket.send(
                """{"method":"event","params":{"type":"session.info","session_id":"$sessionId","payload":{"barrier":"live-processed"}}}""",
            )
            assertTrue(
                "Live frame must be processed before replay completes",
                liveProcessedLatch.await(3, TimeUnit.SECONDS),
            )
            assertEquals(10, HermesWsClient.getSeqWatermarks()[sessionId])
            assertEquals("Live frame should be held in replayHold while replay is in flight", 0, collectedEvents.size)

            // 2. Unblock replay response from server
            allowReplayResponseLatch.countDown()

            // Wait for replay to deliver replayed events and drain held live events
            assertTrue("Should collect replayed and unheld events", collectLatch.await(5, TimeUnit.SECONDS))

            // Watermark should advance to 12
            assertEquals(12, HermesWsClient.getSeqWatermarks()[sessionId])

            // Deduplication: held live frame seq 12 deduped against replay seq 12
            assertEquals(2, collectedEvents.size)
            assertEquals("Replayed message 11", (collectedEvents[0] as WsEvent.MessageComplete).text)
            assertEquals("Replayed message 12", (collectedEvents[1] as WsEvent.MessageComplete).text)
        } finally {
            allowReplayResponseLatch.countDown()
            collectorJob.cancel()
        }
    }
}
