package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class NativeLoginTest {
    private val closeables = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
    }

    // ── PKCE ──

    @Test
    fun `challenge matches the RFC 7636 appendix B vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            NativePkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `random values are valid verifiers and do not repeat`() {
        val a = NativePkce.randomUrlSafe()
        val b = NativePkce.randomUrlSafe()
        assertNotEquals(a, b)
        assertTrue(a.length in 43..128)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `authorize url carries provider, S256 challenge, loopback redirect and state under a path prefix`() {
        val endpoint = ServerEndpoint.parse("https://dash.example.com/hermes/", CleartextPolicy.DENY)
        val url =
            nativeAuthorizeUrl(endpoint, "self-hosted", "chal", "http://127.0.0.1:5000/callback", "st")
        assertEquals("/hermes/auth/native/authorize", url.encodedPath)
        assertEquals("self-hosted", url.queryParameter("provider"))
        assertEquals("chal", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("http://127.0.0.1:5000/callback", url.queryParameter("redirect_uri"))
        assertEquals("st", url.queryParameter("state"))
    }

    // ── Loopback listener ──

    private fun open(): LoopbackCallbackServer = LoopbackCallbackServer.open().also { closeables += it }

    private fun get(
        server: LoopbackCallbackServer,
        pathAndQuery: String,
    ): Int {
        val port = server.redirectUri.substringAfter("127.0.0.1:").substringBefore('/')
        val conn = URL("http://127.0.0.1:$port$pathAndQuery").openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        return try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `redirect uri is a canonical loopback IP literal the gateway accepts`() {
        val uri = open().redirectUri
        assertTrue(uri, Regex("http://127\\.0\\.0\\.1:\\d+/callback").matches(uri))
    }

    @Test
    fun `callback with the right state yields the code`() =
        runBlocking {
            val server = open()
            val result = async(start = CoroutineStart.UNDISPATCHED) { server.awaitCallback("st", 10_000) }
            assertEquals(200, get(server, "/callback?code=abc&state=st"))
            assertEquals(NativeCallback.Code("abc"), withTimeout(10_000) { result.await() })
        }

    @Test
    fun `stray requests and a wrong state are ignored and do not end the wait`() =
        runBlocking {
            val server = open()
            val result = async(start = CoroutineStart.UNDISPATCHED) { server.awaitCallback("st", 10_000) }
            assertEquals(404, get(server, "/favicon.ico"))
            assertEquals(404, get(server, "/callback?code=evil&state=other"))
            assertEquals(404, get(server, "/callback?code=evil"))
            assertEquals(200, get(server, "/callback?code=good&state=st"))
            assertEquals(NativeCallback.Code("good"), withTimeout(10_000) { result.await() })
        }

    @Test
    fun `an error redirect is reported as denied`() =
        runBlocking {
            val server = open()
            val result = async(start = CoroutineStart.UNDISPATCHED) { server.awaitCallback("st", 10_000) }
            get(server, "/callback?error=access_denied&state=st")
            assertEquals(NativeCallback.Denied("access_denied"), withTimeout(10_000) { result.await() })
        }

    @Test
    fun `no redirect times out`() =
        runBlocking {
            val server = open()
            assertEquals(NativeCallback.TimedOut, withTimeout(10_000) { server.awaitCallback("st", 200) })
        }

    @Test
    fun `parseRequestUrl only accepts GET paths`() {
        assertEquals("/callback", LoopbackCallbackServer.parseRequestUrl("GET /callback?x=1 HTTP/1.1")?.encodedPath)
        assertNull(LoopbackCallbackServer.parseRequestUrl("POST /callback HTTP/1.1"))
        assertNull(LoopbackCallbackServer.parseRequestUrl("GET http://evil.test/ HTTP/1.1"))
        assertNull(LoopbackCallbackServer.parseRequestUrl(null))
    }

    // ── Token redemption ──

    @Test
    fun `redeem posts code and verifier and parses bearer tokens`() =
        runBlocking {
            val web = MockWebServer().also { closeables += AutoCloseable { it.shutdown() } }
            web.enqueue(
                MockResponse().setBody(
                    """{"access_token":"at","refresh_token":"rt","token_type":"Bearer","provider":"self-hosted","user_id":"u"}""",
                ),
            )
            web.start()
            val endpoint = ServerEndpoint.parse(web.url("/").toString(), CleartextPolicy.ALLOW_WITH_WARNING)

            val result = redeemNativeCode(OkHttpClient(), endpoint, "the-code", "the-verifier")

            val request = web.takeRequest()
            assertEquals("/auth/native/token", request.path)
            assertEquals("""{"code":"the-code","code_verifier":"the-verifier"}""", request.body.readUtf8())
            val tokens = (result as NativeTokenResult.Success).tokens
            assertEquals("at", tokens.accessToken)
            assertEquals("rt", tokens.refreshToken)
            assertEquals("self-hosted", tokens.provider)
        }

    @Test
    fun `redeem surfaces a rejected code and never logs tokens`() =
        runBlocking {
            val web = MockWebServer().also { closeables += AutoCloseable { it.shutdown() } }
            web.enqueue(
                MockResponse().setResponseCode(400).setBody("""{"detail":"Invalid or expired authorization code."}"""),
            )
            web.start()
            val endpoint = ServerEndpoint.parse(web.url("/").toString(), CleartextPolicy.ALLOW_WITH_WARNING)
            assertEquals(NativeTokenResult.HttpError(400), redeemNativeCode(OkHttpClient(), endpoint, "c", "v"))
            assertEquals("NativeTokens(provider=p)", NativeTokens("secret-at", "secret-rt", "p").toString())
        }

    @Test
    fun `token parsing rejects bodies without an access token and tolerates a missing refresh token`() {
        assertEquals(NativeTokenResult.Malformed, parseNativeTokens("not json"))
        assertEquals(NativeTokenResult.Malformed, parseNativeTokens("""{"refresh_token":"x"}"""))
        assertEquals(NativeTokenResult.Malformed, parseNativeTokens("""{"access_token":""}"""))
        val ok = parseNativeTokens("""{"access_token":"at","refresh_token":null}""") as NativeTokenResult.Success
        assertEquals("", ok.tokens.refreshToken)
    }
}
