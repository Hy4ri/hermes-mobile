package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.contract.SessionEventsSinceResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class WsTransportTest {
    private val testJson =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private class Fixture(
        val scope: CoroutineScope,
        json: Json,
        nowMs: () -> Long = {
            if (scope is TestScope) {
                scope.testScheduler.currentTime
            } else {
                System.nanoTime() / 1_000_000L
            }
        },
    ) {
        val outboundLock = Any()
        val rawMessages = mutableListOf<String>()
        val replayEmittedEvents = mutableListOf<WsEvent>()
        val reconnectPolicy = ReconnectPolicy()

        val rpcSentFrames = mutableListOf<String>()
        val rpcRemovedQueueIds = mutableListOf<String>()
        var rpcIdleChecks = 0

        var transportRef: WsTransport? = null
        private val rpcIdCounter = AtomicInteger(1)

        val connectionHealth =
            ConnectionHealth(
                scope = scope,
                isConnected = { transportRef?.isConnected ?: false },
                pingRequest = { /* ping fake unit */ },
                cancelSocket = { transportRef?.cancelSocket() },
                pendingReply = { transportRef?.pendingReply ?: false },
                nowMs = nowMs,
            )

        val replayTracker =
            ReplayTracker(
                scope = scope,
                fetchEventsSince = { SessionEventsSinceResult() },
                emitEvent = { replayEmittedEvents.add(it) },
            )

        val rpcChannel =
            RpcChannel(
                scope = scope,
                json = json,
                sendRequest = { method, params, onSent ->
                    val id = "rpc-${rpcIdCounter.getAndIncrement()}"
                    val req =
                        rpcChannelRef?.createRequest(method, params)?.copy(id = id)
                            ?: JsonRpcRequest(id = id, method = method)
                    transportRef?.send(req, onSent) ?: id
                },
                sendFrame = { frame ->
                    rpcSentFrames.add(frame)
                    transportRef?.sendDirectFrame(frame) ?: false
                },
                removeQueuedMessage = { id ->
                    rpcRemovedQueueIds.add(id)
                    transportRef?.removeQueuedMessage(id)
                },
                onIdleCheck = {
                    rpcIdleChecks++
                    transportRef?.disconnectIfIdleInBackground()
                },
                outboundLock = outboundLock,
                onResult = { id -> transportRef?.onRpcResult(id) },
                onError = { id -> transportRef?.onRpcError(id) },
            ).also { rpcChannelRef = it }

        val transport =
            WsTransport(
                scope = scope,
                outboundLock = outboundLock,
                reconnectPolicy = reconnectPolicy,
                connectionHealth = connectionHealth,
                replayTracker = replayTracker,
                rpcChannel = rpcChannel,
                onRawMessage = { rawMessages.add(it) },
            ).also { transportRef = it }

        companion object {
            private var rpcChannelRef: RpcChannel? = null
        }
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        ActiveSessionHolder.clear()

        mockkObject(AuthManager)
        every { AuthManager.isAutoReconnect() } returns false

        mockkObject(NetworkMonitor)
        every { NetworkMonitor.isConnected } returns MutableStateFlow(true)
    }

    @After
    fun tearDown() {
        ActiveSessionHolder.clear()
        unmockkAll()
    }

    @Test
    fun sendAcceptCallbackRunsUnderOutboundLockAfterSocketAccept() =
        runTest {
            val fixture = Fixture(this, testJson)
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true

            fixture.transport.connectedForTest.set(true)
            fixture.transport.webSocketForTest = socket

            var callbackRan = false
            var callbackThreadHoldsLock = false
            val request = JsonRpcRequest(id = "req-1", method = "session.list")

            val id =
                fixture.transport.send(
                    request = request,
                    onSent = {
                        callbackRan = true
                        callbackThreadHoldsLock = Thread.holdsLock(fixture.outboundLock)
                    },
                )

            assertEquals("req-1", id)
            assertTrue(callbackRan)
            assertTrue(callbackThreadHoldsLock)
            verify(exactly = 1) { socket.send(any<String>()) }
            assertTrue(fixture.transport.messageQueueForTest.isEmpty())
        }

    @Test
    fun rejectedRetryableSendEnqueuesAndCancelDoesNotAutoRedialWhenAutoReconnectDisabled() =
        runTest {
            val fixture = Fixture(this, testJson)
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns false
            every { socket.queueSize() } returns 0L

            fixture.transport.connectedForTest.set(true)
            fixture.transport.intentionalCloseForTest.set(false)
            fixture.transport.connectionGenerationForTest.set(1)
            fixture.transport.webSocketForTest = socket

            var onSentCalled = false
            var callbackThreadHoldsLock = false
            val request = JsonRpcRequest(id = "req-queued", method = "prompt.submit")

            fixture.transport.send(
                request = request,
                onSent = {
                    onSentCalled = true
                    callbackThreadHoldsLock = Thread.holdsLock(fixture.outboundLock)
                },
                queueIfDisconnected = true,
            )

            // When socket rejects, request is pushed back into retryable queue
            val queue = fixture.transport.messageQueueForTest
            assertEquals(1, queue.size)
            assertTrue(queue.peek()?.contains("req-queued") == true)
            assertTrue(onSentCalled)
            assertTrue(callbackThreadHoldsLock)
            assertFalse(fixture.transport.isConnected)
            assertEquals(ConnectionStatus.DISCONNECTED, fixture.transport.connectionStatus.value)
            assertNull(fixture.transport.reconnectJobForTest)
            verify(exactly = 1) { socket.cancel() }
        }

    @Test
    fun staleOnMessageIgnoredWhileCurrentGenerationFrameUpdatesHealthAndRawCallback() =
        runTest {
            val fixture =
                Fixture(
                    scope = this,
                    json = testJson,
                    nowMs = { testScheduler.currentTime },
                )
            val currentSocket = mockk<WebSocket>(relaxed = true)
            val staleSocket = mockk<WebSocket>(relaxed = true)

            fixture.transport.connectedForTest.set(true)
            fixture.transport.connectionGenerationForTest.set(2)
            fixture.transport.webSocketForTest = currentSocket

            val initialTimestamp = fixture.connectionHealth.lastPongTimestamp
            assertEquals(0L, initialTimestamp)

            val staleListener = fixture.transport.createListenerForTest(1)
            val currentListener = fixture.transport.createListenerForTest(2)

            advanceTimeBy(1_000L)
            // Message from stale listener/generation should be discarded
            staleListener.onMessage(staleSocket, "{\"jsonrpc\":\"2.0\",\"id\":\"old\"}")
            staleListener.onMessage(currentSocket, "{\"jsonrpc\":\"2.0\",\"id\":\"old-mismatched-gen\"}")
            assertTrue(fixture.rawMessages.isEmpty())
            assertEquals(0L, fixture.connectionHealth.lastPongTimestamp)

            advanceTimeBy(1_000L)
            // Message from current generation but mismatched socket should be discarded
            currentListener.onMessage(staleSocket, "{\"jsonrpc\":\"2.0\",\"id\":\"wrong-socket\"}")
            assertTrue(fixture.rawMessages.isEmpty())
            assertEquals(0L, fixture.connectionHealth.lastPongTimestamp)

            advanceTimeBy(1_000L)
            val expectedTimestamp = testScheduler.currentTime
            // Matching generation and matching active socket passes through and updates health
            currentListener.onMessage(currentSocket, "{\"jsonrpc\":\"2.0\",\"id\":\"valid\"}")
            assertEquals(listOf("{\"jsonrpc\":\"2.0\",\"id\":\"valid\"}"), fixture.rawMessages)
            assertEquals(expectedTimestamp, fixture.connectionHealth.lastPongTimestamp)
        }

    @Test
    fun staleOnOpenClosesSupersededWithoutChangingOwnershipOrFlushingQueue() =
        runTest {
            val fixture = Fixture(this, testJson)
            val staleSocket = mockk<WebSocket>(relaxed = true)
            val currentSocket = mockk<WebSocket>(relaxed = true)
            val response = mockk<Response>(relaxed = true)

            fixture.transport.intentionalCloseForTest.set(false)
            fixture.transport.connectionGenerationForTest.set(2)
            fixture.transport.webSocketForTest = currentSocket
            fixture.transport.messageQueueForTest.add("{\"jsonrpc\":\"2.0\",\"id\":\"queued-1\"}")

            val staleListener = fixture.transport.createListenerForTest(1)
            staleListener.onOpen(staleSocket, response)

            // Stale onOpen must close the stale socket with 1000 ("Superseded")
            verify(exactly = 1) { staleSocket.close(1000, "Superseded") }
            // Must not change active socket or flush the queue on stale socket
            verify(exactly = 0) { staleSocket.send(any<String>()) }
            assertEquals(currentSocket, fixture.transport.webSocketForTest)
            assertEquals(1, fixture.transport.messageQueueForTest.size)
        }

    @Test
    fun authClosingCodeSetsTerminalAuthExpiredAndDoesNotAutoRedial() =
        runTest {
            val fixture = Fixture(this, testJson)
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true

            fixture.transport.connectedForTest.set(true)
            fixture.transport.intentionalCloseForTest.set(false)
            fixture.transport.connectionGenerationForTest.set(1)
            fixture.transport.webSocketForTest = socket

            // Register a pending RPC call to verify it is NOT newly rejected
            val pendingDeferred = fixture.rpcChannel.request("custom.in_flight", emptyMap())
            assertFalse(pendingDeferred.isCompleted)

            val listener = fixture.transport.createListenerForTest(1)
            listener.onClosing(socket, 4401, "unauthorized")

            assertEquals(ConnectionStatus.AUTH_EXPIRED, fixture.transport.connectionStatus.value)
            assertTrue(fixture.transport.isConnected)
            assertNull(fixture.transport.reconnectJobForTest)
            assertFalse(pendingDeferred.isCompleted)

            listener.onClosed(socket, 4401, "unauthorized")

            assertFalse(fixture.transport.isConnected)
            assertEquals(ConnectionStatus.AUTH_EXPIRED, fixture.transport.connectionStatus.value)
            assertNull(fixture.transport.reconnectJobForTest)
            assertFalse(pendingDeferred.isCompleted)

            pendingDeferred.cancel()
        }

    @Test
    fun disconnectPreservesPendingCallsWhileClearFlagControlsQueueAndLeaseCleanup() =
        runTest {
            val fixture = Fixture(this, testJson)
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true

            fixture.transport.connectedForTest.set(true)
            fixture.transport.intentionalCloseForTest.set(false)
            fixture.transport.webSocketForTest = socket

            // Register actual pending RPC call over socket accepting send
            val pendingDeferred = fixture.rpcChannel.request("custom.in_flight", emptyMap())
            assertFalse(pendingDeferred.isCompleted)

            // Populate retryable message queue
            fixture.transport.messageQueueForTest.add("{\"jsonrpc\":\"2.0\",\"id\":\"call-1\"}")

            // Ordinary disconnect preserves queued messages and does NOT reject pending calls
            fixture.transport.disconnect(clearPendingMessages = false)
            assertEquals(1, fixture.transport.messageQueueForTest.size)
            assertFalse(fixture.transport.isConnected)
            assertFalse(pendingDeferred.isCompleted)

            // Disconnect with clearPendingMessages = true clears queue and resets leases, still not rejecting pending calls
            fixture.transport.acquireBackgroundConnectionLease()
            assertTrue(fixture.transport.hasBackgroundConnectionLease())

            fixture.transport.disconnect(clearPendingMessages = true)
            assertTrue(fixture.transport.messageQueueForTest.isEmpty())
            assertFalse(fixture.transport.hasBackgroundConnectionLease())
            assertFalse(pendingDeferred.isCompleted)

            pendingDeferred.cancel()
        }

    @Test
    fun reconnectForNetworkChangeRejectsPendingCallsAndExecutesReplacementCallbackOnce() =
        runTest {
            every { AuthManager.isAutoReconnect() } returns true

            val fixture = Fixture(this, testJson)
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true

            fixture.transport.connectedForTest.set(true)
            fixture.transport.intentionalCloseForTest.set(false)
            fixture.transport.connectionGenerationForTest.set(1)
            fixture.transport.webSocketForTest = socket

            // Register non-empty pending RPC while socket accepts send
            val pendingDeferred = fixture.rpcChannel.request("custom.network_call", emptyMap())
            assertFalse(pendingDeferred.isCompleted)

            var replacementOpens = 0
            fixture.transport.reconnectForNetworkChange(
                networkAvailable = true,
                openReplacement = { replacementOpens++ },
            )

            // Outgoing socket canceled once
            verify(exactly = 1) { socket.cancel() }
            assertEquals(1, replacementOpens)
            assertEquals(2, fixture.transport.connectionGenerationForTest.get())
            assertFalse(fixture.transport.isConnected)
            assertEquals(ConnectionStatus.RECONNECTING, fixture.transport.connectionStatus.value)
            assertNull(fixture.transport.webSocketForTest)

            // Network replacement uses the default connection-lost exception, not a timeout.
            assertTrue(pendingDeferred.isCompleted)
            try {
                pendingDeferred.await()
                fail("Expected HermesRpcException")
            } catch (exception: HermesWsClient.HermesRpcException) {
                assertEquals(0, exception.code)
                assertEquals("Connection lost — request cancelled", exception.message)
            }
        }

    @Test
    fun combinedLeasesSurviveIdleBackgroundUntilBothReleased() =
        runTest {
            val fixture = Fixture(this, testJson)
            val socket = mockk<WebSocket>(relaxed = true)

            fixture.transport.connectedForTest.set(true)
            fixture.transport.webSocketForTest = socket
            fixture.transport.setConnectionStatusForTest(ConnectionStatus.CONNECTED)

            fixture.transport.acquireExternalActivityConnectionLease()
            fixture.transport.acquireBackgroundConnectionLease()
            assertTrue(fixture.transport.hasBackgroundConnectionLease())

            // Backgrounding app with both leases active keeps connection alive
            fixture.transport.setAppForeground(false)
            assertTrue(fixture.transport.isConnected)

            // Releasing external lease keeps connection alive due to background lease
            fixture.transport.releaseExternalActivityConnectionLease()
            assertTrue(fixture.transport.isConnected)

            // Releasing background lease releases all leases and disconnects idle background socket
            fixture.transport.releaseBackgroundConnectionLease()
            assertFalse(fixture.transport.isConnected)
            assertEquals(ConnectionStatus.DISCONNECTED, fixture.transport.connectionStatus.value)
        }

    @Test
    fun observeEventMarksPendingReplyStartAndCompleteRespectingIdlePolicy() =
        runTest {
            val fixture = Fixture(this, testJson)
            val socket = mockk<WebSocket>(relaxed = true)

            fixture.transport.connectedForTest.set(true)
            fixture.transport.webSocketForTest = socket
            fixture.transport.setConnectionStatusForTest(ConnectionStatus.CONNECTED)

            // When a message start event arrives BEFORE setAppForeground(false)
            val startEvent = WsEvent.MessageStart(sessionId = "s1")
            fixture.transport.observeEvent(startEvent)
            assertTrue(fixture.transport.pendingReply)

            // Setting app foreground to false while pending reply is true stays connected
            fixture.transport.setAppForeground(false)
            assertTrue(fixture.transport.isConnected)

            // Idle disconnect check while pending reply is true keeps socket connected
            fixture.transport.disconnectIfIdleInBackground()
            assertTrue(fixture.transport.isConnected)

            // Message complete event clears pending reply and immediately idle disconnects
            val completeEvent = WsEvent.MessageComplete(text = "done", sessionId = "s1")
            fixture.transport.observeEvent(completeEvent)
            assertFalse(fixture.transport.pendingReply)
            assertFalse(fixture.transport.isConnected)
            assertEquals(ConnectionStatus.DISCONNECTED, fixture.transport.connectionStatus.value)
        }

    @Test
    fun sendDirectFrameBypassesQueueAndReturnsFalseWhenDisconnected() =
        runTest {
            val fixture = Fixture(this, testJson)
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send("{\"jsonrpc\":\"2.0\",\"method\":\"connected_frame\"}") } returns true
            every { socket.send("{\"jsonrpc\":\"2.0\",\"method\":\"send_fails\"}") } returns false

            fixture.transport.connectedForTest.set(true)
            fixture.transport.webSocketForTest = socket

            // 1. Connected with socket accepting send -> returns true, no queue
            val connectedResult =
                fixture.transport.sendDirectFrame(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"connected_frame\"}",
                )
            assertTrue(connectedResult)
            assertTrue(fixture.transport.messageQueueForTest.isEmpty())

            // 2. Connected with socket rejecting send -> returns false, no queue
            val sendFailsResult = fixture.transport.sendDirectFrame("{\"jsonrpc\":\"2.0\",\"method\":\"send_fails\"}")
            assertFalse(sendFailsResult)
            assertTrue(fixture.transport.messageQueueForTest.isEmpty())

            // 3. Disconnected -> returns false, no queue
            fixture.transport.connectedForTest.set(false)
            fixture.transport.webSocketForTest = null

            val disconnectedResult = fixture.transport.sendDirectFrame("{\"jsonrpc\":\"2.0\",\"method\":\"direct\"}")
            assertFalse(disconnectedResult)
            assertTrue(fixture.transport.messageQueueForTest.isEmpty())
        }
}
