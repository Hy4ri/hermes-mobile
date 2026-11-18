package com.m57.hermescontrol.ui.chat.components

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SpeechStreamClientTest {
    @Test
    fun `normal server close immediately after end does not interrupt buffered playback`() =
        runBlocking {
            val server = MockWebServer()
            val client = OkHttpClient()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            if (text.contains("done")) {
                                webSocket.send("""{"type":"start","sample_rate":24000,"channels":1}""")
                                webSocket.send(ByteArray(48_000).toByteString())
                                webSocket.send("""{"type":"end"}""")
                                webSocket.close(1000, "complete")
                            }
                        }
                    },
                ),
            )
            server.start()
            try {
                val stream =
                    SpeechStreamClient(client) { Request.Builder().url(server.url("/api/audio/speak-stream")).build() }
                var drained = false
                val player =
                    StreamingSpeechPlayer(
                        frames = { stream.frames(it.text) },
                        outputFactory = {
                            object : PcmSpeechOutput {
                                override suspend fun write(pcm: ByteArray) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        kotlinx.coroutines.delay(50)
                                    }
                                }

                                override suspend fun drain() {
                                    drained = true
                                }

                                override fun close() = Unit
                            }
                        },
                    )
                assertTrue(withTimeout(5000) { player.play(SpeechRequest("scope", "id", "hello")) {} })
                assertTrue(drained)
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                server.shutdown()
            }
        }

    @Test
    fun `slow playback does not block pong handling`() =
        runBlocking {
            val server = MockWebServer()
            val client = OkHttpClient.Builder().pingInterval(100, TimeUnit.MILLISECONDS).build()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            if (text.contains("done")) {
                                webSocket.send("""{"type":"start","sample_rate":24000,"channels":1}""")
                                repeat(20) { webSocket.send(byteArrayOf(1, 2).toByteString()) }
                                webSocket.send("""{"type":"end"}""")
                            }
                        }
                    },
                ),
            )
            server.start()
            try {
                val stream =
                    SpeechStreamClient(client) { Request.Builder().url(server.url("/api/audio/speak-stream")).build() }
                var frames = 0
                withTimeout(5000) {
                    stream.frames("Hello").take(22).collect {
                        if (frames++ == 0) kotlinx.coroutines.delay(600)
                    }
                }
                assertEquals(22, frames)
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                server.shutdown()
            }
        }

    @Test
    fun `real socket sends text and done and delivers PCM before end`() =
        runBlocking {
            val server = MockWebServer()
            val client = OkHttpClient()
            val received = mutableListOf<String>()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            synchronized(received) { received.add(text) }
                            if (text.contains("done")) {
                                webSocket.send("""{"type":"start","sample_rate":24000,"channels":1}""")
                                webSocket.send(byteArrayOf(1, 2).toByteString())
                            }
                        }
                    },
                ),
            )
            server.start()
            try {
                val stream =
                    SpeechStreamClient(client) {
                        Request
                            .Builder()
                            .url(
                                server.url("/prefix/api/audio/speak-stream?token=secret&profile=beta"),
                            ).build()
                    }
                val frames = withTimeout(5000) { stream.frames("Hello").take(2).toList() }
                assertEquals(2, frames.size)
                assertEquals(byteArrayOf(1, 2).toByteString(), (frames[1] as SpeechStreamFrame.Audio).bytes)
                synchronized(received) {
                    assertEquals(listOf("""{"text":"Hello"}""", """{"done":true}"""), received)
                }
                assertEquals(
                    "/prefix/api/audio/speak-stream?token=secret&profile=beta",
                    server.takeRequest(2, TimeUnit.SECONDS)!!.path,
                )
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                server.shutdown()
            }
        }

    @Test
    fun `old gateway handshake fails promptly`() =
        runBlocking {
            val server = MockWebServer()
            val client = OkHttpClient()
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            try {
                val stream =
                    SpeechStreamClient(client) { Request.Builder().url(server.url("/api/audio/speak-stream")).build() }
                try {
                    withTimeout(5000) { stream.frames("Hello").toList() }
                    fail("404 must fail")
                } catch (
                    _: java.io.IOException,
                ) {
                }
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                server.shutdown()
            }
        }
}
