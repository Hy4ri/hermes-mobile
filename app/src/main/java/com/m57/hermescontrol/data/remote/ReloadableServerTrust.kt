package com.m57.hermescontrol.data.remote

import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.WeakHashMap
import javax.net.ssl.X509TrustManager

/** Stable delegate for retained clients; retire every TLS context before publishing a new policy. */
internal class ReloadableServerTrust(
    initial: Boolean = false,
    private val load: (Boolean) -> X509TrustManager,
    private val persist: (Boolean) -> Unit,
    private val changed: () -> Unit,
) : X509TrustManager {
    private var enabled = initial
    private var delegate = load(initial)
    private val sockets = WeakHashMap<CertificateSocketFactory, Unit>()
    private var epoch = UUID.randomUUID().toString()

    @Synchronized
    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun cacheEpoch(): String = epoch

    @Synchronized
    fun track(factory: CertificateSocketFactory) {
        sockets[factory] = Unit
    }

    @Synchronized
    fun untrack(factory: CertificateSocketFactory) {
        sockets.remove(factory)
        factory.invalidateAll()
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (value == enabled) return
        val next = load(value)
        // A failed commit leaves both the displayed setting and runtime policy unchanged.
        persist(value)
        replace(next)
        enabled = value
    }

    @Synchronized
    fun reload(): Boolean {
        // Retire first: even a failed store reload cannot leave an already trusted connection alive.
        retire()
        return try {
            delegate = load(enabled)
            true
        } catch (e: Exception) {
            delegate = RejectServerTrust(e)
            false
        }
    }

    private fun replace(next: X509TrustManager) {
        retire()
        delegate = next
    }

    private fun retire() {
        sockets.keys.toList().forEach { it.invalidateAll() }
        epoch = UUID.randomUUID().toString()
        changed()
    }

    @Synchronized
    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) {
        current().checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) {
        checked { it.checkServerTrusted(chain, authType) }
    }

    /** Android's hostname-aware chain cleaner must consult the current delegate, not cached roots. */
    fun <T> checked(check: (X509TrustManager) -> T): T {
        val (manager, generation) = synchronized(this) { delegate to epoch }
        val result = check(manager)
        synchronized(this) {
            if (epoch != generation) throw CertificateException("Server trust changed during verification")
        }
        return result
    }

    @Synchronized
    private fun current(): X509TrustManager = delegate

    fun requireEpoch(expected: String) {
        if (cacheEpoch() != expected) throw IOException("Server trust changed during request")
    }

    private class RejectServerTrust(
        private val cause: Exception,
    ) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ): Unit = throw CertificateException("Android CA store is unavailable", cause)

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ): Unit = throw CertificateException("Android CA store is unavailable", cause)
    }
}
