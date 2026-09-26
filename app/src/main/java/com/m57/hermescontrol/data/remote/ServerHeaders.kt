package com.m57.hermescontrol.data.remote

import android.content.SharedPreferences
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.Locale

/** Validated header values. Never include credentials in diagnostics or toString(). */
class CustomHeaders private constructor(
    val entries: Map<String, String>,
) {
    override fun toString(): String = "CustomHeaders(${entries.size} entries)"

    companion object {
        val EMPTY = CustomHeaders(emptyMap())
        private val namePattern = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val reserved =
            setOf(
                "authorization",
                "proxy-authorization",
                "cookie",
                "host",
                "connection",
                "content-length",
                "transfer-encoding",
                "upgrade",
                "te",
                "trailer",
            )

        fun parse(rows: List<Pair<String, String>>): CustomHeaders {
            val result = linkedMapOf<String, String>()
            val names = mutableSetOf<String>()
            rows.filterNot { it.first.isBlank() && it.second.isEmpty() }.forEach { (rawName, value) ->
                val name = rawName.trim()
                val normalized = name.lowercase(Locale.ROOT)
                // Do not expose OkHttp's validation exceptions: they can contain secret values.
                require(namePattern.matches(name)) { "Invalid header name" }
                require(normalized !in reserved && !normalized.startsWith("sec-websocket-")) {
                    "Header is managed by the app"
                }
                require(names.add(normalized)) { "Duplicate header name" }
                require(value.all { it == '\t' || it in ' '..'~' }) { "Invalid header value" }
                result[name] = value
            }
            return CustomHeaders(result.toMap())
        }
    }
}

/** Headers belong to a canonical server URL, including its reverse-proxy path. */
class ScopedServerHeaders(
    val baseUrl: HttpUrl,
    val headers: CustomHeaders,
) {
    fun contains(url: HttpUrl): Boolean =
        url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port &&
            (
                url.encodedPath.startsWith(baseUrl.encodedPath) ||
                    url.encodedPath == baseUrl.encodedPath.removeSuffix("/")
            )
}

/** Uses AuthManager's encrypted preferences, never the plaintext server_store.json. */
object ServerHeaders {
    private const val KEY = "server_custom_headers"

    @Volatile
    private var configured: List<ScopedServerHeaders> = emptyList()
    private var preferences: SharedPreferences? = null

    @Synchronized
    fun initialize(encryptedPreferences: SharedPreferences) {
        val stored = encryptedPreferences.getString(KEY, null)
        configured =
            if (stored == null) {
                emptyList()
            } else {
                val decoded =
                    try {
                        Json.decodeFromString<Map<String, Map<String, String>>>(stored)
                    } catch (_: SerializationException) {
                        // JSON parser exceptions include the input, which contains credentials.
                        throw IOException("Could not read custom headers")
                    }
                decoded.map { (url, values) ->
                    ScopedServerHeaders(ServerEndpoint.parseForBuild(url).baseUrl, CustomHeaders.parse(values.toList()))
                }
            }
        preferences = encryptedPreferences
    }

    fun get(baseUrl: HttpUrl): CustomHeaders =
        configured.firstOrNull { it.baseUrl == baseUrl }?.headers ?: CustomHeaders.EMPTY

    fun forRequest(url: HttpUrl): ScopedServerHeaders? =
        configured.filter { it.contains(url) }.maxByOrNull { it.baseUrl.encodedPath.length }

    /** Call on IO. Publish only after the encrypted write succeeds. */
    @Synchronized
    fun save(
        baseUrl: HttpUrl,
        headers: CustomHeaders,
    ) {
        val prefs = checkNotNull(preferences) { "Server headers are not initialized" }
        val updated = configured.filterNot { it.baseUrl == baseUrl } + ScopedServerHeaders(baseUrl, headers)
        val serialized = Json.encodeToString(updated.associate { it.baseUrl.toString() to it.headers.entries })
        if (!prefs.edit().putString(KEY, serialized).commit()) throw IOException("Could not save custom headers")
        configured = updated
    }
}

/**
 * HTTP uses this as a network interceptor so redirects are checked before every exchange.
 * OkHttp skips network interceptors for WebSockets, which use it as an application interceptor
 * with redirects disabled. Credentials never follow a request outside its original server scope.
 */
class ServerHeadersInterceptor(
    private val resolve: (HttpUrl) -> ScopedServerHeaders? = ServerHeaders::forRequest,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val scope = resolve(chain.call().request().url) ?: return chain.proceed(request)
        val builder = request.newBuilder()
        val withinScope = scope.contains(request.url) && resolve(request.url)?.baseUrl == scope.baseUrl
        scope.headers.entries.forEach { (name, value) ->
            if (withinScope) builder.header(name, value) else builder.removeHeader(name)
        }
        return chain.proceed(builder.build())
    }
}
