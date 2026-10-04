package com.m57.hermescontrol.data.remote

import java.net.InetAddress
import java.net.Socket
import java.util.WeakHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** Separates TLS sessions by origin and retires live sockets when an identity changes. */
internal class CertificateSocketFactory(
    private val trust: X509TrustManager,
    private val keys: (CertificateOrigin) -> javax.net.ssl.KeyManager,
) : SSLSocketFactory() {
    private class Entry(
        val context: SSLContext,
    ) {
        val sockets = WeakHashMap<Socket, Unit>()
    }

    private val entries = mutableMapOf<CertificateOrigin, Entry>()
    private val defaultFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory

    @Synchronized
    private fun socket(
        origin: CertificateOrigin,
        create: (SSLSocketFactory) -> Socket,
    ): Socket {
        val entry =
            entries.getOrPut(origin) {
                Entry(SSLContext.getInstance("TLS").apply { init(arrayOf(keys(origin)), arrayOf(trust), null) })
            }
        return create(entry.context.socketFactory).also { entry.sockets[it] = Unit }
    }

    fun invalidate(origin: CertificateOrigin) {
        val retired =
            synchronized(this) {
                entries.remove(origin)?.let { it.context to it.sockets.keys.toList() }
            }
                ?: return
        runCatching {
            val sessions = retired.first.clientSessionContext
            val ids = sessions.ids
            while (ids.hasMoreElements()) sessions.getSession(ids.nextElement())?.invalidate()
        }
        // Dropping the context also prevents session-ticket reuse when providers do not enumerate tickets.
        retired.second.forEach { runCatching { it.close() } }
    }

    fun invalidateAll() {
        val origins = synchronized(this) { entries.keys.toList() }
        origins.forEach { invalidate(it) }
    }

    override fun createSocket(
        socket: Socket,
        host: String,
        port: Int,
        autoClose: Boolean,
    ): Socket = socket(CertificateOrigin(host, port)) { it.createSocket(socket, host, port, autoClose) }

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = socket(CertificateOrigin(host, port)) { it.createSocket(host, port) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = socket(CertificateOrigin(host, port)) { it.createSocket(host, port, localHost, localPort) }

    override fun createSocket(
        host: InetAddress,
        port: Int,
    ): Socket = createSocket(host.hostAddress!!, port)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = createSocket(address.hostAddress!!, port, localAddress, localPort)

    override fun getDefaultCipherSuites(): Array<String> = defaultFactory.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = defaultFactory.supportedCipherSuites
}
