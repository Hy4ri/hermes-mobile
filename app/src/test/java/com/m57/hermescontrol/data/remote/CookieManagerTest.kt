package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @After
    fun tearDown() {
        CookieManager.resetForTest()
    }

    @Test
    fun setThenGetSessionCookie_roundTrips() {
        fakeJar()
        CookieManager.setSessionCookie("sess-xyz", "dashboard.local")

        assertEquals("sess-xyz", CookieManager.getSessionCookie())
    }

    @Test
    fun setSessionCookie_nullClearsValue() {
        fakeJar()
        CookieManager.setSessionCookie("sess-xyz", "dashboard.local")
        assertEquals("sess-xyz", CookieManager.getSessionCookie())

        CookieManager.setSessionCookie(null, "dashboard.local")
        assertNull(CookieManager.getSessionCookie())
    }

    @Test
    fun setSessionCookie_hostScoped_onlyMatchesThatHost() =
        runTest {
            val jar = fakeJar()
            CookieManager.setSessionCookie("sess-xyz", "dashboard.local")

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
            CookieManager.setSessionCookie("keep-me", "dashboard.local")
            CookieManager.pruneServerCache()
            // Session cookie is preserved by prune.
            assertEquals("keep-me", CookieManager.getSessionCookie())
        }

    @Test
    fun clearAll_wipesSession() =
        runTest {
            val jar = fakeJar()
            CookieManager.setSessionCookie("bye", "dashboard.local")
            CookieManager.clearAll()
            assertNull(CookieManager.getSessionCookie())
        }
}
