package com.m57.hermescontrol.data.remote

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The SSH tunnel changes which address the platform actually dials, so the
 * Android 17 local-network gate has to follow the SSH server, not the dashboard
 * URL that is only reachable from inside the tunnel.
 */
class SshTunnelLanGateTest {
    @Test
    fun `without a tunnel the url host decides`() {
        assertTrue(requiresLocalNetworkPermission(37, "http://192.168.1.50:9119"))
        assertFalse(requiresLocalNetworkPermission(37, "http://127.0.0.1:9119"))
    }

    @Test
    fun `a tunneled LAN url behind a public ssh host needs no permission`() {
        assertFalse(requiresLocalNetworkPermission(37, "http://192.168.1.50:9119", "gateway.example.com"))
    }

    @Test
    fun `a tunneled loopback url behind a LAN ssh host does need permission`() {
        assertTrue(requiresLocalNetworkPermission(37, "http://127.0.0.1:9119", "192.168.1.50"))
        assertTrue(requiresLocalNetworkPermission(37, "http://127.0.0.1:9119", "hermes-box.tail1234.ts.net"))
    }

    @Test
    fun `the gate stays off below api 37 even with a tunnel`() {
        assertFalse(requiresLocalNetworkPermission(36, "http://192.168.1.50:9119", "192.168.1.50"))
    }

    @Test
    fun `a blank tunnel host falls back to the url host`() {
        assertTrue(requiresLocalNetworkPermission(37, "http://192.168.1.50:9119", ""))
    }
}
