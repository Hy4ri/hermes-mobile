package com.m57.hermescontrol.ui.voice

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ScopedServerHeaders
import com.m57.hermescontrol.data.remote.ServerHeaders
import com.m57.hermescontrol.data.remote.ServerHeadersInterceptor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One call's frozen endpoint/authentication. No logging, redirects, refresh or POST retries. */
internal class TalkGatewayApi(
    private val scope: DataScope,
) {
    private val base = (scope.baseUrl.trimEnd('/') + "/").toHttpUrl()
    private val token: String?
    private val client: okhttp3.OkHttpClient

    init {
        checkScope()
        token = if (AuthManager.isGatedMode()) null else AuthManager.getToken()
        val cookies = CookieManager.cookieJar.loadForRequest(base).toList()
        val headers = ScopedServerHeaders(base, ServerHeaders.get(base))
        checkScope()
        client =
            OkHttpProvider.base
                .newBuilder()
                .apply {
                    networkInterceptors().removeAll { it is ServerHeadersInterceptor }
                    addNetworkInterceptor(ServerHeadersInterceptor { if (headers.contains(it)) headers else null })
                }.cookieJar(
                    object : CookieJar {
                        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }

                        override fun saveFromResponse(
                            url: HttpUrl,
                            cookies: List<Cookie>,
                        ) = Unit
                    },
                ).addInterceptor { chain ->
                    checkScope()
                    val request = chain.request()
                    check(
                        request.url.scheme == base.scheme && request.url.host == base.host &&
                            request.url.port == base.port,
                    )
                    val authenticated =
                        request
                            .newBuilder()
                            .apply {
                                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                            }.build()
                    chain.proceed(authenticated)
                }.followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .authenticator(okhttp3.Authenticator.NONE)
                .build()
    }

    fun checkScope() {
        if (AuthManager.currentDataScope() != scope) throw IOException("Connection or profile changed; reopen voice")
    }

    suspend fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): JsonObject {
        require(path in GET_PATHS)
        val url =
            endpoint(path)
                .newBuilder()
                .apply {
                    query.forEach { (key, value) -> addQueryParameter(key, value) }
                }.build()
        return execute(
            Request
                .Builder()
                .url(url)
                .get()
                .build(),
        )
    }

    suspend fun post(
        path: String,
        body: JsonObject,
    ): JsonObject {
        require(path in POST_PATHS)
        return execute(
            Request
                .Builder()
                .url(endpoint(path))
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build(),
        )
    }

    private fun endpoint(path: String): HttpUrl = checkNotNull(base.resolve("api/plugins/hermes-talk$path"))

    private suspend fun execute(request: Request): JsonObject {
        checkScope()
        val result = client.newCall(request).jsonResult()
        checkScope()
        return result
    }

    companion object {
        private val GET_PATHS = setOf("/status", "/runs")
        private val POST_PATHS = setOf("/session", "/tool")
    }
}

/** Provider negotiation uses the external client: no dashboard cookies, certificates or Access headers. */
internal suspend fun negotiateRealtime(
    offerUrl: String,
    clientSecret: String,
    sdp: String,
): String {
    val url = offerUrl.toHttpUrl()
    require(
        url.scheme == "https" && url.host == "api.openai.com" && url.port == 443 &&
            url.encodedPath == "/v1/realtime/calls" && url.query == null && url.fragment == null &&
            url.username.isEmpty() && url.password.isEmpty(),
    ) { "Untrusted Realtime negotiation endpoint" }
    val request =
        Request
            .Builder()
            .url(url)
            .header("Authorization", "Bearer $clientSecret")
            .post(sdp.toRequestBody("application/sdp".toMediaType()))
            .build()
    return OkHttpProvider.external
        .newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
        .newCall(request)
        .boundedText()
}

private suspend fun Call.jsonResult(): JsonObject {
    val text = boundedText()
    val result = Json.parseToJsonElement(text) as? JsonObject ?: throw IOException("Invalid Talk response")
    if (!result.flag("ok")) throw IOException("Hermes Talk refused the operation")
    return result
}

private suspend fun Call.boundedText(): String =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IOException("Voice network request failed"),
                        )
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    try {
                        val text =
                            response.use {
                                if (!it.isSuccessful) {
                                    throw IOException(
                                        when (it.code) {
                                            401 -> "Voice authentication expired (401); sign in again"
                                            403 -> "Voice access denied (403); check Access and subscription"
                                            404 -> "Hermes Talk route unavailable (404); check plugin installation"
                                            409 -> "Voice session is stale (409); start a new Talk call"
                                            else -> "Voice request refused (HTTP ${it.code})"
                                        },
                                    )
                                }
                                val source = checkNotNull(it.body).source()
                                source.request(2L * 1024 * 1024 + 1)
                                if (source.buffer.size >
                                    2L * 1024 * 1024
                                ) {
                                    throw IOException("Voice response exceeded limit")
                                }
                                source.readUtf8()
                            }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            },
        )
    }
