package com.m57.hermescontrol.ui.chat.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechPcmCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `completed PCM has valid WAV header and exact bytes until owner disposes it`() =
        runTest {
            val cache = SpeechPcmCache(temporary.root, 24000)
            val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
            cache.append(pcm)
            cache.seal()
            val audio = cache.takeAudio() as FileSpeechAudio
            cache.close()
            val bytes = audio.file.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals("WAVE", String(bytes, 8, 4))
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(42, header.getInt(4))
            assertEquals(24000, header.getInt(24))
            assertEquals(48000, header.getInt(28))
            assertEquals(6, header.getInt(40))
            assertArrayEquals(pcm, bytes.copyOfRange(44, bytes.size))
            audio.dispose()
            assertFalse(audio.file.exists())
        }

    @Test
    fun `unfinished or sealed but unclaimed cache is deleted on cancellation`() =
        runTest {
            for (seal in listOf(false, true)) {
                val cache = SpeechPcmCache(temporary.root, 24000)
                cache.append(byteArrayOf(1, 2))
                if (seal) cache.seal()
                cache.close()
                assertTrue(temporary.root.walkTopDown().none { it.isFile })
            }
        }

    @Test
    fun `cancelled return dispatch closes newly allocated output`() =
        runTest {
            var closed = false
            lateinit var job: Job
            val dispatcher = StandardTestDispatcher(testScheduler)
            job =
                launch {
                    openSpeechResource(dispatcher) {
                        job.cancel()
                        AutoCloseable { closed = true }
                    }
                }
            testScheduler.advanceUntilIdle()
            assertTrue(closed)
        }
}
