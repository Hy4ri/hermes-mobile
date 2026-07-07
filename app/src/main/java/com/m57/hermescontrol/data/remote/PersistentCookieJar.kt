package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * OkHttp [CookieJar] backed by an in-memory [ConcurrentHashMap] cache plus
 * encrypted [CookieStore] persistence.
 *
 * Design notes (issue #470):
 * - **Memory cache** keyed by host for O(1) reads on every request.
 * - **Async persistence** — writes are dispatched to [storeScope] and never
 *   block the calling OkHttp thread.
 * - **Atomic server scoping** via [useStore] — callers (login, profile switch)
 *   flip the active server id; subsequent load/save target that scope. Guarded
 *   by [scopeMutex] so a swap can't race a mid-flight load.
 * - **Lazy load** — the first request for a server scope triggers a one-shot
 *   [CookieStore.load] (double-checked through [loadedScopes]).
 * - **Pruning** — [pruneServerCache] evicts expired cookies to bound growth.
 *
 * The session cookie (`hermes_session_at`) is explicitly retained across
 * pruning because its lifetime is owned server-side, not by client expiry.
 */
class PersistentCookieJar(
    private val store: CookieStore,
    private val storeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : CookieJar {
    // serverId -> (host -> cookies)
    private val cache = ConcurrentHashMap<String, MutableMap<String, MutableList<Cookie>>>()
    private val loadedScopes = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var currentServerId = AtomicReference(DEFAULT_SERVER_ID)

    private val scopeMutex = Mutex()

    /** Atomically switch the active server scope and ensure its cookies are loaded. */
    suspend fun useStore(serverId: String) {
        scopeMutex.withLock {
            currentServerId.set(serverId)
            ensureLoaded(serverId)
        }
    }

    /** Current active server scope id. */
    fun currentServer(): String = currentServerId.get()

    /**
     * Return the live [Cookie] with [name] from the active server scope, or
     * null if absent. Scans every host bucket; the session cookie is typically
     * host-scoped to the dashboard host.
     */
    fun getCookie(name: String): Cookie? {
        val serverId = currentServerId.get()
        val hosts = cache[serverId] ?: return null
        for (bucket in hosts.values) {
            synchronized(bucket) {
                val match = bucket.firstOrNull { it.name == name }
                if (match != null) return match
            }
        }
        return null
    }

    /** Convenience: value of the `hermes_session_at` session cookie. */
    fun getSessionCookieValue(): String? = getCookie(SESSION_COOKIE_NAME)?.value

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        if (cookies.isEmpty()) return
        val serverId = currentServerId.get()
        val hostKey = url.host
        val bucket =
            cache
                .getOrPut(serverId) { ConcurrentHashMap() }
                .getOrPut(hostKey) { mutableListOf() }
        synchronized(bucket) {
            for (cookie in cookies) {
                val idx = bucket.indexOfFirst { it.name == cookie.name && it.path == cookie.path }
                if (idx >= 0) bucket[idx] = cookie else bucket.add(cookie)
            }
        }
        // Persist the whole server scope asynchronously.
        persist(serverId)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val serverId = currentServerId.get()
        // Best-effort synchronous load on first touch (tests/first call).
        if (!loadedScopes.contains(serverId)) {
            kotlinx.coroutines.runBlocking { ensureLoaded(serverId) }
        }
        val hostKey = url.host
        val hosts = cache[serverId] ?: return emptyList()
        val now = System.currentTimeMillis()
        val buckets = listOfNotNull(hosts[hostKey], hosts[WILDCARD_HOST])
        synchronized(buckets) {
            return buckets.flatMap { bucket ->
                synchronized(bucket) {
                    bucket.filter { it.matches(url) && (it.expiresAt > now || isSessionCookie(it)) }
                }
            }
        }
    }

    private suspend fun ensureLoaded(serverId: String) {
        if (loadedScopes.contains(serverId)) return
        val persisted = store.load(serverId)
        val byHost = ConcurrentHashMap<String, MutableList<Cookie>>()
        for (cookie in persisted) {
            // Blank domain => host-only (e.g. legacy migrated session cookie).
            // Bucket it under a wildcard "*" so it is returned for every host.
            val host = cookie.domain.removePrefix(".").ifBlank { WILDCARD_HOST }
            byHost.getOrPut(host) { mutableListOf() }.add(cookie)
        }
        cache[serverId] = byHost
        loadedScopes.add(serverId)
    }

    private fun persist(serverId: String) {
        val snapshot =
            cache[serverId]?.values?.flatten()?.toList() ?: return
        storeScope.launch { store.save(serverId, snapshot) }
    }

    /**
     * Evict expired cookies for the active (or all) server scopes to prevent
     * unbounded growth. Session cookies are preserved.
     */
    fun pruneServerCache(allScopes: Boolean = false) {
        val now = System.currentTimeMillis()
        val scopeKeys =
            if (allScopes) cache.keys.toList() else listOf(currentServerId.get())
        for (scope in scopeKeys) {
            val hosts = cache[scope] ?: continue
            for ((host, bucket) in hosts) {
                synchronized(bucket) {
                    bucket.removeAll { it.expiresAt <= now && !isSessionCookie(it) }
                }
                if (bucket.isEmpty()) hosts.remove(host)
            }
            persist(scope)
        }
    }

    /** Clear the active server scope's in-memory + persisted cookies. */
    fun clearActive() {
        val scope = currentServerId.get()
        cache.remove(scope)
        loadedScopes.remove(scope)
        storeScope.launch { store.clear(scope) }
    }

    /** Clear every server scope (logout / full reset). */
    fun clearAll() {
        cache.clear()
        loadedScopes.clear()
        storeScope.launch { store.clearAll() }
    }

    companion object {
        const val DEFAULT_SERVER_ID = "default"

        /** Bucket key for host-only (blank-domain) cookies, returned for any host. */
        const val WILDCARD_HOST = "*"
    }
}
