package com.m57.hermescontrol.data.remote

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Version both Coil caches before the engine; a Keyer alone does not version network disk entries. */
internal class CertificateImageInterceptor(
    private val cacheKey: (HttpUrl) -> String = ClientCertificates::cacheKey,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url =
            chain.request.data
                .toString()
                .toHttpUrlOrNull()
                ?: return chain.proceed()
        val key = cacheKey(url)
        val request =
            chain.request
                .newBuilder()
                .memoryCacheKey(key)
                .diskCacheKey(key)
                .build()
        return chain.withRequest(request).proceed()
    }
}
