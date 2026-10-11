package com.m57.hermescontrol.data.remote

import io.mockk.every
import io.mockk.mockkObject
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Shared HTTP tests have no Application/AndroidCAStore. TLS policy has separate handshake tests. */
fun mockSystemServerTrust() {
    val trust =
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply {
                init(null as KeyStore?)
            }.trustManagers
            .filterIsInstance<X509TrustManager>()
            .single()
    mockkObject(ServerTrust)
    every { ServerTrust.manager } returns trust
}
