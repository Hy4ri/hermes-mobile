package com.m57.hermescontrol.data.remote

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Coordinator for the issue #470 cookie stack.
 *
 * Owns the single [PersistentCookieJar] instance and exposes:
 * - [cookieJar] — inject into every OkHttp client (REST + WS + probe).
 * - [initialize] — build the encrypted store + jar (idempotent).
 * - [useStore] — atomically switch the active server scope.
 * - [pruneServerCache] / [clearAll] — lifecycle/maintenance.
 * - [getSessionCookie] / [setSessionCookie] — backward-compatible accessors
 *   used by [com.m57.hermescontrol.data.local.AuthManager] and the login flow.
 *
 * The session cookie is still exposed as a single string because the WS client
 * and a few REST paths reference it directly; internally it is just the
 * `hermes_session_at` cookie from the active scope.
 */
object CookieManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var jar: PersistentCookieJar? = null

    val cookieJar: PersistentCookieJar
        get() = jar ?: error("CookieManager.initialize(context) must be called before use")

    fun isInitialized(): Boolean = jar != null

    fun initialize(
        context: Context,
        legacyPrefsDeferred: Deferred<SharedPreferences>? = null,
        initialServerId: String = PersistentCookieJar.DEFAULT_SERVER_ID,
    ) {
        if (jar != null) return
        synchronized(this) {
            if (jar != null) return
            val store = EncryptedCookieStore(context.applicationContext, legacyPrefsDeferred)
            jar = PersistentCookieJar(store, scope, initialServerId)
            // Eagerly load the initial scope off the caller thread so the
            // first REST call is instant without blocking app startup.
            scope.launch { jar!!.useStore(initialServerId) }
        }
    }

    /** Switch the active server scope and load its persisted cookies. */
    fun useStore(serverId: String) {
        val j = jar ?: return
        runBlocking(scope.coroutineContext) { j.useStore(serverId) }
    }

    /** Read the current `hermes_session_at` cookie value (or null). */
    fun getSessionCookie(): String? = jar?.getSessionCookieValue()

    /**
     * Set the `hermes_session_at` session cookie for the active scope.
     * [host] should be the dashboard host so the cookie matches REST requests.
     */
    fun setSessionCookie(
        rawValue: String?,
        host: String,
    ) {
        val j = jar ?: return
        if (rawValue.isNullOrBlank()) {
            // Clearing: drop the session cookie (and any other cookies) for the
            // active scope — equivalent to the old AuthManager nulling the
            // session-cookie pref.
            j.clearActive()
            return
        }
        val cookie =
            Cookie
                .Builder()
                .name(SESSION_COOKIE_NAME)
                .value(rawValue)
                .expiresAt(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
                .hostOnlyDomain(host)
                .path("/")
                .httpOnly()
                .build()
        val url = "http://$host/".toHttpUrl()
        j.saveFromResponse(url, listOf(cookie))
    }

    /** Evict expired (non-session) cookies for the active scope. */
    fun pruneServerCache() {
        jar?.pruneServerCache(allScopes = false)
    }

    /** Wipe all cookies across all server scopes (logout / full reset). */
    fun clearAll() {
        jar?.clearAll()
    }

    // ── Test seam ────────────────────────────────────────────────────────

    /**
     * Replace the backing jar with [jar] (used by unit tests to inject a
     * [FakePersistentCookieJar]). Not for production use.
     */
    @Suppress("unused")
    internal fun setJarForTest(jar: PersistentCookieJar?) {
        this.jar = jar
    }

    /** Reset to uninitialized state (tests only). */
    @Suppress("unused")
    internal fun resetForTest() {
        jar = null
    }
}
