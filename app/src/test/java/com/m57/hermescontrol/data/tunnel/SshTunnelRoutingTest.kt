package com.m57.hermescontrol.data.tunnel

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

class SshTunnelRoutingTest {
    private val loopback = InetAddress.getLoopbackAddress()

    @AfterEach
    fun tearDown() {
        SshTunnelRouting.configure(null)
        SshTunnelRouting.activate(null)
    }

    @Test
    fun `IPv6 literal matching uses address identity not compression spelling`() {
        SshTunnelRouting.configure("2001:db8::1", 9119)
        SshTunnelRouting.activate(SshTunnelRoute("2001:db8::1", 9119, 43210))
        val endpoint = InetSocketAddress(InetAddress.getByName("2001:db8::1"), 9119)
        assertEquals(43210, (SshTunnelRouting.remap(endpoint) as InetSocketAddress).port)
    }

    @Test
    fun `without a route or desired configuration nothing is rewritten`() {
        val endpoint = InetSocketAddress(loopback, 9119)
        assertSame(endpoint, SshTunnelRouting.remap(endpoint))
        assertFalse(SshTunnelRouting.covers("127.0.0.1", 9119))
    }

    @Test
    fun `the routed port is sent to the local forward when active`() {
        SshTunnelRouting.configure("127.0.0.1", 9119)
        SshTunnelRouting.activate(SshTunnelRoute(host = "127.0.0.1", port = 9119, localPort = 43_210))

        val endpoint = InetSocketAddress(InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 9119)
        val remapped = SshTunnelRouting.remap(endpoint) as InetSocketAddress
        assertEquals(43_210, remapped.port)
        assertTrue(remapped.address.isLoopbackAddress)
    }

    @Test
    fun `unrelated loopback or other origins are NOT hijacked`() {
        SshTunnelRouting.configure("127.0.0.1", 9119)
        SshTunnelRouting.activate(SshTunnelRoute(host = "127.0.0.1", port = 9119, localPort = 43_210))

        // Unrelated origin loopback port 8080 or other loopback service on different port
        val endpointOtherPort = InetSocketAddress(loopback, 8080)
        assertSame(endpointOtherPort, SshTunnelRouting.remap(endpointOtherPort))

        // Different host on same port (e.g. gateway2.internal:9119) must NOT be hijacked
        val otherHostLoopback =
            InetSocketAddress(InetAddress.getByAddress("other.gateway", byteArrayOf(127, 0, 0, 1)), 9119)
        assertSame(otherHostLoopback, SshTunnelRouting.remap(otherHostLoopback))
    }

    @Test
    fun `route hostname and nonloopback IP literal mapping are covered`() {
        SshTunnelRouting.configure("10.0.0.5", 9119)
        SshTunnelRouting.activate(SshTunnelRoute(host = "10.0.0.5", port = 9119, localPort = 43_210))

        assertTrue(SshTunnelRouting.covers("10.0.0.5", 9119))
        assertFalse(SshTunnelRouting.covers("10.0.0.6", 9119))

        val synthetic = InetAddress.getByAddress("10.0.0.5", byteArrayOf(127, 0, 0, 1))
        val endpoint = InetSocketAddress(synthetic, 9119)
        val remapped = SshTunnelRouting.remap(endpoint) as InetSocketAddress
        assertEquals(43_210, remapped.port)
        assertTrue(remapped.address.isLoopbackAddress)
    }

    @Test
    fun `remap throws IOException when configured desired route is not connected`() {
        SshTunnelRouting.configure("10.0.0.5", 9119)
        SshTunnelRouting.activate(null)

        val synthetic = InetAddress.getByAddress("10.0.0.5", byteArrayOf(127, 0, 0, 1))
        val endpoint = InetSocketAddress(synthetic, 9119)

        val error =
            assertThrows<IOException> {
                SshTunnelRouting.remap(endpoint)
            }
        assertEquals("SSH tunnel is not connected", error.message)
    }

    @Test
    fun `same host other port DNS stays reachable and socket remap is untouched`() {
        SshTunnelRouting.configure("localhost", 9119)
        SshTunnelRouting.activate(SshTunnelRoute(host = "localhost", port = 9119, localPort = 43_210))

        // Port 8080 on the same host must NOT be remapped to tunnel localPort
        val endpointOtherPort =
            InetSocketAddress(InetAddress.getByAddress("localhost", SshTunnelDns.SYNTHETIC_ADDRESS), 8080)
        val remapped = SshTunnelRouting.remap(endpointOtherPort) as InetSocketAddress
        assertEquals(8080, remapped.port)
        assertEquals(
            okhttp3.Dns.SYSTEM
                .lookup("localhost")
                .first(),
            remapped.address,
        )
    }

    @Test
    fun `DNS synthetic loopback preserves hostname`() {
        SshTunnelRouting.configure("gateway.example.com", 9119)
        SshTunnelRouting.activate(SshTunnelRoute(host = "gateway.example.com", port = 9119, localPort = 43_210))

        val resolved = SshTunnelDns.lookup("gateway.example.com")
        assertEquals(1, resolved.size)
        val addr = resolved[0]
        assertTrue(addr.isLoopbackAddress)
        assertEquals("gateway.example.com", addr.hostName)
    }

    @Test
    fun `stopping the tunnel restores direct connections`() {
        SshTunnelRouting.configure("127.0.0.1", 9119)
        SshTunnelRouting.activate(SshTunnelRoute(host = "127.0.0.1", port = 9119, localPort = 43_210))

        // Stop active route and configure null
        SshTunnelRouting.activate(null)
        SshTunnelRouting.configure(null)

        val endpoint = InetSocketAddress(loopback, 9119)
        val remapped = SshTunnelRouting.remap(endpoint) as InetSocketAddress
        assertEquals(9119, remapped.port)
    }
}
