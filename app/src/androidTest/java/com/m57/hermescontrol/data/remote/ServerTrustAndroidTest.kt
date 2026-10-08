package com.m57.hermescontrol.data.remote

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Device-only gates: real Android chain cleaner and current AndroidCAStore, no user-store mutation. */
class ServerTrustAndroidTest {
    private fun fixtures(): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            InstrumentationRegistry.getInstrumentation().context.assets.open("server-trust.p12").use {
                load(it, "test-only".toCharArray())
            }
        }

    private fun trust(store: KeyStore?): X509TrustManager =
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .single()

    @Test
    fun androidStoreUsesActiveRootsAndDisabledPolicyPreservesPlatformDefaults() {
        val active = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        assertEquals(trust(null).acceptedIssuers.toSet(), ServerTrust.loadTrustManager(false).acceptedIssuers.toSet())
        assertEquals(trust(active).acceptedIssuers.toSet(), ServerTrust.loadTrustManager(true).acceptedIssuers.toSet())
    }

    @Test
    fun retainedAndroidClientUsesNewRootsAndRejectsRevokedTrust() {
        val fixture = fixtures()
        var installed = true
        val policy =
            ReloadableServerTrust(load = { enabled ->
                val store =
                    KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                        load(null)
                        setCertificateEntry("system", fixture.getCertificate("system-ca"))
                        if (enabled && installed) setCertificateEntry("user", fixture.getCertificate("user-ca"))
                    }
                trust(store)
            }, persist = {}, changed = {})
        val manager = ServerTrust.AndroidServerTrustManager { policy }
        val sockets =
            CertificateSocketFactory(manager) {
                ClientCertificateKeyManager(choose = {
                    _,
                    _,
                    _,
                    ->
                    null
                }, privateKey = { null }, certificateChain = { null })
            }.also(policy::track)
        val client =
            OkHttpClient
                .Builder()
                .sslSocketFactory(sockets, manager)
                .readTimeout(3, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        val keys =
            KeyStore.getInstance("PKCS12").apply {
                load(null)
                setKeyEntry(
                    "server",
                    fixture.getKey("user", "test-only".toCharArray()),
                    "test-only".toCharArray(),
                    fixture.getCertificateChain("user"),
                )
            }
        val keyManagers =
            KeyManagerFactory
                .getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply {
                    init(keys, "test-only".toCharArray())
                }.keyManagers
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        MockWebServer().use { server ->
            server.useHttps(context.socketFactory, false)
            server.start()

            fun get() =
                client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                    it.body.string()
                }
            assertThrows(IOException::class.java) { get() }
            policy.setEnabled(true)
            server.enqueue(MockResponse().setBody("enabled"))
            assertEquals("enabled", get()) // Same client's Android chain cleaner must discover the new user root.
            policy.setEnabled(false)
            assertThrows(IOException::class.java) { get() }
            policy.setEnabled(true)
            server.enqueue(MockResponse().setBody("enabled again"))
            assertEquals("enabled again", get())
            installed = false
            policy.reload()
            assertThrows(IOException::class.java) { get() }
        }
    }
}
