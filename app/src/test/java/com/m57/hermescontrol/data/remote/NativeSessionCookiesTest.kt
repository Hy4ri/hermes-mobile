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
    fun `native login on HTTP installs bare cookie names and replaces stale prefixed variants`() =
        runTest {
            val jar = buildFakePersistentCookieJar()
            jar.useStore("connection-http")
            CookieManager.setJarForTest(jar)
            val endpoint = ServerEndpoint.parse("http://dashboard.test/", CleartextPolicy.ALLOW_WITH_WARNING)
            val stale =
                SESSION_FAMILY_COOKIE_NAMES.map { name ->
                    Cookie
                        .Builder()
                        .name(name)
                        .value("old-session")
                        .hostOnlyDomain(endpoint.baseUrl.host)
                        .path("/")
                        .httpOnly()
                        .apply { if (name.startsWith("__")) secure() }
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
            assertEquals("self-hosted", CookieManager.getSessionProvider())
            assertTrue(cookies.all { !it.secure && it.httpOnly && it.hostOnly && it.path == "/" })
            stale.filter { it.name.startsWith("__") }.forEach { assertNull(jar.getCookie(it.name)) }
        }

    @Test
    fun `native login on HTTPS root installs __Host- cookies and replaces stale variants`() =
        runTest {
            val jar = buildFakePersistentCookieJar()
            jar.useStore("connection-https-root")
            CookieManager.setJarForTest(jar)
            val endpoint = ServerEndpoint.parse("https://dashboard.test/", CleartextPolicy.DENY)
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
                    "__Host-$SESSION_COOKIE_NAME" to "new-access",
                    "__Host-$SESSION_RT_COOKIE_NAME" to "new-refresh",
                    "__Host-$SESSION_PROVIDER_COOKIE_NAME" to "self-hosted",
                ),
                cookies.associate { it.name to it.value },
            )
            assertEquals("new-access", CookieManager.getSessionCookie())
            assertEquals("self-hosted", CookieManager.getSessionProvider())
            assertTrue(cookies.all { it.secure && it.httpOnly && it.hostOnly && it.path == "/" })
            stale.filter { !it.name.startsWith("__Host-") }.forEach { assertNull(jar.getCookie(it.name)) }
        }

    @Test
    fun `native login on HTTPS subpath installs __Secure- cookies and replaces stale variants`() =
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
                    "__Secure-$SESSION_COOKIE_NAME" to "new-access",
                    "__Secure-$SESSION_RT_COOKIE_NAME" to "new-refresh",
                    "__Secure-$SESSION_PROVIDER_COOKIE_NAME" to "self-hosted",
                ),
                cookies.associate { it.name to it.value },
            )
            assertEquals("new-access", CookieManager.getSessionCookie())
            assertEquals("self-hosted", CookieManager.getSessionProvider())
            assertTrue(cookies.all { it.secure && it.httpOnly && it.hostOnly && it.path == "/hermes" })
            stale.filter { !it.name.startsWith("__Secure-") }.forEach { assertNull(jar.getCookie(it.name)) }
        }

    @Test
    fun `server rotation and clearing replace native session cookies on an HTTPS subpath`() =
        runTest {
            val jar = buildFakePersistentCookieJar()
            jar.useStore("connection-a")
            CookieManager.setJarForTest(jar)
            val endpoint = ServerEndpoint.parse("https://dashboard.test/hermes/", CleartextPolicy.DENY)
            val requestUrl = endpoint.resolve("api/auth/ws-ticket")
            val rotated =
                mapOf(
                    "__Secure-hermes_session_at" to "rotated-access",
                    "__Secure-hermes_session_rt" to "rotated-refresh",
                    "__Secure-hermes_session_provider" to "self-hosted",
                )
            CookieManager.installNativeSession(NativeTokens("first-access", "first-refresh", "self-hosted"), endpoint)

            jar.saveFromResponse(
                requestUrl,
                rotated.map { (name, value) ->
                    checkNotNull(Cookie.parse(requestUrl, "$name=$value; Path=/hermes; Max-Age=60; Secure; HttpOnly"))
                },
            )

            val afterRotation = jar.loadForRequest(requestUrl)
            assertEquals(rotated.size, afterRotation.size)
            assertEquals(rotated, afterRotation.associate { it.name to it.value })

            jar.saveFromResponse(
                requestUrl,
                rotated.keys.map { name ->
                    checkNotNull(Cookie.parse(requestUrl, "$name=; Path=/hermes; Max-Age=0; Secure; HttpOnly"))
                },
            )

            assertTrue(jar.loadForRequest(requestUrl).none { it.name in rotated && it.value.isNotEmpty() })
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
            assertEquals("refresh-a", jar.getCookie("__Host-$SESSION_RT_COOKIE_NAME")?.value)
            assertEquals("nous", jar.getCookie("__Host-$SESSION_PROVIDER_COOKIE_NAME")?.value)
            assertEquals("nous", CookieManager.getSessionProvider())
        }
}
