package com.m57.hermescontrol.data.ws

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import okhttp3.OkHttpClient
import okhttp3.Request
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

class MockWebSocketCleanupTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
        every { Log.println(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun peerCloseAllowsMockServerShutdownWithoutLeavingRunningTasks() {
        val server = MockWebServer()
        val client = OkHttpClient()
        val serverSocket = AtomicReference<WebSocket>()
        val opened = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        server.enqueue(
            MockResponse().withClosingWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        serverSocket.set(webSocket)
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        closing.countDown()
                    }
                },
            ),
        )
        server.start()
        val socket =
            client.newWebSocket(
                Request.Builder().url(server.url("/")).build(),
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        opened.countDown()
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        closed.countDown()
                    }
                },
            )
        try {
            assertTrue("The WebSocket must open", opened.await(5, TimeUnit.SECONDS))
            assertTrue(socket.close(1000, "test complete"))
            assertTrue("The mock peer must observe the close", closing.await(5, TimeUnit.SECONDS))
            // Complete the handshake before shutdown stops the server's task runner.
            assertTrue("The close handshake must complete", closed.await(5, TimeUnit.SECONDS))
            // No fixed sleep or swallowed shutdown exception: an active server queue fails this regression.
            server.shutdown()
        } finally {
            socket.cancel()
            // MockWebServer's accepted socket has no outbound Call, so cancel() is not supported.
            serverSocket.get()?.close(1000, "cleanup")
            try {
                server.shutdown()
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }
}
