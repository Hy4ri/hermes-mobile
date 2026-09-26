package com.m57.hermescontrol.data.remote

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServerHeadersTest {
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private var saved: String? = null
    private lateinit var server: MockWebServer
    private val headers =
        CustomHeaders.parse(
            listOf(
                "CF-Access-Client-Id" to "test-id",
                "CF-Access-Client-Secret" to "secret",
            ),
        )

    @Before
    fun setUp() {
        every { preferences.getString(any(), null) } answers { saved }
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            saved = secondArg()
            editor
        }
        every { editor.commit() } returns true
        ServerHeaders.initialize(preferences)
        CookieManager.setJarForTest(buildFakePersistentCookieJar())
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        saved = null
        ServerHeaders.initialize(preferences)
        server.shutdown()
    }

    @Test
    fun `protected probe login ticket API and media requests require both headers`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.getHeader("CF-Access-Client-Id") == "test-id" &&
                        request.getHeader("CF-Access-Client-Secret") == "secret"
                    ) {
                        MockResponse().setBody("{}")
                    } else {
                        MockResponse().setResponseCode(403)
                    }
            }
        val url = server.url("/hermes/")
        val probe = Request.Builder().url(server.url("/hermes/api/status")).build()
        OkHttpProvider.probe
            .newCall(probe)
            .execute()
            .use { assertEquals(403, it.code) }
        ServerHeaders.save(url, headers)
        val paths = listOf("api/status", "auth/password-login", "api/auth/ws-ticket", "api/sessions", "files/image.png")
        paths.forEach { path ->
            val builder = Request.Builder().url(server.url("/hermes/$path"))
            if (path.contains("login") || path.contains("ticket")) builder.post("{}".toRequestBody())
            listOf(OkHttpProvider.probe, OkHttpProvider.base).forEach { client ->
                client.newCall(builder.build()).execute().use { assertEquals(200, it.code) }
            }
        }
        ServerHeaders.save(url, CustomHeaders.EMPTY)
        OkHttpProvider.probe
            .newCall(probe)
            .execute()
            .use { assertEquals(403, it.code) }
    }

    @Test
    fun `custom headers coexist with Hermes bearer and cookie authentication`() {
        ServerHeaders.save(server.url("/"), headers)
        server.enqueue(MockResponse())
        val request =
            Request
                .Builder()
                .url(server.url("/api/status"))
                .header("Authorization", "Bearer hermes-token")
                .header("Cookie", "hermes_session_at=session")
                .build()
        OkHttpProvider.base
            .newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
            .newCall(request)
            .execute()
            .close()
        val recorded = server.takeRequest()
        assertEquals("test-id", recorded.getHeader("CF-Access-Client-Id"))
        assertEquals("Bearer hermes-token", recorded.getHeader("Authorization"))
        assertEquals("hermes_session_at=session", recorded.getHeader("Cookie"))
    }

    @Test
    fun `WebSocket upgrade carries custom headers and receives a message`() {
        ServerHeaders.save(server.url("/hermes/"), headers)
        val message = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send("connected")
                    }
                },
            ),
        )
        val socket =
            OkHttpProvider.websocket.newWebSocket(
                Request.Builder().url(server.url("/hermes/api/ws?ticket=test")).build(),
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (text == "connected") message.countDown()
                    }
                },
            )
        try {
            assertTrue(message.await(5, TimeUnit.SECONDS))
            val recorded = server.takeRequest()
            assertEquals("test-id", recorded.getHeader("CF-Access-Client-Id"))
            assertEquals("secret", recorded.getHeader("CF-Access-Client-Secret"))
            assertEquals("websocket", recorded.getHeader("Upgrade"))
        } finally {
            socket.cancel()
        }
    }

    @Test
    fun `HTTP redirect to another configured server sends neither servers credentials`() {
        MockWebServer().use { other ->
            other.start()
            ServerHeaders.save(server.url("/"), headers)
            ServerHeaders.save(other.url("/"), CustomHeaders.parse(listOf("X-Other-Secret" to "other-secret")))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/api/status")))
            other.enqueue(MockResponse())
            OkHttpProvider.base
                .newCall(Request.Builder().url(server.url("/api/status")).build())
                .execute()
                .close()
            assertEquals("secret", server.takeRequest().getHeader("CF-Access-Client-Secret"))
            val redirected = other.takeRequest()
            assertNull(redirected.getHeader("CF-Access-Client-Secret"))
            assertNull(redirected.getHeader("CF-Access-Client-Id"))
            assertNull(redirected.getHeader("X-Other-Secret"))
        }
    }

    @Test
    fun `same-server redirect preserves headers but escaping path prefix removes them`() {
        ServerHeaders.save(server.url("/hermes/"), headers)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/hermes/next")))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/outside")))
        server.enqueue(MockResponse())
        OkHttpProvider.base
            .newCall(Request.Builder().url(server.url("/hermes/start")).build())
            .execute()
            .close()
        assertEquals("secret", server.takeRequest().getHeader("CF-Access-Client-Secret"))
        assertEquals("secret", server.takeRequest().getHeader("CF-Access-Client-Secret"))
        assertNull(server.takeRequest().getHeader("CF-Access-Client-Secret"))
    }

    @Test
    fun `redirect into a separately configured path does not inherit parent credentials`() {
        ServerHeaders.save(server.url("/"), headers)
        ServerHeaders.save(server.url("/public/"), CustomHeaders.EMPTY)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/public/api/status")))
        server.enqueue(MockResponse())
        OkHttpProvider.base
            .newCall(Request.Builder().url(server.url("/start")).build())
            .execute()
            .close()
        assertEquals("secret", server.takeRequest().getHeader("CF-Access-Client-Secret"))
        assertNull(server.takeRequest().getHeader("CF-Access-Client-Secret"))
    }

    @Test
    fun `WebSocket redirect is not followed`() {
        MockWebServer().use { other ->
            other.start()
            ServerHeaders.save(server.url("/"), headers)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/api/ws")))
            val failed = CountDownLatch(1)
            val socket =
                OkHttpProvider.websocket.newWebSocket(
                    Request.Builder().url(server.url("/api/ws")).build(),
                    object : WebSocketListener() {
                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            if (response?.code == 302) failed.countDown()
                        }
                    },
                )
            try {
                assertTrue(failed.await(5, TimeUnit.SECONDS))
                assertEquals(0, other.requestCount)
            } finally {
                socket.cancel()
            }
        }
    }

    @Test
    fun `scope matches scheme host port and complete path segment`() {
        val scope = ScopedServerHeaders("https://hermes.example/app/".toHttpUrl(), headers)
        assertTrue(scope.contains("https://hermes.example/app/api/status".toHttpUrl()))
        assertTrue(scope.contains("https://hermes.example/app".toHttpUrl()))
        listOf(
            "http://hermes.example/app/api/status",
            "https://other.example/app/api/status",
            "https://hermes.example:8443/app/api/status",
            "https://hermes.example/application/api/status",
        ).forEach { assertFalse(scope.contains(it.toHttpUrl())) }
    }

    @Test
    fun `saved credentials survive reload and an empty child scope blocks parent credentials`() {
        ServerHeaders.save(server.url("/"), headers)
        ServerHeaders.save(server.url("/public/"), CustomHeaders.EMPTY)
        ServerHeaders.initialize(preferences)
        assertEquals("secret", ServerHeaders.get(server.url("/")).entries["CF-Access-Client-Secret"])
        assertTrue(
            ServerHeaders
                .forRequest(server.url("/public/api/status"))!!
                .headers.entries
                .isEmpty(),
        )
    }

    @Test
    fun `failed storage write does not publish new credentials`() {
        every { editor.commit() } returns false
        assertThrows(IOException::class.java) { ServerHeaders.save(server.url("/"), headers) }
        assertTrue(ServerHeaders.get(server.url("/")).entries.isEmpty())
    }

    @Test
    fun `malformed stored JSON does not expose credentials in errors`() {
        saved = "{\"secret\": broken"
        val error = assertThrows(IOException::class.java) { ServerHeaders.initialize(preferences) }
        assertFalse(error.stackTraceToString().contains("secret"))
    }

    @Test
    fun `reject invalid duplicate and managed headers without revealing values`() {
        listOf(
            listOf("Bad Name" to "secret"),
            listOf("X-Test" to "secret\r\nInjected: bad"),
            listOf("X-Test" to "secret", "x-test" to "secret"),
            listOf("Authorization" to "secret"),
            listOf("Cookie" to "secret"),
            listOf("Host" to "secret"),
            listOf("Sec-WebSocket-Key" to "secret"),
        ).forEach { rows ->
            val error = assertThrows(IllegalArgumentException::class.java) { CustomHeaders.parse(rows) }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
        assertFalse(headers.toString().contains("secret"))
    }
}
