package com.m57.hermescontrol.data.remote

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.m57.hermescontrol.data.local.AuthManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

@OptIn(UnstableApi::class)
object MediaDataSourceProvider {
    class SameOriginAuthInterceptor(
        private val baseUrlProvider: () -> String = { runCatching { AuthManager.baseUrl() }.getOrDefault("") },
        private val tokenProvider: () -> String? = { runCatching { AuthManager.getToken() }.getOrNull() },
        private val isGatedModeProvider: () -> Boolean = {
            runCatching { AuthManager.isGatedMode() }.getOrDefault(
                false,
            )
        },
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            // In gated mode (basic auth), the dashboard authenticates via session cookies;
            // stamping an Authorization: Bearer header causes 401s (issue #470).
            if (isGatedModeProvider()) {
                return chain.proceed(request)
            }
            val url = request.url
            val base = baseUrlProvider().toHttpUrlOrNull()
            val isSameOrigin =
                base != null &&
                    url.scheme.equals(base.scheme, ignoreCase = true) &&
                    url.host.equals(base.host, ignoreCase = true) &&
                    url.port == base.port

            if (isSameOrigin) {
                val token = tokenProvider()
                if (!token.isNullOrBlank() && request.header("Authorization") == null) {
                    return chain.proceed(
                        request
                            .newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build(),
                    )
                }
            }
            return chain.proceed(request)
        }
    }

    @Volatile
    internal var testClient: OkHttpClient? = null

    val mediaOkHttpClient: OkHttpClient
        get() = testClient ?: defaultClient

    private val defaultClient: OkHttpClient by lazy {
        OkHttpProvider.base
            .newBuilder()
            .addInterceptor(SameOriginAuthInterceptor())
            .build()
    }

    fun createDataSourceFactory(context: Context): DataSource.Factory {
        val httpDataSourceFactory = OkHttpDataSource.Factory(mediaOkHttpClient)
        return DefaultDataSource.Factory(context, httpDataSourceFactory)
    }
}
