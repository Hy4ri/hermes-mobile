package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

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
            OkHttpProvider
                .base
                .newBuilder()
                .addInterceptor(tempAuthInterceptor)
                .build()

        val tempRetrofit =
            Retrofit
                .Builder()
                .baseUrl("http://$host:$port/")
                .client(tempOkHttp)
                .addConverterFactory(GsonConverterFactory.create(OkHttpProvider.gson))
                .build()

        return tempRetrofit.create(HermesApiService::class.java)
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun buildService(): HermesApiService {
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
                val request = chain.request()
                val sessionCookie = AuthManager.getSessionCookie()
                if (!sessionCookie.isNullOrBlank()) {
                    // Gated mode: authenticate via session cookie
                    chain.proceed(
                        request
                            .newBuilder()
                            .addHeader("Cookie", "hermes_session_at=$sessionCookie")
                            .build(),
                    )
                } else {
                    // Loopback mode: authenticate via Bearer token
                    val token = AuthManager.getToken()
                    if (!token.isNullOrBlank()) {
                        chain.proceed(
                            request
                                .newBuilder()
                                .addHeader("Authorization", "Bearer $token")
                                .build(),
                        )
                    } else {
                        chain.proceed(request)
                    }
                }
            }

        val certificatePinner = CertificatePinner.Builder().build()

        val okHttp =
            OkHttpProvider
                .base
                .newBuilder()
                .addInterceptor(authInterceptor)
                .addInterceptor(logging)
                .authenticator(TokenRefreshAuthenticator)
                .certificatePinner(certificatePinner)
                .build()

        val rf =
            Retrofit
                .Builder()
                .baseUrl(AuthManager.baseUrl())
                .client(okHttp)
                .addConverterFactory(GsonConverterFactory.create(OkHttpProvider.gson))
                .build()
                .also { retrofit = it }

        return rf.create(HermesApiService::class.java)
    }
}
