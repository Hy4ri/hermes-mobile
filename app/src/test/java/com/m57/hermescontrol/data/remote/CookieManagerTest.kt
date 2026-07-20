package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CookieManager] — the issue #470 coordinator.
 *
 * Injects a [FakePersistentCookieJar] via the test seam so no Android deps
 * are needed. Covers:
 * - session cookie set/get round-trip (host-scoped)
 * - clearing the session cookie
 * - prune/clear pass-through to the jar
 */
class CookieManagerTest {
    private fun fakeJar(): PersistentCookieJar {
        val jar = buildFakePersistentCookieJar()
        runTest { jar.useStore(PersistentCookieJar.DEFAULT_SERVER_ID) }
        CookieManager.setJarForTest(jar)
        return jar
    }

    private fun ep(host: String): ServerEndpoint {
        return ServerEndpoint.fromLegacy(host, 9119)
    }

    private fun httpsEp(
        host: String,
        path: String = "/",
    ): ServerEndpoint =
        ServerEndpoint.parse(
            "https://$host:9119$path",
            CleartextPolicy.ALLOW_WITH_WARNING,
        )

    @After
    fun tearDown() {
        CookieManager.resetForTest()
    }

    @Test
    fun getSessionCookie_recognizesHostPrefixedCookie() =
        runTest {
            val jar = buildFakePersistentCookieJar()
            jar.useStore(PersistentCookieJar.DEFAULT_SERVER_ID)
            CookieManager.setJarForTest(jar)
            // Server stored a __Host- prefixed cookie (HTTPS deployment).
            jar.saveFromResponse(
                "https://dash.local/".toHttpUrl(),
                listOf(
                    Cookie
                        .Builder()
                        .name("__Host-hermes_session_at")
                        .value("prefixed-value")
                        .expiresAt(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
                        .hostOnlyDomain("dash.local")
                        .path("/")
                        .secure()
                        .httpOnly()
                        .build(),
                ),
            )

            assertEquals("prefixed-value", CookieManager.getSessionCookie())
        }

    @Test
    fun setSessionCookie_httpsMarksSecureAndHonorsPath() =
        runTest {
            val jar = fakeJar()
            // Proxy-prefixed HTTPS endpoint.
            CookieManager.setSessionCookie("sess-secure", httpsEp("dash.local", "/hermes/"))

            val cookies =
                jar.loadForRequest(
                    "https://dash.local/hermes/api/status".toHttpUrl(),
                )
            assertEquals(1, cookies.size)
            assertTrue("HTTPS session cookie must be Secure", cookies[0].secure)
            assertEquals("/hermes/", cookies[0].path)
        }

    @Test
    fun setThenGetSessionCookie_roundTrips() {
        fakeJar()
        CookieManager.setSessionCookie("sess-xyz", ep("dashboard.local"))

        assertEquals("sess-xyz", CookieManager.getSessionCookie())
    }

    @Test
    fun setSessionCookie_nullClearsValue() {
        fakeJar()
        CookieManager.setSessionCookie("sess-xyz", ep("dashboard.local"))
        assertEquals("sess-xyz", CookieManager.getSessionCookie())

        CookieManager.setSessionCookie(null, ep("dashboard.local"))
        assertNull(CookieManager.getSessionCookie())
    }

    @Test
    fun setSessionCookie_hostScoped_onlyMatchesThatHost() =
        runTest {
            val jar = fakeJar()
            CookieManager.setSessionCookie("sess-xyz", ep("dashboard.local"))

            val onHost =
                jar.loadForRequest(
                    "http://dashboard.local/api/status".toHttpUrl(),
                )
            assertEquals(1, onHost.size)
            assertEquals("sess-xyz", onHost[0].value)

            val offHost = jar.loadForRequest("http://other.local/".toHttpUrl())
            assertEquals(0, offHost.size)
        }

    @Test
    fun pruneServerCache_delegatesToJar() =
        runTest {
            val jar = fakeJar()
            CookieManager.setSessionCookie("keep-me", ep("dashboard.local"))
            CookieManager.pruneServerCache()
            // Session cookie is preserved by prune.
            assertEquals("keep-me", CookieManager.getSessionCookie())
        }

    @Test
    fun clearAll_wipesSession() =
        runTest {
            val jar = fakeJar()
            CookieManager.setSessionCookie("bye", ep("dashboard.local"))
            CookieManager.clearAll()
            assertNull(CookieManager.getSessionCookie())
        }
}
