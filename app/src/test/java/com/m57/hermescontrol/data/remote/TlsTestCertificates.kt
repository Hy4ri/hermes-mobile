package com.m57.hermescontrol.data.remote

import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Test-only self-signed identities, loaded with standard JSSE; no Android storage or network dependency. */
internal class TlsTestIdentity(
    alias: String,
) {
    private val store =
        KeyStore.getInstance("PKCS12").apply {
            TlsTestIdentity::class.java.getResourceAsStream("/tls/client-identities.p12").use {
                load(requireNotNull(it), PASSWORD)
            }
        }
    val certificate = store.getCertificate(alias) as X509Certificate
    val keyPair = KeyPair(certificate.publicKey, store.getKey(alias, PASSWORD) as PrivateKey)

    companion object {
        val PASSWORD = "test-only".toCharArray()
    }
}

internal class TlsTestContext(
    identity: TlsTestIdentity? = null,
    trusted: List<X509Certificate> = emptyList(),
) {
    private val trustStore =
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            trusted.forEachIndexed { index, certificate -> setCertificateEntry("trusted-$index", certificate) }
        }
    val trustManager =
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply {
                init(trustStore)
            }.trustManagers
            .filterIsInstance<X509TrustManager>()
            .single()
    private val context =
        SSLContext.getInstance("TLS").apply {
            val keyManagers =
                identity?.let {
                    val keys =
                        KeyStore.getInstance("PKCS12").apply {
                            load(null, null)
                            setKeyEntry(
                                "identity",
                                it.keyPair.private,
                                TlsTestIdentity.PASSWORD,
                                arrayOf(it.certificate),
                            )
                        }
                    KeyManagerFactory
                        .getInstance(KeyManagerFactory.getDefaultAlgorithm())
                        .apply {
                            init(keys, TlsTestIdentity.PASSWORD)
                        }.keyManagers
                }
            init(keyManagers, arrayOf(trustManager), null)
        }

    fun sslSocketFactory() = context.socketFactory
}
