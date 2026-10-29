package com.m57.hermescontrol.ui.authlogin

import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.ServerTrust
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import com.m57.hermescontrol.data.remote.mockSystemServerTrust
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GatewayAuthProbeTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        mockSystemServerTrust()
        CookieManager.setJarForTest(buildFakePersistentCookieJar())
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        unmockkObject(ServerTrust)
    }

    private fun testEndpoint(): ServerEndpoint =
        ServerEndpoint.parse(server.url("/").toString(), CleartextPolicy.ALLOW_WITH_WARNING)

    @Test
    fun `status 401 with supported password provider returns auth info`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("Unauthorized"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "providers": [
                                {
                                    "name": "basic",
                                    "display_name": "Username & Password",
                                    "supports_password": true
                                }
                            ]
                        }
                        """.trimIndent(),
                    ),
            )

            val endpoint = testEndpoint()
            val result = probeGatewayAuth(OkHttpProvider.probe, endpoint)

            assertNotNull(result)
            assertEquals(true, result?.authRequired)
            assertFalse(result?.browserSupported ?: true)
            assertEquals(1, result?.providers?.size)
            assertEquals("basic", result?.providers?.first()?.name)
            assertEquals("Username & Password", result?.providers?.first()?.displayName)
            assertTrue(result?.providers?.first()?.supportsPassword == true)

            assertEquals(2, server.requestCount)
            assertEquals("/api/status", server.takeRequest().path)
            assertEquals("/api/auth/providers", server.takeRequest().path)
        }

    @Test
    fun `status 401 when providers endpoint fails returns null`() =
        runTest {
            listOf(401, 404, 500).forEach { errorCode ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(401)
                        .setBody("Unauthorized"),
                )
                server.enqueue(
                    MockResponse()
                        .setResponseCode(errorCode)
                        .setBody("Error $errorCode"),
                )

                val endpoint = testEndpoint()
                val result = probeGatewayAuth(OkHttpProvider.probe, endpoint)

                assertNull(result, "Expected null when /api/auth/providers returns $errorCode")
            }
        }

    @Test
    fun `status 401 when providers returns empty list or unsupported providers returns null`() =
        runTest {
            // Case 1: empty list
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("Unauthorized"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"providers":[]}"""),
            )

            val endpoint = testEndpoint()
            assertNull(probeGatewayAuth(OkHttpProvider.probe, endpoint))

            // Case 2: only unsupported/drain-secret provider (filtered out by parseAuthProviders)
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("Unauthorized"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"providers":[{"name":"drain-secret","display_name":"Drain"}]}"""),
            )
            assertNull(probeGatewayAuth(OkHttpProvider.probe, endpoint))
        }

    @Test
    fun `status 403 404 500 returns null and does not request providers`() =
        runTest {
            listOf(403, 404, 500).forEach { statusCode ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(statusCode)
                        .setBody("Error $statusCode"),
                )

                val endpoint = testEndpoint()
                val result = probeGatewayAuth(OkHttpProvider.probe, endpoint)

                assertNull(result)
            }
            assertEquals(3, server.requestCount)
            assertEquals("/api/status", server.takeRequest().path)
            assertEquals("/api/status", server.takeRequest().path)
            assertEquals("/api/status", server.takeRequest().path)
        }

    @Test
    fun `status 200 keeps standard probe behavior`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "auth_required": true,
                            "auth_providers": ["basic"],
                            "auth_flows": ["native_pkce"]
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "providers": [
                                {
                                    "name": "basic",
                                    "display_name": "Basic Auth",
                                    "supports_password": true
                                }
                            ]
                        }
                        """.trimIndent(),
                    ),
            )

            val endpoint = testEndpoint()
            val result = probeGatewayAuth(OkHttpProvider.probe, endpoint)

            assertNotNull(result)
            assertEquals(true, result?.authRequired)
            assertTrue(result?.browserSupported == true)
            assertEquals(1, result?.providers?.size)
            assertEquals("basic", result?.providers?.first()?.name)
        }
}
