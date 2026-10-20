package com.m57.hermescontrol.ui.authlogin

import android.content.Context
import android.util.Log
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.LoopbackCallbackServer
import com.m57.hermescontrol.data.remote.NativeCallback
import com.m57.hermescontrol.data.remote.NativePkce
import com.m57.hermescontrol.data.remote.NativeTokenResult
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.await
import com.m57.hermescontrol.data.remote.nativeAuthorizeUrl
import com.m57.hermescontrol.data.remote.redeemNativeCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/*
 * Gateway sign-in pieces shared by the login screen and the in-chat re-login dialog (issue #1641):
 * both must offer the same providers and run the same browser flow for one gateway.
 */

private const val TAG = "GatewayAuth"

/** The gateway keeps a pending native login for 10 minutes. */
const val BROWSER_LOGIN_TIMEOUT_MS = 10 * 60 * 1000L

/** How a dashboard wants clients to sign in, from `GET /api/status` (+ `GET /api/auth/providers`). */
data class GatewayAuthInfo(
    val authRequired: Boolean,
    /** Interactive sign-in options; empty when the gate is down. */
    val providers: List<AuthProviderOption>,
    /** False when the gateway predates the native (browser) login flow. */
    val browserSupported: Boolean,
)

/**
 * Read the dashboard's sign-in options. Returns null for a non-success or unparsable status
 * response. Network failures propagate so the caller can recognize client-certificate failures.
 */
suspend fun probeGatewayAuth(
    client: OkHttpClient,
    endpoint: ServerEndpoint,
): GatewayAuthInfo? {
    val request =
        Request
            .Builder()
            .url(endpoint.resolve("api/status").toString())
            .get()
            .build()
    val statusJson =
        client.newCall(request).await().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body.string()
        }

    val authRequired: Boolean
    val providerNames: List<String>
    val authFlows: Set<String>
    try {
        val node = OkHttpProvider.json.parseToJsonElement(statusJson).jsonObject
        authRequired = node["auth_required"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        providerNames =
            node["auth_providers"]
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content }
                .orEmpty()
        authFlows = parseAuthFlows(node["auth_flows"]?.jsonArray)
    } catch (e: Exception) {
        Log.w(TAG, "Status parse failed: ${e.message}")
        return null
    }

    if (!authRequired) return GatewayAuthInfo(authRequired = false, providers = emptyList(), browserSupported = false)

    // The dedicated endpoint says which providers take a password; the bare status names are only
    // a fallback for gateways that lack it.
    val providers = fetchAuthProviders(client, endpoint) ?: providersFromStatusNames(providerNames)
    return GatewayAuthInfo(
        authRequired = true,
        providers = providers,
        browserSupported = "native_pkce" in authFlows,
    )
}

private suspend fun fetchAuthProviders(
    client: OkHttpClient,
    endpoint: ServerEndpoint,
): List<AuthProviderOption>? =
    try {
        val req =
            Request
                .Builder()
                .url(endpoint.resolve("api/auth/providers").toString())
                .get()
                .build()
        client.newCall(req).await().use { resp ->
            if (resp.isSuccessful) parseAuthProviders(resp.body.string()) else null
        }
    } catch (e: IOException) {
        Log.w(TAG, "Provider list fetch failed: ${e.message}")
        null
    }

/** The provider to preselect: the one the last session used, else the first offered. */
fun preferredProvider(
    providers: List<AuthProviderOption>,
    lastUsed: String?,
): AuthProviderOption? = providers.firstOrNull { it.name == lastUsed } ?: providers.firstOrNull()

sealed interface WsTicketResult {
    data class Success(
        val ticket: String,
    ) : WsTicketResult

    data class HttpError(
        val code: Int,
    ) : WsTicketResult

    data object Missing : WsTicketResult
}

