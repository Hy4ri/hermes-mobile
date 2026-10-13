package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DashboardSessionTokenRefresherTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        mockkObject(OkHttpProvider)
        every { OkHttpProvider.probe } returns OkHttpClient()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        unmockkAll()
    }

    @Test
    fun fetchExtractsInjectedDashboardToken() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""<script>window.__HERMES_SESSION_TOKEN__ = "new-token";</script>"""),
            )

            val token = DashboardSessionTokenRefresher.fetch(server.url("/").toString(), OkHttpClient())

            assertEquals("new-token", token)
            assertEquals("/", server.takeRequest().path)
        }

    @Test
    fun fetchReturnsNullWhenDashboardDoesNotInjectToken() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html></html>"))

            val token = DashboardSessionTokenRefresher.fetch(server.url("/").toString(), OkHttpClient())

            assertNull(token)
        }

    @Test
    fun fetchReturnsNullForFailedResponse() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500))

            val token = DashboardSessionTokenRefresher.fetch(server.url("/").toString(), OkHttpClient())

            assertNull(token)
        }

    @Test
    fun fetchReturnsNullWhenTokenIsBlank() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""<script>window.__HERMES_SESSION_TOKEN__ = "";</script>"""),
            )

            val token = DashboardSessionTokenRefresher.fetch(server.url("/").toString(), OkHttpClient())

            assertNull(token)
        }

    @Test
    fun fetchSyncExtractsInjectedDashboardToken() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<script>window.__HERMES_SESSION_TOKEN__ = "sync-token";</script>"""),
        )

        val token = DashboardSessionTokenRefresher.fetchSync(server.url("/").toString(), OkHttpClient())

        assertEquals("sync-token", token)
        assertEquals("/", server.takeRequest().path)
    }

    @Test
    fun refreshUpdatesAuthManagerOnSuccess() {
        val testScope =
            DataScope(
                connectionProfileId = "p1",
                baseUrl = server.url("/").toString(),
                activeProfileId = "default",
                inMemoryAuthGeneration = 1L,
            )
        mockkObject(AuthManager)
        every { AuthManager.baseUrl() } returns server.url("/").toString()
        every { AuthManager.getToken() } returns "old-token"
        every { AuthManager.currentDataScope() } returns testScope
        every { AuthManager.setToken(any()) } returns Unit

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<script>window.__HERMES_SESSION_TOKEN__ = "mocked-token";</script>"""),
        )

        val token = DashboardSessionTokenRefresher.refresh()

        assertEquals("mocked-token", token)
        verify { AuthManager.setToken("mocked-token") }
    }

    @Test
    fun refreshAsyncUpdatesAuthManagerOnSuccess() =
        runBlocking {
            val testScope =
                DataScope(
                    connectionProfileId = "p1",
                    baseUrl = server.url("/").toString(),
                    activeProfileId = "default",
                    inMemoryAuthGeneration = 1L,
                )
            mockkObject(AuthManager)
            every { AuthManager.baseUrl() } returns server.url("/").toString()
            every { AuthManager.getToken() } returns "old-token"
            every { AuthManager.currentDataScope() } returns testScope
            every { AuthManager.setToken(any()) } returns Unit

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""<script>window.__HERMES_SESSION_TOKEN__ = "async-token";</script>"""),
            )

            val token = DashboardSessionTokenRefresher.refreshAsync()

            assertEquals("async-token", token)
            verify { AuthManager.setToken("async-token") }
        }

    @Test
    fun refreshReturnsNullOnException() {
        mockkObject(AuthManager)
        every { AuthManager.baseUrl() } throws RuntimeException("Network Error")
        every { AuthManager.getToken() } returns null
        every { AuthManager.currentDataScope() } returns null

        val token = DashboardSessionTokenRefresher.refresh()

        assertNull(token)
    }

    @Test
    fun refreshReturnsNullWhenFetchReturnsNull() {
        val testScope =
            DataScope(
                connectionProfileId = "p1",
                baseUrl = server.url("/").toString(),
                activeProfileId = "default",
                inMemoryAuthGeneration = 1L,
            )
        mockkObject(AuthManager)
        every { AuthManager.baseUrl() } returns server.url("/").toString()
        every { AuthManager.getToken() } returns "old-token"
        every { AuthManager.currentDataScope() } returns testScope
        // No token returned from fetch
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html></html>"))

        val token = DashboardSessionTokenRefresher.refresh()

        assertNull(token)
        verify(exactly = 0) { AuthManager.setToken(any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Regression Tests for A1552 Contract
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun fetchExtractsTokenAcrossSegmentedResponseChunks() =
        runBlocking {
            val prefix = "<html><head><script>window.__HERMES_SESSI"
            val suffix = """ON_TOKEN__ = "segmented-token";</script></head><body></body></html>"""
            val body = Buffer().writeUtf8(prefix).writeUtf8(suffix)

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setChunkedBody(body, 9)
                    .throttleBody(9, 5, TimeUnit.MILLISECONDS),
            )

            val token = DashboardSessionTokenRefresher.fetch(server.url("/").toString(), OkHttpClient())

            assertEquals("segmented-token", token)
        }

    @Test
    fun fetchSyncExtractsTokenAcrossSegmentedResponseChunks() {
        val prefix = "<html><head><script>window.__HERMES_SESSI"
        val suffix = """ON_TOKEN__ = "segmented-sync-token";</script></head><body></body></html>"""
        val body = Buffer().writeUtf8(prefix).writeUtf8(suffix)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setChunkedBody(body, 9)
                .throttleBody(9, 5, TimeUnit.MILLISECONDS),
        )

        val token = DashboardSessionTokenRefresher.fetchSync(server.url("/").toString(), OkHttpClient())

        assertEquals("segmented-sync-token", token)
    }

    @Test
    fun fetchRetainsACompleteAssignmentAcrossManySmallReads() =
        runBlocking {
            val expected = "t".repeat(4000)
            val assignment = "__HERMES_SESSION_TOKEN__" + " ".repeat(1500) + "= \"$expected\""
            server.enqueue(
                MockResponse()
                    .setChunkedBody(assignment, 128)
                    .throttleBody(128, 1, TimeUnit.MILLISECONDS),
            )

            assertEquals(expected, DashboardSessionTokenRefresher.fetch(server.url("/").toString(), OkHttpClient()))
        }

    @Test
    fun refreshDiscardsAResponseAfterTheActualFetchChangesScope() {
        val scope = DataScope("p1", server.url("/").toString(), "default", 1L)
        val currentScope = AtomicReference<DataScope?>(scope)
        mockkObject(AuthManager)
        every { AuthManager.baseUrl() } returns server.url("/").toString()
        every { AuthManager.currentDataScope() } answers { currentScope.get() }
        every { AuthManager.getToken() } returns "old-token"
        every { AuthManager.setToken(any()) } returns Unit
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    currentScope.set(scope.copy(inMemoryAuthGeneration = 2L))
                    return MockResponse().setBody("""__HERMES_SESSION_TOKEN__ = "old-generation-token";""")
                }
            }

        assertNull(DashboardSessionTokenRefresher.refreshIfStale("old-token", scope))
        assertEquals(1, server.requestCount, "The test must exercise a result arriving after an actual fetch")
        verify(exactly = 0) { AuthManager.setToken(any()) }
    }

    @Test
    fun fetchStopsEarlyAndDoesNotReadBeyondCapWhenTokenIsFound() =
        runBlocking {
            val tokenScript = """<script>window.__HERMES_SESSION_TOKEN__ = "early-token";</script>"""
            val padding = "x".repeat(128 * 1024)
            val fullBody = Buffer().writeUtf8(tokenScript).writeUtf8(padding)

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(fullBody),
            )

            val token = DashboardSessionTokenRefresher.fetch(server.url("/").toString(), OkHttpClient())

            assertEquals("early-token", token)
        }

    @Test
    fun fetchReturnsNullWhenTokenIsBeyond64KiBCap() =
        runBlocking {
            val padding = "a".repeat(65536)
            val lateScript = """<script>window.__HERMES_SESSION_TOKEN__ = "beyond-cap-token";</script>"""
            val fullBody = Buffer().writeUtf8(padding).writeUtf8(lateScript)

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(fullBody),
            )

            val token = DashboardSessionTokenRefresher.fetch(server.url("/").toString(), OkHttpClient())

            assertNull(token)
        }

    @Test
    fun fetchCancelsUnderlyingCallWhenCoroutineIsCancelledDuringBodyRead() =
        runBlocking {
            val bodyReadStarted = CountDownLatch(1)
            val startedCall = AtomicReference<Call>()
            val client =
                OkHttpClient
                    .Builder()
                    .eventListener(
                        object : EventListener() {
                            override fun callStart(call: Call) {
                                startedCall.set(call)
                            }

                            override fun responseBodyStart(call: Call) {
                                bodyReadStarted.countDown()
                            }
                        },
                    ).build()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("x".repeat(4096))
                    .throttleBody(1, 1, TimeUnit.DAYS),
            )

            val job =
                launch(Dispatchers.IO) {
                    DashboardSessionTokenRefresher.fetch(server.url("/").toString(), client)
                }

            assertTrue(bodyReadStarted.await(5, TimeUnit.SECONDS), "Reader must reach the response body")
            job.cancel()
            withTimeout(2000) { job.join() }

            assertTrue(job.isCancelled)
            assertTrue(startedCall.get().isCanceled(), "Cancellation must close the actual OkHttp call")
        }

    @Test
    fun concurrentSyncAndAsyncRefreshCallsCoalesceToOneNetworkRequest() =
        runBlocking {
            val testScope =
                DataScope(
                    connectionProfileId = "p1",
                    baseUrl = server.url("/").toString(),
                    activeProfileId = "default",
                    inMemoryAuthGeneration = 1L,
                )
            val currentToken = AtomicReference<String?>("old-token")
            val callersEnteredGate = CountDownLatch(4)
            mockkObject(AuthManager)
            every { AuthManager.baseUrl() } returns server.url("/").toString()
            every { AuthManager.getToken() } answers {
                callersEnteredGate.countDown()
                currentToken.get()
            }
            every { AuthManager.currentDataScope() } returns testScope
            every { AuthManager.setToken(any()) } answers { currentToken.set(firstArg()) }

            val responseBody = """<script>window.__HERMES_SESSION_TOKEN__ = "coalesced-token";</script>"""
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        check(callersEnteredGate.await(5, TimeUnit.SECONDS)) { "Both refresh callers must overlap" }
                        return MockResponse().setResponseCode(200).setBody(responseBody)
                    }
                }

            val asyncDeferred = async(Dispatchers.IO) { DashboardSessionTokenRefresher.refreshAsync() }
            val syncDeferred = async(Dispatchers.IO) { DashboardSessionTokenRefresher.refresh() }

            val asyncResult = asyncDeferred.await()
            val syncResult = syncDeferred.await()

            assertEquals("coalesced-token", asyncResult)
            assertEquals("coalesced-token", syncResult)
            assertEquals(1, server.requestCount)
            assertEquals("coalesced-token", currentToken.get())
        }

    @Test
    fun singleFlightCoalescingSharesFailureAndAllowsSubsequentIndependentRetry() =
        runBlocking {
            val testScope =
                DataScope(
                    connectionProfileId = "p1",
                    baseUrl = server.url("/").toString(),
                    activeProfileId = "default",
                    inMemoryAuthGeneration = 1L,
                )
            val currentToken = AtomicReference<String?>("old-token")
            val callersEnteredGate = CountDownLatch(4)
            mockkObject(AuthManager)
            every { AuthManager.baseUrl() } returns server.url("/").toString()
            every { AuthManager.getToken() } answers {
                callersEnteredGate.countDown()
                currentToken.get()
            }
            every { AuthManager.currentDataScope() } returns testScope
            every { AuthManager.setToken(any()) } answers { currentToken.set(firstArg()) }

            val requests = AtomicInteger()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (requests.incrementAndGet() == 1) {
                            check(callersEnteredGate.await(5, TimeUnit.SECONDS)) { "Both refresh callers must overlap" }
                            return MockResponse().setResponseCode(500)
                        }
                        return MockResponse()
                            .setResponseCode(200)
                            .setBody("""<script>window.__HERMES_SESSION_TOKEN__ = "retry-token";</script>""")
                    }
                }

            val d1 = async(Dispatchers.IO) { DashboardSessionTokenRefresher.refreshAsync() }
            val d2 = async(Dispatchers.IO) { DashboardSessionTokenRefresher.refresh() }

            val r1 = d1.await()
            val r2 = d2.await()

            assertNull(r1)
            assertNull(r2)
            assertEquals(1, server.requestCount)

            // A new request after the failed flight must not reuse its null result forever.
            val retryResult = DashboardSessionTokenRefresher.refresh()

            assertEquals("retry-token", retryResult)
            assertEquals(2, server.requestCount)
            assertEquals("retry-token", currentToken.get())
        }

    @Test
    fun refreshIfStaleReusesChangedCurrentTokenUnderGate() {
        val testScope =
            DataScope(
                connectionProfileId = "p1",
                baseUrl = server.url("/").toString(),
                activeProfileId = "default",
                inMemoryAuthGeneration = 1L,
            )
        mockkObject(AuthManager)
        every { AuthManager.baseUrl() } returns server.url("/").toString()
        every { AuthManager.currentDataScope() } returns testScope
        // AuthManager already has a new non-blank token different from failedToken
        every { AuthManager.getToken() } returns "already-updated-token"
        every { AuthManager.setToken(any()) } returns Unit

        // No server response enqueued; if network is hit, it will throw or fail
        val result = DashboardSessionTokenRefresher.refreshIfStale("stale-failed-token", testScope)

        assertEquals("already-updated-token", result)
        assertEquals(0, server.requestCount)
        verify(exactly = 0) { AuthManager.setToken(any()) }
    }

    @Test
    fun refreshIfStaleDiscardsResultIfAuthGenerationChangesDuringFetch() {
        val initialScope =
            DataScope(
                connectionProfileId = "p1",
                baseUrl = server.url("/").toString(),
                activeProfileId = "default",
                inMemoryAuthGeneration = 1L,
            )
        val modifiedScope = initialScope.copy(inMemoryAuthGeneration = 2L)
        val storedToken = AtomicReference<String?>("stale-token")

        mockkObject(AuthManager)
        every { AuthManager.baseUrl() } returns server.url("/").toString()
        val scopeCallCount = AtomicInteger(0)
        every { AuthManager.currentDataScope() } answers {
            if (scopeCallCount.incrementAndGet() > 1) modifiedScope else initialScope
        }
        every { AuthManager.getToken() } answers { storedToken.get() }
        every { AuthManager.setToken(any()) } answers { storedToken.set(firstArg()) }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<script>window.__HERMES_SESSION_TOKEN__ = "discarded-token";</script>"""),
        )

        val result = DashboardSessionTokenRefresher.refreshIfStale("stale-token", initialScope)

        assertNull(result)
        assertEquals("stale-token", storedToken.get())
    }

    @Test
    fun refreshIfStaleDiscardsResultIfBaseUrlChangesDuringFetch() {
        val initialScope =
            DataScope(
                connectionProfileId = "p1",
                baseUrl = server.url("/").toString(),
                activeProfileId = "default",
                inMemoryAuthGeneration = 1L,
            )
        val switchedScope =
            DataScope(
                connectionProfileId = "p2",
                baseUrl = "http://different-server:8080",
                activeProfileId = "default",
                inMemoryAuthGeneration = 1L,
            )
        val storedToken = AtomicReference<String?>("stale-token")

        mockkObject(AuthManager)
        val urlCallCount = AtomicInteger(0)
        every { AuthManager.baseUrl() } answers {
            if (urlCallCount.incrementAndGet() > 1) "http://different-server:8080" else server.url("/").toString()
        }
        every { AuthManager.currentDataScope() } answers {
            if (urlCallCount.get() > 1) switchedScope else initialScope
        }
        every { AuthManager.getToken() } answers { storedToken.get() }
        every { AuthManager.setToken(any()) } answers { storedToken.set(firstArg()) }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<script>window.__HERMES_SESSION_TOKEN__ = "new-host-token";</script>"""),
        )

        val result = DashboardSessionTokenRefresher.refreshIfStale("stale-token", initialScope)

        assertNull(result)
        assertEquals("stale-token", storedToken.get())
    }

    @Test
    fun refreshIfStaleDiscardsResultIfLoggedOutDuringFetch() {
        val initialScope =
            DataScope(
                connectionProfileId = "p1",
                baseUrl = server.url("/").toString(),
                activeProfileId = "default",
                inMemoryAuthGeneration = 1L,
            )
        val storedToken = AtomicReference<String?>("stale-token")

        mockkObject(AuthManager)
        val callCount = AtomicInteger(0)
        every { AuthManager.baseUrl() } returns server.url("/").toString()
        every { AuthManager.currentDataScope() } answers {
            if (callCount.incrementAndGet() > 1) null else initialScope
        }
        every { AuthManager.getToken() } answers {
            if (callCount.get() > 1) null else storedToken.get()
        }
        every { AuthManager.setToken(any()) } answers { storedToken.set(firstArg()) }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<script>window.__HERMES_SESSION_TOKEN__ = "logged-out-token";</script>"""),
        )

        val result = DashboardSessionTokenRefresher.refreshIfStale("stale-token", initialScope)

        assertNull(result)
        verify(exactly = 0) { AuthManager.setToken(any()) }
    }
}
