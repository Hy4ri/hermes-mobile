package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.OkHttpProvider
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GatewaySpeechConnectionTest {
    private lateinit var server: MockWebServer
    private lateinit var baseClient: OkHttpClient
    private lateinit var wsClient: OkHttpClient
    private lateinit var scope: DataScope

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseClient =
            OkHttpClient
                .Builder()
                .readTimeout(30_000, TimeUnit.MILLISECONDS)
                .callTimeout(30_000, TimeUnit.MILLISECONDS)
                .build()
        wsClient =
            OkHttpClient
                .Builder()
                .readTimeout(30_000, TimeUnit.MILLISECONDS)
                .build()
        scope =
            DataScope(
                connectionProfileId = "srv-1",
                baseUrl = server.url("/prefix").toString().trimEnd('/'),
                activeProfileId = "beta",
            )

        mockkObject(AuthManager)
        mockkObject(CookieManager)
        mockkObject(OkHttpProvider)

        every { OkHttpProvider.base } returns baseClient
        every { OkHttpProvider.websocket } returns wsClient
        every { AuthManager.currentDataScope() } returns scope
        every { AuthManager.isGatedMode() } returns false
        every { AuthManager.getToken() } returns null
        every { CookieManager.cookieJar } returns
            io.mockk.mockk<com.m57.hermescontrol.data.remote.PersistentCookieJar> {
                every { loadForRequest(any()) } returns emptyList()
            }
    }

    @After
    fun tearDown() {
        unmockkObject(OkHttpProvider)
        unmockkObject(CookieManager)
        unmockkObject(AuthManager)
        baseClient.dispatcher.executorService.shutdown()
        baseClient.connectionPool.evictAll()
        wsClient.dispatcher.executorService.shutdown()
        wsClient.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun `gated speech mints fresh ws-ticket and opens speak-stream with ticket and profile`() =
        runBlocking {
            every { AuthManager.isGatedMode() } returns true
            every { AuthManager.getToken() } returns "stored-gateway-ticket"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ticket":"fresh-speech-ticket"}"""),
            )
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            if (text.contains("done")) {
                                webSocket.send("""{"type":"start","sample_rate":24000,"channels":1}""")
                                webSocket.send(byteArrayOf(7, 8).toByteString())
                            }
                        }
                    },
                ),
            )

            val frames = withTimeout(5000) { GatewaySpeechConnection.frames(scope, "Hello").take(2).toList() }
            assertEquals(2, frames.size)
            assertEquals(byteArrayOf(7, 8).toByteString(), (frames[1] as SpeechStreamFrame.Audio).bytes)

            val ticketReq = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", ticketReq.method)
            assertEquals("/prefix/api/auth/ws-ticket", ticketReq.requestUrl?.encodedPath)
            assertEquals("beta", ticketReq.requestUrl?.queryParameter("profile"))

            val wsReq = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/prefix/api/audio/speak-stream", wsReq.requestUrl?.encodedPath)
            assertEquals("fresh-speech-ticket", wsReq.requestUrl?.queryParameter("ticket"))
            assertEquals("beta", wsReq.requestUrl?.queryParameter("profile"))
            assertNull(wsReq.requestUrl?.queryParameter("token"))
        }

    @Test
    fun `token mode opens speak-stream with encoded token and profile without ticket POST`() =
        runBlocking {
            every { AuthManager.isGatedMode() } returns false
            every { AuthManager.getToken() } returns "tok+1/2=3"

            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            if (text.contains("done")) {
                                webSocket.send(byteArrayOf(3, 4).toByteString())
                            }
                        }
                    },
                ),
            )

            val frames = withTimeout(5000) { GatewaySpeechConnection.frames(scope, "Hi").take(1).toList() }
            assertEquals(1, frames.size)
            assertEquals(1, server.requestCount)

            val wsReq = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/prefix/api/audio/speak-stream", wsReq.requestUrl?.encodedPath)
            assertEquals("tok+1/2=3", wsReq.requestUrl?.queryParameter("token"))
            assertEquals("beta", wsReq.requestUrl?.queryParameter("profile"))
            assertNull(wsReq.requestUrl?.queryParameter("ticket"))
        }

    @Test
    fun `captured cookies attach to ticket REST call and never attach to WebSocket`() =
        runBlocking {
            every { AuthManager.isGatedMode() } returns true
            val sessionCookie =
                Cookie
                    .Builder()
                    .name("hermes_session")
                    .value("cookie-secret")
                    .domain(server.hostName)
                    .path("/")
                    .build()
            every { CookieManager.cookieJar } returns
                io.mockk.mockk<com.m57.hermescontrol.data.remote.PersistentCookieJar> {
                    every { loadForRequest(any()) } returns listOf(sessionCookie)
                }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ticket":"t-cookie"}"""),
            )
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            if (text.contains("done")) {
                                webSocket.send(byteArrayOf(9).toByteString())
                            }
                        }
                    },
                ),
            )

            withTimeout(5000) { GatewaySpeechConnection.frames(scope, "Cookie check").take(1).toList() }

            val ticketReq = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertTrue(ticketReq.getHeader("Cookie")?.contains("hermes_session=cookie-secret") == true)

            val wsReq = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertNull(wsReq.getHeader("Cookie"))
        }

    @Test
    fun `scope switch before ticket prevents dialing`() =
        runBlocking {
            val otherScope = scope.copy(connectionProfileId = "srv-2")
            every { AuthManager.currentDataScope() } returns otherScope

            try {
                withTimeout(5000) { GatewaySpeechConnection.frames(scope, "Hello").toList() }
                fail("Expected IOException on scope switch")
            } catch (e: IOException) {
                assertEquals("Speech connection changed", e.message)
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `scope switch after ticket prevents opening WebSocket`() =
        runBlocking {
            every { AuthManager.isGatedMode() } returns true
            val otherScope = scope.copy(connectionProfileId = "srv-2")
            val activeScope = AtomicReference(scope)
            every { AuthManager.currentDataScope() } answers { activeScope.get() }
            every { OkHttpProvider.base } returns
                baseClient
                    .newBuilder()
                    .addInterceptor { chain ->
                        chain.proceed(chain.request()).also { activeScope.set(otherScope) }
                    }.build()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"ticket":"unused-ticket"}"""),
            )

            try {
                withTimeout(5000) { GatewaySpeechConnection.frames(scope, "Hello").toList() }
                fail("Expected IOException on scope switch after ticket")
            } catch (e: IOException) {
                assertEquals("Speech connection changed", e.message)
            }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `speech timeout policy and scoped client timeout leave base 30s unchanged`() {
        assertEquals(180_000L, speechRequestTimeoutMs("a".repeat(646)))
        assertEquals(280_000L, speechRequestTimeoutMs("a".repeat(8000)))
        assertEquals(600_000L, speechRequestTimeoutMs("a".repeat(20_000)))

        var observedReadTimeoutMs = -1
        val probeClient =
            baseClient
                .newBuilder()
                .addInterceptor { chain ->
                    observedReadTimeoutMs = chain.readTimeoutMillis()
                    throw IOException("probe-abort")
                }.build()
        every { OkHttpProvider.base } returns probeClient

        val connection = GatewaySpeechConnection.capture(scope)
        val service = connection.service(180_000L)
        try {
            runBlocking { service.mintSpeechTicket() }
            fail("Expected probe IOException")
        } catch (_: IOException) {
        }

        assertEquals(180_000, observedReadTimeoutMs)
        assertEquals(30_000, baseClient.readTimeoutMillis)
        assertEquals(30_000, baseClient.callTimeoutMillis)
    }
}
