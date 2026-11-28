package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ServerEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Request
import java.io.IOException

/** Captured credentials never follow a later connection/profile selection. */
internal class GatewaySpeechConnection private constructor(
    private val scope: DataScope,
    private val token: String?,
    private val gated: Boolean,
    private val cookies: List<Cookie>,
) {
    private val endpoint = ServerEndpoint.parseForBuild(scope.baseUrl)

    fun service(timeoutMs: Long): HermesApiService {
        checkCurrent()
        return ApiClient.createScopedService(
            scope,
            token,
            cookies,
            requestTimeoutMs = timeoutMs,
            authenticator = com.m57.hermescontrol.data.remote.TokenRefreshAuthenticator,
        )
    }

    private fun checkCurrent() {
        if (AuthManager.currentDataScope() != scope) throw IOException("Speech connection changed")
    }

    private suspend fun streamRequest(): Request {
        val credential =
            if (gated) {
                // Native Android's fresh, unnamed ticket is accepted by the audio socket.
                // Browser /api/webapp/socket-ticket is unavailable on some gateway surfaces.
                val response = service(10_000L).mintSpeechTicket()
                if (!response.isSuccessful) throw IOException("Speech ticket request failed")
                response.body()?.ticket?.takeIf { it.isNotBlank() } ?: throw IOException("Missing speech ticket")
            } else {
                token.orEmpty()
            }
        checkCurrent()
        return Request
            .Builder()
            .url(
                endpoint.webSocketUrl(
                    path = "api/audio/speak-stream",
                    authParameter = if (gated) "ticket" else "token",
                    credential = credential,
                    extraParams = mapOf("profile" to scope.activeProfileId),
                ),
            ).build()
    }

    companion object {
        fun capture(scope: DataScope): GatewaySpeechConnection {
            if (AuthManager.currentDataScope() != scope) throw IOException("Speech connection changed")
            val endpoint = ServerEndpoint.parseForBuild(scope.baseUrl)
            val gated = AuthManager.isGatedMode()
            val token = if (gated) null else AuthManager.getToken()
            val cookies = CookieManager.cookieJar.loadForRequest(endpoint.baseUrl).toList()
            if (AuthManager.currentDataScope() != scope) throw IOException("Speech connection changed")
            return GatewaySpeechConnection(scope, token, gated, cookies)
        }

        fun frames(
            scope: DataScope,
            text: String,
        ): Flow<SpeechStreamFrame> =
            flow {
                val connection = capture(scope)
                val client =
                    OkHttpProvider.websocket
                        .newBuilder()
                        // The ticket/token owns the handshake, not whichever cookie store is selected later.
                        .cookieJar(CookieJar.NO_COOKIES)
                        .build()
                emitAll(SpeechStreamClient(client, connection::streamRequest).frames(text))
            }
    }
}
