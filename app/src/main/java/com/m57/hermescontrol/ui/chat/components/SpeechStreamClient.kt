package com.m57.hermescontrol.ui.chat.components

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

internal sealed interface SpeechStreamFrame {
    data class Text(
        val value: String,
    ) : SpeechStreamFrame

    data class Audio(
        val bytes: ByteString,
    ) : SpeechStreamFrame
}

/** One socket per reply. A bounded backlog preserves PCM order without blocking ping/pong reads. */
internal class SpeechStreamClient(
    private val client: OkHttpClient,
    private val requestProvider: suspend () -> Request,
) {
    fun frames(text: String): Flow<SpeechStreamFrame> =
        flow {
            val request = requestProvider()
            val events = Channel<SpeechStreamFrame>(MAX_PENDING_FRAMES)
            val pendingBytes = AtomicInteger(0)
            val socket =
                client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            val accepted =
                                webSocket.send(buildJsonObject { put("text", text) }.toString()) &&
                                    webSocket.send("""{"done":true}""")
                            if (!accepted) {
                                events.close(IOException("Could not submit speech text"))
                                webSocket.cancel()
                            }
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            if (text.length > MAX_CONTROL_CHARS) {
                                events.close(IOException("Oversized speech control frame"))
                                webSocket.cancel()
                            } else {
                                deliver(webSocket, SpeechStreamFrame.Text(text))
                            }
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            bytes: ByteString,
                        ) {
                            if (bytes.size > MAX_AUDIO_BYTES) {
                                events.close(IOException("Oversized speech audio frame"))
                                webSocket.cancel()
                            } else if (bytes.size > 0) {
                                deliver(webSocket, SpeechStreamFrame.Audio(bytes))
                            }
                        }

                        private fun deliver(
                            webSocket: WebSocket,
                            event: SpeechStreamFrame,
                        ) {
                            val cost = bufferedSize(event)
                            if (pendingBytes.addAndGet(cost) > MAX_PENDING_BYTES) {
                                pendingBytes.addAndGet(-cost)
                                events.close(IOException("Speech audio buffer limit exceeded"))
                                webSocket.cancel()
                                return
                            }
                            // The reader must process pongs even while the speaker is busy.
                            if (events.trySend(event).isFailure) {
                                pendingBytes.addAndGet(-cost)
                                events.close(IOException("Speech frame buffer limit exceeded"))
                                webSocket.cancel()
                            }
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            events.close()
                        }

                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            webSocket.close(code, null)
                            // Drain queued end/audio normally; the protocol player rejects a missing end.
                            events.close()
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            response?.close()
                            events.close(IOException("Speech stream connection failed", t))
                        }
                    },
                )
            try {
                for (event in events) {
                    pendingBytes.addAndGet(-bufferedSize(event))
                    emit(event)
                }
            } finally {
                events.cancel()
                socket.cancel()
            }
        }

    companion object {
        // Per-frame limit; queued audio also has independent byte/event budgets.
        const val MAX_AUDIO_BYTES = 16 * 1024 * 1024
        private const val MAX_CONTROL_CHARS = 4096
        private const val MAX_PENDING_BYTES = 32 * 1024 * 1024
        private const val MAX_PENDING_FRAMES = 16_384

        private fun bufferedSize(frame: SpeechStreamFrame): Int =
            when (frame) {
                is SpeechStreamFrame.Audio -> frame.bytes.size
                is SpeechStreamFrame.Text -> frame.value.length * 2
            }
    }
}
