package com.m57.hermescontrol.data.tunnel

import okhttp3.Dns
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * The single TCP destination that the shared OkHttp clients re-route into the
 * SSH tunnel.
 *
 * [host] and [port] are the dashboard origin's own address (the URL the app
 * would use on the SSH server); [localPort] is the loopback port that the SSH
 * local forward is listening on.
 */
data class SshTunnelRoute(
    val host: String,
    val port: Int,
    val localPort: Int,
)

/**
 * Holds the route of the currently connected tunnel. Both OkHttp hooks read it
 * per connection, so starting or dropping a tunnel never requires rebuilding a
 * client, and every URL, cookie scope, TLS binding and WebSocket handshake
 * stays exactly as configured.
 */
object SshTunnelRouting {
    @Volatile
    private var route: SshTunnelRoute? = null

    fun activate(route: SshTunnelRoute?) {
        this.route = route
    }

    fun current(): SshTunnelRoute? = route

    /** True when requests to [host]:[port] would be carried by the tunnel. */
    fun covers(
        host: String,
        port: Int,
    ): Boolean {
        val current = route ?: return false
        return current.port == port && current.host.equals(host, ignoreCase = true)
    }

    /**
     * Sends the tunnel's dashboard address to the local forward instead of the
     * network. [SshTunnelDns] has already resolved that address to loopback, so
     * a loopback endpoint on the tunneled port is the routed destination; a
     * genuinely local dashboard on the same port is the same destination.
     */
    fun remap(endpoint: SocketAddress): SocketAddress {
        val current = route ?: return endpoint
        if (endpoint !is InetSocketAddress) return endpoint
        if (endpoint.port != current.port) return endpoint
        val address =
            endpoint.address
                ?: runCatching { InetAddress.getByName(endpoint.hostString) }.getOrNull()
                ?: return endpoint
        if (!address.isLoopbackAddress) return endpoint
        return InetSocketAddress(InetAddress.getLoopbackAddress(), current.localPort)
    }
}

/**
 * Resolves the tunneled dashboard address to loopback so route construction
 * never depends on the phone being able to resolve (or reach) it. Every other
 * hostname keeps the platform resolver.
 */
object SshTunnelDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val route = SshTunnelRouting.current()
        if (route != null && route.host.equals(hostname, ignoreCase = true)) {
            return listOf(InetAddress.getLoopbackAddress())
        }
        return Dns.SYSTEM.lookup(hostname)
    }
}

/**
 * OkHttp creates the raw TCP socket through this factory (also for HTTPS, where
 * the TLS layer is wrapped over it afterwards), so overriding [SshTunnelSocket]
 * is enough to move the payload into the tunnel while SNI and certificate
 * verification keep using the URL's real host.
 */
object SshTunnelSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = SshTunnelSocket()

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = SshTunnelSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket =
        SshTunnelSocket().apply {
            if (localHost != null) bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(
        host: InetAddress,
        port: Int,
    ): Socket = SshTunnelSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket =
        SshTunnelSocket().apply {
            if (localAddress != null) bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}

/** A plain TCP socket whose destination is rewritten by [SshTunnelRouting]. */
private class SshTunnelSocket : Socket() {
    override fun connect(
        endpoint: SocketAddress,
        timeout: Int,
    ) {
        super.connect(SshTunnelRouting.remap(endpoint), timeout)
    }

    override fun connect(endpoint: SocketAddress) {
        super.connect(SshTunnelRouting.remap(endpoint))
    }
}
