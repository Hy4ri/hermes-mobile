package com.m57.hermescontrol.ui.chat

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChatPastedImageStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun startupSweepDeletesOnlyPastedImagesOlderThanSevenDays() {
        val cacheDir = temp.newFolder("cache")
        val directory = cacheDir.resolve("pasted_images").apply { mkdir() }
        val now = 2_000_000_000_000L
        val cutoff = now - TimeUnit.DAYS.toMillis(7)
        val stale = stagePastedImage(ByteArrayInputStream(byteArrayOf(1)), directory, "png")!!
        assertTrue(stale.setLastModified(cutoff - 1000))
        val boundary = directory.resolve("boundary.png").apply { writeText("keep") }
        assertTrue(boundary.setLastModified(cutoff))
        val recent = directory.resolve("recent.png").apply { writeText("keep") }
        assertTrue(recent.setLastModified(now))
        val future = directory.resolve("future.png").apply { writeText("keep") }
        assertTrue(future.setLastModified(now + 1000))
        val unrelated = cacheDir.resolve("unrelated.png").apply { writeText("keep") }
        assertTrue(unrelated.setLastModified(cutoff - 1000))
        val nested =
            directory
                .resolve("nested")
                .apply { mkdir() }
                .resolve("old.png")
                .apply { writeText("keep") }
        assertTrue(nested.setLastModified(cutoff - 1000))

        cleanStalePastedImages(cacheDir, now)
        cleanStalePastedImages(cacheDir, now)

        assertFalse(stale.exists())
        listOf(boundary, recent, future, unrelated, nested).forEach { assertEquals("keep", it.readText()) }
    }

    @Test
    fun startupSweepDoesNotCreateAMissingCacheDirectory() {
        val cacheDir = temp.newFolder("empty-cache")

        cleanStalePastedImages(cacheDir)

        assertTrue(cacheDir.listFiles()!!.isEmpty())
    }

    @Test
    fun startupSweepPreservesAnImportCreatedAfterTheStartupSnapshot() {
        val cacheDir = temp.newFolder("live-cache")
        val directory = cacheDir.resolve("pasted_images")
        val startupTime = System.currentTimeMillis()
        val imported = stagePastedImage(ByteArrayInputStream(byteArrayOf(1, 2)), directory, "png")!!

        cleanStalePastedImages(cacheDir, startupTime)

        assertArrayEquals(byteArrayOf(1, 2), imported.readBytes())
    }

    @Test
    fun exactLimitIsAcceptedWithItsActualByteCount() {
        val dir = temp.newFolder("exact")
        val bytes = byteArrayOf(1, 2, 3, 4)

        val file = stagePastedImage(ByteArrayInputStream(bytes), dir, "png", maxBytes = 4)

        assertArrayEquals(bytes, file!!.readBytes())
        assertEquals(4L, file.length())
        assertTrue(file.name.endsWith(".png"))
        assertEquals(listOf(file), dir.listFiles()!!.toList())
    }

    @Test
    fun callerLimitAbovePerFileCapStillRejectsAfterTenMiBAndCleansUp() {
        val dir = temp.newFolder("per-file-cap")
        val input = ByteArrayInputStream(ByteArray(10 * 1024 * 1024 + 1))
        try {
            stagePastedImage(input, dir, "png", maxBytes = 20L * 1024 * 1024)
            fail("Expected the per-file limit")
        } catch (_: PastedImageTooLargeException) {
            assertTrue(dir.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun unknownSizeIsBoundedByTheActualStreamAndReadsAtMostLimitPlusOne() {
        val dir = temp.newFolder("oversize")
        val input =
            object : ByteArrayInputStream(ByteArray(100)) {
                var consumed = 0

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = super.read(b, off, len).also { if (it > 0) consumed += it }
            }

        try {
            stagePastedImage(input, dir, "png", maxBytes = 4)
            fail("Expected size limit rejection")
        } catch (_: PastedImageTooLargeException) {
            // The exact limit is accepted by the preceding test; only the extra byte rejects.
        }
        assertEquals(5, input.consumed)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun emptyStreamIsRejectedAndLeavesNoCacheFile() {
        val dir = temp.newFolder("empty")

        assertNull(stagePastedImage(ByteArrayInputStream(byteArrayOf()), dir, "webp", maxBytes = 4))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun streamFailureDeletesPartialFileAndClosesSource() {
        val dir = temp.newFolder("broken")
        val input =
            object : InputStream() {
                var reads = 0
                var closed = false

                override fun read(): Int = if (reads++ == 0) 7 else throw IOException("provider failed")

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int =
                    if (reads++ == 0) {
                        b[off] = 7
                        1
                    } else {
                        throw IOException("provider failed")
                    }

                override fun close() {
                    closed = true
                }
            }

        try {
            stagePastedImage(input, dir, "png", maxBytes = 4)
            fail("Expected stream failure")
        } catch (_: IOException) {
            assertTrue(input.closed)
            assertTrue(dir.listFiles()!!.isEmpty())
        }
    }

    @Test
    fun existingNameCollisionNeverTruncatesOrDeletesExistingFile() {
        val dir = temp.newFolder("collision")
        val id = UUID.randomUUID().toString()
        val sentinel = dir.resolve("$id.png")
        sentinel.writeText("keep me")

        try {
            stagePastedImage(ByteArrayInputStream(byteArrayOf(1)), dir, "png", newId = { id })
            fail("Expected exclusive-create failure")
        } catch (_: IOException) {
            assertEquals("keep me", sentinel.readText())
            assertEquals(listOf(sentinel), dir.listFiles()!!.toList())
        }
    }

    @Test
    fun simultaneousImportsUseDistinctFilesAndKeepTheirOwnBytes() {
        val dir = temp.newFolder("concurrent")
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks =
                (1..16).map { n ->
                    Callable {
                        val bytes = ByteArray(n) { n.toByte() }
                        val file = stagePastedImage(ByteArrayInputStream(bytes), dir, "jpeg", maxBytes = 64)!!
                        file to bytes
                    }
                }
            val results = executor.invokeAll(tasks).map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(16, results.map { it.first.name }.toSet().size)
            results.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
            assertEquals(16, dir.listFiles()!!.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationCheckDeletesPartialFileAndPropagatesCancellation() {
        val dir = temp.newFolder("cancel")
        var checks = 0
        val input = ByteArrayInputStream(ByteArray(100))

        try {
            stagePastedImage(
                input,
                dir,
                "png",
                maxBytes = 100,
                checkActive = {
                    if (++checks == 2) throw CancellationException("cancelled")
                },
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(dir.listFiles()!!.isEmpty())
            assertTrue(checks >= 2)
        }
    }

    @Test
    fun aDiscardedResultDeletesOnlyItsOwnedFile() {
        val dir = temp.newFolder("discard")
        val file = stagePastedImage(ByteArrayInputStream(byteArrayOf(1)), dir, "png")!!
        val unrelated = dir.resolve("unrelated.png").apply { writeText("keep") }
        val result =
            PastedImage(
                attachment =
                    com.m57.hermescontrol.data.model.Attachment(
                        uri = "content://example/file",
                        name = file.name,
                        mimeType = "image/png",
                        size = 1,
                    ),
                file = file,
            )

        result.discard()
        assertFalse(file.exists())
        assertTrue(unrelated.exists())
        assertEquals("keep", unrelated.readText())
    }
}
