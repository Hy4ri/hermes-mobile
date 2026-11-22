package com.m57.hermescontrol.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.m57.hermescontrol.data.local.AuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
            jar =
                PersistentCookieJar(store, scope, initialServerId) {
                    // Wildcard session cookies may only go to the active server's host.
                    setOfNotNull(
                        AuthManager.baseUrlFlow.value
                            .toHttpUrlOrNull()
                            ?.host,
                    )
                }
            // Eagerly load the initial scope off the caller thread so the
            // first REST call is instant without blocking app startup.
            scope.launch { jar!!.useStore(initialServerId) }
        }
    }

    /** Switch the active server scope and load its persisted cookies. */
    suspend fun useStore(serverId: String) {
        val j = jar ?: return
        j.useStore(serverId)
    }

    /** Read the current dashboard access-cookie value (or null). */
    fun getSessionCookie(): String? = jar?.getSessionCookieValue()

    /**
     * The provider the active scope's last dashboard session signed in with (the gateway's
     * `hermes_session_provider` routing hint), or null. May block briefly while the scope loads.
     */
    fun getSessionProvider(): String? {
        val j = jar ?: return null
        return listOf("__Host-", "__Secure-", "")
            .firstNotNullOfOrNull { prefix -> j.getCookie(prefix + SESSION_PROVIDER_COOKIE_NAME)?.value }
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Resolve the cookie-name prefix expected by Hermes Agent (`hermes_cli/dashboard_auth/cookies.py`):
     * - Plain HTTP: bare name (`""`)
     * - HTTPS at root (`/`): `__Host-` (RFC 6265bis: `Secure`, `Path=/`, host-only)
     * - HTTPS behind a path prefix: `__Secure-` (RFC 6265bis: `Secure`)
     */
    internal fun sessionCookiePrefix(url: HttpUrl): String =
        when {
            !url.isHttps -> ""
            url.encodedPath.trimEnd('/').isEmpty() -> "__Host-"
            else -> "__Secure-"
        }

    private fun buildSessionCookie(
        url: HttpUrl,
        bareName: String,
        value: String,
        expiresAt: Long,
    ): Cookie {
        val prefix = sessionCookiePrefix(url)
        val cookiePath = if (prefix == "__Host-") "/" else url.encodedPath
        return Cookie
            .Builder()
            .name(prefix + bareName)
            .value(value)
            .expiresAt(expiresAt)
            .hostOnlyDomain(url.host)
            .path(cookiePath)
            .httpOnly()
            .apply { if (url.isHttps) secure() }
            .build()
    }

    /**
     * Set the session cookie for the active scope and canonical endpoint.
     * HTTPS endpoints produce Secure, prefix-hardened cookies (`__Host-` at root,
     * `__Secure-` behind a path prefix) that cannot be sent over HTTP.
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
        val url = endpoint.baseUrl
        val cookie =
            buildSessionCookie(
                url = url,
                bareName = SESSION_COOKIE_NAME,
                value = rawValue,
                expiresAt = System.currentTimeMillis() + SESSION_COOKIE_LIFETIME_MS,
            )
        j.replaceCookies(url, listOf(cookie), SESSION_COOKIE_NAMES.toSet())
    }

    /**
     * Install the bearer tokens from the dashboard's native (browser) login as the same session
     * cookies a password login would have set. On HTTPS, Hermes strictly requires the prefix
     * matching the request shape (`__Host-` at root `/`, `__Secure-` behind a path prefix) and
     * rejects bare names; over plain HTTP it accepts bare names. The server rotates them itself
     * through `Set-Cookie` when the access token expires.
     */
    fun installNativeSession(
        tokens: NativeTokens,
        endpoint: ServerEndpoint,
    ) {
        val j = jar ?: return
        val url = endpoint.baseUrl
        val now = System.currentTimeMillis()

        val cookies =
            buildList {
                // Lifetime of the access token is owned server-side, as for password login.
                add(buildSessionCookie(url, SESSION_COOKIE_NAME, tokens.accessToken, now + SESSION_COOKIE_LIFETIME_MS))
                if (tokens.refreshToken.isNotBlank()) {
                    add(
                        buildSessionCookie(
                            url,
                            SESSION_RT_COOKIE_NAME,
                            tokens.refreshToken,
                            now + REFRESH_COOKIE_LIFETIME_MS,
                        ),
                    )
                }
                if (tokens.provider.isNotBlank()) {
                    add(
                        buildSessionCookie(
                            url,
                            SESSION_PROVIDER_COOKIE_NAME,
                            tokens.provider,
                            now + REFRESH_COOKIE_LIFETIME_MS,
                        ),
                    )
                }
            }
        j.replaceCookies(url, cookies, SESSION_FAMILY_COOKIE_NAMES)
    }

    private const val SESSION_COOKIE_LIFETIME_MS = 10L * 365 * 24 * 60 * 60 * 1000
    private const val REFRESH_COOKIE_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000

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
