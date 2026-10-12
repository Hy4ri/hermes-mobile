package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** JSSE + injected roots only. AndroidCAStore and Android's chain cleaner need device verification. */
class ServerTrustPolicyTest {
    private val fixture = "/tls/server-trust.p12"
    private val system = TlsTestIdentity("system", fixture)
    private val user = TlsTestIdentity("user", fixture)
    private val unknown = TlsTestIdentity("unknown", fixture)
    private val expired = TlsTestIdentity("expired", fixture)
    private val roots =
        KeyStore.getInstance("PKCS12").apply {
            ServerTrustPolicyTest::class.java.getResourceAsStream(fixture).use {
                load(requireNotNull(it), TlsTestIdentity.PASSWORD)
            }
        }

    private fun root(name: String) = roots.getCertificate("$name-ca") as X509Certificate

    private var persisted = false
    private var writesFail = false

    private fun policy(enabled: Boolean = persisted) =
        StartupServerTrust(
            active = enabled,
            load = { includeUser ->
                TlsTestContext(
                    trusted = listOf(root("system")) + if (includeUser) listOf(root("user")) else emptyList(),
                ).trustManager
            },
            persist = { if (writesFail) throw IOException("disk failure") else persisted = it },
        )

    private fun manager(identity: TlsTestIdentity?) =
        ClientCertificateKeyManager(
            choose = { _, _, _ -> identity?.let { "client" } },
            privateKey = { identity?.keyPair?.private },
            certificateChain = { identity?.chain },
        )

    private fun client(policy: StartupServerTrust) =
        OkHttpClient
            .Builder()
            .sslSocketFactory(CertificateSocketFactory(policy.manager) { manager(null) }, policy.manager)
            .retryOnConnectionFailure(false)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

    private fun server(
        identity: TlsTestIdentity,
        trusted: List<X509Certificate> = emptyList(),
    ) = MockWebServer().apply {
        useHttps(TlsTestContext(identity, trusted).sslSocketFactory(), false)
        start()
    }

    private fun get(
        client: OkHttpClient,
        server: MockWebServer,
    ) = client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body.string() }

    @Test
    fun `saved and active remain separate until restart and switching back clears difference`() {
        val process = policy(false)
        assertFalse(process.active)
        process.save(true)
        assertTrue(process.state.value)
        assertFalse(process.active)
        assertThrows(CertificateException::class.java) { process.manager.checkServerTrusted(user.chain, "RSA") }
        assertTrue(policy().active)
        process.save(false)
        assertEquals(process.active, process.state.value)
        assertFalse(policy().active)
    }

    @Test
    fun `failed save leaves saved and active policy unchanged`() {
        val process = policy(true)
        writesFail = true
        assertThrows(IOException::class.java) { process.save(false) }
        assertTrue(process.state.value)
        assertTrue(process.active)
    }

    @Test
    fun `system CA works in both states and user CA only when enabled`() {
        for (enabled in listOf(false, true)) {
            val policy = policy(enabled)
            server(system).use { server ->
                server.enqueue(MockResponse().setBody("system"))
                assertEquals("system", get(client(policy), server))
            }
            server(user).use { server ->
                server.enqueue(MockResponse().setBody("user"))
                if (enabled) {
                    assertEquals("user", get(client(policy), server))
                } else {
                    assertThrows(IOException::class.java) { get(client(policy), server) }
                }
            }
        }
    }

    @Test
    fun `unknown CA expired leaf and incorrect hostname fail even when enabled`() {
        val policy = policy(true)
        for (identity in listOf(unknown, expired)) {
            server(identity).use { server ->
                server.enqueue(MockResponse())
                assertThrows(IOException::class.java) { get(client(policy), server) }
            }
        }
        server(user).use { server ->
            val client = client(policy).newBuilder().dns { listOf(java.net.InetAddress.getByName("127.0.0.1")) }.build()
            assertThrows(IOException::class.java) {
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(
                                server
                                    .url("/")
                                    .newBuilder()
                                    .host("wrong.test")
                                    .build(),
                            ).build(),
                    ).execute()
                    .close()
            }
        }
    }

    @Test
    fun `user CA supports WSS and saved preference does not close established websocket`() {
        val policy = policy(true)
        server(user).use { server ->
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            webSocket.send(text)
                        }
                    },
                ),
            )
            val opened = CompletableFuture<Unit>()
            val echoed = CompletableFuture<String>()
            val ws =
                client(policy).newWebSocket(
                    Request.Builder().url(server.url("/ws")).build(),
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: okhttp3.Response,
                        ) {
                            opened.complete(Unit)
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            echoed.complete(text)
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: okhttp3.Response?,
                        ) {
                            echoed.completeExceptionally(t)
                        }
                    },
                )
            try {
                opened.get(5, TimeUnit.SECONDS)
                policy.save(false)
                assertTrue(ws.send("after save"))
                assertEquals("after save", echoed.get(5, TimeUnit.SECONDS))
                assertTrue(policy.active)
            } finally {
                ws.cancel()
            }
        }
    }

    @Test
    fun `independent mTLS verification keeps client selection and shares server trust`() =
        runBlocking {
            val first = TlsTestIdentity("first")
            val disabled = policy(false)
            server(user, listOf(first.certificate)).use { server ->
                server.requireClientAuth()
                assertThrows(IOException::class.java) {
                    runBlocking { verifyClientCertificate(server.url("/"), disabled.manager, manager(first)) }
                }
                val policy = policy(true)
                server.enqueue(MockResponse())
                verifyClientCertificate(server.url("/"), policy.manager, manager(first))
                assertEquals(
                    first.certificate,
                    server
                        .takeRequest(2, TimeUnit.SECONDS)!!
                        .handshake!!
                        .peerCertificates
                        .single(),
                )
            }
        }

    @Test
    fun `startup loader failure is propagated without a permissive fallback`() {
        assertThrows(IOException::class.java) {
            StartupServerTrust(true, {}) { throw IOException("CA store unavailable") }
        }
    }
}
