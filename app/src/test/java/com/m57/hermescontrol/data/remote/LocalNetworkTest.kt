package com.m57.hermescontrol.data.remote

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalNetworkTest {
    @Test
    fun `loopback host is exempt`() {
        assertFalse(isLocalNetworkHost("127.0.0.1"))
        assertFalse(isLocalNetworkHost("localhost"))
        assertFalse(isLocalNetworkHost("::1"))
    }

    @Test
    fun `private rfc1918 ranges require permission`() {
        assertTrue(isLocalNetworkHost("192.168.1.50"))
        assertTrue(isLocalNetworkHost("10.0.0.5"))
        assertTrue(isLocalNetworkHost("10.255.255.254"))
        assertTrue(isLocalNetworkHost("172.16.0.1"))
        assertTrue(isLocalNetworkHost("172.31.255.255"))
    }

    @Test
    fun `link-local and cgnat require permission`() {
        assertTrue(isLocalNetworkHost("169.254.0.1"))
        assertTrue(isLocalNetworkHost("100.64.0.1"))
        assertTrue(isLocalNetworkHost("100.127.255.254"))
    }

    @Test
    fun `public remote hosts are exempt`() {
        assertFalse(isLocalNetworkHost("8.8.8.8"))
        assertFalse(isLocalNetworkHost("1.1.1.1"))
    }

    @Test
    fun `needsLocalNetworkPermission gates by base url host`() {
        assertFalse(needsLocalNetworkPermission("http://127.0.0.1:9119"))
        assertFalse(needsLocalNetworkPermission("https://gateway.example.com"))
        assertTrue(needsLocalNetworkPermission("http://192.168.1.50:9119"))
        assertTrue(needsLocalNetworkPermission("https://10.0.0.5:9119/api"))
    }

    @Test
    fun `malformed base url does not require permission`() {
        assertFalse(needsLocalNetworkPermission("not-a-url"))
        assertFalse(needsLocalNetworkPermission(""))
    }
}