/** Mint a WebSocket ticket from the session cookie already in the shared jar. */
suspend fun mintWsTicket(
    client: OkHttpClient,
    endpoint: ServerEndpoint,
): WsTicketResult {
    val ticketReq =
        Request
            .Builder()
            .url(endpoint.resolve("api/auth/ws-ticket").toString())
            .post("{}".toRequestBody())
            .build()
    client.newCall(ticketReq).await().use { resp ->
        if (!resp.isSuccessful) return WsTicketResult.HttpError(resp.code)
        val ticket =
            Regex(""""ticket":"([^"]+)"""")
                .find(resp.body.string())
                ?.groupValues
                ?.getOrNull(1)
        return if (ticket.isNullOrBlank()) WsTicketResult.Missing else WsTicketResult.Success(ticket)
    }
}

sealed interface BrowserLoginResult {
    data class Success(
        val wsTicket: String,
    ) : BrowserLoginResult

    /** The loopback listener closed (the login was cancelled); not an error. */
    data object Cancelled : BrowserLoginResult

    data class ConnectionFailed(
        val detail: String,
    ) : BrowserLoginResult

    data object NoBrowser : BrowserLoginResult

    data class Denied(
        val error: String,
    ) : BrowserLoginResult

    data object TimedOut : BrowserLoginResult

    /** The token redemption or the ticket mint returned an HTTP error. */
    data class HttpError(
        val code: Int,
    ) : BrowserLoginResult

    data object BadResponse : BrowserLoginResult

    data object TicketMissing : BrowserLoginResult
}

/**
 * Sign in through the system browser (RFC 8252: loopback redirect + PKCE) against [provider].
 * [openBrowser] receives the authorize URL and must show it; it may throw when no browser exists.
 * On success the session cookies are installed in the shared jar and a WS ticket is returned.
 * Blocking: call from an IO context.
 */
suspend fun runNativeBrowserLogin(
    client: OkHttpClient,
    endpoint: ServerEndpoint,
    provider: String,
    openBrowser: (String) -> Unit,
    timeoutMs: Long = BROWSER_LOGIN_TIMEOUT_MS,
): BrowserLoginResult {
    val verifier = NativePkce.randomUrlSafe()
    val clientState = NativePkce.randomUrlSafe()
    val server =
        try {
            LoopbackCallbackServer.open()
        } catch (e: IOException) {
            return BrowserLoginResult.ConnectionFailed(e.message.orEmpty())
        }
    return server.use {
        val authorizeUrl =
            nativeAuthorizeUrl(
                endpoint = endpoint,
                provider = provider,
                codeChallenge = NativePkce.challengeFor(verifier),
                redirectUri = server.redirectUri,
                state = clientState,
            )
        try {
            openBrowser(authorizeUrl.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Could not open browser: ${e.javaClass.simpleName}")
            return@use BrowserLoginResult.NoBrowser
        }
        when (val callback = server.awaitCallback(clientState, timeoutMs)) {
            is NativeCallback.Code -> redeemBrowserCode(client, endpoint, callback.code, verifier)
            is NativeCallback.Denied -> BrowserLoginResult.Denied(callback.error)
            NativeCallback.TimedOut -> BrowserLoginResult.TimedOut
            NativeCallback.Closed -> BrowserLoginResult.Cancelled
        }
    }
}

private suspend fun redeemBrowserCode(
    client: OkHttpClient,
    endpoint: ServerEndpoint,
    code: String,
    verifier: String,
): BrowserLoginResult {
    val result =
        try {
            redeemNativeCode(client, endpoint, code, verifier)
        } catch (e: IOException) {
            return BrowserLoginResult.ConnectionFailed(e.message.orEmpty())
        }
    return when (result) {
        is NativeTokenResult.Success -> {
            CookieManager.installNativeSession(result.tokens, endpoint)
            val ticket =
                try {
                    mintWsTicket(client, endpoint)
                } catch (e: IOException) {
                    CookieManager.setSessionCookie(null, endpoint)
                    return BrowserLoginResult.ConnectionFailed(e.message.orEmpty())
                }
            when (ticket) {
                is WsTicketResult.Success -> {
                    BrowserLoginResult.Success(ticket.ticket)
                }

                is WsTicketResult.HttpError -> {
                    CookieManager.setSessionCookie(null, endpoint)
                    BrowserLoginResult.HttpError(ticket.code)
                }

                WsTicketResult.Missing -> {
                    CookieManager.setSessionCookie(null, endpoint)
                    BrowserLoginResult.TicketMissing
                }
            }
        }

        is NativeTokenResult.HttpError -> {
            BrowserLoginResult.HttpError(result.code)
        }

        NativeTokenResult.Malformed -> {
            BrowserLoginResult.BadResponse
        }
    }
}

/** User-facing text for a failed browser sign-in; null for success and cancellation. */
fun browserLoginErrorText(
    context: Context,
    result: BrowserLoginResult,
): String? =
    when (result) {
        is BrowserLoginResult.Success, BrowserLoginResult.Cancelled -> {
            null
        }

        is BrowserLoginResult.ConnectionFailed -> {
            context.getString(
                R.string.connect_error_connection_failed,
                result.detail,
            )
        }

        BrowserLoginResult.NoBrowser -> {
            context.getString(R.string.auth_login_error_browser_no_browser)
        }

        is BrowserLoginResult.Denied -> {
            context.getString(R.string.auth_login_error_browser_denied, result.error)
        }

        BrowserLoginResult.TimedOut -> {
            context.getString(R.string.auth_login_error_browser_timeout)
        }

        is BrowserLoginResult.HttpError -> {
            context.getString(R.string.connect_error_http_code, result.code)
        }

        BrowserLoginResult.BadResponse -> {
            context.getString(R.string.auth_login_error_browser_bad_response)
        }

        BrowserLoginResult.TicketMissing -> {
            "Failed to obtain WebSocket ticket"
        }
    }
