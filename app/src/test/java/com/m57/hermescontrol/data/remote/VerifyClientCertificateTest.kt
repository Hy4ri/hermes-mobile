package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class VerifyClientCertificateTest {
    private val serverIdentity = TlsTestIdentity("server")
    private val first = TlsTestIdentity("first")
    private val second = TlsTestIdentity("second")
    private val trust = TlsTestContext(trusted = listOf(serverIdentity.certificate)).trustManager
    private val serverTls = TlsTestContext(serverIdentity, listOf(first.certificate, second.certificate))

    private fun manager(identity: TlsTestIdentity?) =
        ClientCertificateKeyManager(
            choose = { _, _, _ -> identity?.let { "candidate" } },
            privateKey = { identity?.keyPair?.private },
            certificateChain = { identity?.let { arrayOf(it.certificate) } },
        )

    @Test
    fun `headers suffice for every status and request carries no credentials`() =
        runBlocking {
            MockWebServer().use { server ->
                server.useHttps(serverTls.sslSocketFactory(), false)
                server.requireClientAuth()
                for (status in listOf(200, 401, 403, 407, 408, 421, 500, 503)) {
                    server.enqueue(MockResponse().setResponseCode(status).addHeader("Retry-After", "0"))
                    val input =
                        server
                            .url(
                                "/nested?token=secret",
                            ).newBuilder()
                            .username("user")
                            .password("password")
                            .build()
                    verifyClientCertificate(input, trust, manager(first))
                    val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    assertEquals("GET", request.method)
                    assertEquals("/api/status", request.path)
                    assertNull(request.getHeader("Authorization"))
                    assertNull(request.getHeader("Cookie"))
                    assertNull(request.getHeader("Proxy-Authorization"))
                    assertEquals(first.certificate, request.handshake!!.peerCertificates.single())
                }
                assertEquals(8, server.requestCount)
            }
        }

    @Test
    fun `redirect headers pass but target receives nothing`() =
        runBlocking {
            MockWebServer().use { target ->
                MockWebServer().use { server ->
                    server.useHttps(serverTls.sslSocketFactory(), false)
                    server.requireClientAuth()
                    server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/private")))
                    verifyClientCertificate(server.url("/"), trust, manager(first))
                    assertEquals(1, server.requestCount)
                    assertEquals(0, target.requestCount)
                }
            }
        }

    @Test
    fun `each validation establishes a new session with its candidate`() =
        runBlocking {
            MockWebServer().use { server ->
                server.useHttps(serverTls.sslSocketFactory(), false)
                server.requireClientAuth()
                for (identity in listOf(first, second)) {
                    server.enqueue(MockResponse())
                    verifyClientCertificate(server.url("/"), trust, manager(identity))
                    val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    assertEquals(identity.certificate, request.handshake!!.peerCertificates.single())
                    assertEquals(0, request.sequenceNumber)
                    assertEquals("TLSv1.3", request.handshake!!.tlsVersion.javaName)
                }
            }
        }

    @Test
    fun `TLS 1_3 rejection without a certificate never passes validation`() {
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.requireClientAuth()
            server.enqueue(MockResponse())
            assertThrows(IOException::class.java) {
                runBlocking { verifyClientCertificate(server.url("/"), trust, manager(null)) }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `rejected certificate never passes validation`() {
        MockWebServer().use { server ->
            server.useHttps(TlsTestContext(serverIdentity, listOf(first.certificate)).sslSocketFactory(), false)
            server.requireClientAuth()
            server.enqueue(MockResponse())
            assertThrows(IOException::class.java) {
                runBlocking { verifyClientCertificate(server.url("/"), trust, manager(second)) }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `server trust validation is retained`() {
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.enqueue(MockResponse())
            val wrongTrust = TlsTestContext(trusted = listOf(first.certificate)).trustManager
            assertThrows(IOException::class.java) {
                runBlocking { verifyClientCertificate(server.url("/"), wrongTrust, manager(first)) }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `server hostname validation is retained`() {
        MockWebServer().use { server ->
            server.useHttps(TlsTestContext(first).sslSocketFactory(), false)
            server.enqueue(MockResponse())
            val trustedWrongHost = TlsTestContext(trusted = listOf(first.certificate)).trustManager
            assertThrows(IOException::class.java) {
                runBlocking { verifyClientCertificate(server.url("/"), trustedWrongHost, manager(first)) }
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `disconnect before headers fails without retry`() {
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.requireClientAuth()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertThrows(IOException::class.java) {
                runBlocking { verifyClientCertificate(server.url("/"), trust, manager(first)) }
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `waiting for headers can be cancelled`() =
        runBlocking {
            MockWebServer().use { server ->
                server.useHttps(serverTls.sslSocketFactory(), false)
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val failure =
                    runCatching {
                        withTimeout(1000) { verifyClientCertificate(server.url("/"), trust, manager(first)) }
                    }.exceptionOrNull()
                assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
                assertEquals(1, server.requestCount)
            }
        }
}
