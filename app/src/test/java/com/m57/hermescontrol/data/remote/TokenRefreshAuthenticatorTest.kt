package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TokenRefreshAuthenticatorTest {
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

    private fun dummy401Response(
        url: String,
        authHeader: String?,
        priorResponse: Response? = null,
    ): Response {
        val reqBuilder = Request.Builder().url(url)
        if (authHeader != null) {
            reqBuilder.header("Authorization", authHeader)
        }
        val request = reqBuilder.build()
        val builder =
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")

        if (priorResponse != null) {
            builder.priorResponse(priorResponse)
        }
        return builder.build()
    }

    @Test
    fun authenticateReturnsNullWhenInGatedMode() {
        mockkObject(AuthManager)
        every { AuthManager.isGatedMode() } returns true

        val response = dummy401Response(server.url("/api/data").toString(), "Bearer stale-token")
        val result = TokenRefreshAuthenticator.authenticate(null, response)

        assertNull(result)
    }

    @Test
    fun authenticateReturnsNullWhenAuthHeaderMissing() {
        mockkObject(AuthManager)
        every { AuthManager.isGatedMode() } returns false

        val response = dummy401Response(server.url("/api/data").toString(), null)
        val result = TokenRefreshAuthenticator.authenticate(null, response)

        assertNull(result)
    }

    @Test
    fun authenticateReturnsNullWhenAuthHeaderIsNotExactBearerToken() {
        mockkObject(AuthManager)
        every { AuthManager.isGatedMode() } returns false

        // Basic auth instead of Bearer
        val response = dummy401Response(server.url("/api/data").toString(), "Basic dXNlcjpwYXNz")
        val result = TokenRefreshAuthenticator.authenticate(null, response)

        assertNull(result)
    }

    @Test
    fun authenticateReturnsNullWhenBearerPrefixMatchesSubstringOnly() {
        mockkObject(AuthManager)
        every { AuthManager.isGatedMode() } returns false

        // Starts with BearerXYZ or NotBearer instead of exact "Bearer " prefix
        val response = dummy401Response(server.url("/api/data").toString(), "BearerTokenWithoutSpace")
        val result = TokenRefreshAuthenticator.authenticate(null, response)

        assertNull(result)
    }

    @Test
    fun authenticateReusesAlreadyUpdatedTokenUnderGateWithoutCallingRefresh() {
        val testScope =
            DataScope(
                connectionProfileId = "p1",
                baseUrl = server.url("/").toString(),
                activeProfileId = "default",
                inMemoryAuthGeneration = 1L,
            )
        mockkObject(AuthManager)
        every { AuthManager.isGatedMode() } returns false
        every { AuthManager.baseUrl() } returns server.url("/").toString()
        every { AuthManager.currentDataScope() } returns testScope
        // The token stored in AuthManager is already newer than the failed request's token
        every { AuthManager.getToken() } returns "newly-refreshed-token"

        val response = dummy401Response(server.url("/api/data").toString(), "Bearer old-failed-token")
        val authenticatedRequest = TokenRefreshAuthenticator.authenticate(null, response)

        assertEquals("Bearer newly-refreshed-token", authenticatedRequest?.header("Authorization"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun authenticateRefreshesAndRetriesWithNewBearerHeader() {
        val testScope =
            DataScope(
                connectionProfileId = "p1",
                baseUrl = server.url("/").toString(),
                activeProfileId = "default",
                inMemoryAuthGeneration = 1L,
            )
        var currentToken: String? = "stale-token"
        mockkObject(AuthManager)
        every { AuthManager.isGatedMode() } returns false
        every { AuthManager.baseUrl() } returns server.url("/").toString()
        every { AuthManager.currentDataScope() } returns testScope
        every { AuthManager.getToken() } answers { currentToken }
        every { AuthManager.setToken(any()) } answers { currentToken = firstArg() }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<script>window.__HERMES_SESSION_TOKEN__ = "freshly-minted-token";</script>"""),
        )

        val response = dummy401Response(server.url("/api/data").toString(), "Bearer stale-token")
        val authenticatedRequest = TokenRefreshAuthenticator.authenticate(null, response)

        assertEquals("Bearer freshly-minted-token", authenticatedRequest?.header("Authorization"))
        assertEquals(1, server.requestCount)
        assertEquals("freshly-minted-token", currentToken)
    }

    @Test
    fun authenticateReturnsNullWhenRefreshFails() {
        val testScope =
            DataScope(
                connectionProfileId = "p1",
                baseUrl = server.url("/").toString(),
                activeProfileId = "default",
                inMemoryAuthGeneration = 1L,
            )
        mockkObject(AuthManager)
        every { AuthManager.isGatedMode() } returns false
        every { AuthManager.baseUrl() } returns server.url("/").toString()
        every { AuthManager.currentDataScope() } returns testScope
        every { AuthManager.getToken() } returns "stale-token"

        server.enqueue(MockResponse().setResponseCode(500))

        val response = dummy401Response(server.url("/api/data").toString(), "Bearer stale-token")
        val result = TokenRefreshAuthenticator.authenticate(null, response)

        assertNull(result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun authenticateReturnsNullWhenAuthScopeChangesDuringRefresh() {
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
                baseUrl = "http://switched-host:9999",
                activeProfileId = "other",
                inMemoryAuthGeneration = 2L,
            )
        val storedToken = AtomicReference<String?>("stale-token")

        mockkObject(AuthManager)
        every { AuthManager.isGatedMode() } returns false
        val scopeCount = AtomicInteger(0)
        every { AuthManager.baseUrl() } answers {
            if (scopeCount.get() > 1) "http://switched-host:9999" else server.url("/").toString()
        }
        every { AuthManager.currentDataScope() } answers {
            if (scopeCount.incrementAndGet() > 1) switchedScope else initialScope
        }
        every { AuthManager.getToken() } answers { storedToken.get() }
        every { AuthManager.setToken(any()) } answers { storedToken.set(firstArg()) }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""<script>window.__HERMES_SESSION_TOKEN__ = "untrusted-switched-token";</script>"""),
        )

        val response = dummy401Response(server.url("/api/data").toString(), "Bearer stale-token")
        val result = TokenRefreshAuthenticator.authenticate(null, response)

        assertNull(result)
        // Ensure the token from the old server was not installed into the switched scope
        assertEquals("stale-token", storedToken.get())
    }

    @Test
    fun authenticateReturnsNullWhenLoopDetectedViaPriorResponses() {
        mockkObject(AuthManager)
        every { AuthManager.isGatedMode() } returns false

        // Construct a chain of 401 prior responses simulating retry loop
        val r1 = dummy401Response(server.url("/api/data").toString(), "Bearer stale-token")
        val r2 = dummy401Response(server.url("/api/data").toString(), "Bearer stale-token", r1)
        val r3 = dummy401Response(server.url("/api/data").toString(), "Bearer stale-token", r2)

        val result = TokenRefreshAuthenticator.authenticate(null, r3)

        assertNull(result)
    }
}
