package com.m57.hermescontrol.data.remote

import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayFileFetchTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockServer: MockWebServer
    private lateinit var cacheDir: File
    private val testDispatcher = StandardTestDispatcher()
    private val calls = ConcurrentLinkedQueue<Call>()
    private val bodyReadStarted = AtomicReference<CountDownLatch?>()
    private val mainTestThread = Thread.currentThread()

    private fun runOnMain(block: suspend CoroutineScope.() -> Unit) =
        runTest(testDispatcher) { withContext(Dispatchers.Main, block) }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        mockkObject(AuthManager)
        every { AuthManager.isGatedMode() } returns false

        mockkObject(ClientCertificates)
        every { ClientCertificates.cacheKey(any()) } returns "mock-cert-key"

        mockkObject(OkHttpProvider)
        every { OkHttpProvider.base } returns
            OkHttpClient
                .Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    assertFalse("Request work must leave Main", Thread.currentThread() === mainTestThread)
                    chain.proceed(chain.request())
                }.eventListener(
                    object : EventListener() {
                        override fun callStart(call: Call) {
                            calls.add(call)
                        }

                        override fun responseBodyStart(call: Call) {
                            bodyReadStarted.get()?.countDown()
                        }
                    },
                ).build()

        mockServer = MockWebServer()
        mockServer.start()

        every { AuthManager.getBaseUrl() } returns mockServer.url("/").toString()
        every { AuthManager.getToken() } returns "test-token"

        cacheDir = tempFolder.newFolder("gateway_cache")

        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mockServer.shutdown()
        unmockkStatic(Log::class)
        unmockkAll()
    }

    @Test
    fun fetchRunsSafelyFromMainDispatcherWithoutBlockingMainThread() =
        runOnMain {
            val content = "hello board export"
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/plain")
                    .setHeader("Content-Disposition", "attachment; filename=\"export.txt\"")
                    .setBody(content),
            )

            val result = GatewayFileClient.fetch("/tmp/export.txt", cacheDir)

            assertTrue("Expected Success, got $result", result is GatewayFileResult.Success)
            val success = result as GatewayFileResult.Success
            assertEquals("export.txt", success.file.name)
            assertEquals("text/plain", success.file.mimeType)
            assertEquals(content, success.file.cacheFile.readText())
            assertTrue(success.file.cacheFile.exists())
        }

    @Test
    fun fetchExplicitOverloadDownloadsSuccessfully() =
        runOnMain {
            val fileBytes = "binary-archive-payload".toByteArray()
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/gzip")
                    .setHeader("Content-Disposition", "attachment; filename=\"board.tar.gz\"")
                    .setBody(Buffer().write(fileBytes)),
            )

            val result =
                GatewayFileClient.fetch(
                    path = "/tmp/board.tar.gz",
                    baseUrl = mockServer.url("/").toString(),
                    token = "explicit-token",
                    cacheDir = cacheDir,
                )

            assertTrue("Expected Success, got $result", result is GatewayFileResult.Success)
            val success = result as GatewayFileResult.Success
            assertEquals("board.tar.gz", success.file.name)
            assertEquals("application/gzip", success.file.mimeType)
            assertTrue(
                success.file.cacheFile
                    .readBytes()
                    .contentEquals(fileBytes),
            )

            val recorded = mockServer.takeRequest(2, TimeUnit.SECONDS)
            org.junit.Assert.assertNotNull(recorded)
            assertTrue(recorded!!.path!!.contains("token=explicit-token"))
            assertTrue(recorded.path!!.contains("path=%2Ftmp%2Fboard.tar.gz"))
        }

    @Test
    fun fetchReusesFreshCacheAndSkipsNetworkRequest() =
        runOnMain {
            val firstBody = "cached version"
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/plain")
                    .setHeader("Content-Disposition", "attachment; filename=\"data.csv\"")
                    .setBody(firstBody),
            )

            val firstResult = GatewayFileClient.fetch("/tmp/data.csv", cacheDir)
            assertTrue(firstResult is GatewayFileResult.Success)
            val firstSuccess = firstResult as GatewayFileResult.Success
            assertEquals(firstBody, firstSuccess.file.cacheFile.readText())
            assertEquals(1, mockServer.requestCount)

            // Second fetch for identical path: must reuse fresh cache without hitting network
            val secondResult = GatewayFileClient.fetch("/tmp/data.csv", cacheDir)
            assertTrue(secondResult is GatewayFileResult.Success)
            val secondSuccess = secondResult as GatewayFileResult.Success
            assertEquals(firstSuccess.file.cacheFile.absolutePath, secondSuccess.file.cacheFile.absolutePath)
            assertEquals(firstBody, secondSuccess.file.cacheFile.readText())
            assertEquals(1, mockServer.requestCount)
        }

    @Test
    fun fetchMapsHttp404ToNotFound() =
        runOnMain {
            mockServer.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

            val result = GatewayFileClient.fetch("/tmp/missing.bin", cacheDir)

            assertEquals(GatewayFileResult.NotFound, result)
        }

    @Test
    fun fetchMapsHttp403ToForbidden() =
        runOnMain {
            mockServer.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

            val result = GatewayFileClient.fetch("/etc/shadow", cacheDir)

            assertEquals(GatewayFileResult.Forbidden, result)
        }

    @Test
    fun fetchMapsHttp413ToTooLarge() =
        runOnMain {
            mockServer.enqueue(MockResponse().setResponseCode(413).setBody("Payload Too Large"))

            val result = GatewayFileClient.fetch("/tmp/huge.zip", cacheDir)

            assertEquals(GatewayFileResult.TooLarge, result)
        }

    @Test
    fun fetchMapsHttp401ToUnauthorized() =
        runOnMain {
            mockServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

            val result = GatewayFileClient.fetch("/tmp/secret.doc", cacheDir)

            assertEquals(GatewayFileResult.Unauthorized, result)
        }

    @Test
    fun fetchMapsHttp500ToFailure() =
        runOnMain {
            mockServer.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

            val result = GatewayFileClient.fetch("/tmp/crash.log", cacheDir)

            assertTrue("Expected Failure, got $result", result is GatewayFileResult.Failure)
        }

    @Test
    fun fetchMapsNetworkExceptionToFailure() =
        runOnMain {
            mockServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

            val result = GatewayFileClient.fetch("/tmp/error.bin", cacheDir)

            assertTrue("Expected Failure, got $result", result is GatewayFileResult.Failure)
            val failure = result as GatewayFileResult.Failure
            assertTrue(failure.throwable is IOException)
        }

    @Test
    fun fetchCancelsPromptlyAndCleansTemporaryFilesWhenCancelledBeforeHeaders() =
        runOnMain {
            mockServer.enqueue(
                MockResponse()
                    .setSocketPolicy(SocketPolicy.NO_RESPONSE),
            )

            val job =
                launch(Dispatchers.Main) {
                    GatewayFileClient.fetch("/tmp/hanging-headers.bin", cacheDir)
                }

            // Allow request to reach MockWebServer before cancelling
            val recorded = withContext(Dispatchers.IO) { mockServer.takeRequest(2, TimeUnit.SECONDS) }
            org.junit.Assert.assertNotNull("Request should reach the server", recorded)

            job.cancel()
            withContext(Dispatchers.IO) { withTimeout(2000) { job.join() } }

            assertTrue("Coroutine must be cancelled", job.isCancelled)
            assertTrue("Actual header-waiting call must be cancelled", calls.last().isCanceled())

            val tmpFiles =
                cacheDir.listFiles()?.filter { it.name.contains(".tmp-") || it.name.endsWith(".tmp") } ?: emptyList()
            assertTrue("Temporary files must be deleted on cancellation, found: $tmpFiles", tmpFiles.isEmpty())
        }

    @Test
    fun fetchCancelsPromptlyCleansTemporaryFilesAndPreservesExistingValidCacheWhenCancelledDuringBody() =
        runOnMain {
            val existingCacheContent = "preserved-valid-cache-content"
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/plain")
                    .setHeader("Content-Disposition", "attachment; filename=\"stalled.bin\"")
                    .setBody(existingCacheContent),
            )

            val initialResult = GatewayFileClient.fetch("/tmp/stalled.bin", cacheDir)
            assertTrue(initialResult is GatewayFileResult.Success)
            val initialFile = (initialResult as GatewayFileResult.Success).file.cacheFile
            assertTrue(initialFile.exists())
            assertEquals(existingCacheContent, initialFile.readText())
            org.junit.Assert.assertNotNull(mockServer.takeRequest(2, TimeUnit.SECONDS))

            // Force cache expiry so next fetch attempts a new network download
            initialFile.setLastModified(System.currentTimeMillis() - 20 * 60 * 1000L)
            assertFalse(GatewayFileClient.isFreshCacheFile(initialFile))

            val bodyStarted = CountDownLatch(1)
            bodyReadStarted.set(bodyStarted)
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/plain")
                    .setHeader("Content-Disposition", "attachment; filename=\"stalled.bin\"")
                    .setBody("x".repeat(65536))
                    .throttleBody(64, 1, TimeUnit.DAYS),
            )

            val job =
                launch(Dispatchers.Main) {
                    GatewayFileClient.fetch("/tmp/stalled.bin", cacheDir)
                }

            assertTrue(
                "Must cancel during body streaming, not before response headers",
                withContext(Dispatchers.IO) { bodyStarted.await(5, TimeUnit.SECONDS) },
            )
            assertTrue("Copy must have created a partial file", cacheDir.listFiles()!!.any { ".tmp-" in it.name })

            job.cancel()
            withContext(Dispatchers.IO) { withTimeout(2000) { job.join() } }

            assertTrue("Job must be cancelled", job.isCancelled)
            assertTrue("Actual streaming call must be cancelled", calls.last().isCanceled())

            // Temporary files (.tmp-*) must be deleted
            val tmpFiles =
                cacheDir.listFiles()?.filter { it.name.contains(".tmp-") || it.name.endsWith(".tmp") } ?: emptyList()
            assertTrue("No partial .tmp files should remain after cancellation, found: $tmpFiles", tmpFiles.isEmpty())

            // Existing valid cache file must still exist and be intact
            assertTrue("Pre-existing cache file must be preserved", initialFile.exists())
            assertEquals(existingCacheContent, initialFile.readText())
        }
}
