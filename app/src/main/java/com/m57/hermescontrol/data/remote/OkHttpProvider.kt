package com.m57.hermescontrol.data.remote

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object OkHttpProvider {
    // Single connection pool shared by ALL clients (REST, WS, probes)
    // 5 idle connections, 30s keep-alive — tuned for single-server LAN
    private val connectionPool = ConnectionPool(5, 30, TimeUnit.SECONDS)

    // Base client: connection pool + sensible defaults
    val base: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectionPool(connectionPool)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    // WebSocket-optimized variant (infinite read timeout, ping interval)
    val websocket: OkHttpClient =
        base
            .newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

    // Short-timeout variant for probes and ticket minting
    val probe: OkHttpClient =
        base
            .newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

    // Shared Gson — thread-safe, zero-config
    val gson: Gson = GsonBuilder().create()
}
