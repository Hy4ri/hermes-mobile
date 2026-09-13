package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
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
 * Handles basic credential login and WS ticket minting for in-chat reconnect/re-authentication.
 *
 * Extracted from [com.m57.hermescontrol.ui.chat.ChatViewModel] to isolate raw OkHttp network calls.
 */
class ChatReloginAuthenticator(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    suspend fun relogin(
        username: String,
        password: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        withContext(ioDispatcher) {
            val endpoint = AuthManager.endpointForBuild()
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            val jsonBody =
                JSONObject()
                    .put("provider", "basic")
                    .put("username", username)
                    .put("password", password)
                    .put("next", "")
                    .toString()

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
                        val msg =
                            when (loginResp.code) {
                                401 -> "Invalid username or password (401)"
                                403 -> "Forbidden (403)"
                                else -> "HTTP error code: ${loginResp.code}"
                            }
                        withContext(mainDispatcher) {
                            onFailure(msg)
                        }
                        return@withContext
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
                        withContext(mainDispatcher) {
                            onFailure("Failed to mint WS ticket: HTTP ${ticketResp.code}")
                        }
                        return@withContext
                    }

                    val body = ticketResp.body.string()
                    val ticket = JSONObject(body).optString("ticket").takeIf { it.isNotBlank() }

                    if (ticket.isNullOrBlank()) {
                        withContext(mainDispatcher) {
                            onFailure("Invalid ticket returned from server")
                        }
                        return@withContext
                    }

                    AuthManager.setWsAuthParam("ticket")
                    AuthManager.setToken(ticket)

                    withContext(mainDispatcher) {
                        onSuccess()
                    }
                }
            } catch (e: IOException) {
                withContext(mainDispatcher) {
                    onFailure("Connection failed: ${e.message}")
                }
            } catch (e: JSONException) {
                withContext(mainDispatcher) {
                    onFailure("Connection failed: ${e.message}")
                }
            }
        }
    }
}
