package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Policy applied when a complete dashboard base URL uses cleartext HTTP. */
enum class CleartextPolicy {
    DENY,
    ALLOW_WITH_WARNING,
}

/**
 * Canonical representation of a Hermes dashboard endpoint.
 *
 * The base URL always has an HTTP(S) scheme and a trailing slash. All REST,
 * authentication, cookie, and WebSocket URLs are derived from this value so a
 * reverse-proxy path prefix cannot be dropped by an individual caller.
 */
class ServerEndpoint private constructor(
    val baseUrl: HttpUrl,
    val securityWarning: String?,
) {
    fun resolve(relativePath: String): HttpUrl {
        val path = relativePath.trim().trimStart('/')
        require(path.isNotEmpty()) { "Endpoint path must not be blank" }
        require(path.split('/').none { it == ".." }) {
            "Endpoint path must not escape the configured base path"
        }
        return baseUrl.newBuilder().addPathSegments(path).build()
    }

    fun webSocketUrl(
        authParameter: String,
        credential: String,
    ): String {
        require(authParameter == "token" || authParameter == "ticket") {
            "Unsupported WebSocket authentication parameter"
        }
        val socketHttpUrl =
            resolve("api/ws")
                .newBuilder()
                .addQueryParameter(authParameter, credential)
                .build()
        val socketScheme = if (baseUrl.isHttps) "wss" else "ws"
        return replaceScheme(socketHttpUrl.toString(), socketScheme)
    }

    /** Return a request path relative to this endpoint's proxy prefix. */
    fun relativeRequestPath(requestUrl: HttpUrl): String {
        if (!sameOrigin(requestUrl)) return requestUrl.encodedPath
        val prefix = baseUrl.encodedPath
        if (!requestUrl.encodedPath.startsWith(prefix)) {
            return requestUrl.encodedPath
        }
        return "/" + requestUrl.encodedPath.removePrefix(prefix)
    }

    private fun sameOrigin(other: HttpUrl): Boolean =
        baseUrl.scheme == other.scheme &&
            baseUrl.host == other.host &&
            baseUrl.port == other.port

    companion object {
        const val DEFAULT_BASE_URL = "https://127.0.0.1:9119/"
        const val CLEARTEXT_WARNING =
            "Cleartext HTTP exposes credentials and messages. " +
                "Use HTTPS unless this is a trusted development network."

        fun parse(
            rawValue: String,
            cleartextPolicy: CleartextPolicy = CleartextPolicy.DENY,
        ): ServerEndpoint {
            val raw = rawValue.trim()
            require(raw.isNotEmpty()) { "Base URL is required" }
            require(
                raw.startsWith("https://", ignoreCase = true) ||
                    raw.startsWith("http://", ignoreCase = true),
            ) { "Base URL must use https:// or http://" }

            val parsed =
                raw.toHttpUrlOrNull()
                    ?: throw IllegalArgumentException("Malformed base URL")
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
                "Base URL must not contain credentials"
            }
            require(parsed.encodedQuery == null) {
                "Base URL must not contain a query"
            }
            require(parsed.encodedFragment == null) {
                "Base URL must not contain a fragment"
            }

            val normalized =
                if (parsed.encodedPath.endsWith('/')) {
                    parsed
                } else {
                    parsed.newBuilder().addPathSegment("").build()
                }

            if (!normalized.isHttps && cleartextPolicy == CleartextPolicy.DENY) {
                throw IllegalArgumentException(
                    "Cleartext HTTP is disabled in this build. Use HTTPS.",
                )
            }
            val warning = if (normalized.isHttps) null else CLEARTEXT_WARNING
            return ServerEndpoint(normalized, warning)
        }

        fun parseForBuild(rawValue: String): ServerEndpoint =
            parse(
                rawValue,
                if (BuildConfig.ALLOW_CLEARTEXT) {
                    CleartextPolicy.ALLOW_WITH_WARNING
                } else {
                    CleartextPolicy.DENY
                },
            )

        /** Preserve the pre-migration HTTP behavior for an existing install. */
        fun fromLegacy(
            host: String,
            port: Int,
        ): ServerEndpoint {
            require(port in 1..65535) { "Legacy port is out of range" }
            val normalizedHost =
                host.trim().removePrefix("[").removeSuffix("]")
            val url =
                HttpUrl
                    .Builder()
                    .scheme("http")
                    .host(normalizedHost)
                    .port(port)
                    .build()
            return parse(
                url.toString(),
                CleartextPolicy.ALLOW_WITH_WARNING,
            )
        }

        fun redactWebSocketUrlForLog(rawUrl: String): String {
            val httpScheme =
                when {
                    rawUrl.startsWith("wss://", ignoreCase = true) -> "https"
                    rawUrl.startsWith("ws://", ignoreCase = true) -> "http"
                    else -> return "<invalid-websocket-url>"
                }
            val socketScheme = if (httpScheme == "https") "wss" else "ws"
            val converted =
                replaceScheme(rawUrl, httpScheme).toHttpUrlOrNull()
                    ?: return "<invalid-websocket-url>"
            val redacted = converted.newBuilder().query(null).build().toString()
            return replaceScheme(redacted, socketScheme)
        }

        private fun replaceScheme(
            url: String,
            scheme: String,
        ): String = url.replaceBefore("://", scheme)
    }
}
