package com.m57.hermescontrol.util

import android.content.ContentResolver
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.Buffer
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StreamingUriRequestBodyTest {
    @Test
    fun isOneShot_returnsTrue() {
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()
        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = "application/gzip".toMediaTypeOrNull(),
                contentLength = 100L,
            )

        assertTrue(requestBody.isOneShot())
    }

    @Test
    fun contentLengthAndType_reflectConstructorParameters() {
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()
        val mediaType = "application/json".toMediaTypeOrNull()
        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = mediaType,
                contentLength = 42L,
            )

        assertEquals(mediaType, requestBody.contentType())
        assertEquals(42L, requestBody.contentLength())
    }

    @Test
    fun defaultContentLength_isMinusOne() {
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()
        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = null,
            )

        assertEquals(-1L, requestBody.contentLength())
    }

    @Test
    fun sourceNotOpenedUntilWriteTo() {
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()
        val opened = AtomicBoolean(false)

        every { mockResolver.openInputStream(mockUri) } answers {
            opened.set(true)
            ByteArrayInputStream(ByteArray(0))
        }

        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = null,
            )

        assertFalse("openInputStream must not be called upon construction", opened.get())

        requestBody.writeTo(Buffer())

        assertTrue("openInputStream must be called during writeTo", opened.get())
    }

    @Test
    fun streamsChunksOfAtMost8192WithoutMaterializingWholePayloadInHeap() {
        val totalBytes = 30000L
        val maxChunkRead = AtomicInteger(0)
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()

        val generatedStream =
            object : InputStream() {
                private var bytesLeft = totalBytes

                override fun read(): Int {
                    if (bytesLeft <= 0) return -1
                    bytesLeft--
                    return 0x7F
                }

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (bytesLeft <= 0) return -1
                    val toRead = minOf(len.toLong(), bytesLeft).toInt()
                    if (toRead > maxChunkRead.get()) {
                        maxChunkRead.set(toRead)
                    }
                    b.fill(0x7F.toByte(), off, off + toRead)
                    bytesLeft -= toRead
                    return toRead
                }
            }

        every { mockResolver.openInputStream(mockUri) } returns generatedStream

        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = null,
                contentLength = totalBytes,
            )

        var totalReceived = 0L
        val discardSink = mockk<BufferedSink>(relaxed = true)
        every { discardSink.write(any<ByteArray>(), any(), any()) } answers {
            val byteCount = arg<Int>(2)
            totalReceived += byteCount
            discardSink
        }

        requestBody.writeTo(discardSink)

        assertEquals(totalBytes, totalReceived)
        assertTrue("Max chunk read should be bounded by 8192 bytes", maxChunkRead.get() <= 8192)
        assertEquals(8192, maxChunkRead.get())
    }

    @Test
    fun progressMonotonicWithKnownSize() {
        val payloadSize = 25000L
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()

        val stream =
            object : InputStream() {
                private var remaining = payloadSize

                override fun read(): Int = if (remaining-- > 0) 1 else -1

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(len.toLong(), remaining).toInt()
                    remaining -= count
                    return count
                }
            }

        every { mockResolver.openInputStream(mockUri) } returns stream

        val progressUpdates = mutableListOf<Pair<Long, Long>>()
        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = null,
                contentLength = payloadSize,
                onProgress = { written, total ->
                    progressUpdates.add(written to total)
                },
            )

        val discardSink = mockk<BufferedSink>(relaxed = true)
        requestBody.writeTo(discardSink)

        assertTrue("Expected progress updates", progressUpdates.isNotEmpty())
        assertEquals(0L to payloadSize, progressUpdates.first())
        assertEquals(payloadSize to payloadSize, progressUpdates.last())

        for (i in 1 until progressUpdates.size) {
            val prev = progressUpdates[i - 1]
            val curr = progressUpdates[i]
            assertTrue("Progress written bytes must be monotonic", curr.first >= prev.first)
            assertEquals(payloadSize, curr.second)
        }
    }

    @Test
    fun progressMonotonicWithUnknownSize() {
        val payloadSize = 18000L
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()

        val stream =
            object : InputStream() {
                private var remaining = payloadSize

                override fun read(): Int = if (remaining-- > 0) 1 else -1

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(len.toLong(), remaining).toInt()
                    remaining -= count
                    return count
                }
            }

        every { mockResolver.openInputStream(mockUri) } returns stream

        val progressUpdates = mutableListOf<Pair<Long, Long>>()
        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = null,
                contentLength = -1L,
                onProgress = { written, total ->
                    progressUpdates.add(written to total)
                },
            )

        val discardSink = mockk<BufferedSink>(relaxed = true)
        requestBody.writeTo(discardSink)

        assertTrue(progressUpdates.isNotEmpty())
        assertEquals(0L to -1L, progressUpdates.first())
        assertEquals(payloadSize to -1L, progressUpdates.last())

        for (i in 1 until progressUpdates.size) {
            val prev = progressUpdates[i - 1]
            val curr = progressUpdates[i]
            assertTrue("Progress written bytes must be monotonic", curr.first >= prev.first)
            assertEquals(-1L, curr.second)
        }
    }

    @Test
    fun throwsWhenContentLengthExceedsMaxBytesBeforeOpening() {
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()
        val opened = AtomicBoolean(false)

        every { mockResolver.openInputStream(mockUri) } answers {
            opened.set(true)
            ByteArrayInputStream(ByteArray(0))
        }

        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = null,
                contentLength = 1000L,
                maxBytes = 500L,
            )

        try {
            requestBody.writeTo(mockk<BufferedSink>(relaxed = true))
            fail("Expected IOException when contentLength > maxBytes")
        } catch (e: IOException) {
            assertEquals("Selected file exceeds the upload size limit", e.message)
            assertFalse("Stream must not be opened if declared length exceeds limit", opened.get())
        }
    }

    @Test
    fun throwsAndClosesStreamWhenActualBytesExceedMaxBytesWithMisreportedLength() {
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()
        val closed = AtomicBoolean(false)

        val stream =
            object : InputStream() {
                private var remaining = 20000L

                override fun read(): Int = if (remaining-- > 0) 1 else -1

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(len.toLong(), remaining).toInt()
                    remaining -= count
                    return count
                }

                override fun close() {
                    closed.set(true)
                    super.close()
                }
            }

        every { mockResolver.openInputStream(mockUri) } returns stream

        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = null,
                contentLength = 100L,
                maxBytes = 10000L,
            )

        try {
            requestBody.writeTo(mockk<BufferedSink>(relaxed = true))
            fail("Expected IOException when actual bytes exceed limit")
        } catch (e: IOException) {
            assertEquals("Selected file exceeds the upload size limit", e.message)
            assertTrue("Stream must be closed on size limit error", closed.get())
        }
    }

    @Test
    fun throwsAndClosesStreamWhenActualBytesExceedMaxBytesWithUnknownLength() {
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()
        val closed = AtomicBoolean(false)

        val stream =
            object : InputStream() {
                private var remaining = 15000L

                override fun read(): Int = if (remaining-- > 0) 1 else -1

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(len.toLong(), remaining).toInt()
                    remaining -= count
                    return count
                }

                override fun close() {
                    closed.set(true)
                    super.close()
                }
            }

        every { mockResolver.openInputStream(mockUri) } returns stream

        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = null,
                contentLength = -1L,
                maxBytes = 8192L,
            )

        try {
            requestBody.writeTo(mockk<BufferedSink>(relaxed = true))
            fail("Expected IOException when actual bytes exceed limit")
        } catch (e: IOException) {
            assertEquals("Selected file exceeds the upload size limit", e.message)
            assertTrue("Stream must be closed on size limit error", closed.get())
        }
    }

    @Test
    fun succeedsAtExactMaxBytesBoundary() {
        val exactLimit = 16384L
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()
        val closed = AtomicBoolean(false)

        val stream =
            object : InputStream() {
                private var remaining = exactLimit

                override fun read(): Int = if (remaining-- > 0) 1 else -1

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(len.toLong(), remaining).toInt()
                    remaining -= count
                    return count
                }

                override fun close() {
                    closed.set(true)
                    super.close()
                }
            }

        every { mockResolver.openInputStream(mockUri) } returns stream

        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = null,
                contentLength = exactLimit,
                maxBytes = exactLimit,
            )

        var totalWritten = 0L
        val sink = mockk<BufferedSink>(relaxed = true)
        every { sink.write(any<ByteArray>(), any(), any()) } answers {
            val count = arg<Int>(2)
            totalWritten += count
            sink
        }

        requestBody.writeTo(sink)

        assertEquals(exactLimit, totalWritten)
        assertTrue("Stream must be closed upon completion", closed.get())
    }

    @Test
    fun cancelledBeforeWrite_throwsImmediatelyWithoutOpeningStream() {
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()
        val opened = AtomicBoolean(false)

        every { mockResolver.openInputStream(mockUri) } answers {
            opened.set(true)
            ByteArrayInputStream(ByteArray(0))
        }

        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = null,
                isCancelled = { true },
            )

        try {
            requestBody.writeTo(mockk<BufferedSink>(relaxed = true))
            fail("Expected IOException when pre-cancelled")
        } catch (e: IOException) {
            assertEquals("Upload cancelled", e.message)
            assertFalse("Stream must not be opened if cancelled beforehand", opened.get())
        }
    }

    @Test
    fun cancelledMidStream_throwsAndClosesStream() {
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()
        val closed = AtomicBoolean(false)
        var shouldCancel = false

        val stream =
            object : InputStream() {
                private var remaining = 30000L

                override fun read(): Int = if (remaining-- > 0) 1 else -1

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(len.toLong(), remaining).toInt()
                    remaining -= count
                    return count
                }

                override fun close() {
                    closed.set(true)
                    super.close()
                }
            }

        every { mockResolver.openInputStream(mockUri) } returns stream

        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = null,
                onProgress = { written, _ ->
                    if (written >= 8192L) {
                        shouldCancel = true
                    }
                },
                isCancelled = { shouldCancel },
            )

        try {
            requestBody.writeTo(mockk<BufferedSink>(relaxed = true))
            fail("Expected IOException on mid-stream cancellation")
        } catch (e: IOException) {
            assertEquals("Upload cancelled", e.message)
            assertTrue("Stream must be closed on mid-stream cancellation", closed.get())
        }
    }

    @Test
    fun sinkFailure_closesStream() {
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()
        val closed = AtomicBoolean(false)

        val stream =
            object : InputStream() {
                private var remaining = 20000L

                override fun read(): Int = if (remaining-- > 0) 1 else -1

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(len.toLong(), remaining).toInt()
                    remaining -= count
                    return count
                }

                override fun close() {
                    closed.set(true)
                    super.close()
                }
            }

        every { mockResolver.openInputStream(mockUri) } returns stream

        val failingSink = mockk<BufferedSink>()
        every { failingSink.write(any<ByteArray>(), any(), any()) } throws IOException("Socket write failed")

        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = null,
            )

        try {
            requestBody.writeTo(failingSink)
            fail("Expected IOException from failing sink")
        } catch (e: IOException) {
            assertEquals("Socket write failed", e.message)
            assertTrue("Stream must be closed when sink throws", closed.get())
        }
    }

    @Test
    fun throwsWhenContentResolverReturnsNullStream() {
        val mockResolver = mockk<ContentResolver>()
        val mockUri = mockk<Uri>()

        every { mockResolver.openInputStream(mockUri) } returns null

        val requestBody =
            StreamingUriRequestBody(
                contentResolver = mockResolver,
                uri = mockUri,
                contentType = null,
            )

        try {
            requestBody.writeTo(mockk<BufferedSink>(relaxed = true))
            fail("Expected IOException when stream is null")
        } catch (e: IOException) {
            assertEquals("Unable to open input stream for selected file", e.message)
        }
    }
}
