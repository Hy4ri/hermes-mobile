package com.m57.hermescontrol.ui.system

import android.app.Application
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.ActionResponse
import com.m57.hermescontrol.data.model.ActionStatusResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.util.StreamingUriRequestBody
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class SystemImportStreamingTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApp = mockk<Application>(relaxed = true)
    private lateinit var mockApi: HermesApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        // Use the VM's injected ownership seams instead of mocking the AuthManager singleton,
        // whose real initialization in sibling suites makes object mocking order-dependent.
        currentScope =
            DataScope(
                connectionProfileId = "server-primary",
                baseUrl = "https://primary.example.com",
                activeProfileId = "alpha-profile",
                inMemoryAuthGeneration = 0L,
            )
        currentService = mockApi
        // A relaxed Response never reports success, so the 1.2 s action-log poll would loop forever under
        // advanceUntilIdle. Default to a finished action; tests asserting polling override this stub.
        coEvery { mockApi.getActionStatus(any()) } returns
            Response.success(ActionStatusResponse(name = "default", running = false, exit_code = 0))
    }

    private var currentScope: DataScope? = null
    private lateinit var currentService: HermesApiService

    private val createdViewModels = mutableListOf<SystemViewModel>()

    private fun newViewModel(ioDispatcher: CoroutineDispatcher = testDispatcher): SystemViewModel =
        SystemViewModel(
            mockApp,
            migrationDispatcher = testDispatcher,
            ioDispatcher = ioDispatcher,
            getCurrentScope = { currentScope },
            getApiService = { currentService },
        ).also { createdViewModels += it }

    @After
    fun tearDown() {
        // Cancel viewModelScope so no job outlives the test, then park Main on an inert dispatcher:
        // cancelled continuations resume on worker threads and would throw if Main were reset.
        createdViewModels.forEach { vm ->
            androidx.lifecycle.ViewModel::class.java.declaredMethods
                .firstOrNull { it.name.startsWith("clear") && it.parameterCount == 0 }
                ?.apply { isAccessible = true }
                ?.invoke(vm)
        }
        createdViewModels.clear()
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
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
        mimeType: String? = "application/tar+gzip",
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
    fun importArchive_streamsDirectlyViaStreamingUriRequestBodyAndPreservesMultipartContract() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()
            val totalBytes = 32_000L

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "hermes-backup.tar.gz",
                size = totalBytes,
                mimeType = "application/gzip",
            )

            val streamClosed = AtomicBoolean(false)
            val readBytesCount = AtomicLong(0L)
            val generatedStream =
                object : InputStream() {
                    private var remaining = totalBytes

                    override fun read(): Int =
                        if (remaining-- > 0) {
                            readBytesCount.incrementAndGet()
                            0x7E
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
                        b.fill(0x7E.toByte(), off, off + toRead)
                        readBytesCount.addAndGet(toRead.toLong())
                        return toRead
                    }

                    override fun close() {
                        streamClosed.set(true)
                        super.close()
                    }
                }
            every { mockResolver.openInputStream(mockUri) } returns generatedStream

            val forceSlot = slot<RequestBody>()
            val fileSlot = slot<MultipartBody.Part>()
            val profileSlot = slot<String?>()

            coEvery {
                mockApi.importUpload(
                    force = capture(forceSlot),
                    file = capture(fileSlot),
                    profile = captureNullable(profileSlot),
                )
            } coAnswers {
                val part = fileSlot.captured
                val sink = createDiscardSink()
                part.body.writeTo(sink)
                Response.success(
                    ActionResponse(
                        ok = true,
                        name = "system-restore",
                        pid = 4040,
                    ),
                )
            }
            coEvery { mockApi.getActionStatus("system-restore") } returns
                Response.success(
                    ActionStatusResponse(
                        name = "system-restore",
                        running = false,
                        exit_code = 0,
                        lines = listOf("Archive unpacked", "Restore finished"),
                    ),
                )

            val viewModel =
                newViewModel()
            viewModel.importArchive(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) {
                mockApi.importUpload(any(), any(), profile = eq("alpha-profile"))
            }

            assertEquals("false", readRequestBodyText(forceSlot.captured))
            assertEquals("alpha-profile", profileSlot.captured)
            val part = fileSlot.captured
            assertNotNull(part)
            assertTrue(
                "file part body must be StreamingUriRequestBody",
                part.body is StreamingUriRequestBody,
            )

            assertEquals(totalBytes, readBytesCount.get())
            assertTrue("Input stream must be closed after writing to sink", streamClosed.get())
            assertFalse(viewModel.uiState.value.isImporting)
            assertNull(viewModel.uiState.value.importProgress)
        }

    @Test
    fun importArchive_readsMetadataAndStreamsOffMainDispatcher() =
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
            every { mockResolver.getType(mockUri) } returns "application/gzip"
            every { cursor.moveToFirst() } returns true
            every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
            every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns 1
            every { cursor.isNull(0) } returns false
            every { cursor.getString(0) } returns "backup.tar.gz"
            every { cursor.isNull(1) } returns false
            every { cursor.getLong(1) } returns 128L

            val stream =
                object : ByteArrayInputStream("archive header and entries".toByteArray(Charsets.UTF_8)) {
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
            val ioExecutor = Executors.newSingleThreadExecutor { Thread(it, "system-import-worker") }
            val ioDispatcher = ioExecutor.asCoroutineDispatcher()

            try {
                coEvery {
                    mockApi.importUpload(any(), capture(fileSlot), any())
                } coAnswers {
                    kotlinx.coroutines.withContext(ioDispatcher) {
                        val discardSink = createDiscardSink()
                        fileSlot.captured.body.writeTo(discardSink)
                    }
                    Response.success(ActionResponse(ok = true, name = "system-restore", pid = 777))
                }

                val viewModel =
                    newViewModel(ioDispatcher = ioDispatcher)
                viewModel.importArchive(uri = mockUri, contentResolver = mockResolver)

                testDispatcher.scheduler.advanceUntilIdle()

                val queryThread = queryThreadName.await()
                val readThread = readThreadName.await()
                awaitSettled { !viewModel.uiState.value.isImporting }

                assertFalse(
                    "query must not run on main test thread",
                    queryThread.startsWith("main") || queryThread.contains("Test worker"),
                )
                // Coroutine debug mode appends " @coroutine#N" to the thread name.
                assertTrue(readThread.startsWith("system-import-worker"))
            } finally {
                ioExecutor.shutdown()
            }
        }

    @Test
    fun importArchive_rejectsKnownFileOver100MiBBeforeHTTPCall() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()
            val oversizedBytes = 100L * 1024L * 1024L + 1L

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "huge-backup.tar.gz",
                size = oversizedBytes,
            )

            val viewModel =
                newViewModel()
            viewModel.importArchive(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) {
                mockApi.importUpload(any(), any(), any())
            }

            assertFalse(viewModel.uiState.value.isImporting)
            assertNull(viewModel.uiState.value.importProgress)
            assertTrue(
                "Toast message must indicate upload size limit failure",
                viewModel.uiState.value.toastMessage
                    ?.contains("exceeds", ignoreCase = true) == true ||
                    viewModel.uiState.value.toastMessage
                        ?.contains("limit", ignoreCase = true) == true,
            )
        }

    @Test
    fun importArchive_reportsMonotonicProgressForKnownSize() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()
            val archiveSize = 50_000L

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "measurable-backup.tar.gz",
                size = archiveSize,
            )

            val stream =
                object : InputStream() {
                    private var remaining = archiveSize

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

            val viewModel =
                newViewModel()

            val progressJob =
                backgroundScope.launch(Dispatchers.Unconfined) {
                    viewModel.uiState.collect { state ->
                        observedProgress.add(state.importProgress)
                    }
                }

            coEvery {
                mockApi.importUpload(any(), capture(fileSlot), any())
            } coAnswers {
                val part = fileSlot.captured
                part.body.writeTo(createDiscardSink())
                Response.success(ActionResponse(ok = true, name = "restore-poll"))
            }

            viewModel.importArchive(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.advanceUntilIdle()
            progressJob.cancel()

            val nonNullProgress = observedProgress.filterNotNull()
            assertTrue("Monotonic progress samples should be observed", nonNullProgress.isNotEmpty())

            for (i in 1 until nonNullProgress.size) {
                val prev = nonNullProgress[i - 1]
                val curr = nonNullProgress[i]
                assertTrue("Progress must be monotonic: $curr >= $prev", curr >= prev)
            }
            assertTrue("Final known progress must reach >= 0.99f", nonNullProgress.last() >= 0.99f)
            assertNull("importProgress resets to null when upload finishes", viewModel.uiState.value.importProgress)
            assertFalse(viewModel.uiState.value.isImporting)
        }

    @Test
    fun importArchive_keepsProgressNullThroughoutForUnknownSize() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "indeterminate.tar.gz",
                size = null,
            )

            val stream = ByteArrayInputStream("indeterminate stream payload".toByteArray(Charsets.UTF_8))
            every { mockResolver.openInputStream(mockUri) } returns stream

            val observedProgress = mutableListOf<Float?>()
            val fileSlot = slot<MultipartBody.Part>()

            val viewModel =
                newViewModel()

            val progressJob =
                backgroundScope.launch(Dispatchers.Unconfined) {
                    viewModel.uiState.collect { state ->
                        observedProgress.add(state.importProgress)
                    }
                }

            coEvery {
                mockApi.importUpload(any(), capture(fileSlot), any())
            } coAnswers {
                fileSlot.captured.body.writeTo(createDiscardSink())
                Response.success(ActionResponse(ok = true, name = "action-indet"))
            }

            viewModel.importArchive(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.advanceUntilIdle()
            progressJob.cancel()

            assertTrue("importProgress must remain null for unknown size", observedProgress.all { it == null })
            assertFalse(viewModel.uiState.value.isImporting)
        }

    @Test
    fun importArchive_ignoresDuplicateTriggerWhileImportIsRunning() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri1 = mockk<Uri>()
            val mockUri2 = mockk<Uri>()

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri1,
                displayName = "first.tar.gz",
                size = 100L,
            )
            every { mockResolver.openInputStream(mockUri1) } answers {
                ByteArrayInputStream("first content".toByteArray(Charsets.UTF_8))
            }

            val deferredUpload = CompletableDeferred<Response<ActionResponse>>()
            coEvery {
                mockApi.importUpload(any(), any(), any())
            } coAnswers {
                deferredUpload.await()
            }

            val viewModel =
                newViewModel()
            viewModel.importArchive(uri = mockUri1, contentResolver = mockResolver)

            testDispatcher.scheduler.runCurrent()
            assertTrue("isImporting must be true while upload is in flight", viewModel.uiState.value.isImporting)

            viewModel.importArchive(uri = mockUri2, contentResolver = mockResolver)
            testDispatcher.scheduler.runCurrent()

            coVerify(exactly = 1) {
                mockApi.importUpload(any(), any(), any())
            }

            deferredUpload.complete(Response.success(ActionResponse(ok = true, name = "first-restore")))
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isImporting)
        }

    @Test
    fun importArchive_usesCapturedServiceOwnerEvenIfAmbientScopeChangesWhileQueued() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "scoped.tar.gz",
                size = 50L,
            )
            every { mockResolver.openInputStream(mockUri) } answers {
                ByteArrayInputStream("scoped bytes".toByteArray(Charsets.UTF_8))
            }

            val capturedProfileSlot = slot<String?>()
            val initialService = mockApi
            val replacementService = mockk<HermesApiService>(relaxed = true)

            val fileSlot = slot<MultipartBody.Part>()
            coEvery {
                initialService.importUpload(any(), capture(fileSlot), profile = captureNullable(capturedProfileSlot))
            } coAnswers {
                fileSlot.captured.body.writeTo(createDiscardSink())
                Response.success(ActionResponse(ok = true, name = "scoped-restore"))
            }

            val viewModel =
                newViewModel()

            viewModel.importArchive(uri = mockUri, contentResolver = mockResolver)

            currentScope =
                DataScope(
                    connectionProfileId = "server-secondary",
                    baseUrl = "https://secondary.example.com",
                    activeProfileId = "beta-profile",
                    inMemoryAuthGeneration = 0L,
                )
            currentService = replacementService
            every { ApiClient.hermesApi } returns replacementService

            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) {
                initialService.importUpload(any(), any(), profile = eq("alpha-profile"))
            }
            coVerify(exactly = 0) {
                replacementService.importUpload(any(), any(), any())
            }
            assertEquals("alpha-profile", capturedProfileSlot.captured)
        }

    @Test
    fun importArchive_successfulActionResponseTriggersActionPollingExactlyOnce() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "valid.tar.gz",
                size = 60L,
            )
            every { mockResolver.openInputStream(mockUri) } answers {
                ByteArrayInputStream("valid payload".toByteArray(Charsets.UTF_8))
            }

            val invocationCount = AtomicInteger(0)
            coEvery {
                mockApi.importUpload(any(), any(), any())
            } answers {
                invocationCount.incrementAndGet()
                Response.success(
                    ActionResponse(
                        ok = true,
                        name = "hermes-import",
                        pid = 5555,
                    ),
                )
            }

            coEvery { mockApi.getActionStatus("hermes-import") } returnsMany
                listOf(
                    Response.success(
                        ActionStatusResponse(
                            name = "hermes-import",
                            running = true,
                            lines = listOf("Extracting files..."),
                        ),
                    ),
                    Response.success(
                        ActionStatusResponse(
                            name = "hermes-import",
                            running = false,
                            exit_code = 0,
                            lines = listOf("Extracting files...", "Done"),
                        ),
                    ),
                )

            val viewModel =
                newViewModel()
            viewModel.importArchive(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.runCurrent()

            // F1554-system: a successful restore starts the existing action-log polling exactly once.
            coVerify(exactly = 1) { mockApi.importUpload(any(), any(), any()) }
            assertEquals(1, invocationCount.get())
            assertEquals("hermes-import", viewModel.uiState.value.activeAction)

            testDispatcher.scheduler.advanceTimeBy(1300)
            testDispatcher.scheduler.runCurrent()
            assertEquals(
                true,
                viewModel.uiState.value.actionLog
                    ?.running,
            )

            testDispatcher.scheduler.advanceTimeBy(1300)
            testDispatcher.scheduler.runCurrent()
            assertEquals(
                0,
                viewModel.uiState.value.actionLog
                    ?.exit_code,
            )
            coVerify(exactly = 2) { mockApi.getActionStatus("hermes-import") }
            coVerify(exactly = 1) { mockApi.importUpload(any(), any(), any()) }
        }

    @Test
    fun importArchive_rejectedOrUnsuccessfulActionResponseNeverStartsPolling() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "rejected.tar.gz",
                size = 40L,
            )
            every { mockResolver.openInputStream(mockUri) } answers {
                ByteArrayInputStream("rejected payload".toByteArray(Charsets.UTF_8))
            }

            coEvery {
                mockApi.importUpload(any(), any(), any())
            } returns
                Response.success(
                    ActionResponse(
                        ok = false,
                        name = "hermes-import",
                        error = "Archive checksum mismatch",
                    ),
                )

            val viewModel =
                newViewModel()
            viewModel.importArchive(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { mockApi.importUpload(any(), any(), any()) }
            coVerify(exactly = 0) { mockApi.getActionStatus(any()) }

            assertFalse(viewModel.uiState.value.isImporting)
            assertNull(viewModel.uiState.value.importProgress)
            assertNull(viewModel.uiState.value.activeAction)
            assertEquals("Import failed", viewModel.uiState.value.toastMessage)
        }

    @Test
    fun importArchive_networkFailureReleasesGuardsAndExecutesZeroSafeApiCallRetries() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "io-failure.tar.gz",
                size = 30L,
            )
            every { mockResolver.openInputStream(mockUri) } answers {
                ByteArrayInputStream("failure payload".toByteArray(Charsets.UTF_8))
            }

            val invocationCount = AtomicInteger(0)
            coEvery {
                mockApi.importUpload(any(), any(), any())
            } answers {
                invocationCount.incrementAndGet()
                throw IOException("Network stream interrupted")
            }

            val viewModel =
                newViewModel()
            viewModel.importArchive(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Restore mutation must not retry upon network failure", 1, invocationCount.get())
            assertFalse(viewModel.uiState.value.isImporting)
            assertNull(viewModel.uiState.value.importProgress)
            coVerify(exactly = 0) { mockApi.getActionStatus(any()) }
        }

    @Test
    fun importArchive_clearingViewModelStoreCancelsActiveScopeAndClosesStream() =
        runTest {
            val mockResolver = mockk<ContentResolver>()
            val mockUri = mockk<Uri>()
            val streamClosed = AtomicBoolean(false)

            mockContentResolverWithCursor(
                resolver = mockResolver,
                uri = mockUri,
                displayName = "lifecycle-cancel.tar.gz",
                size = 100_000L,
            )

            val stream =
                object : InputStream() {
                    private var remaining = 100_000L

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
                mockApi.importUpload(any(), capture(fileSlot), any())
            } coAnswers {
                uploadStarted.complete(Unit)
                fileSlot.captured.body.writeTo(createDiscardSink())
                Response.success(ActionResponse(ok = true, name = "long-op"))
            }

            val store = ViewModelStore()
            val factory =
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                        newViewModel() as T
                }
            val provider = ViewModelProvider(store, factory)
            val viewModel = provider[SystemViewModel::class.java]

            viewModel.importArchive(uri = mockUri, contentResolver = mockResolver)

            testDispatcher.scheduler.runCurrent()
            uploadStarted.await()

            store.clear()
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue("Stream must be closed on ViewModelStore.clear() lifecycle cancellation", streamClosed.get())
        }

    @Test
    fun importArchive_confirmationGatePendingInstrumentationNote() {
        // Confirmation UI gate (ConfirmDialog before invoking importArchive) is owned
        // by Compose layout and is PENDING instrumented proof; not claimed to have been run here.
        assertTrue("Confirmation UI gate verification pending instrumented proof", true)
    }
}
