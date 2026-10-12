package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The browser flow must replace a complete session without leaking it to another connection. */
class NativeSessionCookiesTest {
    @After
    fun tearDown() {
        CookieManager.resetForTest()
    }

    @Test
    fun `native login replaces every stale prefixed session cookie`() =
        runTest {
            val jar = buildFakePersistentCookieJar()
            jar.useStore("connection-a")
            CookieManager.setJarForTest(jar)
            val endpoint = ServerEndpoint.parse("https://dashboard.test/hermes/", CleartextPolicy.DENY)
            val stale =
                SESSION_FAMILY_COOKIE_NAMES.map { name ->
                    Cookie
                        .Builder()
                        .name(name)
                        .value("old-session")
                        .hostOnlyDomain(endpoint.baseUrl.host)
                        .path("/")
                        .secure()
                        .httpOnly()
                        .build()
                }
            jar.saveFromResponse(endpoint.baseUrl, stale)

            CookieManager.installNativeSession(NativeTokens("new-access", "new-refresh", "self-hosted"), endpoint)

            val cookies = jar.loadForRequest(endpoint.resolve("api/auth/ws-ticket"))
            assertEquals(
                mapOf(
                    SESSION_COOKIE_NAME to "new-access",
                    SESSION_RT_COOKIE_NAME to "new-refresh",
                    SESSION_PROVIDER_COOKIE_NAME to "self-hosted",
                ),
                cookies.associate { it.name to it.value },
            )
            assertEquals("new-access", CookieManager.getSessionCookie())
            assertTrue(cookies.all { it.secure && it.httpOnly && it.hostOnly && it.path == "/hermes/" })
            stale.filter { it.name.startsWith("__") }.forEach { assertNull(jar.getCookie(it.name)) }
        }

    @Test
    fun `access-only native login removes a previous refresh token and provider hint`() =
        runTest {
            val jar = buildFakePersistentCookieJar()
            jar.useStore("connection-a")
            CookieManager.setJarForTest(jar)
            val endpoint = ServerEndpoint.parse("http://dashboard.test/", CleartextPolicy.ALLOW_WITH_WARNING)
            CookieManager.installNativeSession(NativeTokens("first-access", "old-refresh", "nous"), endpoint)

            CookieManager.installNativeSession(NativeTokens("new-access", "", ""), endpoint)

            val cookies = jar.loadForRequest(endpoint.resolve("api/auth/ws-ticket"))
            assertEquals(listOf(SESSION_COOKIE_NAME), cookies.map { it.name })
            assertEquals("new-access", cookies.single().value)
            assertTrue(!cookies.single().secure)
            assertNull(jar.getCookie(SESSION_RT_COOKIE_NAME))
            assertNull(jar.getCookie(SESSION_PROVIDER_COOKIE_NAME))
        }

    @Test
    fun `native login leaves other connection credentials untouched`() =
        runTest {
            val jar = buildFakePersistentCookieJar()
            CookieManager.setJarForTest(jar)
            val endpoint = ServerEndpoint.parse("https://dashboard.test/", CleartextPolicy.DENY)
            jar.useStore("connection-a")
            CookieManager.installNativeSession(NativeTokens("access-a", "refresh-a", "nous"), endpoint)
            jar.useStore("connection-b")
            assertNull(CookieManager.getSessionCookie())
            CookieManager.installNativeSession(NativeTokens("access-b", "refresh-b", "self-hosted"), endpoint)
            assertEquals("access-b", CookieManager.getSessionCookie())

            jar.useStore("connection-a")

            assertEquals("access-a", CookieManager.getSessionCookie())
            assertEquals("refresh-a", jar.getCookie(SESSION_RT_COOKIE_NAME)?.value)
            assertEquals("nous", jar.getCookie(SESSION_PROVIDER_COOKIE_NAME)?.value)
        }
}
