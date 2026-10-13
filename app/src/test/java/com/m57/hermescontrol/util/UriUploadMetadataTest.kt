package com.m57.hermescontrol.util

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UriUploadMetadataTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun returnsMetadataFromValidCursorAndMimeType() =
        runTest {
            val contentResolver = mockk<ContentResolver>()
            val uri = mockk<Uri>()
            val cursor = mockk<Cursor>()

            every {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            } returns cursor

            every { cursor.moveToFirst() } returns true
            every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
            every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns 1
            every { cursor.isNull(0) } returns false
            every { cursor.isNull(1) } returns false
            every { cursor.getString(0) } returns "backup.tar.gz"
            every { cursor.getLong(1) } returns 2048L
            every { cursor.close() } returns Unit
            every { contentResolver.getType(uri) } returns "application/gzip"

            val metadata = contentResolver.uploadMetadata(uri, fallbackName = "default.bin")

            assertEquals("backup.tar.gz", metadata.name)
            assertEquals("application/gzip", metadata.mimeType)
            assertEquals(2048L, metadata.size)
            verify(exactly = 1) { cursor.close() }
        }

    @Test
    fun cursorClosedAlwaysEvenWhenQueryThrows() =
        runTest {
            val contentResolver = mockk<ContentResolver>()
            val uri = mockk<Uri>()
            val cursor = mockk<Cursor>()

            every {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            } returns cursor

            every { cursor.moveToFirst() } throws IllegalStateException("Cursor corrupted")
            every { cursor.close() } returns Unit

            try {
                contentResolver.uploadMetadata(uri, fallbackName = "fallback.bin")
                fail("Expected IllegalStateException")
            } catch (e: IllegalStateException) {
                assertEquals("Cursor corrupted", e.message)
                verify(exactly = 1) { cursor.close() }
            }
        }

    @Test
    fun handlesNullCursorGracefully() =
        runTest {
            val contentResolver = mockk<ContentResolver>()
            val uri = mockk<Uri>()

            every {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            } returns null
            every { contentResolver.getType(uri) } returns "image/png"

            val metadata = contentResolver.uploadMetadata(uri, fallbackName = "photo.png")

            assertEquals("photo.png", metadata.name)
            assertEquals("image/png", metadata.mimeType)
            assertEquals(-1L, metadata.size)
        }

    @Test
    fun handlesEmptyCursorWhereMoveToFirstReturnsFalse() =
        runTest {
            val contentResolver = mockk<ContentResolver>()
            val uri = mockk<Uri>()
            val cursor = mockk<Cursor>()

            every {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            } returns cursor
            every { cursor.moveToFirst() } returns false
            every { cursor.close() } returns Unit
            every { contentResolver.getType(uri) } returns null

            val metadata = contentResolver.uploadMetadata(uri, fallbackName = "empty_cursor.bin")

            assertEquals("empty_cursor.bin", metadata.name)
            assertEquals("application/octet-stream", metadata.mimeType)
            assertEquals(-1L, metadata.size)
            verify(exactly = 1) { cursor.close() }
        }

    @Test
    fun handlesMissingColumnsWhereGetColumnIndexReturnsNegative() =
        runTest {
            val contentResolver = mockk<ContentResolver>()
            val uri = mockk<Uri>()
            val cursor = mockk<Cursor>()

            every {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            } returns cursor
            every { cursor.moveToFirst() } returns true
            every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns -1
            every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns -1
            every { cursor.close() } returns Unit
            every { contentResolver.getType(uri) } returns "text/plain"

            val metadata = contentResolver.uploadMetadata(uri, fallbackName = "notes.txt")

            assertEquals("notes.txt", metadata.name)
            assertEquals("text/plain", metadata.mimeType)
            assertEquals(-1L, metadata.size)
            verify(exactly = 1) { cursor.close() }
        }

    @Test
    fun handlesNullColumnValuesInCursor() =
        runTest {
            val contentResolver = mockk<ContentResolver>()
            val uri = mockk<Uri>()
            val cursor = mockk<Cursor>()

            every {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            } returns cursor
            every { cursor.moveToFirst() } returns true
            every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
            every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns 1
            every { cursor.isNull(0) } returns true
            every { cursor.isNull(1) } returns true
            every { cursor.close() } returns Unit
            every { contentResolver.getType(uri) } returns "application/json"

            val metadata = contentResolver.uploadMetadata(uri, fallbackName = "default.json")

            assertEquals("default.json", metadata.name)
            assertEquals("application/json", metadata.mimeType)
            assertEquals(-1L, metadata.size)
            verify(exactly = 1) { cursor.close() }
        }

    @Test
    fun handlesBlankOrEmptyDisplayNameFallingBackToFallbackName() =
        runTest {
            val contentResolver = mockk<ContentResolver>()
            val uri = mockk<Uri>()
            val cursor = mockk<Cursor>()

            every {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            } returns cursor
            every { cursor.moveToFirst() } returns true
            every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
            every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns 1
            every { cursor.isNull(0) } returns false
            every { cursor.isNull(1) } returns false
            every { cursor.getString(0) } returns "   "
            every { cursor.getLong(1) } returns 1024L
            every { cursor.close() } returns Unit
            every { contentResolver.getType(uri) } returns "text/csv"

            val metadata = contentResolver.uploadMetadata(uri, fallbackName = "table.csv")

            assertEquals("table.csv", metadata.name)
            assertEquals("text/csv", metadata.mimeType)
            assertEquals(1024L, metadata.size)
        }

    @Test
    fun handlesNegativeSizeInCursorByNormalizingToMinusOne() =
        runTest {
            val contentResolver = mockk<ContentResolver>()
            val uri = mockk<Uri>()
            val cursor = mockk<Cursor>()

            every {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            } returns cursor
            every { cursor.moveToFirst() } returns true
            every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
            every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns 1
            every { cursor.isNull(0) } returns false
            every { cursor.isNull(1) } returns false
            every { cursor.getString(0) } returns "audio.wav"
            every { cursor.getLong(1) } returns -5L
            every { cursor.close() } returns Unit
            every { contentResolver.getType(uri) } returns "audio/wav"

            val metadata = contentResolver.uploadMetadata(uri, fallbackName = "audio.wav")

            assertEquals(-1L, metadata.size)
        }

    @Test
    fun defaultsMimeTypeToOctetStreamWhenGetTypeReturnsNull() =
        runTest {
            val contentResolver = mockk<ContentResolver>()
            val uri = mockk<Uri>()

            every {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            } returns null
            every { contentResolver.getType(uri) } returns null

            val metadata = contentResolver.uploadMetadata(uri, fallbackName = "data.bin")

            assertEquals("application/octet-stream", metadata.mimeType)
        }

    @Test
    fun throwsWhenKnownSizeExceeds100MiB() =
        runTest {
            val contentResolver = mockk<ContentResolver>()
            val uri = mockk<Uri>()
            val cursor = mockk<Cursor>()
            val overlimitBytes = StreamingUriRequestBody.MAX_UPLOAD_BYTES + 1L

            every {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            } returns cursor
            every { cursor.moveToFirst() } returns true
            every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
            every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns 1
            every { cursor.isNull(0) } returns false
            every { cursor.isNull(1) } returns false
            every { cursor.getString(0) } returns "huge_archive.zip"
            every { cursor.getLong(1) } returns overlimitBytes
            every { cursor.close() } returns Unit

            try {
                contentResolver.uploadMetadata(uri, fallbackName = "archive.zip")
                fail("Expected IOException when size exceeds 100MiB")
            } catch (e: IOException) {
                assertEquals("Selected file exceeds the upload size limit", e.message)
                verify(exactly = 1) { cursor.close() }
            }
        }

    @Test
    fun executesOffMainThreadOnDispatchersIO() =
        runTest {
            val contentResolver = mockk<ContentResolver>()
            val uri = mockk<Uri>()
            var executedThreadName = ""

            every {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            } answers {
                executedThreadName = Thread.currentThread().name
                null
            }
            every { contentResolver.getType(uri) } returns "text/plain"

            // Dispatch uploadMetadata via test coroutine
            val deferred =
                async {
                    contentResolver.uploadMetadata(uri, fallbackName = "test.txt")
                }

            testDispatcher.scheduler.advanceUntilIdle()
            val metadata = deferred.await()

            assertEquals("test.txt", metadata.name)
            assertTrue("Expected execution off main thread", executedThreadName.isNotEmpty())
        }
}
