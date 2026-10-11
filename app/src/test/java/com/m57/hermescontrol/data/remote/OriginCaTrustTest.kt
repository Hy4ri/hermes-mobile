package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Real local JSSE handshakes. Store sources are injected, never the user's Android/device store. */
class OriginCaTrustTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val fixture = "/tls/server-trust.p12"

    private fun identity(name: String) = TlsTestIdentity(name, fixture)

    private val roots =
        KeyStore.getInstance("PKCS12").apply {
            OriginCaTrustTest::class.java
                .getResourceAsStream(
                    fixture,
                ).use { load(requireNotNull(it), TlsTestIdentity.PASSWORD) }
        }

    private fun root(name: String) = roots.getCertificate("$name-ca") as X509Certificate

    private val androidStore =
        KeyStore.getInstance("PKCS12").apply {
            load(null)
            setCertificateEntry("system:system", root("system"))
            setCertificateEntry("user:user", root("user"))
            setCertificateEntry("user:unknown", root("unknown"))
        }
    private val catalog = AndroidCaStore { androidStore }

    private fun reference(name: String) = catalog.list().single { it.reference.alias.endsWith(":$name") }.reference

    private val policy =
        StartupServerTrust(true, {}) { enabled ->
            TlsTestContext(
                trusted =
                    listOf(root("system")) +
                        if (enabled) listOf(root("user"), root("unknown")) else emptyList(),
            ).trustManager
        }
    private var persisted = emptyMap<String, String?>()
    private var failPersist = false
    private val bindings: CertificateBindings =
        CertificateBindings(
            persist = { values, _ -> if (failPersist) throw IOException("disk failure") else persisted = values },
            invalidate = { sockets.invalidate(it) },
        )

    private fun scoped(origin: CertificateOrigin): X509TrustManager =
        OriginCaTrustManager(
            policy.manager,
            bindings,
            origin,
            selected = { catalog.trust(CaReference.decode(it), policy.active) },
        )

    private val clientIdentity = TlsTestIdentity("first")
    private var useClientIdentity = false
    private val sockets: CertificateSocketFactory =
        CertificateSocketFactory(policy.manager, ::scoped, checkOrigin = { origin ->
            bindings.checkedBinding(origin) { present, encoded ->
                if (present) catalog.resolveAllowed(CaReference.decode(requireNotNull(encoded)), policy.active)
            }
        }) {
            ClientCertificateKeyManager(
                choose = { _, _, _ -> if (useClientIdentity) "first" else null },
                privateKey = { clientIdentity.keyPair.private },
                certificateChain = { clientIdentity.chain },
            )
        }

    private fun client() =
        OkHttpClient
            .Builder()
            .sslSocketFactory(sockets, policy.manager)
            // Same verification wrapper as production, preventing cross-origin HTTP/2 coalescing.
            .hostnameVerifier { host, session -> OkHttpClient().hostnameVerifier.verify(host, session) }
            .retryOnConnectionFailure(false)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

    private fun server(
        name: String = "system",
        mtls: Boolean = false,
    ) = MockWebServer().apply {
        useHttps(
            TlsTestContext(
                identity(name),
                if (mtls) listOf(clientIdentity.certificate) else emptyList(),
            ).sslSocketFactory(),
            false,
        )
        if (mtls) requireClientAuth()
        start()
    }

    private fun bind(
        url: HttpUrl,
        name: String,
    ) = bindings.save(
        url.takeIf { it.toString() in bindings.state.value },
        url,
        reference(name).encode(),
        bindings.state.value,
    )

    private fun remove(url: HttpUrl) = bindings.save(url, null, null, bindings.state.value)

    private fun get(
        client: OkHttpClient,
        url: HttpUrl,
    ): String = client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

    @Test
    fun `globally trusted wrong CA fails while unconfigured origins use global trust`() {
        server().use { server ->
            val url = server.url("/")
            bind(url, "user")
            assertThrows(IOException::class.java) { get(client(), url) }
            remove(url)
            server.enqueue(MockResponse().setBody("global"))
            assertEquals("global", get(client(), url))
        }
    }

    @Test
    fun `exact ports isolate reuse and redirects validate destination restrictions`() {
        server().use { first ->
            server().use { second ->
                val retained = client()
                first.enqueue(MockResponse().setBody("first"))
                second.enqueue(MockResponse().setBody("second"))
                assertEquals("first", get(retained, first.url("/")))
                assertEquals("second", get(retained, second.url("/")))
                bind(second.url("/"), "user")
                first.enqueue(MockResponse().setBody("first still works"))
                assertEquals("first still works", get(retained, first.url("/")))
                assertThrows(IOException::class.java) { get(retained, second.url("/")) }
                first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/media")))
                assertThrows(IOException::class.java) { get(retained, first.url("/redirect")) }
                assertEquals(1, second.requestCount) // No HTTP request sent after the restricted handshake.
            }
        }
    }

    @Test
    fun `host names on the same port are independent even on the same IP`() {
        server().use { server ->
            val localhost = server.url("/")
            val ip = localhost.newBuilder().host("127.0.0.1").build()
            bind(localhost, "user")
            val retained = client()
            server.enqueue(MockResponse().setBody("IP only"))
            assertEquals("IP only", get(retained, ip))
            assertThrows(IOException::class.java) { get(retained, localhost) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `expired leaves and hostname mismatch still fail with the matching CA`() {
        server("expired").use { server ->
            bind(server.url("/"), "system")
            assertThrows(IOException::class.java) { get(client(), server.url("/")) }
        }
        server("user").use { server ->
            val wrong =
                server
                    .url("/")
                    .newBuilder()
                    .host("wrong.test")
                    .build()
            bind(wrong, "user")
            val localDns = client().newBuilder().dns { listOf(InetAddress.getByName("127.0.0.1")) }.build()
            assertThrows(IOException::class.java) { get(localDns, wrong) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `edit and removal retire live TLS sessions retained clients and redirect cache generations`() {
        server().use { server ->
            val url = server.url("/")
            bind(url, "system")
            val retained = client()
            server.enqueue(MockResponse().setBody("initial"))
            assertEquals("initial", get(retained, url))
            val cache = bindings.cacheKey(url)
            val unrelated = bindings.cacheKey(url.newBuilder().host("other.test").build())
            val socket = sockets.createSocket(url.host, url.port) as SSLSocket
            socket.startHandshake()
            val session = socket.session
            assertTrue(session.isValid)
            bind(url, "user")
            assertTrue(socket.isClosed)
            assertFalse(session.isValid)
            assertNotEquals(cache, bindings.cacheKey(url))
            assertNotEquals(unrelated, bindings.cacheKey(url.newBuilder().host("other.test").build()))
            assertThrows(IOException::class.java) { get(retained, url) }
            remove(url)
            server.enqueue(MockResponse().setBody("restored"))
            assertEquals("restored", get(retained, url))
        }
    }

    @Test
    fun `CA removal or same-alias replacement fails closed on subsequent connections`() {
        server().use { server ->
            val url = server.url("/")
            bind(url, "system")
            val retained = client()
            server.enqueue(MockResponse().setBody("before"))
            assertEquals("before", get(retained, url))
            androidStore.deleteEntry("system:system")
            retained.connectionPool.evictAll()
            assertThrows(IOException::class.java) { get(retained, url) }
            androidStore.setCertificateEntry("system:system", root("user"))
            retained.connectionPool.evictAll()
            assertThrows(IOException::class.java) { get(retained, url) }
        }
    }

    @Test
    fun `TLS and mTLS coexist and candidate identity verification respects server CA restrictions`(): Unit =
        runBlocking {
            useClientIdentity = true
            server(mtls = true).use { server ->
                val url = server.url("/")
                bind(url, "system")
                server.enqueue(MockResponse().setBody("both"))
                assertEquals("both", get(client(), url))
                assertEquals(
                    clientIdentity.certificate,
                    server
                        .takeRequest()
                        .handshake!!
                        .peerCertificates
                        .single(),
                )
                bind(url, "user")
                val manager =
                    ClientCertificateKeyManager(
                        choose = { _, _, _ -> "first" },
                        privateKey = { clientIdentity.keyPair.private },
                        certificateChain = { clientIdentity.chain },
                    )
                assertThrows(IOException::class.java) {
                    runBlocking { verifyClientCertificate(url, scoped(CertificateOrigin.from(url)!!), manager) }
                }
            }
        }

    @Test
    fun `stale edits and failed persistence preserve the prior binding`() {
        val url = CertificateOrigin("example.test", 443).url
        bind(url, "system")
        val previous = bindings.state.value
        failPersist = true
        assertThrows(IOException::class.java) { bind(url, "user") }
        assertEquals(previous, bindings.state.value)
        assertEquals(previous, persisted)
        failPersist = false
        bind(url, "user")
        assertThrows(IllegalStateException::class.java) { bindings.save(url, null, null, previous) }
    }

    @Test
    fun `missing or malformed saved selections fail closed rather than acting unconfigured`() {
        val url = CertificateOrigin("example.test", 443).url
        for (selection in listOf(null, "", "invalid-json")) {
            bindings.save(url.takeIf { it.toString() in bindings.state.value }, url, selection, bindings.state.value)
            assertThrows(CertificateException::class.java) {
                scoped(CertificateOrigin.from(url)!!).checkServerTrusted(identity("system").chain, "RSA")
            }
        }
    }

    @Test
    fun `gateway attachment cache cannot reuse an earlier CA binding after tightening`(): Unit =
        runBlocking {
            server().use { server ->
                val url = server.url("/")
                bind(url, "system")
                val retained = client()
                val directory = temporary.newFolder()
                io.mockk.mockkObject(OkHttpProvider, ClientCertificates, com.m57.hermescontrol.data.local.AuthManager)
                io.mockk.every { OkHttpProvider.base } returns retained
                io.mockk.every { ClientCertificates.cacheKey(any()) } answers {
                    bindings.cacheKey(firstArg())
                }
                io.mockk.every {
                    com.m57.hermescontrol.data.local.AuthManager
                        .isGatedMode()
                } returns true
                try {
                    server.enqueue(MockResponse().setBody("attachment").setHeader("Content-Type", "application/pdf"))
                    val first = GatewayFileClient.fetch("/test/report.pdf", url.toString(), "", directory)
                    assertTrue(first is GatewayFileResult.Success)
                    assertEquals("attachment", (first as GatewayFileResult.Success).file.cacheFile.readText())
                    val cacheHit = GatewayFileClient.fetch("/test/report.pdf", url.toString(), "", directory)
                    assertTrue(cacheHit is GatewayFileResult.Success)
                    assertEquals(1, server.requestCount)
                    bind(url, "user")
                    assertThrows(IOException::class.java) {
                        runBlocking { GatewayFileClient.fetch("/test/report.pdf", url.toString(), "", directory) }
                    }
                    assertEquals(1, server.requestCount)
                    remove(url)
                    server.enqueue(MockResponse().setBody("fresh"))
                    val restored = GatewayFileClient.fetch("/test/report.pdf", url.toString(), "", directory)
                    assertEquals("fresh", (restored as GatewayFileResult.Success).file.cacheFile.readText())
                    assertEquals(2, server.requestCount)
                } finally {
                    io.mockk.unmockkObject(
                        OkHttpProvider,
                        ClientCertificates,
                        com.m57.hermescontrol.data.local.AuthManager,
                    )
                }
            }
        }

    @Test
    fun `editing a CA restriction terminates an already established websocket`() {
        server("user").use { server ->
            val url = server.url("/")
            bind(url, "user")
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            val opened = CompletableFuture<Unit>()
            val failed = CompletableFuture<Unit>()
            val retained = client()
            val ws =
                retained.newWebSocket(
                    Request.Builder().url(url).build(),
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
                bind(url, "system")
                failed.get(5, TimeUnit.SECONDS)
                assertThrows(IOException::class.java) { get(retained, url) }
            } finally {
                ws.cancel()
            }
        }
    }
}
