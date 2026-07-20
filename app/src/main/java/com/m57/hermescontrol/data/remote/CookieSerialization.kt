package com.m57.hermescontrol.data.remote

import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * Wire format for a persisted [Cookie].
 *
 * OkHttp's [Cookie] is not itself serializable, so we project its immutable
 * fields into this flat holder. `sameSite` is nullable in newer OkHttp
 * versions — default to null on decode so older payloads still load.
 */
@kotlinx.serialization.Serializable
data class CookieHolder(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
    val sameSite: String? = null,
)

/** Project a [Cookie] into its serializable form. */
fun Cookie.serialize(): CookieHolder =
    CookieHolder(
        name = name,
        value = value,
        expiresAt = expiresAt,
        domain = domain,
        path = path,
        secure = secure,
        httpOnly = httpOnly,
        hostOnly = hostOnly,
        sameSite = sameSite,
    )

/** Rebuild a [Cookie] from a [CookieHolder]. */
fun CookieHolder.toCookie(): Cookie? =
    runCatching {
        Cookie
            .Builder()
            .name(name)
            .value(value)
            .expiresAt(expiresAt)
            .apply { if (hostOnly) hostOnlyDomain(domain) else domain(domain) }
            .path(path)
            .also { if (secure) it.secure() }
            .also { if (httpOnly) it.httpOnly() }
            .also { if (sameSite != null) it.sameSite(sameSite) }
            .build()
    }.getOrNull()

/**
 * Wrap a raw `hermes_session_at` value (the pre-#470 single-string session
 * cookie) as a permissive session cookie. Host/path are left empty so it
 * matches any request the jar sends — matching the previous behaviour where
 * the value was injected as a bare `Cookie: hermes_session_at=<value>` header
 * on every REST call.
 */
fun wrapSessionCookie(rawValue: String): Cookie? {
    val value = rawValue.trim()
    if (value.isEmpty()) return null
    return runCatching {
        Cookie
            .Builder()
            .name(SESSION_COOKIE_NAME)
            .value(value)
            // Far-future expiry (10 years) — session lifetime is owned by the
            // dashboard, not by client-side cookie expiry.
            .expiresAt(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
            .path("/")
            .httpOnly()
            .build()
    }.getOrNull()
}

/**
 * Build a permissive [Cookie] from a Set-Cookie `hermes_session_at=...`
 * header captured during login. Used by [PersistentCookieJar] when the server
 * does not echo an explicit domain (common for same-host dashboard logins).
 */
fun sessionCookieFromValue(value: String): Cookie? = wrapSessionCookie(value)

const val SESSION_COOKIE_NAME = "hermes_session_at"

/**
 * All names the dashboard may use for its session cookie, strictest variant
 * first. Over HTTPS the server emits a [`__Host-`](https://developer.mozilla.org/en-US/docs/Web/HTTP/Cookies#cookie_prefixes)
 * (root-facing HTTPS) or `__Secure-` (HTTPS reverse-proxy with a path prefix)
 * prefixed cookie; the bare name is the legacy HTTP form. Ordering matches the
 * server's own fallback precedence so the most-constrained variant wins.
 */
val SESSION_COOKIE_NAMES: List<String> =
    listOf(
        "__Host-$SESSION_COOKIE_NAME",
        "__Secure-$SESSION_COOKIE_NAME",
        SESSION_COOKIE_NAME,
    )

/** True when [name] is any recognized dashboard session-cookie name. */
fun isSessionCookieName(name: String): Boolean = name in SESSION_COOKIE_NAMES

/** True when [cookie] is the dashboard session cookie. */
fun isSessionCookie(cookie: Cookie): Boolean = isSessionCookieName(cookie.name)

/** Parse a raw `Set-Cookie` header value (sans the `hermes_session_at=` key). */
fun Cookie.Companion.parseRaw(
    url: HttpUrl,
    header: String,
): Cookie? = Cookie.parse(url, header)
