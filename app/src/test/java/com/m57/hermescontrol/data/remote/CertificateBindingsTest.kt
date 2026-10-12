package com.m57.hermescontrol.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CertificateBindingsTest {
    private val url = "https://example.com:8443/".toHttpUrl()
    private val origin = CertificateOrigin.from(url)!!

    @Test
    fun `redirect source caches change when a different destination binding changes`() {
        val store = CertificateBindings(persist = { _, _ -> }, invalidate = {})
        val httpsSource = "https://cdn.test/image".toHttpUrl()
        val httpSource = "http://cdn.test/image".toHttpUrl()
        val before = listOf(httpsSource, httpSource).map(store::cacheKey)
        store.save(null, url, "selected", store.state.value)
        assertNotEquals(before, listOf(httpsSource, httpSource).map(store::cacheKey))
        val selected = store.cacheKey(httpSource)
        store.save(url, url, null, store.state.value)
        assertNotEquals(selected, store.cacheKey(httpSource))
    }

    @Test
    fun `KeyChain changes retire cache and TLS state even when persistence fails`() {
        var fail = false
        var retired = 0
        val store =
            CertificateBindings(persist = {
                _,
                _,
                ->
                if (fail) throw IOException("disk full")
            }, invalidate = { retired++ })
        store.save(null, url, "selected", store.state.value)
        val old = store.cacheKey(url)
        fail = true
        assertThrows(IOException::class.java) { store.keyChainChanged() }
        assertNotEquals(old, store.cacheKey(url))
        assertEquals("selected", store.state.value[url.toString()])
        assertEquals(2, retired)
    }

    @Test
    fun `missing or unavailable saved alias returns immediately without choosing`() {
        val store = CertificateBindings(persist = { _, _ -> }, invalidate = {})
        assertNull(store.selected(origin) { error("No saved key may be checked") })
        store.save(null, url, "removed-key", store.state.value)
        assertNull(store.selected(origin) { false })
        assertEquals("removed-key", store.state.value[url.toString()])
    }

    @Test
    fun `save reselect clear delete and move invalidate origins and cache versions`() {
        val retired = mutableListOf<CertificateOrigin>()
        var persisted = emptyMap<String, String?>()
        var versions = emptyMap<String, String>()
        val store =
            CertificateBindings(persist = {
                saved,
                revisions,
                ->
                persisted = saved
                versions = revisions
            }, invalidate = retired::add)
        val before = store.cacheKey(url)
        store.save(null, url, "first", store.state.value)
        assertEquals("first", store.selected(origin) { true })
        val first = store.cacheKey(url)
        assertNotEquals(before, first)
        store.save(url, url, "first", store.state.value)
        assertNotEquals(first, store.cacheKey(url))
        store.save(url, url, null, store.state.value)
        assertTrue(url.toString() in store.state.value)
        assertNull(store.selected(origin) { error("Clear cannot check a key") })
        val cleared = store.cacheKey(url)
        store.save(url, null, null, store.state.value)
        assertFalse(url.toString() in store.state.value)
        assertNotEquals(cleared, store.cacheKey(url))
        val restored = CertificateBindings(persisted, versions, persist = { _, _ -> }, invalidate = {})
        assertEquals(store.cacheKey(url), restored.cacheKey(url))
        store.save(null, url, "new", store.state.value)
        val other = "https://other.test/".toHttpUrl()
        val unrelated = "https://unrelated.test/image".toHttpUrl()
        val otherKey = store.cacheKey(other)
        val unrelatedKey = store.cacheKey(unrelated)
        store.save(url, other, "new", store.state.value)
        assertNotEquals(otherKey, store.cacheKey(other))
        assertNotEquals(unrelatedKey, store.cacheKey(unrelated))
        assertEquals(listOf(origin, origin, origin, origin, origin, origin, CertificateOrigin.from(other)), retired)
    }

    @Test
    fun `cache generations restored after a stopped process cannot vouch for a bound identity`() {
        var persisted = emptyMap<String, String?>()
        var versions = emptyMap<String, String>()
        val store =
            CertificateBindings(persist = { saved, revisions ->
                persisted = saved
                versions = revisions
            }, invalidate = {})
        store.save(null, url, "first", store.state.value)
        val image = "https://cdn.test/image".toHttpUrl()
        val relaunched = CertificateBindings(persisted, versions, persist = { _, _ -> }, invalidate = {})
        assertEquals("first", relaunched.state.value[url.toString()])
        assertNotEquals(store.cacheKey(url), relaunched.cacheKey(url))
        assertNotEquals(store.cacheKey(image), relaunched.cacheKey(image))
        assertEquals(relaunched.cacheKey(image), relaunched.cacheKey(image))
    }

    @Test
    fun `cache generations persist across launches when no binding exists`() {
        var persisted = emptyMap<String, String?>()
        var versions = emptyMap<String, String>()
        val store =
            CertificateBindings(persist = { saved, revisions ->
                persisted = saved
                versions = revisions
            }, invalidate = {})
        store.save(null, url, "first", store.state.value)
        store.save(url, null, null, store.state.value)
        val image = "https://cdn.test/image".toHttpUrl()
        val relaunched = CertificateBindings(persisted, versions, persist = { _, _ -> }, invalidate = {})
        assertEquals(store.cacheKey(image), relaunched.cacheKey(image))
    }

    @Test
    fun `duplicates stale edits and persistence errors leave state and sockets intact`() {
        var fail = false
        var invalidations = 0
        val store =
            CertificateBindings(persist = {
                _,
                _,
                ->
                if (fail) throw IOException("disk full")
            }, invalidate = { invalidations++ })
        val empty = store.state.value
        store.save(null, url, "first", empty)
        assertThrows(IllegalStateException::class.java) { store.save(url, null, null, empty) }
        assertThrows(IllegalArgumentException::class.java) { store.save(null, url, "second", store.state.value) }
        val key = store.cacheKey(url)
        fail = true
        assertThrows(IOException::class.java) { store.save(url, null, null, store.state.value) }
        assertEquals("first", store.selected(origin) { true })
        assertEquals(key, store.cacheKey(url))
        assertEquals(1, invalidations)
    }

    @Test
    fun `clear or same-alias reselection during KeyChain validation cannot return stale identity`() {
        listOf<String?>(null, "first").forEach { replacement ->
            val store = CertificateBindings(persist = { _, _ -> }, invalidate = {})
            store.save(null, url, "first", store.state.value)
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val result =
                CompletableFuture.supplyAsync {
                    store.selected(origin) {
                        entered.countDown()
                        assertTrue(resume.await(3, TimeUnit.SECONDS))
                        true
                    }
                }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            try {
                store.save(url, url, replacement, store.state.value)
            } finally {
                resume.countDown()
            }
            assertNull(result.get(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `draft callbacks expire on host edit clear newer selection or dismissal`() {
        val draft = CertificateEditSession()
        val first = draft.beginSelection()
        assertTrue(draft.accept(first))
        assertFalse(draft.accept(first))
        val pendingEdit = draft.beginSelection()
        draft.invalidate()
        assertFalse(draft.accept(pendingEdit))
        val second = draft.beginSelection()
        val third = draft.beginSelection()
        assertFalse(draft.accept(second))
        assertTrue(draft.accept(third))
        val pendingDismissal = draft.beginSelection()
        draft.close()
        assertFalse(draft.accept(pendingDismissal))
        assertFalse(draft.accept(draft.beginSelection()))
    }

    @Test
    fun `manager input canonicalizes domains IPv6 and ports without accepting URLs`() {
        assertEquals(CertificateOrigin("example.com", 443), CertificateOrigin.parse(" EXAMPLE.com ", "443"))
        assertEquals(CertificateOrigin("::1", 8443), CertificateOrigin.parse("[::1]", "8443"))
        listOf("", "https://example.com", "example.com/path", "name@host", "a b").forEach { host ->
            assertThrows(IllegalArgumentException::class.java) { CertificateOrigin.parse(host, "443") }
        }
        listOf("0", "65536", "https", "-1").forEach { port ->
            assertThrows(IllegalArgumentException::class.java) { CertificateOrigin.parse("example.com", port) }
        }
    }
}
