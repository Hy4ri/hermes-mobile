package com.m57.hermescontrol.data.tunnel

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress

class SshTunnelRoutingTest {
    private val loopback = InetAddress.getLoopbackAddress()

    @AfterEach
    fun tearDown() {
        SshTunnelRouting.activate(null)
    }

    @Test
    fun `without a route nothing is rewritten`() {
        val endpoint = InetSocketAddress(loopback, 9119)
        assertSame(endpoint, SshTunnelRouting.remap(endpoint))
        assertFalse(SshTunnelRouting.covers("127.0.0.1", 9119))
    }

    @Test
    fun `the routed port is sent to the local forward`() {
        SshTunnelRouting.activate(SshTunnelRoute(host = "127.0.0.1", port = 9119, localPort = 43_210))
        val remapped = SshTunnelRouting.remap(InetSocketAddress(loopback, 9119)) as InetSocketAddress
        assertEquals(43_210, remapped.port)
        assertTrue(remapped.address.isLoopbackAddress)
    }

    @Test
    fun `other ports on the same host are untouched`() {
        SshTunnelRouting.activate(SshTunnelRoute(host = "127.0.0.1", port = 9119, localPort = 43_210))
        val endpoint = InetSocketAddress(loopback, 8080)
        assertSame(endpoint, SshTunnelRouting.remap(endpoint))
    }

    @Test
    fun `non loopback destinations are untouched`() {
        SshTunnelRouting.activate(SshTunnelRoute(host = "gateway.example.com", port = 9119, localPort = 43_210))
        val public = InetSocketAddress(InetAddress.getByName("8.8.8.8"), 9119)
        assertSame(public, SshTunnelRouting.remap(public))
    }

    @Test
    fun `covers names the tunneled origin`() {
        SshTunnelRouting.activate(SshTunnelRoute(host = "gateway.example.com", port = 9119, localPort = 43_210))
        assertTrue(SshTunnelRouting.covers("Gateway.Example.com", 9119))
        assertFalse(SshTunnelRouting.covers("gateway.example.com", 443))
    }

    @Test
    fun `dns resolves the tunneled host to loopback and leaves the rest alone`() {
        SshTunnelRouting.activate(SshTunnelRoute(host = "gateway.example.com", port = 9119, localPort = 43_210))
        assertEquals(listOf(loopback), SshTunnelDns.lookup("gateway.example.com"))
        assertEquals(listOf(loopback), SshTunnelDns.lookup("GATEWAY.example.com"))
        assertTrue(SshTunnelDns.lookup("localhost").all { it.isLoopbackAddress })
    }

    @Test
    fun `stopping the tunnel restores direct connections`() {
        SshTunnelRouting.activate(SshTunnelRoute(host = "127.0.0.1", port = 9119, localPort = 43_210))
        SshTunnelRouting.activate(null)
        val remapped = SshTunnelRouting.remap(InetSocketAddress(loopback, 9119)) as InetSocketAddress
        assertEquals(9119, remapped.port)
    }
}
