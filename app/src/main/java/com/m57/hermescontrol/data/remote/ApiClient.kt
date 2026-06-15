package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
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

    // ── Internal ─────────────────────────────────────────────────────────

    private fun buildService(): HermesApiService {
        val logging =
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
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

        val okHttp =
            OkHttpClient
                .Builder()
                .addInterceptor(authInterceptor)
                .addInterceptor(logging)
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
