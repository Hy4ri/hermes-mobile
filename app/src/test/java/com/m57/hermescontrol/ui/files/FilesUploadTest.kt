package com.m57.hermescontrol.ui.files

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.m57.hermescontrol.data.model.ManagedFileActionResponse
import com.m57.hermescontrol.data.model.ManagedFileEntry
import com.m57.hermescontrol.data.model.ManagedFilesListResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.util.StreamingUriRequestBody
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class FilesUploadTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockApi = mockk(relaxed = true)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /** Real-executor tests: drive the test scheduler until the VM's continuation settles. */
    private fun awaitSettled(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "ViewModel work did not settle" }
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(5)
        }
    }

    private fun createDiscardSink(): BufferedSink {
        val discardSink = mockk<BufferedSink>(relaxed = true)
        every { discardSink.write(any<ByteArray>(), any(), any()) } answers { discardSink }
        every { discardSink.write(any<okio.ByteString>()) } answers { discardSink }
        return discardSink
    }

    private fun readRequestBodyText(body: RequestBody): String {
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun mockContentResolverWithCursor(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String?,
        size: Long?,
        mimeType: String? = "application/octet-stream",
    ): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        every {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )
        } returns cursor
        every { resolver.getType(uri) } returns mimeType

        every { cursor.moveToFirst() } returns true
        every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns 1

        if (displayName != null) {
            every { cursor.isNull(0) } returns false
            every { cursor.getString(0) } returns displayName
        } else {
            every { cursor.isNull(0) } returns true
            every { cursor.getString(0) } returns null
        }

        if (size != null) {
            every { cursor.isNull(1) } returns false
            every { cursor.getLong(1) } returns size
        } else {
            every { cursor.isNull(1) } returns true
        }

        return cursor
    }

    @Test
    fun uploadFile_executesMultipartBodyStreamAndTransfersDataWithoutHeapMaterialization() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()
            val totalBytes = 25_000L

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "report.pdf",
                size = totalBytes,
                mimeType = "application/pdf",
            )

            val streamClosed = AtomicBoolean(false)
            val readCount = AtomicLong(0)
            val generatedStream =
                object : InputStream() {
                    private var remaining = totalBytes

                    override fun read(): Int =
                        if (remaining-- > 0) {
                            readCount.incrementAndGet()
                            0x5A
                        } else {
                            -1
                        }

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val toRead = minOf(len.toLong(), remaining).toInt()
                        remaining -= toRead
                        b.fill(0x5A.toByte(), off, off + toRead)
                        readCount.addAndGet(toRead.toLong())
                        return toRead
                    }

                    override fun close() {
                        streamClosed.set(true)
                        super.close()
                    }
                }
            every { mockResolver.openInputStream(mockUri) } returns generatedStream

            val pathSlot = slot<RequestBody>()
            val overwriteSlot = slot<RequestBody>()
            val fileSlot = slot<MultipartBody.Part>()

            coEvery {
                mockApi.uploadManagedFileStream(
                    path = capture(pathSlot),
                    overwrite = capture(overwriteSlot),
                    file = capture(fileSlot),
                )
            } coAnswers {
                val part = fileSlot.captured
                val sink = createDiscardSink()
                part.body.writeTo(sink)
                Response.success(ManagedFileActionResponse(ok = true, path = "uploaded"))
            }

            val viewModel = FilesViewModel(ioDispatcher = testDispatcher)
            viewModel.uploadFile(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) {
                mockApi.uploadManagedFileStream(any(), any(), any())
            }

            // files.py upload-stream: `path` is the destination file path.
            assertEquals("report.pdf", readRequestBodyText(pathSlot.captured))
            assertEquals("true", readRequestBodyText(overwriteSlot.captured))
            assertNotNull(fileSlot.captured)

            assertEquals(totalBytes, readCount.get())
            assertTrue("Input stream must be closed after writing to sink", streamClosed.get())
            assertFalse(viewModel.uiState.value.isUploading)
            assertNull(viewModel.uiState.value.uploadProgress)
            assertEquals("File uploaded", viewModel.uiState.value.toastMessage)
        }

    @Test
    fun uploadFile_readsMetadataAndStreamsOffMainDispatcher() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()

            val queryThreadName = CompletableDeferred<String>()
            val readThreadName = CompletableDeferred<String>()

            val cursor = mockk<Cursor>(relaxed = true)
            every {
                mockResolver.query(
                    mockUri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            } answers {
                queryThreadName.complete(Thread.currentThread().name)
                cursor
            }
            every { mockResolver.getType(mockUri) } returns "text/plain"
            every { cursor.moveToFirst() } returns true
            every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
            every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns 1
            every { cursor.isNull(0) } returns false
            every { cursor.getString(0) } returns "sample.txt"
            every { cursor.isNull(1) } returns false
            every { cursor.getLong(1) } returns 100L

            val stream =
                object : ByteArrayInputStream("sample payload".toByteArray(Charsets.UTF_8)) {
                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        if (!readThreadName.isCompleted) {
                            readThreadName.complete(Thread.currentThread().name)
                        }
                        return super.read(b, off, len)
                    }
                }
            every { mockResolver.openInputStream(mockUri) } returns stream

            val fileSlot = slot<MultipartBody.Part>()
            val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "test-io-worker") }
            val ioDispatcher = ioExecutor.asCoroutineDispatcher()

            try {
                coEvery {
                    mockApi.uploadManagedFileStream(any(), any(), capture(fileSlot))
                } coAnswers {
                    kotlinx.coroutines.withContext(ioDispatcher) {
                        val discardSink = createDiscardSink()
                        fileSlot.captured.body.writeTo(discardSink)
                    }
                    Response.success(ManagedFileActionResponse(ok = true))
                }

                val viewModel = FilesViewModel(ioDispatcher = ioDispatcher)
                viewModel.uploadFile(uri = mockUri, contentResolver = mockResolver)

                testDispatcher.scheduler.advanceUntilIdle()

                val queryThread = queryThreadName.await()
                val readThread = readThreadName.await()
                // Let the VM continuation finish before the executor shuts down and Main is reset.
                awaitSettled { !viewModel.uiState.value.isUploading }

                assertFalse(
                    "query must not run on main test thread",
                    queryThread.startsWith("main") || queryThread.contains("Test worker"),
                )
                // Coroutine debug mode appends " @coroutine#N" to the thread name.
                assertTrue(readThread.startsWith("test-io-worker"))
            } finally {
                ioExecutor.shutdown()
            }
        }

    @Test
    fun uploadFile_fallsBackWhenCursorIsNullAndHandlesMissingMimeType() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()

            every {
                mockResolver.query(
                    mockUri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            } returns null
            every { mockResolver.getType(mockUri) } returns null

            val streamClosed = AtomicBoolean(false)
            val stream =
                object : ByteArrayInputStream("fallback data".toByteArray(Charsets.UTF_8)) {
                    override fun close() {
                        streamClosed.set(true)
                        super.close()
                    }
                }
            every { mockResolver.openInputStream(mockUri) } returns stream

            val fileSlot = slot<MultipartBody.Part>()
            coEvery {
                mockApi.uploadManagedFileStream(any(), any(), capture(fileSlot))
            } coAnswers {
                fileSlot.captured.body.writeTo(createDiscardSink())
                Response.success(ManagedFileActionResponse(ok = true))
            }

            val viewModel = FilesViewModel(ioDispatcher = testDispatcher)
            viewModel.uploadFile(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { mockApi.uploadManagedFileStream(any(), any(), any()) }
            assertTrue("Stream must be closed", streamClosed.get())
            assertFalse(viewModel.uiState.value.isUploading)
            assertNull(viewModel.uiState.value.uploadProgress)
            assertEquals("File uploaded", viewModel.uiState.value.toastMessage)
        }

    @Test
    fun uploadFile_updatesProgressMonotonicallyForKnownSize() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()
            val fileSize = 40_000L

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "known.bin",
                size = fileSize,
            )

            val stream =
                object : InputStream() {
                    private var remaining = fileSize

                    override fun read(): Int = if (remaining-- > 0) 1 else -1

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val toRead = minOf(len.toLong(), remaining).toInt()
                        remaining -= toRead
                        b.fill(1.toByte(), off, off + toRead)
                        return toRead
                    }
                }
            every { mockResolver.openInputStream(mockUri) } returns stream

            val observedProgress = mutableListOf<Float?>()
            val fileSlot = slot<MultipartBody.Part>()

            val viewModel = FilesViewModel(ioDispatcher = testDispatcher)

            val progressJob =
                backgroundScope.launch(Dispatchers.Unconfined) {
                    viewModel.uiState.collect { state ->
                        observedProgress.add(state.uploadProgress)
                    }
                }

            coEvery {
                mockApi.uploadManagedFileStream(any(), any(), capture(fileSlot))
            } coAnswers {
                val part = fileSlot.captured
                part.body.writeTo(createDiscardSink())
                Response.success(ManagedFileActionResponse(ok = true))
            }

            viewModel.uploadFile(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.advanceUntilIdle()

            progressJob.cancel()

            val nonNullProgress = observedProgress.filterNotNull()
            assertTrue("Progress should have been recorded", nonNullProgress.isNotEmpty())

            for (i in 1 until nonNullProgress.size) {
                val prev = nonNullProgress[i - 1]
                val curr = nonNullProgress[i]
                assertTrue("Progress must be monotonic: $curr >= $prev", curr >= prev)
            }
            assertTrue("Final known progress should reach 1.0f", nonNullProgress.last() >= 0.99f)
            assertNull("UploadProgress must reset to null when finished", viewModel.uiState.value.uploadProgress)
        }

    @Test
    fun uploadFile_keepsUploadProgressNullForUnknownSize() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "stream.bin",
                size = null,
            )

            val stream = ByteArrayInputStream("indeterminate stream bytes".toByteArray(Charsets.UTF_8))
            every { mockResolver.openInputStream(mockUri) } returns stream

            val observedProgress = mutableListOf<Float?>()
            val fileSlot = slot<MultipartBody.Part>()

            val viewModel = FilesViewModel(ioDispatcher = testDispatcher)
            val progressJob =
                backgroundScope.launch(Dispatchers.Unconfined) {
                    viewModel.uiState.collect { state ->
                        observedProgress.add(state.uploadProgress)
                    }
                }

            coEvery {
                mockApi.uploadManagedFileStream(any(), any(), capture(fileSlot))
            } coAnswers {
                fileSlot.captured.body.writeTo(createDiscardSink())
                Response.success(ManagedFileActionResponse(ok = true))
            }

            viewModel.uploadFile(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.advanceUntilIdle()

            progressJob.cancel()

            assertTrue(
                "UploadProgress must remain null for unknown size throughout upload",
                observedProgress.all {
                    it ==
                        null
                },
            )
            assertFalse(viewModel.uiState.value.isUploading)
        }

    @Test
    fun uploadFile_enforces100MiBLimitBeforeNetworkCall() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()
            val oversizedBytes = 101L * 1024L * 1024L

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "too_large.zip",
                size = oversizedBytes,
            )

            val viewModel = FilesViewModel(ioDispatcher = testDispatcher)
            viewModel.uploadFile(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) {
                mockApi.uploadManagedFileStream(any(), any(), any())
            }

            assertFalse(viewModel.uiState.value.isUploading)
            assertNull(viewModel.uiState.value.uploadProgress)
            assertTrue(
                "Toast message must indicate size limit error",
                viewModel.uiState.value.toastMessage
                    ?.contains("exceeds", ignoreCase = true) == true ||
                    viewModel.uiState.value.toastMessage
                        ?.contains("size limit", ignoreCase = true) == true,
            )
        }

    @Test
    fun uploadFile_duplicateUploadIgnoredWhileUploadIsRunning() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "first.txt",
                size = 50L,
            )
            every { mockResolver.openInputStream(mockUri) } answers {
                ByteArrayInputStream("first content".toByteArray(Charsets.UTF_8))
            }

            val deferredResponse = CompletableDeferred<Response<ManagedFileActionResponse>>()
            coEvery {
                mockApi.uploadManagedFileStream(any(), any(), any())
            } coAnswers {
                deferredResponse.await()
            }

            val viewModel = FilesViewModel(ioDispatcher = testDispatcher)
            viewModel.uploadFile(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.runCurrent()
            assertTrue(viewModel.uiState.value.isUploading)

            val mockUri2 = mockk<Uri>()
            viewModel.uploadFile(uri = mockUri2, contentResolver = mockResolver)
            testDispatcher.scheduler.runCurrent()

            coVerify(exactly = 1) {
                mockApi.uploadManagedFileStream(any(), any(), any())
            }

            deferredResponse.complete(Response.success(ManagedFileActionResponse(ok = true)))
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isUploading)
        }

    @Test
    fun uploadFile_serverFailureReleasesIsUploadingAndUploadProgressInFinally() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "fail.txt",
                size = 20L,
            )
            every { mockResolver.openInputStream(mockUri) } answers {
                ByteArrayInputStream("fail".toByteArray(Charsets.UTF_8))
            }

            val invocationCount = AtomicInteger(0)
            coEvery {
                mockApi.uploadManagedFileStream(any(), any(), any())
            } answers {
                invocationCount.incrementAndGet()
                throw IOException("Socket broken")
            }

            val viewModel = FilesViewModel(ioDispatcher = testDispatcher)
            viewModel.uploadFile(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("SafeApiCall retries must be 0 for upload mutation", 1, invocationCount.get())
            assertFalse(viewModel.uiState.value.isUploading)
            assertNull(viewModel.uiState.value.uploadProgress)
            assertTrue(
                "Toast message should reflect failure",
                viewModel.uiState.value.toastMessage
                    ?.contains("failed", ignoreCase = true) == true ||
                    viewModel.uiState.value.toastMessage
                        ?.contains("error", ignoreCase = true) == true ||
                    viewModel.uiState.value.toastMessage
                        ?.contains("Socket broken", ignoreCase = true) == true,
            )
        }

    @Test
    fun uploadFile_cancellationClosesProviderStream() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()
            val streamClosed = AtomicBoolean(false)

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "cancel_me.bin",
                size = 50_000L,
            )

            val stream =
                object : InputStream() {
                    private var remaining = 50_000L

                    override fun read(): Int = if (remaining-- > 0) 1 else -1

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val toRead = minOf(len.toLong(), remaining).toInt()
                        remaining -= toRead
                        return toRead
                    }

                    override fun close() {
                        streamClosed.set(true)
                        super.close()
                    }
                }
            every { mockResolver.openInputStream(mockUri) } returns stream

            val fileSlot = slot<MultipartBody.Part>()
            val uploadStarted = CompletableDeferred<Unit>()

            coEvery {
                mockApi.uploadManagedFileStream(any(), any(), capture(fileSlot))
            } coAnswers {
                uploadStarted.complete(Unit)
                val part = fileSlot.captured
                try {
                    part.body.writeTo(createDiscardSink())
                } catch (e: IOException) {
                    throw e
                }
                Response.success(ManagedFileActionResponse(ok = true))
            }

            val viewModel = FilesViewModel(ioDispatcher = testDispatcher)

            val uploadScopeJob =
                launch {
                    viewModel.uploadFile(uri = mockUri, contentResolver = mockResolver)
                }

            testDispatcher.scheduler.runCurrent()
            uploadStarted.await()

            uploadScopeJob.cancel(CancellationException("User navigated away"))
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue("Provider stream must be closed upon cancellation", streamClosed.get())
            assertFalse(viewModel.uiState.value.isUploading)
            assertNull(viewModel.uiState.value.uploadProgress)
        }

    @Test
    fun uploadFile_retainsCurrentPathInMultipartBodyPart() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "nested.txt",
                size = 10L,
            )
            every { mockResolver.openInputStream(mockUri) } returns
                ByteArrayInputStream("nested".toByteArray(Charsets.UTF_8))

            val pathSlot = slot<RequestBody>()
            val overwriteSlot = slot<RequestBody>()
            val fileSlot = slot<MultipartBody.Part>()

            coEvery {
                mockApi.uploadManagedFileStream(
                    path = capture(pathSlot),
                    overwrite = capture(overwriteSlot),
                    file = capture(fileSlot),
                )
            } coAnswers {
                fileSlot.captured.body.writeTo(createDiscardSink())
                Response.success(ManagedFileActionResponse(ok = true))
            }

            // navigateTo() adopts the server-confirmed listing path, so the listing must succeed.
            coEvery { mockApi.listManagedFiles("projects/my-app") } returns
                Response.success(ManagedFilesListResponse(path = "projects/my-app"))

            val viewModel = FilesViewModel(ioDispatcher = testDispatcher)
            viewModel.navigateTo(ManagedFileEntry(path = "projects/my-app", isDirectory = true))
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.uploadFile(uri = mockUri, contentResolver = mockResolver)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("projects/my-app/nested.txt", readRequestBodyText(pathSlot.captured))
            assertEquals("true", readRequestBodyText(overwriteSlot.captured))
        }
}
