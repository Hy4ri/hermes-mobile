package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resumeWithException

/** One credential-free request with an isolated TLS session; nothing is persisted here. */
internal suspend fun verifyClientCertificate(
    url: HttpUrl,
    trust: X509TrustManager,
    keyManager: X509ExtendedKeyManager,
) {
    val origin = requireNotNull(CertificateOrigin.from(url))
    val context = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), arrayOf(trust), null) }
    val pool = ConnectionPool()
    val client =
        OkHttpClient
            .Builder()
            .sslSocketFactory(context.socketFactory, trust)
            .connectionPool(pool)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .fastFallback(false)
            // This private client consumes only headers, never HTTP status. Normalize it before
            // OkHttp's follow-up layer (503 + Retry-After: 0 otherwise retries even with retries off).
            .addNetworkInterceptor { chain ->
                chain
                    .proceed(chain.request())
                    .newBuilder()
                    .code(200)
                    .build()
            }.connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    val call =
        client.newCall(
            Request
                .Builder()
                .url(origin.url.resolve("/api/status")!!)
                .get()
                .build(),
        )
    try {
        // TLS 1.3 can report local handshake completion before a peer's certificate rejection.
        // Require response headers, but no particular HTTP status or response body.
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        response.close()
                        if (continuation.isActive) continuation.resume(Unit) { _, _, _ -> }
                    }
                },
            )
        }
    } finally {
        call.cancel()
        pool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
