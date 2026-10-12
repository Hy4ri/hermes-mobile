package com.m57.hermescontrol.data.remote

import android.util.Log
import com.m57.hermescontrol.data.config.ServerStore
import com.m57.hermescontrol.data.config.ServerStoreState
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.ReconnectPolicy
import com.m57.hermescontrol.data.ws.WsTransport
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CertificateTlsTest {
    private val serverIdentity = TlsTestIdentity("server")
    private val firstIdentity = TlsTestIdentity("first")
    private val secondIdentity = TlsTestIdentity("second")
    private val serverTls =
        TlsTestContext(serverIdentity, listOf(firstIdentity.certificate, secondIdentity.certificate))
    private val clientTrust = TlsTestContext(trusted = listOf(serverIdentity.certificate))
    private val aliases = mutableMapOf<CertificateOrigin, String>()
    private val selections = AtomicInteger()
    private val identities = mapOf("first" to firstIdentity, "second" to secondIdentity)
    private val sockets =
        CertificateSocketFactory(clientTrust.trustManager) { origin ->
            ClientCertificateKeyManager(
                choose = { _, _, _ ->
                    selections.incrementAndGet()
                    aliases[origin]
                },
                privateKey = { alias -> identities[alias]?.keyPair?.private },
                certificateChain = { alias -> identities[alias]?.let { arrayOf(it.certificate) } },
            )
        }
    private val client =
        OkHttpClient
            .Builder()
            .sslSocketFactory(sockets, clientTrust.trustManager)
            .hostnameVerifier { host, session -> OkHttpClient().hostnameVerifier.verify(host, session) }
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

    private fun server(
        require: Boolean = false,
        request: Boolean = false,
    ): MockWebServer =
        MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            if (require) {
                requireClientAuth()
            } else if (request) {
                requestClientAuth()
            }
            start()
        }

    private fun get(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use {
            assertTrue(it.isSuccessful)
            it.body.string()
        }

    @Test
    fun `identity changes close live sockets and invalidate established TLS sessions`() {
        server(require = true).use { server ->
            val origin = CertificateOrigin.from(server.url("/"))!!
            aliases[origin] = "first"
            sockets.createSocket("localhost", server.port).use { socket ->
                (socket as javax.net.ssl.SSLSocket).startHandshake()
                val session = socket.session
                assertTrue(session.isValid)
                sockets.invalidate(origin)
                assertTrue(socket.isClosed)
                org.junit.Assert.assertFalse(session.isValid)
            }
        }
    }

    @Test
    fun `origin canonicalizes hostname default port and excludes path credentials query`() {
        assertEquals(
            CertificateOrigin.from("https://EXAMPLE.com/a?b=c".toHttpUrl()),
            CertificateOrigin.from("https://example.com:443/elsewhere".toHttpUrl()),
        )
        assertNotEquals(CertificateOrigin("example.com", 443), CertificateOrigin("example.com", 8443))
        assertNull(CertificateOrigin.from("http://example.com/".toHttpUrl()))
    }

    @Test
    fun `mTLS requests a certificate while ordinary HTTPS and HTTP do not`() {
        server(require = true).use { server ->
            val url = server.url("/api/status")
            aliases[CertificateOrigin.from(url)!!] = "first"
            server.enqueue(MockResponse().setBody("mtls"))
            assertEquals("mtls", get(url.toString()))
            assertEquals(
                firstIdentity.certificate,
                server
                    .takeRequest()
                    .handshake!!
                    .peerCertificates
                    .single(),
            )
            assertTrue(selections.get() > 0)
        }
        selections.set(0)
        server().use { server ->
            aliases[CertificateOrigin.from(server.url("/"))!!] = "first"
            server.enqueue(MockResponse().setBody("https"))
            assertEquals("https", get(server.url("/").toString()))
            assertTrue(
                server
                    .takeRequest()
                    .handshake!!
                    .peerCertificates
                    .isEmpty(),
            )
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("http"))
            assertEquals("http", get(server.url("/").toString()))
        }
        assertEquals(0, selections.get())
    }

    @Test
    fun `same address supports mTLS and ordinary HTTPS without a mode switch`() {
        server(require = true).use { server ->
            val url = server.url("/")
            val origin = CertificateOrigin.from(url)!!
            aliases[origin] = "first"
            server.enqueue(MockResponse().setBody("outside"))
            assertEquals("outside", get(url.toString()))
            assertEquals(
                firstIdentity.certificate,
                server
                    .takeRequest()
                    .handshake!!
                    .peerCertificates
                    .single(),
            )
            // Simulate a new TLS connection to a backend which does not request client auth.
            server.noClientAuth()
            sockets.invalidate(origin)
            selections.set(0)
            server.enqueue(MockResponse().setBody("inside"))
            assertEquals("inside", get(url.toString()))
            assertTrue(
                server
                    .takeRequest()
                    .handshake!!
                    .peerCertificates
                    .isEmpty(),
            )
            assertEquals(0, selections.get())
            assertEquals("first", aliases[origin])
        }
    }

    @Test
    fun `HTTP2 and redirects never reuse client identity for another host or port`() {
        server(request = true).use { first ->
            server(request = true).use { second ->
                val url = first.url("/")
                aliases[CertificateOrigin.from(url)!!] = "first"
                first.enqueue(MockResponse().setBody("selected"))
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    assertEquals(Protocol.HTTP_2, response.protocol)
                    assertEquals("selected", response.body.string())
                }
                assertEquals(
                    firstIdentity.certificate,
                    first
                        .takeRequest()
                        .handshake!!
                        .peerCertificates
                        .single(),
                )
                first.enqueue(MockResponse().setBody("other-host"))
                val otherHost = url.newBuilder().host("127.0.0.1").build()
                assertEquals("other-host", get(otherHost.toString()))
                val otherRequest = first.takeRequest()
                assertEquals(0, otherRequest.sequenceNumber)
                assertTrue(otherRequest.handshake!!.peerCertificates.isEmpty())
                first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/media")))
                second.enqueue(MockResponse().setBody("redirect"))
                assertEquals("redirect", get(url.toString()))
                assertTrue(
                    second
                        .takeRequest()
                        .handshake!!
                        .peerCertificates
                        .isEmpty(),
                )
            }
        }
    }

    @Test
    fun `reselect and clear retire existing sockets and TLS sessions`() {
        server(request = true).use { server ->
            val url = server.url("/")
            val origin = CertificateOrigin.from(url)!!
            listOf("first", "second", null).forEach { alias ->
                if (alias == null) aliases.remove(origin) else aliases[origin] = alias
                sockets.invalidate(origin)
                server.enqueue(MockResponse().setBody("ok"))
                assertEquals("ok", get(url.toString()))
                val peer = server.takeRequest().handshake!!.peerCertificates
                if (alias ==
                    null
                ) {
                    assertTrue(peer.isEmpty())
                } else {
                    assertEquals(identities[alias]!!.certificate, peer.single())
                }
            }
        }
    }

    @Test
    fun `server certificate and hostname are still verified`() {
        server().use { server ->
            val emptyTrust = TlsTestContext().trustManager
            val untrustedSockets =
                CertificateSocketFactory(emptyTrust) { origin ->
                    ClientCertificateKeyManager(
                        choose = { _, _, _ -> aliases[origin] },
                        privateKey = { null },
                        certificateChain = { null },
                    )
                }
            val untrusted = OkHttpClient.Builder().sslSocketFactory(untrustedSockets, emptyTrust).build()
            server.enqueue(MockResponse())
            assertThrows(IOException::class.java) {
                untrusted.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
            }
            val wrongHost = client.newBuilder().dns { listOf(java.net.InetAddress.getByName("127.0.0.1")) }.build()
            server.enqueue(MockResponse())
            assertThrows(IOException::class.java) {
                wrongHost
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
    fun `required mTLS fails without selection and succeeds after choosing`() {
        server(require = true).use { server ->
            val url = server.url("/")
            server.enqueue(MockResponse().setBody("authorized"))
            assertThrows(IOException::class.java) { get(url.toString()) }
            aliases[CertificateOrigin.from(url)!!] = "first"
            assertEquals("authorized", get(url.toString()))
            assertEquals(
                firstIdentity.certificate,
                server
                    .takeRequest()
                    .handshake!!
                    .peerCertificates
                    .single(),
            )
        }
    }

    @Test
    fun `extracted WsTransport ticket and socket use saved identity and retire together`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            mockkStatic(Log::class)
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any<String>(), any<String>()) } returns 0
            every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
            mockkObject(AuthManager)
            every { AuthManager.serverStore } returns
                mockk<ServerStore>().also {
                    every { it.getLatestState() } returns ServerStoreState(wsAuthParam = "ticket")
                }
            every { AuthManager.setToken(any()) } returns Unit
            every { AuthManager.isAutoReconnect() } returns false
            mockkObject(DashboardSessionTokenRefresher)
            coEvery { DashboardSessionTokenRefresher.refreshAsync() } returns null
            mockkObject(OkHttpProvider)
            every { OkHttpProvider.websocket } returns client
            every { OkHttpProvider.probe } returns client
            try {
                server(require = true).use { server ->
                    val url = server.url("/ws")
                    val origin = CertificateOrigin.from(url)!!
                    every { AuthManager.endpointForBuild() } returns ServerEndpoint.parse(server.url("/").toString())
                    every { AuthManager.wsUrl() } returns url.toString().replace("https://", "wss://")
                    val transport =
                        WsTransport(
                            scope = scope,
                            outboundLock = Any(),
                            reconnectPolicy = ReconnectPolicy(),
                            connectionHealth = mockk(relaxed = true),
                            replayTracker = mockk(relaxed = true),
                            rpcChannel = mockk(relaxed = true),
                            onRawMessage = {},
                        )
                    try {
                        for (alias in listOf("first", "second")) {
                            aliases[origin] = alias
                            server.enqueue(MockResponse().setBody("""{"ticket":"test-ticket"}"""))
                            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
                            transport.openSocket()
                            withTimeout(5000) {
                                transport.connectionStatus.first { it == ConnectionStatus.CONNECTED }
                            }
                            val ticketRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
                            assertEquals("/api/auth/ws-ticket", ticketRequest.path)
                            assertEquals(
                                identities[alias]!!.certificate,
                                ticketRequest.handshake!!.peerCertificates.single(),
                            )
                            assertEquals(
                                identities[alias]!!.certificate,
                                server
                                    .takeRequest(5, TimeUnit.SECONDS)!!
                                    .handshake!!
                                    .peerCertificates
                                    .single(),
                            )
                            aliases.remove(origin)
                            sockets.invalidate(origin)
                            withTimeout(5000) {
                                transport.connectionStatus.first { it == ConnectionStatus.DISCONNECTED }
                            }
                            org.junit.Assert.assertFalse(transport.isConnected)
                        }
                    } finally {
                        transport.disconnect(clearPendingMessages = true)
                    }
                }
            } finally {
                scope.cancel()
                unmockkAll()
            }
        }

    @Test
    fun `WebSocket handshake uses client identity`() {
        server(require = true).use { server ->
            val url = server.url("/ws")
            aliases[CertificateOrigin.from(url)!!] = "first"
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            webSocket.send("ready")
                        }
                    },
                ),
            )
            val received = CompletableFuture<String>()
            val socket =
                client.newWebSocket(
                    Request.Builder().url(url).build(),
                    object : WebSocketListener() {
                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            received.complete(text)
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            received.completeExceptionally(t)
                        }
                    },
                )
            try {
                assertEquals("ready", received.get(5, TimeUnit.SECONDS))
                assertEquals(
                    firstIdentity.certificate,
                    server
                        .takeRequest()
                        .handshake!!
                        .peerCertificates
                        .single(),
                )
            } finally {
                socket.cancel()
            }
        }
    }
}
