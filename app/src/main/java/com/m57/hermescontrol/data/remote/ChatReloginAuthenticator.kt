package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.ui.authlogin.BrowserLoginResult
import com.m57.hermescontrol.ui.authlogin.GatewayAuthInfo
import com.m57.hermescontrol.ui.authlogin.probeGatewayAuth
import com.m57.hermescontrol.ui.authlogin.runNativeBrowserLogin
import com.m57.hermescontrol.ui.chat.ReloginBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Network side of the in-chat re-authentication dialog: reads the gateway's sign-in options and
 * signs in again with the provider the user picks (password form or system browser, issue #1641).
 *
 * Extracted from [com.m57.hermescontrol.ui.chat.ChatViewModel] to isolate raw OkHttp network calls.
 */
class ChatReloginAuthenticator(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReloginBackend {
    /** The active gateway's sign-in options, or null when it is unreachable. */
    override suspend fun probe(): GatewayAuthInfo? =
        withContext(ioDispatcher) {
            try {
                probeGatewayAuth(OkHttpProvider.probe, AuthManager.endpointForBuild())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /** The provider the expired session used, when the gateway left its routing hint. */
    override suspend fun lastProvider(): String? = withContext(ioDispatcher) { CookieManager.getSessionProvider() }

    /** Sign in through the system browser; on success the new WS ticket is installed. */
    override suspend fun browserLogin(
        provider: String,
        openBrowser: (String) -> Unit,
    ): BrowserLoginResult =
        withContext(ioDispatcher) {
            val result =
                runNativeBrowserLogin(OkHttpProvider.probe, AuthManager.endpointForBuild(), provider, openBrowser)
            if (result is BrowserLoginResult.Success) {
                AuthManager.setWsAuthParam("ticket")
                AuthManager.setToken(result.wsTicket)
            }
            result
        }

    /**
     * Password login against [provider], then mint a WS ticket. Returns null on success, else a
     * failure message.
     */
    override suspend fun passwordLogin(
        username: String,
        password: String,
        provider: String,
    ): String? =
        withContext(ioDispatcher) {
            val endpoint = AuthManager.endpointForBuild()
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            val jsonBody = AuthPayloads.passwordLogin(username, password, provider)

            try {
                val loginClient =
                    OkHttpProvider.probe
                        .newBuilder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()

                val loginReq =
                    Request
                        .Builder()
                        .url(endpoint.resolve("auth/password-login").toString())
                        .header("Content-Type", "application/json")
                        .post(jsonBody.toRequestBody(jsonMediaType))
                        .build()

                loginClient.newCall(loginReq).execute().use { loginResp ->
                    if (!loginResp.isSuccessful) {
                        return@withContext when (loginResp.code) {
                            401 -> "Invalid username or password (401)"
                            403 -> "Forbidden (403)"
                            else -> "HTTP error code: ${loginResp.code}"
                        }
                    }
                }

                val ticketClient =
                    OkHttpProvider.base
                        .newBuilder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()

                val ticketReq =
                    Request
                        .Builder()
                        .url(endpoint.resolve("api/auth/ws-ticket").toString())
                        .post("{}".toRequestBody(jsonMediaType))
                        .build()

                ticketClient.newCall(ticketReq).execute().use { ticketResp ->
                    if (!ticketResp.isSuccessful) {
                        return@withContext "Failed to mint WS ticket: HTTP ${ticketResp.code}"
                    }

                    val body = ticketResp.body.string()
                    val ticket = JSONObject(body).optString("ticket").takeIf { it.isNotBlank() }
                    if (ticket.isNullOrBlank()) return@withContext "Invalid ticket returned from server"

                    AuthManager.setWsAuthParam("ticket")
                    AuthManager.setToken(ticket)
                    null
                }
            } catch (e: IOException) {
                "Connection failed: ${e.message}"
            } catch (e: JSONException) {
                "Connection failed: ${e.message}"
            }
        }
}
