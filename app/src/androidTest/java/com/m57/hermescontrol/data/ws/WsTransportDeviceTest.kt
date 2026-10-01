package com.m57.hermescontrol.data.ws

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.data.ws.contract.SessionEventsSinceResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class WsTransportDeviceTest {
    private class FakeWebSocket(
        private val request: Request,
        var accept: Boolean = true,
        var queueSizeValue: Long = 0L,
    ) : WebSocket {
        val sentFrames = ConcurrentLinkedQueue<String>()
        val attempts = AtomicInteger(0)
        val closedCode = AtomicInteger(-1)
        val closedReason =
            java.util.concurrent.atomic
                .AtomicReference<String?>(null)
        val canceled = AtomicBoolean(false)

        override fun request(): Request = request

        override fun queueSize(): Long = queueSizeValue

        override fun send(text: String): Boolean {
            attempts.incrementAndGet()
            if (!accept) return false
            sentFrames.add(text)
            return true
        }

        override fun send(bytes: ByteString): Boolean {
            attempts.incrementAndGet()
            if (!accept) return false
            sentFrames.add(bytes.utf8())
            return true
        }

        override fun close(
            code: Int,
            reason: String?,
        ): Boolean {
            closedCode.set(code)
            closedReason.set(reason)
            return true
        }

        override fun cancel() {
            canceled.set(true)
        }
    }

    private class TestFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lock = Any()
        val clock = AtomicLong(100L)
        val dummyRequest: Request = Request.Builder().url("http://localhost/ws").build()
        val openResponse: Response =
            Response
                .Builder()
                .request(dummyRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build()

        val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = false
            }

        val recordedEvents = ConcurrentLinkedQueue<WsEvent>()
        lateinit var transport: WsTransport
        lateinit var rpc: RpcChannel
        lateinit var health: ConnectionHealth
        lateinit var replay: ReplayTracker

        init {
            health =
                ConnectionHealth(
                    scope = scope,
                    isConnected = { transport.isConnected },
                    pingRequest = { _ -> },
                    cancelSocket = { transport.cancelSocket() },
                    pendingReply = { transport.pendingReply },
                    nowMs = { clock.get() },
                )

            replay =
                ReplayTracker(
                    scope = scope,
                    fetchEventsSince = { SessionEventsSinceResult() },
                    emitEvent = { recordedEvents.add(it) },
                )

            rpc =
                RpcChannel(
                    scope = scope,
                    json = json,
                    outboundLock = lock,
                    sendRequest = { method, params, onSent ->
                        transport.send(rpc.createRequest(method, params), onSent)
                    },
                    sendFrame = { transport.sendDirectFrame(it) },
                    removeQueuedMessage = { transport.removeQueuedMessage(it) },
                    onIdleCheck = { transport.disconnectIfIdleInBackground() },
                    onResult = { transport.onRpcResult(it) },
                    onError = { transport.onRpcError(it) },
                )

            transport =
                WsTransport(
                    scope = scope,
                    outboundLock = lock,
                    reconnectPolicy = ReconnectPolicy(),
                    connectionHealth = health,
                    replayTracker = replay,
                    rpcChannel = rpc,
                    onRawMessage = { raw ->
                        rpc.handleIncomingFrame(raw, replay) { recordedEvents.add(it) }
                    },
                )
        }

        fun close() {
            try {
                transport.disconnect(clearPendingMessages = true)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun transportRpcRoundTripWithStaleListenerIsolation() =
        kotlinx.coroutines.runBlocking {
            val fixture = TestFixture()
            try {
                val fakeWs = FakeWebSocket(fixture.dummyRequest, accept = true, queueSizeValue = 0L)
                fixture.transport.connectionGenerationForTest.set(1)
                fixture.transport.intentionalCloseForTest.set(false)

                val listenerGen1 = fixture.transport.createListenerForTest(1)
                listenerGen1.onOpen(fakeWs, fixture.openResponse)

                assertTrue(fixture.transport.isConnected)
                assertEquals(fakeWs, fixture.transport.webSocketForTest)

                val deferred = fixture.rpc.request("gateway.ping", emptyMap())
                val sentFrame = requireNotNull(fakeWs.sentFrames.poll())

                val parsedSent = fixture.json.decodeFromString<JsonRpcRequest>(sentFrame)
                val reqId = parsedSent.id

                fixture.clock.set(200L)
                val responseJson = """{"jsonrpc":"2.0","id":"$reqId","result":42.0}"""
                listenerGen1.onMessage(fakeWs, responseJson)

                val result = withTimeout(2_000L) { deferred.await() }
                assertEquals(JsonPrimitive(42.0), result)

                val event = requireNotNull(fixture.recordedEvents.poll())
                assertTrue(event is WsEvent.RpcResult)
                assertEquals(reqId, (event as WsEvent.RpcResult).id)
                assertEquals(200L, fixture.health.lastPongTimestamp)

                val staleListener = fixture.transport.createListenerForTest(0)
                val staleWs = FakeWebSocket(fixture.dummyRequest, accept = true)
                staleListener.onMessage(staleWs, """{"jsonrpc":"2.0","id":999,"result":99.0}""")
                listenerGen1.onMessage(staleWs, """{"jsonrpc":"2.0","id":998,"result":88.0}""")

                assertTrue(fixture.transport.isConnected)
                assertEquals(fakeWs, fixture.transport.webSocketForTest)
                assertEquals(200L, fixture.health.lastPongTimestamp)
                assertTrue(fixture.recordedEvents.isEmpty())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun rejectingWsQueuesAndFlushesOnNextAcceptSocket() =
        kotlinx.coroutines.runBlocking {
            val fixture = TestFixture()
            try {
                val rejectingWs = FakeWebSocket(fixture.dummyRequest, accept = false, queueSizeValue = 1L)
                fixture.transport.connectionGenerationForTest.set(1)
                fixture.transport.intentionalCloseForTest.set(false)

                val listener1 = fixture.transport.createListenerForTest(1)
                listener1.onOpen(rejectingWs, fixture.openResponse)
                assertTrue(fixture.transport.isConnected)

                var recordedId: String? = null
                var callbackRanUnderLock = false
                val req1 = fixture.rpc.createRequest("test.one", emptyMap())
                val returnedId =
                    fixture.transport.send(
                        request = req1,
                        onSent = { id ->
                            callbackRanUnderLock = Thread.holdsLock(fixture.lock)
                            recordedId = id
                        },
                    )

                assertEquals(returnedId, req1.id.toString())
                assertEquals(returnedId, recordedId)
                assertTrue(callbackRanUnderLock)

                val req2 = fixture.rpc.createRequest("test.two", emptyMap())
                fixture.transport.messageQueueForTest.add(fixture.rpc.encodeRequest(req2))

                assertFalse(fixture.transport.isConnected)
                assertEquals(ConnectionStatus.RECONNECTING, fixture.transport.connectionStatus.value)
                assertNotNull(fixture.transport.outboundDrainJobForTest)
                assertEquals(2, fixture.transport.messageQueueForTest.size)

                val acceptWs = FakeWebSocket(fixture.dummyRequest, accept = true, queueSizeValue = 0L)
                val listenerReplacement = fixture.transport.createListenerForTest(1)
                listenerReplacement.onOpen(acceptWs, fixture.openResponse)

                assertTrue(fixture.transport.isConnected)
                assertEquals(ConnectionStatus.CONNECTED, fixture.transport.connectionStatus.value)
                assertNull(fixture.transport.outboundDrainJobForTest)

                assertTrue(fixture.transport.messageQueueForTest.isEmpty())
                val frameList = acceptWs.sentFrames.toList()
                assertEquals(
                    listOf(fixture.rpc.encodeRequest(req1), fixture.rpc.encodeRequest(req2)),
                    frameList,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun authClosingDoesNotPrematurelyDropPendingRpcAndClosedHaltsReconnect() =
        kotlinx.coroutines.runBlocking {
            val fixture = TestFixture()
            try {
                val fakeWs = FakeWebSocket(fixture.dummyRequest, accept = true, queueSizeValue = 0L)
                fixture.transport.connectionGenerationForTest.set(1)
                fixture.transport.intentionalCloseForTest.set(false)

                val listener = fixture.transport.createListenerForTest(1)
                listener.onOpen(fakeWs, fixture.openResponse)
                assertTrue(fixture.transport.isConnected)

                val pendingDeferred = fixture.rpc.request("gateway.ping", emptyMap())
                assertTrue(fixture.rpc.hasPendingCalls())

                listener.onClosing(fakeWs, 4401, "AUTH_EXPIRED")
                assertTrue(fixture.transport.isConnected)
                assertTrue(fixture.rpc.hasPendingCalls())
                assertFalse(pendingDeferred.isCompleted)

                listener.onClosed(fakeWs, 4401, "AUTH_EXPIRED")
                assertFalse(fixture.transport.isConnected)
                assertNull(fixture.transport.reconnectJobForTest)
                assertTrue(fixture.rpc.hasPendingCalls())
                assertFalse(pendingDeferred.isCompleted)

                fixture.transport.disconnect(clearPendingMessages = true)
                assertFalse(pendingDeferred.isCompleted)

                pendingDeferred.cancel()
            } finally {
                fixture.close()
            }
        }
}
