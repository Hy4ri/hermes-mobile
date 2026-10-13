package com.m57.hermescontrol.data.remote

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Shared HTTP tests have no Application/AndroidCAStore. TLS policy has separate handshake tests. */
fun mockPlatformServerTrust(): X509TrustManager {
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
    return trust
}

fun mockSystemServerTrust() {
    val trust = mockPlatformServerTrust()
    mockkObject(ServerCaRestrictions)
    every { ServerCaRestrictions.manager(any()) } returns trust
    every { ServerCaRestrictions.checkAvailable(any()) } returns Unit
    every { ServerCaRestrictions.cacheKey(any()) } answers { firstArg<okhttp3.HttpUrl>().toString() }
}

/** Pair with the HTTP fixture setup so CA negative tests never inherit its object mocks. */
fun unmockSystemServerTrust() = unmockkObject(ServerTrust, ServerCaRestrictions)
