package com.m57.hermescontrol.data.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SshTunnelConfigTest {
    private val valid =
        SshTunnelConfig(
            host = "gateway.example.com",
            username = "hermes",
            password = "secret",
            remotePort = 9119,
        )

    @Test
    fun `a complete password config has no problem`() {
        assertNull(valid.problem())
    }

    @Test
    fun `required fields are validated`() {
        assertEquals(SshTunnelProblem.SSH_HOST, valid.copy(host = " ").problem())
        assertEquals(SshTunnelProblem.SSH_PORT, valid.copy(port = 0).problem())
        assertEquals(SshTunnelProblem.SSH_PORT, valid.copy(port = 65_536).problem())
        assertEquals(SshTunnelProblem.USERNAME, valid.copy(username = "").problem())
        assertEquals(SshTunnelProblem.PASSWORD, valid.copy(password = "").problem())
        assertEquals(SshTunnelProblem.REMOTE_HOST, valid.copy(remoteHost = "").problem())
        assertEquals(SshTunnelProblem.REMOTE_PORT, valid.copy(remotePort = 0).problem())
    }

    @Test
    fun `private key auth needs a key instead of a password`() {
        val keyed = valid.copy(authMode = SshAuthMode.PRIVATE_KEY, password = "", privateKey = "-----BEGIN KEY-----")
        assertNull(keyed.problem())
        assertEquals(SshTunnelProblem.PRIVATE_KEY, keyed.copy(privateKey = "").problem())
    }

    @Test
    fun `change token ignores no credentials and changes with them`() {
        assertEquals(valid.changeToken(), valid.copy().changeToken())
        assertNotEquals(valid.changeToken(), valid.copy(password = "other").changeToken())
        assertNotEquals(valid.changeToken(), valid.copy(port = 2222).changeToken())
        assertNotEquals(valid.changeToken(), valid.copy(enabled = false).changeToken())
    }

    @Test
    fun `toString never reveals credentials`() {
        val text = valid.toString()
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("hermes"))
    }

    @Test
    fun `origin drops the path and normalizes the host`() {
        assertEquals("http://127.0.0.1:9119", tunnelOriginOf("http://127.0.0.1:9119/")!!)
        assertEquals(
            "https://gateway.example.com:9119",
            tunnelOriginOf("https://Gateway.Example.com:9119/sub/path?x=1")!!,
        )
        assertEquals("http://10.0.0.5:80", tunnelOriginOf("  http://10.0.0.5  ")!!)
    }

    @Test
    fun `IPv6 origins retain brackets and remain parseable`() {
        assertEquals("http://[2001:db8::1]:9119", tunnelOriginOf("http://[2001:db8::1]:9119/"))
        assertEquals("http://[::1]:80", tunnelOriginOf("http://[::1]"))
    }

    @Test
    fun `non http urls have no origin`() {
        assertNull(tunnelOriginOf(null))
        assertNull(tunnelOriginOf(""))
        assertNull(tunnelOriginOf("ftp://example.com"))
        assertNull(tunnelOriginOf("not a url"))
        assertTrue(tunnelOriginOf("ws://example.com:8080").isNullOrEmpty())
    }
}
