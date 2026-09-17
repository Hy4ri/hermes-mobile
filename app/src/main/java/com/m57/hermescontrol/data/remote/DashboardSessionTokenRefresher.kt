package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

internal object DashboardSessionTokenRefresher {
    private val tokenPattern = Regex("""__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"""")
    private val refreshMutex = Mutex()

    // Synchronized so concurrent 401s / reconnect storms don't each fire a
    // redundant GET at the dashboard SPA (thundering-herd guard).
    @Synchronized
    fun refresh(): String? =
        try {
            val token = fetchSync(AuthManager.baseUrl(), OkHttpProvider.probe) ?: return null
            AuthManager.setToken(token)
            token
        } catch (_: Exception) {
            null
        }

    suspend fun refreshAsync(): String? =
        refreshMutex.withLock {
            try {
                val token = fetch(AuthManager.baseUrl(), OkHttpProvider.probe) ?: return@withLock null
                AuthManager.setToken(token)
                token
            } catch (_: Exception) {
                null
            }
        }

    internal suspend fun fetch(
        baseUrl: String,
        client: OkHttpClient,
    ): String? =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request
                        .Builder()
                        .url(baseUrl)
                        .get()
                        .build()

                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    extractToken(response)
                }
            } catch (_: Exception) {
                null
            }
        }

    internal fun fetchSync(
        baseUrl: String,
        client: OkHttpClient,
    ): String? =
        try {
            val request =
                Request
                    .Builder()
                    .url(baseUrl)
                    .get()
                    .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                extractToken(response)
            }
        } catch (_: Exception) {
            null
        }

    private fun extractToken(response: Response): String? {
        // Cap the read — the SPA HTML can be large, and we only need the
        // injected token near the top of the document. Reading the whole
        // body into a String risks OOM on low-end devices.
        val html =
            response.body.source().use { source ->
                val buffer = Buffer()
                source.read(buffer, MAX_BODY_BYTES)
                buffer.readUtf8()
            }
        return tokenPattern
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    private const val MAX_BODY_BYTES: Long = 64 * 1024
}
