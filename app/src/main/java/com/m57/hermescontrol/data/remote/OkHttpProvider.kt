package com.m57.hermescontrol.data.remote

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object OkHttpProvider {
    // Single connection pool shared by ALL clients (REST, WS, probes)
    // 5 idle connections, 30s keep-alive — tuned for single-server LAN
    private val connectionPool = ConnectionPool(5, 30, TimeUnit.SECONDS)

    /**
     * Shared [okhttp3.CookieJar] used by every client (REST, WS, probe) so a
     * Set-Cookie from one request is reusable by the others (issue #470).
     *
     * Resolved lazily (per client build) so it is only touched after
     * [CookieManager.initialize] has run at app startup — NOT at
     * [OkHttpProvider] object-init time, which would otherwise throw for unit
     * tests / early access before the app context exists.
     */
    private fun resolveCookieJar(): okhttp3.CookieJar = CookieManager.cookieJar

    // Base client: connection pool + sensible defaults.
    // Lazily built so the shared CookieJar is only resolved at first use
    // (after CookieManager.initialize), not during object construction.
    // Note: retryOnConnectionFailure(true) lets OkHttp recover from low-level
    // connection/route failures (route timeouts, IPv4/IPv6 fallback), separate
    // from safeApiCall's app-level retries (backoff + 5xx/429/timeouts).
    val base: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .cookieJar(resolveCookieJar())
            .connectionPool(connectionPool)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // WebSocket-optimized variant (infinite read timeout, ping interval)
    val websocket: OkHttpClient by lazy {
        base
            .newBuilder()
            .cookieJar(resolveCookieJar())
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    // Short-timeout variant for probes and ticket minting
    val probe: OkHttpClient by lazy {
        base
            .newBuilder()
            .cookieJar(resolveCookieJar())
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    @OptIn(ExperimentalSerializationApi::class)
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            namingStrategy = JsonNamingStrategy.SnakeCase
        }
}
