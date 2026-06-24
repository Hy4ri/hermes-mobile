package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Provides a Retrofit-backed [HermesApiService].
 *
 * The client is lazily built using the current [AuthManager] settings and
 * can be rebuilt at any time via [rebuild] (e.g. after the user changes
 * host / port / token).
 */
object ApiClient {
    @Volatile
    private var retrofit: Retrofit? = null

    @Volatile
    private var service: HermesApiService? = null

    /** The current [HermesApiService] instance. Lazily created on first access. */
    val hermesApi: HermesApiService
        get() {
            return service ?: synchronized(this) {
                service ?: buildService().also { service = it }
            }
        }

    /** Force-rebuild the Retrofit client (e.g. after settings change). */
    fun rebuild() {
        synchronized(this) {
            retrofit = null
            service = null
        }
    }

    /** Creates a standalone, temporary [HermesApiService] without modifying the global instance. */
    fun createTempService(
        host: String,
        port: Int,
        token: String,
    ): HermesApiService {
        val tempAuthInterceptor =
            Interceptor { chain ->
                val request =
                    if (token.isNotBlank()) {
                        chain
                            .request()
                            .newBuilder()
                            .addHeader("Authorization", "Bearer $token")
                            .build()
                    } else {
                        chain.request()
                    }
                chain.proceed(request)
            }

        val tempOkHttp =
            OkHttpClient
                .Builder()
                .addInterceptor(tempAuthInterceptor)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()

        val tempRetrofit =
            Retrofit
                .Builder()
                .baseUrl("http://$host:$port/")
                .client(tempOkHttp)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

        return tempRetrofit.create(HermesApiService::class.java)
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun buildService(): HermesApiService {
        // B1 (Jun 18 2026, kanban t_afc1d26f): Level.BODY prints every request —
        // including the Authorization: Bearer header from authInterceptor below —
        // to logcat in plaintext. Gate on BuildConfig.DEBUG so release builds
        // are silent; debug builds behave identically to before.
        val logging =
            HttpLoggingInterceptor().apply {
                level =
                    if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor.Level.BODY
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
            }

        val authInterceptor =
            Interceptor { chain ->
                val token = AuthManager.getToken()
                val request =
                    if (!token.isNullOrBlank()) {
                        chain
                            .request()
                            .newBuilder()
                            .addHeader("Authorization", "Bearer $token")
                            .build()
                    } else {
                        chain.request()
                    }
                chain.proceed(request)
            }

        // SEC-11: No certificate pinning is configured by default since the app
        // intentionally uses HTTP for LAN and hosts are dynamic.
        // CertificatePinner infrastructure is provided here if HTTPS is used with a known host.
        val certificatePinner = CertificatePinner.Builder().build()

        val okHttp =
            OkHttpClient
                .Builder()
                .addInterceptor(authInterceptor)
                .addInterceptor(logging)
                .certificatePinner(certificatePinner)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()

        val rf =
            Retrofit
                .Builder()
                .baseUrl(AuthManager.baseUrl())
                .client(okHttp)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .also { retrofit = it }

        return rf.create(HermesApiService::class.java)
    }
}
