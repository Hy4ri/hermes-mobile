package com.m57.hermescontrol.ui.chat.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.timeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

fun interface SpeechStreamingPlayer {
    /** False permits REST fallback, but only if no audio has started. Cancellation always propagates. */
    suspend fun play(
        request: SpeechRequest,
        onPlaying: () -> Unit,
    ): Boolean

    fun takeCachedAudio(): SpeechAudio? = null

    fun clearCache() = Unit
}

internal interface PcmSpeechOutput : AutoCloseable {
    suspend fun write(pcm: ByteArray)

    suspend fun drain()
}

/** Validates the wire format and owns output until the last scheduled sample has played. */
@OptIn(FlowPreview::class)
internal class StreamingSpeechPlayer(
    private val frames: (SpeechRequest) -> Flow<SpeechStreamFrame>,
    private val outputFactory: suspend (sampleRate: Int) -> PcmSpeechOutput,
    private val idleTimeoutMs: Long = 180_000L,
    private val cacheFactory: suspend (Int) -> SpeechPcmCache? = { null },
) : SpeechStreamingPlayer {
    private var completedAudio: SpeechAudio? = null

    override fun takeCachedAudio(): SpeechAudio? = completedAudio.also { completedAudio = null }

    override fun clearCache() {
        completedAudio?.dispose()
        completedAudio = null
    }

    override suspend fun play(
        request: SpeechRequest,
        onPlaying: () -> Unit,
    ): Boolean {
        clearCache()
        var output: PcmSpeechOutput? = null
        var cache: SpeechPcmCache? = null
        var played = false
        var ended = false
        var fallback = false
        var carry: Byte? = null
        try {
            frames(request)
                .timeout(idleTimeoutMs.milliseconds)
                .takeWhile { frame ->
                    when (frame) {
                        is SpeechStreamFrame.Text -> {
                            val node = Json.parseToJsonElement(frame.value).jsonObject
                            when (node["type"]?.jsonPrimitive?.content) {
                                "start" -> {
                                    if (output != null) throw IOException("Duplicate speech start")
                                    val rate = node["sample_rate"]?.jsonPrimitive?.intOrNull
                                    val channels = node["channels"]?.jsonPrimitive?.intOrNull
                                    if (rate == null || rate !in 8000..48000 || channels != 1) {
                                        throw IOException("Unsupported speech format")
                                    }
                                    output = outputFactory(rate)
                                    cache =
                                        try {
                                            cacheFactory(rate)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: IOException) {
                                            null
                                        }
                                }

                                "end" -> {
                                    if (output == null || carry != null) throw IOException("Incomplete speech audio")
                                    ended = true
                                }

                                "fallback" -> {
                                    if (played) throw IOException("Speech fallback after partial playback")
                                    fallback = true
                                }

                                else -> {
                                    throw IOException("Unknown speech control frame")
                                }
                            }
                        }

                        is SpeechStreamFrame.Audio -> {
                            val sink = output ?: throw IOException("Speech audio arrived before its format")
                            val incoming = frame.bytes.toByteArray()
                            val bytes = carry?.let { byteArrayOf(it) + incoming } ?: incoming
                            val usable = bytes.size - bytes.size % 2
                            carry = if (usable < bytes.size) bytes.last() else null
                            var offset = 0
                            while (offset < usable) {
                                currentCoroutineContext().ensureActive()
                                val end = minOf(offset + 4096, usable)
                                // Once any samples are submitted, a failed write must not replay them via REST.
                                played = true
                                val pcm = bytes.copyOfRange(offset, end)
                                sink.write(pcm)
                                onPlaying()
                                try {
                                    cache?.append(pcm)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: IOException) {
                                    cache?.close()
                                    cache = null
                                }
                                offset = end
                            }
                        }
                    }
                    !ended && !fallback
                }.collect()
            if (fallback) return false
            if (!ended) throw IOException("Speech stream has no end frame")
            output?.drain()
            if (played) {
                try {
                    cache?.seal()
                    completedAudio = cache?.takeAudio()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    // Disk failure must not turn successfully played speech into an error.
                }
            }
            return true
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (e !is TimeoutCancellationException) throw e
            if (played) throw IOException("Speech stream stalled", e)
            return false
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (played) throw IOException("Speech playback interrupted", e)
            return false
        } finally {
            try {
                output?.close()
            } finally {
                cache?.close()
            }
        }
    }
}

/** Desktop's blocking TTS budget, separate from ordinary REST requests. */
internal fun speechRequestTimeoutMs(text: String): Long = (text.length.toLong() * 35L).coerceIn(180_000L, 600_000L)
