package com.m57.hermescontrol.ui.chat.components

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingSpeechPlayerTest {
    private class Output : PcmSpeechOutput {
        val bytes = mutableListOf<Byte>()
        var drained = false
        var closed = false

        override suspend fun write(pcm: ByteArray) {
            bytes.addAll(pcm.toList())
        }

        override suspend fun drain() {
            drained = true
        }

        override fun close() {
            closed = true
        }
    }

    private val request = SpeechRequest("scope", "message", "Hello.")

    private fun text(value: String) = SpeechStreamFrame.Text(value)

    private fun audio(vararg bytes: Int) =
        SpeechStreamFrame.Audio(bytes.map { it.toByte() }.toByteArray().toByteString())

    @Test
    fun `plays before end and preserves odd byte boundaries then drains`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val output = Output()
            var playing = false
            val player =
                StreamingSpeechPlayer(
                    frames = {
                        flow {
                            emit(text("""{"type":"start","sample_rate":24000,"channels":1}"""))
                            emit(audio(1, 2, 3))
                            emit(audio(4, 5, 6))
                            gate.await()
                            emit(text("""{"type":"end"}"""))
                        }
                    },
                    outputFactory = { rate ->
                        assertEquals(24000, rate)
                        output
                    },
                )
            val job = launch { assertTrue(player.play(request) { playing = true }) }
            runCurrent()
            assertTrue(playing)
            assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), output.bytes)
            assertFalse(output.drained)
            assertFalse(job.isCompleted)
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(output.drained)
            assertTrue(output.closed)
        }

    @Test
    fun `fallback and unavailable endpoint before audio return false`() =
        runTest {
            for (frames in listOf(
                flow<SpeechStreamFrame> { emit(text("""{"type":"fallback"}""")) },
                flow { throw IOException("404") },
            )) {
                val player = StreamingSpeechPlayer({ frames }, { error("must not allocate output") })
                assertFalse(player.play(request) { error("must not start") })
            }
        }

    @Test
    fun `drop after audio fails without fallback or drain`() =
        runTest {
            val output = Output()
            val player =
                StreamingSpeechPlayer({
                    flow {
                        emit(text("""{"type":"start","sample_rate":24000,"channels":1}"""))
                        emit(audio(1, 2))
                        throw IOException("connection lost")
                    }
                }, { output })
            try {
                player.play(request) {}
                fail("must fail after partial speech")
            } catch (_: IOException) {
            }
            assertTrue(output.closed)
            assertFalse(output.drained)
        }

    @Test
    fun `cancellation closes output and stream without fallback`() =
        runTest {
            val output = Output()
            var streamClosed = false
            val player =
                StreamingSpeechPlayer({
                    flow {
                        try {
                            emit(text("""{"type":"start","sample_rate":24000,"channels":1}"""))
                            emit(audio(1, 2))
                            awaitCancellation()
                        } finally {
                            streamClosed = true
                        }
                    }
                }, { output })
            val job = launch { player.play(request) {} }
            runCurrent()
            job.cancel()
            advanceUntilIdle()
            assertTrue(output.closed)
            assertTrue(streamClosed)
            assertFalse(output.drained)
        }

    @Test
    fun `invalid format never reaches hardware`() =
        runTest {
            for (start in listOf(
                """{"type":"start","sample_rate":0,"channels":1}""",
                """{"type":"start","sample_rate":24000,"channels":2}""",
                """{"type":"start","sample_rate":24000}""",
            )) {
                val player =
                    StreamingSpeechPlayer({
                        flow {
                            emit(text(start))
                            emit(audio(1, 2))
                        }
                    }, { error("bad format") })
                assertFalse(player.play(request) {})
            }
        }

    @Test
    fun `incomplete sample at end fails after partial speech`() =
        runTest {
            val output = Output()
            val player =
                StreamingSpeechPlayer({
                    flow {
                        emit(text("""{"type":"start","sample_rate":24000,"channels":1}"""))
                        emit(audio(1, 2, 3))
                        emit(text("""{"type":"end"}"""))
                    }
                }, { output })
            try {
                player.play(request) {}
                fail("odd tail must fail")
            } catch (_: IOException) {
            }
            assertTrue(output.closed)
        }

    @Test
    fun `first audio timeout is bounded and cancellation is not fallback`() =
        runTest {
            val player =
                StreamingSpeechPlayer({ flow { awaitCancellation() } }, { error("no audio") }, idleTimeoutMs = 50)
            assertFalse(player.play(request) {})
        }
}
