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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.WebSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class WsTransportSettlementTest {
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
        private var rpcChannelRef: RpcChannel? = null

        val connectionHealth =
            ConnectionHealth(
                scope = scope,
                isConnected = { transportRef?.isConnected ?: false },
                pingRequest = { /* ping fake */ },
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
    fun onClosedOnCurrentGenerationSettlesPendingDeferredAndCancelsTimeout() =
        runTest {
            val fixture = Fixture(this, testJson)
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true

            fixture.transport.connectedForTest.set(true)
            fixture.transport.intentionalCloseForTest.set(false)
            fixture.transport.connectionGenerationForTest.set(1)
            fixture.transport.webSocketForTest = socket

            val deferred = fixture.rpcChannel.request("custom.in_flight", emptyMap(), timeoutMs = 60_000L)
            val id = fixture.rpcChannel.pendingCallIdsForTest().first()
            val timeoutJob = fixture.rpcChannel.pendingCallTimeoutForTest(id)
            assertNotNull(timeoutJob)
            assertFalse(timeoutJob!!.isCancelled)
            assertFalse(deferred.isCompleted)

            val listener = fixture.transport.createListenerForTest(1)
            listener.onClosed(socket, 1000, "Normal closure")

            assertTrue("Deferred must be completed on current-generation onClosed", deferred.isCompleted)
            assertTrue("Timeout job must be cancelled on current-generation onClosed", timeoutJob.isCancelled)
            assertFalse(fixture.rpcChannel.hasPendingCalls())
            assertTrue(fixture.rpcChannel.pendingCallIdsForTest().isEmpty())

            try {
                deferred.await()
                fail("Expected HermesRpcException")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertEquals("Connection lost — request cancelled", e.message)
                assertEquals(0, e.code)
                assertNull(e.data)
            }
        }

    @Test
    fun onFailureOnCurrentGenerationSettlesPendingDeferredAndCleansQueue() =
        runTest {
            val fixture = Fixture(this, testJson)
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true

            fixture.transport.connectedForTest.set(true)
            fixture.transport.intentionalCloseForTest.set(false)
            fixture.transport.connectionGenerationForTest.set(1)
            fixture.transport.webSocketForTest = socket

            val deferred = fixture.rpcChannel.request("custom.failed_call", emptyMap(), timeoutMs = 60_000L)
            val id = fixture.rpcChannel.pendingCallIdsForTest().first()
            val timeoutJob = fixture.rpcChannel.pendingCallTimeoutForTest(id)
            assertNotNull(timeoutJob)
            assertFalse(deferred.isCompleted)

            val listener = fixture.transport.createListenerForTest(1)
            listener.onFailure(socket, RuntimeException("Transport pipe broken"), null)

            assertTrue("Deferred must be completed on current-generation onFailure", deferred.isCompleted)
            assertTrue("Timeout job must be cancelled on onFailure", timeoutJob!!.isCancelled)
            assertFalse(fixture.rpcChannel.hasPendingCalls())

            try {
                deferred.await()
                fail("Expected HermesRpcException")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertEquals("Connection lost — request cancelled", e.message)
                assertEquals(0, e.code)
            }
        }

    @Test
    fun explicitDisconnectSettlesPendingRpcCallsImmediately() =
        runTest {
            val fixture = Fixture(this, testJson)
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true

            fixture.transport.connectedForTest.set(true)
            fixture.transport.intentionalCloseForTest.set(false)
            fixture.transport.webSocketForTest = socket

            val deferred = fixture.rpcChannel.request("custom.disconnect_call", emptyMap(), timeoutMs = 60_000L)
            assertFalse(deferred.isCompleted)

            fixture.transport.disconnect(clearPendingMessages = false)

            assertTrue("Deferred must be settled on explicit disconnect", deferred.isCompleted)
            assertFalse(fixture.rpcChannel.hasPendingCalls())

            try {
                deferred.await()
                fail("Expected HermesRpcException")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertEquals("Connection lost — request cancelled", e.message)
                assertEquals(0, e.code)
            }
        }

    @Test
    fun staleCloseOrFailureRetainsNewPendingCallsInNextGeneration() =
        runTest {
            val fixture = Fixture(this, testJson)
            val socket1 = mockk<WebSocket>(relaxed = true)
            val socket2 = mockk<WebSocket>(relaxed = true)
            every { socket1.send(any<String>()) } returns true
            every { socket2.send(any<String>()) } returns true

            fixture.transport.connectedForTest.set(true)
            fixture.transport.intentionalCloseForTest.set(false)
            fixture.transport.connectionGenerationForTest.set(2)
            fixture.transport.webSocketForTest = socket2

            val staleListener = fixture.transport.createListenerForTest(1)

            val newDeferred = fixture.rpcChannel.request("fresh.call_gen2", emptyMap(), timeoutMs = 60_000L)
            val newId = fixture.rpcChannel.pendingCallIdsForTest().first()
            assertFalse(newDeferred.isCompleted)

            // Stale listener callback from generation 1 arrives
            staleListener.onClosed(socket1, 1000, "Old close")
            staleListener.onFailure(socket1, RuntimeException("Old failure"), null)

            // Stale listener must NOT reject gen 2 pending call
            assertFalse("Stale close/failure must not settle generation 2 call", newDeferred.isCompleted)
            assertTrue(fixture.rpcChannel.pendingCallIdsForTest().contains(newId))

            newDeferred.cancel()
        }

    @Test
    fun timeoutCancellationPerformsQueueCleanupAndNoDelayedLeak() =
        runTest {
            val fixture = Fixture(this, testJson)
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true

            fixture.transport.connectedForTest.set(true)
            fixture.transport.webSocketForTest = socket

            val deferred = fixture.rpcChannel.request("test.timeout", emptyMap(), timeoutMs = 1_000L)
            val id = fixture.rpcChannel.pendingCallIdsForTest().first()

            runCurrent()
            advanceTimeBy(999L)
            runCurrent()
            assertFalse(deferred.isCompleted)
            assertTrue(fixture.rpcChannel.hasPendingCalls())

            advanceTimeBy(1L)
            runCurrent()

            assertTrue("Deferred must complete on timeout", deferred.isCompleted)
            assertFalse(fixture.rpcChannel.hasPendingCalls())
            assertTrue("Queue cleanup must record removal of timed out call", fixture.rpcRemovedQueueIds.contains(id))

            try {
                deferred.await()
                fail("Expected HermesRpcException on timeout")
            } catch (e: HermesWsClient.HermesRpcException) {
                assertEquals("Request timed out: test.timeout", e.message)
                assertEquals(-1, e.code)
            }

            // Advancing further time must not trigger any delayed leak or duplicate failure
            advanceTimeBy(10_000L)
            runCurrent()
            assertFalse(fixture.rpcChannel.hasPendingCalls())
        }

    @Test
    fun plainDisconnectRetainsUnrelatedFireAndForgetFramesWhileClearPendingMessagesClearsThem() =
        runTest {
            val fixture = Fixture(this, testJson)
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true

            fixture.transport.connectedForTest.set(true)
            fixture.transport.webSocketForTest = socket

            // Enqueue fire-and-forget frames into retryable queue
            val frame1 = """{"jsonrpc":"2.0","method":"prompt.submit","params":{"text":"queued-1"}}"""
            val frame2 = """{"jsonrpc":"2.0","method":"prompt.submit","params":{"text":"queued-2"}}"""
            fixture.transport.messageQueueForTest.add(frame1)
            fixture.transport.messageQueueForTest.add(frame2)

            // Plain disconnect (clearPendingMessages = false) retains fire-and-forget frames
            fixture.transport.disconnect(clearPendingMessages = false)
            assertEquals(2, fixture.transport.messageQueueForTest.size)
            assertTrue(fixture.transport.messageQueueForTest.contains(frame1))
            assertTrue(fixture.transport.messageQueueForTest.contains(frame2))

            // Disconnect with clearPendingMessages = true wipes all frames
            fixture.transport.disconnect(clearPendingMessages = true)
            assertTrue(fixture.transport.messageQueueForTest.isEmpty())
        }

    @Test
    fun pendingCallsAreSettledBeforeStatusEmitsDisconnectedOrReconnecting() =
        runTest {
            val fixture = Fixture(this, testJson)
            val socket = mockk<WebSocket>(relaxed = true)
            every { socket.send(any<String>()) } returns true

            fixture.transport.connectedForTest.set(true)
            fixture.transport.intentionalCloseForTest.set(false)
            fixture.transport.connectionGenerationForTest.set(1)
            fixture.transport.webSocketForTest = socket

            val deferred = fixture.rpcChannel.request("custom.order_check", emptyMap(), timeoutMs = 60_000L)
            assertFalse(deferred.isCompleted)
            fixture.transport.connectionStatusForTest.value = ConnectionStatus.CONNECTED

            var deferredSettledAtStatusTransition: Boolean? = null
            val collectJob =
                backgroundScope.launch {
                    fixture.transport.connectionStatus.collect { status ->
                        if (status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.RECONNECTING) {
                            deferredSettledAtStatusTransition = deferred.isCompleted
                        }
                    }
                }
            runCurrent()

            val listener = fixture.transport.createListenerForTest(1)
            listener.onClosed(socket, 1000, "Closed")
            runCurrent()

            assertEquals(true, deferredSettledAtStatusTransition)
            assertTrue(deferred.isCompleted)
            collectJob.cancel()
        }
}
