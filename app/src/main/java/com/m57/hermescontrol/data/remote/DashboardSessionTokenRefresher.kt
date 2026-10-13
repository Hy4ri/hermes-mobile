package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import kotlin.coroutines.resume

internal object DashboardSessionTokenRefresher {
    private val tokenPattern = Regex("""__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"""")
    private val refreshMutex = Mutex()
    private var inFlight: Flight? = null

    private data class Identity(
        val baseUrl: String,
        val scope: DataScope?,
        val failedToken: String?,
    )

    private class Flight(
        val identity: Identity,
        val result: CompletableDeferred<String?> = CompletableDeferred(),
    )

    fun refresh(): String? = runBlocking { refreshAsync() }

    suspend fun refreshAsync(): String? =
        try {
            recover(Identity(AuthManager.baseUrl(), AuthManager.currentDataScope(), AuthManager.getToken()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    internal fun refreshIfStale(
        failedToken: String?,
        expectedScope: DataScope?,
    ): String? =
        runBlocking {
            try {
                recover(Identity(AuthManager.baseUrl(), expectedScope, failedToken))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    private fun owns(identity: Identity): Boolean =
        AuthManager.currentDataScope() == identity.scope && AuthManager.baseUrl() == identity.baseUrl

    private suspend fun recover(identity: Identity): String? {
        val (flight, leader) =
            refreshMutex.withLock {
                // #1552: verify ownership BEFORE reusing credentials from an overlapping refresh.
                if (!owns(identity)) return null
                val current = AuthManager.getToken()
                if (!current.isNullOrBlank() && current != identity.failedToken) return current
                val existing = inFlight
                if (existing != null && existing.identity == identity) {
                    existing to false
                } else {
                    Flight(identity).also { inFlight = it } to true
                }
            }
        if (!leader) return flight.result.await()

        try {
            val fetched = fetch(identity.baseUrl, OkHttpProvider.probe)
            val result =
                refreshMutex.withLock {
                    when {
                        !owns(identity) -> {
                            null
                        }

                        AuthManager.getToken() != identity.failedToken -> {
                            AuthManager.getToken()?.takeIf {
                                it
                                    .isNotBlank()
                            }
                        }

                        fetched != null -> {
                            fetched.also(AuthManager::setToken)
                        }

                        else -> {
                            null
                        }
                    }
                }
            flight.result.complete(result)
            return result
        } catch (e: CancellationException) {
            flight.result.cancel(e)
            throw e
        } catch (_: Exception) {
            flight.result.complete(null)
            return null
        } finally {
            // Cleanup must run even when the leader was cancelled; otherwise followers can hang forever.
            withContext(NonCancellable) {
                refreshMutex.withLock { if (inFlight === flight) inFlight = null }
            }
        }
    }

    internal suspend fun fetch(
        baseUrl: String,
        client: OkHttpClient,
    ): String? =
        withContext(Dispatchers.IO) {
            try {
                val call =
                    client.newCall(
                        Request
                            .Builder()
                            .url(baseUrl)
                            .get()
                            .build(),
                    )
                val owner = currentCoroutineContext()
                coroutineScope {
                    suspendCancellableCoroutine { continuation ->
                        val worker =
                            launch {
                                try {
                                    val token =
                                        call.await().use { response ->
                                            if (response.isSuccessful) {
                                                extractToken(
                                                    response,
                                                ) { owner.ensureActive() }
                                            } else {
                                                null
                                            }
                                        }
                                    continuation.resume(token)
                                } catch (e: CancellationException) {
                                    continuation.cancel(e)
                                } catch (_: Exception) {
                                    continuation.resume(null)
                                }
                            }
                        // A cancelled body reader cannot reach its own catch until the socket is closed.
                        continuation.invokeOnCancellation {
                            call.cancel()
                            worker.cancel()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    internal fun fetchSync(
        baseUrl: String,
        client: OkHttpClient,
    ): String? =
        try {
            client
                .newCall(
                    Request
                        .Builder()
                        .url(baseUrl)
                        .get()
                        .build(),
                ).execute()
                .use { response ->
                    if (response.isSuccessful) extractToken(response) else null
                }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private fun extractToken(
        response: Response,
        checkCancellation: () -> Unit = {},
    ): String? {
        val source = response.body.source()
        val buffer = Buffer()
        while (buffer.size < MAX_BODY_BYTES) {
            checkCancellation()
            if (source.read(buffer, minOf(CHUNK_SIZE, MAX_BODY_BYTES - buffer.size)) == -1L) break
            checkCancellation()
            // Retain the entire bounded window: arbitrary token/whitespace lengths can span many chunks.
            tokenPattern.find(buffer.clone().readUtf8())?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let {
                return it
            }
        }
        return null
    }

    private const val MAX_BODY_BYTES = 64L * 1024
    private const val CHUNK_SIZE = 8L * 1024
}
