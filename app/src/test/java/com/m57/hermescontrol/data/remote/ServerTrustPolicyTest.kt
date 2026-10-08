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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

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

    private var userInstalled = true
    private var persisted = false
    private var writesFail = false
    private val policy =
        ReloadableServerTrust(
            load = { enabled ->
                TlsTestContext(
                    trusted =
                        listOf(root("system")) +
                            if (enabled && userInstalled) listOf(root("user")) else emptyList(),
                ).trustManager
            },
            persist = { if (writesFail) throw IOException("disk failure") else persisted = it },
            changed = {},
        )
    private val sockets = CertificateSocketFactory(policy) { manager(null) }.also(policy::track)

    private fun manager(identity: TlsTestIdentity?) =
        ClientCertificateKeyManager(
            choose = { _, _, _ -> identity?.let { "client" } },
            privateKey = { identity?.keyPair?.private },
            certificateChain = { identity?.chain },
        )

    // JSSE's OkHttp chain cleaner snapshots roots at client construction. Android instead uses
    // the kept three-argument X509TrustManagerExtensions overload to consult the current delegate.
    private fun client() =
        OkHttpClient
            .Builder()
            .sslSocketFactory(sockets, policy)
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
    fun `fresh and upgraded missing preference default off and failed persistence does not publish`() {
        assertFalse(policy.isEnabled())
        assertFalse(persisted)
        assertThrows(CertificateException::class.java) { policy.checkServerTrusted(user.chain, "RSA") }
        policy.setEnabled(true)
        assertTrue(persisted)
        writesFail = true
        assertThrows(IOException::class.java) { policy.setEnabled(false) }
        assertTrue(policy.isEnabled())
        assertTrue(persisted)
        writesFail = false
        policy.setEnabled(false)
        assertFalse(persisted)
        assertFalse(policy.isEnabled())
    }

    @Test
    fun `system CA works in both states and user CA only when enabled`() {
        for (enabled in listOf(false, true)) {
            policy.setEnabled(enabled)
            server(system).use { server ->
                server.enqueue(MockResponse().setBody("system"))
                assertEquals("system", get(client(), server))
            }
            server(user).use { server ->
                server.enqueue(MockResponse().setBody("user"))
                if (enabled) {
                    assertEquals("user", get(client(), server))
                } else {
                    assertThrows(IOException::class.java) { get(client(), server) }
                }
            }
        }
    }

    @Test
    fun `unknown CA expired leaf and incorrect hostname fail even when enabled`() {
        policy.setEnabled(true)
        for (identity in listOf(unknown, expired)) {
            server(identity).use { server ->
                server.enqueue(MockResponse())
                assertThrows(IOException::class.java) { get(client(), server) }
            }
        }
        server(user).use { server ->
            val client = client().newBuilder().dns { listOf(java.net.InetAddress.getByName("127.0.0.1")) }.build()
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
    fun `disable or remove CA retires live sockets TLS sessions pools and cache generations`() {
        for (remove in listOf(false, true)) {
            userInstalled = true
            policy.setEnabled(true)
            policy.reload()
            server(user).use { server ->
                val retained = client()
                server.enqueue(MockResponse().setBody("old"))
                assertEquals("old", get(retained, server))
                val epoch = policy.cacheEpoch()
                val socket = sockets.createSocket("localhost", server.port) as SSLSocket
                socket.startHandshake()
                val session = socket.session
                assertTrue(session.isValid)
                if (remove) {
                    userInstalled = false
                    policy.reload()
                } else {
                    policy.setEnabled(false)
                }
                assertTrue(socket.isClosed)
                assertFalse(session.isValid)
                assertNotEquals(epoch, policy.cacheEpoch())
                assertThrows(IOException::class.java) { policy.requireEpoch(epoch) }
                server.enqueue(MockResponse().setBody("must not use old trust"))
                assertThrows(IOException::class.java) { get(retained, server) }
            }
        }
    }

    @Test
    fun `user CA supports WSS and an established websocket fails on revocation`() {
        policy.setEnabled(true)
        server(user).use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            val opened = CompletableFuture<Unit>()
            val failed = CompletableFuture<Unit>()
            val ws =
                client().newWebSocket(
                    Request.Builder().url(server.url("/ws")).build(),
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: okhttp3.Response,
                        ) {
                            opened.complete(Unit)
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: okhttp3.Response?,
                        ) {
                            failed.complete(Unit)
                        }
                    },
                )
            try {
                opened.get(5, TimeUnit.SECONDS)
                policy.setEnabled(false)
                failed.get(5, TimeUnit.SECONDS)
            } finally {
                ws.cancel()
            }
        }
    }

    @Test
    fun `independent mTLS verification keeps client selection and shares server trust`() =
        runBlocking {
            val first = TlsTestIdentity("first")
            server(user, listOf(first.certificate)).use { server ->
                server.requireClientAuth()
                assertThrows(IOException::class.java) {
                    runBlocking { verifyClientCertificate(server.url("/"), policy, manager(first)) }
                }
                policy.setEnabled(true)
                server.enqueue(MockResponse())
                verifyClientCertificate(server.url("/"), policy, manager(first), policy::track, policy::untrack)
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
    fun `verification racing a trust update fails and reload errors fail closed`() {
        policy.setEnabled(true)
        assertThrows(CertificateException::class.java) {
            policy.checked {
                it.checkServerTrusted(user.chain, "RSA")
                policy.setEnabled(false)
            }
        }
        var fail = false
        val failing =
            ReloadableServerTrust(load = {
                if (fail) throw IOException("CA store unavailable")
                TlsTestContext(trusted = listOf(root("system"))).trustManager
            }, persist = {}, changed = {})
        failing.checkServerTrusted(system.chain, "RSA")
        fail = true
        failing.reload()
        assertThrows(CertificateException::class.java) { failing.checkServerTrusted(system.chain, "RSA") }
    }
}
