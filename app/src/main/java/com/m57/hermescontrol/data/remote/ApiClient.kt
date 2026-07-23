package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.local.AuthManager
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

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
        baseUrl: String,
        token: String,
    ): HermesApiService {
        val endpoint = ServerEndpoint.parseForBuild(baseUrl)
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
                .baseUrl(endpoint.baseUrl)
                .client(tempOkHttp)
                .addConverterFactory(OkHttpProvider.json.asConverterFactory("application/json".toMediaType()))
                .build()

        return tempRetrofit.create(HermesApiService::class.java)
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun buildService(): HermesApiService {
        val logging =
            HttpLoggingInterceptor().apply {
                level =
                    if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor.Level.BASIC
                    } else {
                        HttpLoggingInterceptor.Level.NONE
                    }
            }

        // REST auth header strategy:
        //  - Loopback/token mode: stamp `Authorization: Bearer <token>`.
        //  - Gated (basic-auth cookie) mode: the shared CookieManager jar
        //    attaches the session cookie automatically (issue #470). The
        //    dashboard 401s any request that ALSO carries an
        //    `Authorization: Bearer` header (verified live 2026-07-23), so we
        //    must NOT stamp one here — otherwise every REST tab (skills, cron,
        //    config, ...) fails with "token expired" while the WS chat, which
        //    authenticates via ?ticket=, keeps working.
        val authInterceptor =
            Interceptor { chain ->
                val request = chain.request()
                if (AuthManager.isGatedMode()) {
                    // Cookie in the shared jar is the only valid REST credential.
                    return@Interceptor chain.proceed(request)
                }
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

        val okHttp =
            OkHttpProvider
                .base
                .newBuilder()
                .addInterceptor(authInterceptor)
                .addInterceptor(ProfileScopeInterceptor)
                .addInterceptor(logging)
                .authenticator(TokenRefreshAuthenticator)
                .build()

        val rf =
            Retrofit
                .Builder()
                .baseUrl(AuthManager.endpointForBuild().baseUrl)
                .client(okHttp)
                .addConverterFactory(OkHttpProvider.json.asConverterFactory("application/json".toMediaType()))
                .build()
                .also { retrofit = it }

        return rf.create(HermesApiService::class.java)
    }
}
