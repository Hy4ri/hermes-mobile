package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [ApiClient] — auth interceptor and BuildConfig.DEBUG logging gate.
 *
 * Uses a real MockWebServer so the interceptor chain is exercised end-to-end.
 * [AuthManager] is fully mocked so we control the stored host/port/token.
 * [BuildConfig.DEBUG] is statically mocked so we can test both code paths.
 *
 * TEST-04 (issue #292)
 */
class ApiClientTest {
    private lateinit var mockWebServer: MockWebServer

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        mockkObject(AuthManager)
        mockkStatic(BuildConfig::class)

        // Point AuthManager at our MockWebServer
        every { AuthManager.baseUrl() } returns mockWebServer.url("/").toString()
        every { AuthManager.getHost() } returns "127.0.0.1"
        every { AuthManager.getPort() } returns 9119
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
        unmockkAll()
    }

    @Test
    fun testAuthInterceptor_addsBearerTokenWhenPresent() =
        runTest {
            every { AuthManager.getToken() } returns "test-token-123"

            // Rebuild to pick up the new mock values
            ApiClient.rebuild()

            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"gateway_running":true}"""),
            )

            val response = ApiClient.hermesApi.getStatus()
            assertTrue(response.isSuccessful)

            val request = mockWebServer.takeRequest()
            val authHeader = request.getHeader("Authorization")
            assertNotNull(authHeader)
            assertEquals("Bearer test-token-123", authHeader)
        }

    @Test
    fun testAuthInterceptor_omitsBearerWhenTokenBlank() =
        runTest {
            every { AuthManager.getToken() } returns ""

            ApiClient.rebuild()

            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"gateway_running":true}"""),
            )

            val response = ApiClient.hermesApi.getStatus()
            assertTrue(response.isSuccessful)

            val request = mockWebServer.takeRequest()
            val authHeader = request.getHeader("Authorization")
            // Should be null — no token means no Authorization header
            assertNull(authHeader)
        }

    @Test
    fun testAuthInterceptor_omitsBearerWhenTokenNull() =
        runTest {
            every { AuthManager.getToken() } returns null

            ApiClient.rebuild()

            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"gateway_running":true}"""),
            )

            val response = ApiClient.hermesApi.getStatus()
            assertTrue(response.isSuccessful)

            val request = mockWebServer.takeRequest()
            val authHeader = request.getHeader("Authorization")
            assertNull(authHeader)
        }

    @Test
    fun testDebugGate_doesNotCrashWhenDebugTrue() =
        runTest {
            every { BuildConfig.DEBUG } returns true
            every { AuthManager.getToken() } returns "token"

            ApiClient.rebuild()

            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"gateway_running":true}"""),
            )

            val response = ApiClient.hermesApi.getStatus()
            assertTrue(response.isSuccessful)

            val request = mockWebServer.takeRequest()
            assertEquals("Bearer token", request.getHeader("Authorization"))
        }

    @Test
    fun testDebugGate_doesNotCrashWhenDebugFalse() =
        runTest {
            every { BuildConfig.DEBUG } returns false
            every { AuthManager.getToken() } returns "token"

            ApiClient.rebuild()

            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"gateway_running":true}"""),
            )

            val response = ApiClient.hermesApi.getStatus()
            assertTrue(response.isSuccessful)

            val request = mockWebServer.takeRequest()
            assertEquals("Bearer token", request.getHeader("Authorization"))
        }

    @Test
    fun testAuthInterceptor_tokenWithSpecialCharacters() =
        runTest {
            val rawToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test-token"
            every { AuthManager.getToken() } returns rawToken

            ApiClient.rebuild()

            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"gateway_running":true}"""),
            )

            val response = ApiClient.hermesApi.getStatus()
            assertTrue(response.isSuccessful)

            val request = mockWebServer.takeRequest()
            assertEquals("Bearer $rawToken", request.getHeader("Authorization"))
        }
}
