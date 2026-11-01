package com.m57.hermescontrol.data.tunnel

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

/** Routing policy exists even while SSH is connecting: an enabled tunnel must never dial direct. */
object SshTunnelRouting {
    private data class Destination(
        val host: String,
        val port: Int,
    ) {
        fun matches(
            host: String,
            port: Int,
        ): Boolean = this.port == port && canonicalHost(this.host) == canonicalHost(host)
    }

    private fun canonicalHost(host: String): String =
        okhttp3.HttpUrl
            .Builder()
            .scheme("http")
            .host(host.removeSurrounding("[", "]"))
            .build()
            .host

    private var route: SshTunnelRoute? = null
    private var desired: Destination? = null
    private val sockets = java.util.WeakHashMap<Socket, Destination>()

    @Synchronized
    fun configure(
        host: String?,
        port: Int = 0,
    ) {
        val next = host?.let { Destination(it, port) }
        if (next == desired) return
        val old = desired
        desired = next
        closeSockets(old, next)
    }

    @Synchronized
    fun activate(route: SshTunnelRoute?) {
        if (this.route == route) return
        val old = this.route?.let { Destination(it.host, it.port) }
        this.route = route
        closeSockets(old, route?.let { Destination(it.host, it.port) })
    }

    private fun closeSockets(vararg destinations: Destination?) {
        // Close active as well as idle connections. Pool eviction alone leaves HTTP/2
        // connections eligible for reuse after changing the SSH transport.
        val stale =
            sockets.entries
                .filter { entry ->
                    destinations.any { it?.matches(entry.value.host, entry.value.port) == true }
                }.map { it.key }
        stale.forEach { runCatching { it.close() } }
    }

    @Synchronized
    fun current(): SshTunnelRoute? = route

    @Synchronized
    fun covers(
        host: String,
        port: Int,
    ): Boolean = route?.let { Destination(it.host, it.port).matches(host, port) } == true

    private fun storedDestinations(): List<Destination> =
        SshTunnelStore.all().filterValues { it.enabled }.keys.mapNotNull { origin ->
            origin.toHttpUrlOrNull()?.let { Destination(it.host, it.port) }
        }

    private fun required(
        host: String,
        port: Int,
    ): Boolean = desired?.matches(host, port) == true || storedDestinations().any { it.matches(host, port) }

    @Synchronized
    fun usesHost(host: String): Boolean {
        val canonical = canonicalHost(host)
        return listOfNotNull(desired?.host, route?.host).any { canonicalHost(it) == canonical } ||
            storedDestinations().any { canonicalHost(it.host) == canonical }
    }

    /** Also runs before pooled connections are acquired, not just on new TCP connections. */
    @Synchronized
    fun requireReady(
        host: String,
        port: Int,
    ) {
        if (SshTunnelStore.loadFailed || (required(host, port) && !covers(host, port))) {
            throw java.io.IOException("SSH tunnel is not connected")
        }
    }

    @Synchronized
    internal fun register(
        socket: Socket,
        endpoint: SocketAddress,
    ): SocketAddress {
        val result = remap(endpoint)
        if (endpoint is InetSocketAddress) sockets[socket] = Destination(endpoint.hostString, endpoint.port)
        return result
    }

    @Synchronized
    internal fun unregister(socket: Socket) {
        sockets.remove(socket)
    }

    /** Matches the original host, including IP literals for which OkHttp never calls Dns. */
    @Synchronized
    fun remap(endpoint: SocketAddress): SocketAddress {
        if (endpoint !is InetSocketAddress) return endpoint
        val host = endpoint.hostString
        requireReady(host, endpoint.port)
        val current = route
        if (current != null && Destination(current.host, current.port).matches(host, endpoint.port)) {
            return InetSocketAddress(InetAddress.getByName("127.0.0.1"), current.localPort)
        }
        // Dns has no port argument. A synthetic result for the tunneled host must
        // not send its OTHER ports to loopback; resolve those normally here.
        if (endpoint.address?.address?.contentEquals(SshTunnelDns.SYNTHETIC_ADDRESS) == true &&
            !host.equals(endpoint.address.hostAddress, ignoreCase = true)
        ) {
            val address = Dns.SYSTEM.lookup(host).firstOrNull() ?: throw java.net.UnknownHostException(host)
            return InetSocketAddress(address, endpoint.port)
        }
        return endpoint
    }
}

/** Keeps the hostname on the synthetic address so socket routing cannot hijack other loopback origins. */
object SshTunnelDns : Dns {
    internal val SYNTHETIC_ADDRESS = byteArrayOf(127, 0, 0, 2)

    override fun lookup(hostname: String): List<InetAddress> =
        if (SshTunnelRouting.usesHost(hostname)) {
            listOf(InetAddress.getByAddress(hostname, SYNTHETIC_ADDRESS))
        } else {
            Dns.SYSTEM.lookup(hostname)
        }
}

/** Fail closed on every request, including requests that could reuse a pooled connection. */
object SshTunnelInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val url = chain.request().url
        SshTunnelRouting.requireReady(url.host, url.port)
        return chain.proceed(chain.request())
    }
}

/** Do not let a system HTTP/SOCKS proxy bypass the tunnel socket factory. */
object SshTunnelProxySelector : java.net.ProxySelector() {
    private val system = getDefault()

    override fun select(uri: java.net.URI): List<java.net.Proxy> {
        val url = uri.toString().toHttpUrlOrNull()
        if (url != null && SshTunnelRouting.covers(url.host, url.port)) {
            return listOf(java.net.Proxy.NO_PROXY)
        }
        return system?.select(uri) ?: listOf(java.net.Proxy.NO_PROXY)
    }

    override fun connectFailed(
        uri: java.net.URI,
        sa: SocketAddress,
        ioe: java.io.IOException,
    ) {
        system?.connectFailed(uri, sa, ioe)
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
    override fun close() {
        SshTunnelRouting.unregister(this)
        super.close()
    }

    override fun connect(
        endpoint: SocketAddress,
        timeout: Int,
    ) {
        try {
            super.connect(SshTunnelRouting.register(this, endpoint), timeout)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun connect(endpoint: SocketAddress) {
        connect(endpoint, 0)
    }
}
