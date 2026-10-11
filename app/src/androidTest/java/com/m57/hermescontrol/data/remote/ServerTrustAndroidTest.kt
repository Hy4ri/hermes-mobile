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
    fun androidClientUsesStartupRootsUntilNewProcessPolicy() {
        val fixture = fixtures()
        var saved = false

        fun process(): StartupServerTrust =
            StartupServerTrust(saved, { saved = it }) { enabled ->
                trust(
                    KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                        load(null)
                        setCertificateEntry("system", fixture.getCertificate("system-ca"))
                        if (enabled) setCertificateEntry("user", fixture.getCertificate("user-ca"))
                    },
                )
            }

        fun client(policy: StartupServerTrust): OkHttpClient =
            OkHttpClient
                .Builder()
                .sslSocketFactory(
                    CertificateSocketFactory(policy.manager) {
                        ClientCertificateKeyManager(
                            choose = { _, _, _ -> null },
                            privateKey = { null },
                            certificateChain = { null },
                        )
                    },
                    policy.manager,
                ).readTimeout(3, TimeUnit.SECONDS)
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

            fun get(client: OkHttpClient) =
                client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                    it.body.string()
                }
            val disabled = process()
            val oldClient = client(disabled)
            assertThrows(IOException::class.java) { get(oldClient) }
            disabled.save(true)
            assertThrows(IOException::class.java) { get(oldClient) }
            val enabled = process()
            val enabledClient = client(enabled)
            server.enqueue(MockResponse().setBody("enabled after restart"))
            assertEquals("enabled after restart", get(enabledClient))
            enabled.save(false)
            server.enqueue(MockResponse().setBody("still active"))
            assertEquals("still active", get(enabledClient))
            assertThrows(IOException::class.java) { get(client(process())) }
            listOf(oldClient, enabledClient).forEach {
                it.connectionPool.evictAll()
                it.dispatcher.executorService.shutdown()
            }
        }
    }
}
