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
 * A single-value accessor remains for compatibility with migrated sessions;
 * internally it resolves the dashboard access cookie from the active scope,
 * including HTTPS prefix variants.
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

    /** Read the current dashboard access-cookie value (or null). */
    fun getSessionCookie(): String? = jar?.getSessionCookieValue()

    /**
     * Set the session cookie for the active scope and canonical endpoint.
     * HTTPS endpoints produce Secure cookies that cannot be sent over HTTP.
     */
    fun setSessionCookie(
        rawValue: String?,
        endpoint: ServerEndpoint,
    ) {
        val j = jar ?: return
        if (rawValue.isNullOrBlank()) {
            j.clearActive()
            return
        }
        val builder =
            Cookie
                .Builder()
                .name(SESSION_COOKIE_NAME)
                .value(rawValue)
                .expiresAt(
                    System.currentTimeMillis() +
                        10L * 365 * 24 * 60 * 60 * 1000,
                )
                .hostOnlyDomain(endpoint.baseUrl.host)
                .path(endpoint.baseUrl.encodedPath)
                .httpOnly()
        if (endpoint.baseUrl.isHttps) builder.secure()
        j.saveFromResponse(endpoint.baseUrl, listOf(builder.build()))
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
