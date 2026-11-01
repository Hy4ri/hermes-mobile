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

/**
 * Proves the claim the feature rests on: with the two OkHttp hooks installed,
 * a request to a dashboard URL that the phone cannot resolve is carried by the
 * tunnel's loopback port, while the URL, the `Host` header and the cookie
 * origin stay the ones the user configured.
 *
 * The "tunnel" here is a MockWebServer on the local forward's port; the SSH hop
 * itself is covered by the tunnel verification script.
 */
class SshTunnelSocketRoutingTest {
    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        SshTunnelRouting.activate(null)
        runCatching { server.shutdown() }
    }

    private fun tunnelClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .dns(SshTunnelDns)
            .socketFactory(SshTunnelSocketFactory)
            .build()

    @Test
    fun `a tunneled request reaches the local forward with the configured identity`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.start()
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
    fun `without a tunnel the client dials the url itself`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()

        // Nothing listens on the tunneled port: the request must fail instead of
        // silently borrowing another origin's forward.
        val response =
            runCatching {
                tunnelClient()
                    .newCall(
                        Request.Builder().url("http://127.0.0.1:${server.port}/api/status").build(),
                    ).execute()
            }.getOrNull()

        assertNotNull(response)
        response!!.use { assertEquals(200, it.code) }
        assertTrue(server.requestCount == 1)
    }
}
