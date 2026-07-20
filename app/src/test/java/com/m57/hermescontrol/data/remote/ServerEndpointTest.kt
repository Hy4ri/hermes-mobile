package com.m57.hermescontrol.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerEndpointTest {
    @Test
    fun `parse accepts https with trailing slash normalization`() {
        val ep = ServerEndpoint.parse("https://example.com/hermes", CleartextPolicy.ALLOW_WITH_WARNING)
        assertEquals("https://example.com/hermes/", ep.baseUrl.toString())
        assertNull(ep.securityWarning)
        assertFalse(ep.isCleartext)
    }

    @Test
    fun `parse accepts http with warning`() {
        val ep = ServerEndpoint.parse("http://127.0.0.1:9119/", CleartextPolicy.ALLOW_WITH_WARNING)
        assertEquals("http://127.0.0.1:9119/", ep.baseUrl.toString())
        assertEquals(ServerEndpoint.CLEARTEXT_WARNING, ep.securityWarning)
        assertTrue(ep.isCleartext)
    }

    @Test
    fun `parse rejects non http scheme`() {
        assertThrows<IllegalArgumentException> {
            ServerEndpoint.parse("ftp://example.com/", CleartextPolicy.ALLOW_WITH_WARNING)
        }
    }

    @Test
    fun `parse rejects embedded credentials`() {
        assertThrows<IllegalArgumentException> {
            ServerEndpoint.parse("https://user:pass@example.com/", CleartextPolicy.ALLOW_WITH_WARNING)
        }
    }

    @Test
    fun `parse rejects query string`() {
        assertThrows<IllegalArgumentException> {
            ServerEndpoint.parse("https://example.com/?token=abc", CleartextPolicy.ALLOW_WITH_WARNING)
        }
    }

    @Test
    fun `parse rejects fragment`() {
        assertThrows<IllegalArgumentException> {
            ServerEndpoint.parse("https://example.com/#frag", CleartextPolicy.ALLOW_WITH_WARNING)
        }
    }

    @Test
    fun `DENY policy rejects http`() {
        assertThrows<IllegalArgumentException> {
            ServerEndpoint.parse("http://127.0.0.1:9119/", CleartextPolicy.DENY)
        }
    }

    @Test
    fun `DENY policy accepts https`() {
        val ep = ServerEndpoint.parse("https://example.com/", CleartextPolicy.DENY)
        assertNull(ep.securityWarning)
    }

    @Test
    fun `resolve builds prefixed url and rejects path escape`() {
        val ep = ServerEndpoint.parse("https://example.com/hermes/", CleartextPolicy.ALLOW_WITH_WARNING)
        assertEquals("https://example.com/hermes/api/status", ep.resolve("api/status").toString())
        assertThrows<IllegalArgumentException> {
            ep.resolve("../etc/passwd")
        }
        assertThrows<IllegalArgumentException> {
            ep.resolve("  ")
        }
    }

    @Test
    fun `webSocketUrl converts https to wss and http to ws`() {
        val https = ServerEndpoint.parse("https://example.com/hermes/", CleartextPolicy.ALLOW_WITH_WARNING)
        assertEquals(
            "wss://example.com/hermes/api/ws?token=abc",
            https.webSocketUrl("token", "abc"),
        )
        val http = ServerEndpoint.parse("http://127.0.0.1:9119/", CleartextPolicy.ALLOW_WITH_WARNING)
        assertEquals(
            "ws://127.0.0.1:9119/api/ws?ticket=xyz",
            http.webSocketUrl("ticket", "xyz"),
        )
    }

    @Test
    fun `webSocketUrl rejects unsupported auth param`() {
        val ep = ServerEndpoint.parse("https://example.com/", CleartextPolicy.ALLOW_WITH_WARNING)
        assertThrows<IllegalArgumentException> {
            ep.webSocketUrl("cookie", "abc")
        }
    }

    @Test
    fun `relativeRequestPath strips proxy prefix`() {
        val ep = ServerEndpoint.parse("https://example.com/hermes/", CleartextPolicy.ALLOW_WITH_WARNING)
        val request = ep.resolve("api/status")
        assertEquals("/api/status", ep.relativeRequestPath(request))
    }

    @Test
    fun `relativeRequestPath passes through non matching origin`() {
        val ep = ServerEndpoint.parse("https://example.com/hermes/", CleartextPolicy.ALLOW_WITH_WARNING)
        val other = ServerEndpoint.parse("https://other.com/x/", CleartextPolicy.ALLOW_WITH_WARNING)
        val request = other.resolve("api/status")
        assertEquals("/x/api/status", ep.relativeRequestPath(request))
    }

    @Test
    fun `redactWebSocketUrlForLog strips query`() {
        assertEquals(
            "wss://example.com/hermes/api/ws",
            ServerEndpoint.redactWebSocketUrlForLog("wss://example.com/hermes/api/ws?token=secret"),
        )
        assertEquals(
            "ws://127.0.0.1:9119/api/ws",
            ServerEndpoint.redactWebSocketUrlForLog("ws://127.0.0.1:9119/api/ws?ticket=secret"),
        )
    }

    @Test
    fun `fromLegacy preserves http host and port`() {
        val ep = ServerEndpoint.fromLegacy("10.0.0.1", 8080)
        assertEquals("http://10.0.0.1:8080/", ep.baseUrl.toString())
        assertTrue(ep.isCleartext)
    }

    @Test
    fun `DEFAULT_BASE_URL is https localhost 9119`() {
        assertEquals("https://127.0.0.1:9119/", ServerEndpoint.DEFAULT_BASE_URL)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName} but nothing was thrown")
        } catch (e: Throwable) {
            if (e !is T) throw e
        }
    }
}
