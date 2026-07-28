package com.m57.hermescontrol.data.ws

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HermesWsClientTest {
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        mockWebServer = MockWebServer()
        mockWebServer.start()

        mockkObject(AuthManager)
        every { AuthManager.wsUrl() } returns mockWebServer.url("/").toString().replace("http://", "ws://")
        every { AuthManager.isAutoReconnect() } returns false
        every { AuthManager.getSessionCookie() } returns null
        // Non-gated by default (token mode) so the gated ticket path is exercised
        // only by the explicit gated-mode test below.
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config
                        .ServerStoreState()
            }

        // Issue #470: clients are built through OkHttpProvider, which now
        // resolves the shared CookieManager.cookieJar. Inject a fake jar so
        // the WS stack can build its OkHttp clients without app context.
        CookieManager.setJarForTest(buildFakePersistentCookieJar())

        // Reset state
        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val statusField = HermesWsClient::class.java.getDeclaredField("_connectionStatus")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (statusField.get(HermesWsClient) as MutableStateFlow<ConnectionStatus>).value = ConnectionStatus.DISCONNECTED

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>).clear()

        HermesWsClient.disconnect(clearPendingMessages = true) // Ensure it starts clean
        val acceptQueuedMessagesField = HermesWsClient::class.java.getDeclaredField("acceptQueuedMessages")
        acceptQueuedMessagesField.isAccessible = true
        (acceptQueuedMessagesField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)
    }

    @After
    fun tearDown() {
        HermesWsClient.disconnect(clearPendingMessages = true)
        // Wait a bit to allow internal OkHttp coroutines to clean up before shutting down MockWebServer
        // Increased from 100ms for OkHttp 5.x — needs more time for the WS close handshake
        Thread.sleep(500)
        try {
            mockWebServer.shutdown()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        unmockkAll()
    }

    @Test
    fun testConnectAndSend() {
        var serverWebSocket: WebSocket? = null
        val serverLatch = CountDownLatch(1)
        val messageLatch = CountDownLatch(1)
        var receivedMessage: String? = null

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        receivedMessage = text
                        messageLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue("Server failed to accept connection", serverLatch.await(5, TimeUnit.SECONDS))
        assertTrue(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)

        // Send a message
        val id = HermesWsClient.send("test_method", mapOf("param" to "value"))

        // Verify message received by server
        assertTrue("Message not received", messageLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedMessage)
        val msg = receivedMessage ?: ""
        assertTrue(msg.contains("test_method"))
        assertTrue(msg.contains("value"))
        assertTrue(msg.contains(id))
    }

    @Test
    fun testFailedSocketSendQueuesMessageForReconnect() {
        val staleSocket = mockk<WebSocket>(relaxed = true)
        every { staleSocket.send(any<String>()) } returns false

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, staleSocket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val statusField = HermesWsClient::class.java.getDeclaredField("_connectionStatus")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (statusField.get(HermesWsClient) as MutableStateFlow<ConnectionStatus>).value = ConnectionStatus.CONNECTED

        HermesWsClient.send(WsMethods.PROMPT_SUBMIT, mapOf("session_id" to "s1", "text" to "hello"))

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        assertFalse(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
        assertEquals(1, queue.size)
    }

    @Test
    fun testReconnectSchedulingIsIdempotentWhilePending() {
        mockkObject(NetworkMonitor)
        every { AuthManager.isAutoReconnect() } returns true
        every { NetworkMonitor.isConnected } returns MutableStateFlow(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val backoffField = HermesWsClient::class.java.getDeclaredField("currentBackoff")
        backoffField.isAccessible = true
        backoffField.setLong(HermesWsClient, 1_000L)

        val reconnectMethod = HermesWsClient::class.java.getDeclaredMethod("scheduleReconnect")
        reconnectMethod.isAccessible = true
        reconnectMethod.invoke(HermesWsClient)
        reconnectMethod.invoke(HermesWsClient)

        assertEquals(2_000L, backoffField.getLong(HermesWsClient))
    }

    @Test
    fun testCompletedReconnectDoesNotClearReplacementJob() {
        mockkObject(NetworkMonitor)
        every { AuthManager.isAutoReconnect() } returns true
        every { NetworkMonitor.isConnected } returns MutableStateFlow(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val backoffField = HermesWsClient::class.java.getDeclaredField("currentBackoff")
        backoffField.isAccessible = true
        backoffField.setLong(HermesWsClient, 0L)

        val reconnectJobField = HermesWsClient::class.java.getDeclaredField("reconnectJob")
        reconnectJobField.isAccessible = true
        val replacementJob = mockk<Job>(relaxed = true)

        val lockField = HermesWsClient::class.java.getDeclaredField("outboundLock")
        lockField.isAccessible = true
        val lock = lockField.get(HermesWsClient)
        val reconnectMethod = HermesWsClient::class.java.getDeclaredMethod("scheduleReconnect")
        reconnectMethod.isAccessible = true

        synchronized(lock) {
            reconnectMethod.invoke(HermesWsClient)
            reconnectJobField.set(HermesWsClient, replacementJob)
        }

        Thread.sleep(100)
        assertSame(replacementJob, reconnectJobField.get(HermesWsClient))
    }

    @Test
    fun testOversizedRejectedMessageIsNotQueued() {
        val staleSocket = mockk<WebSocket>(relaxed = true)
        every { staleSocket.send(any<String>()) } returns false

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, staleSocket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        HermesWsClient.send(
            WsMethods.PROMPT_SUBMIT,
            mapOf("session_id" to "s1", "text" to "x".repeat(16 * 1024 * 1024 + 1)),
        )

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        assertTrue(queue.isEmpty())
        io.mockk.verify(exactly = 0) { staleSocket.cancel() }
    }

    @Test
    fun testDisconnectedOversizedMessageIsNotQueued() {
        HermesWsClient.send(
            WsMethods.PROMPT_SUBMIT,
            mapOf("session_id" to "s1", "text" to "x".repeat(16 * 1024 * 1024 + 1)),
        )

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        assertTrue(queue.isEmpty())
    }

    @Test
    fun testQueuePressureDoesNotCancelAcceptedFrames() {
        val pressuredSocket = mockk<WebSocket>(relaxed = true)
        every { pressuredSocket.send(any<String>()) } returns false
        every { pressuredSocket.queueSize() } returns 1024L

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, pressuredSocket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        HermesWsClient.send(WsMethods.PROMPT_SUBMIT, mapOf("session_id" to "s1", "text" to "hello"))

        io.mockk.verify(exactly = 0) { pressuredSocket.cancel() }
        val drainJobField = HermesWsClient::class.java.getDeclaredField("outboundDrainJob")
        drainJobField.isAccessible = true
        assertNotNull(drainJobField.get(HermesWsClient))
    }

    @Test
    fun testFailureDuringOutboundDrainSchedulesReconnect() {
        mockkObject(NetworkMonitor)
        every { AuthManager.isAutoReconnect() } returns true
        every { NetworkMonitor.isConnected } returns MutableStateFlow(true)

        val pressuredSocket = mockk<WebSocket>(relaxed = true)
        every { pressuredSocket.send(any<String>()) } returns false
        every { pressuredSocket.queueSize() } returns 1024L

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, pressuredSocket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val generationField = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        generationField.isAccessible = true
        (generationField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicInteger).set(1)

        val backoffField = HermesWsClient::class.java.getDeclaredField("currentBackoff")
        backoffField.isAccessible = true
        backoffField.setLong(HermesWsClient, 1_000L)

        HermesWsClient.send(WsMethods.PROMPT_SUBMIT, mapOf("session_id" to "s1", "text" to "hello"))

        val listenerClass = Class.forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
        val constructor = listenerClass.declaredConstructors.single()
        constructor.isAccessible = true
        val listener = constructor.newInstance(1) as WebSocketListener
        listener.onFailure(pressuredSocket, IOException("connection lost"), null)

        assertEquals(2_000L, backoffField.getLong(HermesWsClient))
    }

    @Test
    fun testReplayPressureSchedulesRecoveryWithoutDroppingHead() {
        val pressuredSocket = mockk<WebSocket>(relaxed = true)
        every { pressuredSocket.send(any<String>()) } returnsMany listOf(true, false)
        every { pressuredSocket.queueSize() } returns 1024L

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        queue.add("{\"jsonrpc\":\"2.0\",\"id\":\"1\"}")
        queue.add("{\"jsonrpc\":\"2.0\",\"id\":\"2\"}")

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val generationField = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        generationField.isAccessible = true
        (generationField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicInteger).set(1)

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, pressuredSocket)

        val listenerClass = Class.forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
        val constructor = listenerClass.declaredConstructors.single()
        constructor.isAccessible = true
        val listener = constructor.newInstance(1) as WebSocketListener
        listener.onOpen(pressuredSocket, mockk(relaxed = true))

        assertFalse(HermesWsClient.isConnected)
        assertEquals(1, queue.size)
        assertTrue(queue.peek()?.contains("\"2\"") == true)
        io.mockk.verify(exactly = 0) { pressuredSocket.cancel() }
        val drainJobField = HermesWsClient::class.java.getDeclaredField("outboundDrainJob")
        drainJobField.isAccessible = true
        assertNotNull(drainJobField.get(HermesWsClient))
    }

    @Test
    fun testStaleOnOpenDoesNotReplayQueue() {
        val staleSocket = mockk<WebSocket>(relaxed = true)
        val currentSocket = mockk<WebSocket>(relaxed = true)
        every { currentSocket.send(any<String>()) } returns true

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, currentSocket)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val generationField = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        generationField.isAccessible = true
        (generationField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicInteger).set(2)

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        queue.add("{\"jsonrpc\":\"2.0\",\"id\":\"1\"}")

        val listenerClass = Class.forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
        val constructor = listenerClass.declaredConstructors.single()
        constructor.isAccessible = true
        val staleListener = constructor.newInstance(1) as WebSocketListener
        val currentListener = constructor.newInstance(2) as WebSocketListener
        val response = mockk<okhttp3.Response>(relaxed = true)

        staleListener.onOpen(staleSocket, response)
        currentListener.onOpen(currentSocket, response)

        io.mockk.verify(exactly = 0) { staleSocket.send(any<String>()) }
        io.mockk.verify(exactly = 1) { currentSocket.send(any<String>()) }
        assertTrue(queue.isEmpty())
    }

    @Test
    fun testRejectedSendAfterDisconnectIsNotQueued() {
        val staleSocket = mockk<WebSocket>(relaxed = true)
        every { staleSocket.send(any<String>()) } answers {
            HermesWsClient.disconnect(clearPendingMessages = true)
            false
        }

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, staleSocket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        HermesWsClient.send(WsMethods.PROMPT_SUBMIT, mapOf("session_id" to "s1", "text" to "hello"))

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        assertTrue(queue.isEmpty())
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testDisconnectPreservesQueuedMessagesUnlessExplicitlyCleared() {
        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        HermesWsClient.send(WsMethods.PROMPT_SUBMIT, mapOf("session_id" to "s1", "text" to "hello"))

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        assertEquals(1, queue.size)

        HermesWsClient.disconnect()
        assertEquals(1, queue.size)

        HermesWsClient.send(WsMethods.PROMPT_SUBMIT, mapOf("session_id" to "s1", "text" to "during reconnect"))
        assertEquals(2, queue.size)

        HermesWsClient.disconnect(clearPendingMessages = true)
        assertTrue(queue.isEmpty())

        HermesWsClient.send(WsMethods.PROMPT_SUBMIT, mapOf("session_id" to "s1", "text" to "after logout"))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun testRejectAllPendingRemovesQueuedAwaitedRpc() {
        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val deferred = HermesWsClient.request(WsMethods.PROCESS_LIST, mapOf("session_id" to "s1"))

        val queueField = HermesWsClient::class.java.getDeclaredField("messageQueue")
        queueField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(HermesWsClient) as java.util.concurrent.ConcurrentLinkedQueue<String>
        assertEquals(1, queue.size)

        HermesWsClient.rejectAllPending()

        assertTrue(deferred.isCompleted)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun testAuthClosingCodeSurvivesRejectedSendRecovery() {
        val socket = mockk<WebSocket>(relaxed = true)
        every { socket.send(any<String>()) } returns false
        every { socket.queueSize() } returns 0L

        val socketField = HermesWsClient::class.java.getDeclaredField("webSocket")
        socketField.isAccessible = true
        socketField.set(HermesWsClient, socket)

        val connectedField = HermesWsClient::class.java.getDeclaredField("connected")
        connectedField.isAccessible = true
        (connectedField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        val intentionalCloseField = HermesWsClient::class.java.getDeclaredField("intentionalClose")
        intentionalCloseField.isAccessible = true
        (intentionalCloseField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicBoolean).set(false)

        val generationField = HermesWsClient::class.java.getDeclaredField("connectionGeneration")
        generationField.isAccessible = true
        (generationField.get(HermesWsClient) as java.util.concurrent.atomic.AtomicInteger).set(1)

        val listenerClass = Class.forName("com.m57.hermescontrol.data.ws.HermesWsClient\$WsListenerImpl")
        val constructor = listenerClass.declaredConstructors.single()
        constructor.isAccessible = true
        val listener = constructor.newInstance(1) as WebSocketListener

        listener.onClosing(socket, 4401, "expired")
        HermesWsClient.send(WsMethods.PROMPT_SUBMIT, mapOf("session_id" to "s1", "text" to "hello"))

        assertEquals(ConnectionStatus.AUTH_EXPIRED, HermesWsClient.connectionStatus.value)
        io.mockk.verify(exactly = 0) { socket.cancel() }
    }

    @Test
    fun testReceiveMessage() {
        var serverWebSocket: WebSocket? = null
        val serverLatch = CountDownLatch(1)

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS))

        // Server sends a message to client
        val jsonResponse =
            """
            {
                "jsonrpc": "2.0",
                "id": "1",
                "result": "success"
            }
            """.trimIndent()

        val receivedEvent =
            runBlocking {
                withTimeout(5000) {
                    launch { serverWebSocket?.send(jsonResponse) }
                    HermesWsClient.events.first { it is WsEvent.RpcResult }
                }
            }

        assertTrue(receivedEvent is WsEvent.RpcResult)
        assertEquals("1", (receivedEvent as WsEvent.RpcResult).id)
    }

    @Test
    fun testDisconnect() {
        val serverLatch = CountDownLatch(1)
        val closedLatch = CountDownLatch(1)

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        closedLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS))

        HermesWsClient.disconnect(clearPendingMessages = true)
        assertFalse(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)

        // Verify server received close frame
        assertTrue(closedLatch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun testSendMessage() {
        var serverWebSocket: WebSocket? = null
        val serverLatch = CountDownLatch(1)
        val messageLatch = CountDownLatch(1)
        var receivedMessage: String? = null

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverWebSocket = webSocket
                        serverLatch.countDown()
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        receivedMessage = text
                        messageLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue("Server failed to accept connection", serverLatch.await(5, TimeUnit.SECONDS))

        // Use the convenience method
        HermesWsClient.sendMessage("test_session_id", "Hello Hermes!")

        // Verify message received by server
        assertTrue("Message not received", messageLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedMessage)
        val msg = receivedMessage ?: ""
        assertTrue(msg.contains(WsMethods.PROMPT_SUBMIT))
        assertTrue(msg.contains("test_session_id"))
        assertTrue(msg.contains("Hello Hermes!"))
    }

    @Test
    fun testAutoReconnect() {
        every { AuthManager.isAutoReconnect() } returns true

        var serverSocket1: WebSocket? = null
        var serverSocket2: WebSocket? = null

        val connect1Latch = CountDownLatch(1)
        val connect2Latch = CountDownLatch(1)

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverSocket1 = webSocket
                        connect1Latch.countDown()
                    }
                },
            ),
        )

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverSocket2 = webSocket
                        connect2Latch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()

        assertTrue("Failed initial connection", connect1Latch.await(5, TimeUnit.SECONDS))
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)

        // Force server to close socket 1 to trigger reconnect
        serverSocket1?.close(1001, "Server shutting down")

        // Wait for status to become RECONNECTING
        runBlocking {
            withTimeout(
                5000,
            ) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.RECONNECTING } }
        }

        // The client should now attempt to reconnect after initial backoff (1000ms)
        // Wait for the second connection to hit the server
        assertTrue("Failed to reconnect", connect2Latch.await(6, TimeUnit.SECONDS))
    }

    // ── TEST-10: WS reconnect state recovery ────────────────────────────

    @Test
    fun testBackoffResetsOnSuccessfulConnect() {
        every { AuthManager.isAutoReconnect() } returns true

        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }

        // After connect, backoff should be back to initial
        val backoffField = HermesWsClient::class.java.getDeclaredField("currentBackoff")
        backoffField.isAccessible = true
        assertEquals(
            "Backoff should reset to initial after successful connect",
            1000L,
            backoffField.getLong(HermesWsClient),
        )
    }

    @Test
    fun testIntentionalClosePreventsReconnect() {
        every { AuthManager.isAutoReconnect() } returns true

        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }

        // Disconnect — this sets intentionalClose = true and cancels reconnect
        HermesWsClient.disconnect(clearPendingMessages = true)

        assertFalse(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testDoubleConnect_ignoresSecondCallWhenConnected() {
        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertTrue(HermesWsClient.isConnected)

        // Second connect call should be a no-op
        HermesWsClient.connect()
        assertTrue(HermesWsClient.isConnected)
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testStatusTransitionOnConnect() {
        val serverLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        serverLatch.countDown()
                    }
                },
            ),
        )

        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)

        HermesWsClient.connect()

        // After connect(), status should be CONNECTING
        var status: ConnectionStatus
        val deadline = System.currentTimeMillis() + 2000
        do {
            status = HermesWsClient.connectionStatus.value
            if (status == ConnectionStatus.CONNECTING) break
            Thread.sleep(10)
        } while (System.currentTimeMillis() < deadline)
        assertEquals(ConnectionStatus.CONNECTING, status)

        // Wait for actual connection
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)
    }

    @Test
    fun testDisconnectWhileReconnecting_transitionsToDisconnected() {
        every { AuthManager.isAutoReconnect() } returns true

        val connectLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        connectLatch.countDown()
                    }
                },
            ),
        )

        // Enqueue a second response for reconnect attempt
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        // No-op — should be cancelled
                    }
                },
            ),
        )

        HermesWsClient.connect()
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS))
        runBlocking { withTimeout(5000) { HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED } } }

        // Disconnect (sets intentionalClose) — after this, reconnect should be prevented
        HermesWsClient.disconnect(clearPendingMessages = true)
        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
        assertFalse(HermesWsClient.isConnected)
    }

    // ── Issue #635: gated-mode WS ticket fetch must not be blocked by a
    // missing bare-name session cookie (HTTPS deployments prefix it with
    // __Host- / __Secure-). ────────────────────────────────────────────────

    @Test
    fun testGatedMode_attemptsTicketFetchWithoutBareCookie() {
        // Force gated mode (ws auth via ticket, not loopback token).
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config
                        .ServerStoreState(wsAuthParam = "ticket")
            }
        // No bare-name session cookie present (the prefixed one is server-side).
        every { AuthManager.getSessionCookie() } returns null
        // setToken is exercised by the ticket refresh; stub it (AuthManager is
        // a mocked object, so unstubbed calls throw).
        every { AuthManager.setToken(any()) } returns Unit

        // Separate server for the ticket endpoint so its queue can't interleave
        // with the WebSocket upgrade on the main mockWebServer.
        val ticketServer = MockWebServer()
        ticketServer.start()
        every { AuthManager.endpointForBuild() } returns
            ServerEndpoint.parse(
                ticketServer.url("/").toString(),
                CleartextPolicy.ALLOW_WITH_WARNING,
            )
        ticketServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ticket":"refreshed-ticket"}"""),
        )

        val connectLatch = CountDownLatch(1)
        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        ws: WebSocket,
                        response: okhttp3.Response,
                    ) {
                        connectLatch.countDown()
                    }
                },
            ),
        )

        HermesWsClient.connect()

        // Before the fix, a null bare cookie short-circuited to AUTH_EXPIRED and
        // the ticket endpoint was NEVER called. After the fix it is attempted,
        // so the connection reaches CONNECTED.
        assertTrue(
            "Gated WS ticket fetch should be attempted even without a bare cookie",
            connectLatch.await(5, TimeUnit.SECONDS),
        )
        // The server-side onOpen latch fires a hair before the client receives
        // the 101 handshake and WsListenerImpl sets CONNECTED — await the real
        // status transition (as every other connect test does) instead of a
        // racy read that can observe CONNECTING.
        runBlocking {
            withTimeout(5000) {
                HermesWsClient.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
        }
        assertEquals(ConnectionStatus.CONNECTED, HermesWsClient.connectionStatus.value)

        ticketServer.shutdown()
    }

    @Test
    fun testGatedMode_permanentTicketFailureStopsRetrying() {
        every { AuthManager.isAutoReconnect() } returns true
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config
                        .ServerStoreState(wsAuthParam = "ticket")
            }

        val ticketServer = MockWebServer()
        ticketServer.start()
        ticketServer.enqueue(MockResponse().setResponseCode(400))
        every { AuthManager.endpointForBuild() } returns
            ServerEndpoint.parse(
                ticketServer.url("/").toString(),
                CleartextPolicy.ALLOW_WITH_WARNING,
            )

        HermesWsClient.connect()

        assertEquals(ConnectionStatus.DISCONNECTED, HermesWsClient.connectionStatus.value)
        ticketServer.shutdown()
    }

    @Test
    fun testGatedMode_transientTicketFailureKeepsReconnectFlow() {
        every { AuthManager.isAutoReconnect() } returns true
        every { AuthManager.serverStore } returns
            mockk<com.m57.hermescontrol.data.config.ServerStore>().also {
                every { it.getLatestState() } returns
                    com.m57.hermescontrol.data.config
                        .ServerStoreState(wsAuthParam = "ticket")
            }

        val unavailableTicketServer = MockWebServer()
        unavailableTicketServer.start()
        val unavailableEndpoint = unavailableTicketServer.url("/").toString()
        unavailableTicketServer.shutdown()
        every { AuthManager.endpointForBuild() } returns
            ServerEndpoint.parse(
                unavailableEndpoint,
                CleartextPolicy.ALLOW_WITH_WARNING,
            )

        HermesWsClient.connect()

        assertEquals(ConnectionStatus.RECONNECTING, HermesWsClient.connectionStatus.value)
    }
}
