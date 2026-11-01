package com.m57.hermescontrol.data.tunnel

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tests socket routing, DNS synthetic loopback mapping, fail-closed desired route
 * enforcement, and request protection.
 */
class SshTunnelSocketRoutingTest {
    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        SshTunnelRouting.configure(null)
        SshTunnelRouting.activate(null)
        runCatching { server.shutdown() }
    }

    private fun tunnelClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .callTimeout(1, TimeUnit.SECONDS)
            .dns(SshTunnelDns)
            .socketFactory(SshTunnelSocketFactory)
            .addInterceptor(SshTunnelInterceptor)
            .build()

    @Test
    fun `enabling tunnel closes a previously pooled direct socket`() {
        MockWebServer().use { forward ->
            server.enqueue(MockResponse().setBody("direct"))
            server.start()
            forward.enqueue(MockResponse().setBody("tunneled"))
            forward.start()
            val client = tunnelClient()
            val request = Request.Builder().url("http://127.0.0.1:${server.port}/api/status").build()
            client.newCall(request).execute().use { assertEquals("direct", it.body.string()) }
            SshTunnelRouting.configure("127.0.0.1", server.port)
            SshTunnelRouting.activate(SshTunnelRoute("127.0.0.1", server.port, forward.port))
            client.newCall(request).execute().use { assertEquals("tunneled", it.body.string()) }
            assertEquals(1, server.requestCount)
            assertEquals(1, forward.requestCount)
            SshTunnelRouting.activate(null)
            val error = assertThrows<IOException> { client.newCall(request).execute().close() }
            assertEquals("SSH tunnel is not connected", error.message)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `other loopback origin on the same port is not borrowed by the tunnel`() {
        server.enqueue(MockResponse().setBody("direct"))
        server.start()
        SshTunnelRouting.configure("gateway.internal", server.port)
        SshTunnelRouting.activate(SshTunnelRoute("gateway.internal", server.port, 1))
        val request = Request.Builder().url("http://127.0.0.1:${server.port}/").build()
        tunnelClient().newCall(request).execute().use { assertEquals("direct", it.body.string()) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a tunneled request reaches the local forward with the configured identity`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.start()

        SshTunnelRouting.configure("gateway.internal", 9119)
        SshTunnelRouting.activate(
            SshTunnelRoute(host = "gateway.internal", port = 9119, localPort = server.port),
        )

        val response =
            tunnelClient()
                .newCall(
                    Request.Builder().url("http://gateway.internal:9119/api/status").build(),
                ).execute()

        response.use {
            assertEquals(200, it.code)
            assertEquals("""{"ok":true}""", it.body.string())
        }
        val recorded = server.takeRequest()
        assertEquals("gateway.internal:9119", recorded.getHeader("Host"))
    }

    @Test
    fun `the tunnel also carries a loopback dashboard url`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("tunneled"))
        server.start()

        SshTunnelRouting.configure("127.0.0.1", 9119)
        SshTunnelRouting.activate(
            SshTunnelRoute(host = "127.0.0.1", port = 9119, localPort = server.port),
        )

        val response =
            tunnelClient()
                .newCall(
                    Request.Builder().url("http://127.0.0.1:9119/api/status").build(),
                ).execute()

        response.use {
            assertEquals(200, it.code)
            assertEquals("tunneled", it.body.string())
        }
    }

    @Test
    fun `route hostname and nonloopback IP literal mapping reach local forward`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ip-literal-tunneled"))
        server.start()

        SshTunnelRouting.configure("192.0.2.44", 9119)
        SshTunnelRouting.activate(
            SshTunnelRoute(host = "192.0.2.44", port = 9119, localPort = server.port),
        )

        val response =
            tunnelClient()
                .newCall(
                    Request.Builder().url("http://192.0.2.44:9119/api/status").build(),
                ).execute()

        response.use {
            assertEquals(200, it.code)
            assertEquals("ip-literal-tunneled", it.body.string())
        }
    }

    @Test
    fun `fail closed configured desired route blocks request before live route is active`() {
        // Desired route is configured for gateway.internal:9119, but tunnel is not active yet (route is null)
        SshTunnelRouting.configure("gateway.internal", 9119)
        SshTunnelRouting.activate(null)

        val error =
            assertThrows<IOException> {
                tunnelClient()
                    .newCall(
                        Request.Builder().url("http://gateway.internal:9119/api/status").build(),
                    ).execute()
            }
        assertEquals("SSH tunnel is not connected", error.message)
    }

    @Test
    fun `fail closed blocks requests after active route is lost`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()

        SshTunnelRouting.configure("gateway.internal", 9119)
        SshTunnelRouting.activate(
            SshTunnelRoute(host = "gateway.internal", port = 9119, localPort = server.port),
        )

        // Route lost
        SshTunnelRouting.activate(null)

        val error =
            assertThrows<IOException> {
                tunnelClient()
                    .newCall(
                        Request.Builder().url("http://gateway.internal:9119/api/status").build(),
                    ).execute()
            }
        assertEquals("SSH tunnel is not connected", error.message)
    }

    @Test
    fun `unrelated origins are not hijacked and dial direct`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()

        // Tunnel configured and active for different host
        SshTunnelRouting.configure("gateway.internal", 9119)
        SshTunnelRouting.activate(
            SshTunnelRoute(host = "gateway.internal", port = 9119, localPort = 44444),
        )

        // Request directly to mockwebserver loopback port must reach server and not be redirected to 44444
        val response =
            tunnelClient()
                .newCall(
                    Request.Builder().url("http://127.0.0.1:${server.port}/api/status").build(),
                ).execute()

        assertNotNull(response)
        response.use { assertEquals(200, it.code) }
        assertTrue(server.requestCount == 1)
    }

    @Test
    fun `without a tunnel the client dials the url itself`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()

        val response =
            tunnelClient()
                .newCall(
                    Request.Builder().url("http://127.0.0.1:${server.port}/api/status").build(),
                ).execute()

        assertNotNull(response)
        response.use { assertEquals(200, it.code) }
        assertTrue(server.requestCount == 1)
    }
}
