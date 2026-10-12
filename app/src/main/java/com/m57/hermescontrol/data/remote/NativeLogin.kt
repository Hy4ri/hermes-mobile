package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * Client side of the dashboard's RFC 8252 native-app login (system browser +
 * loopback redirect + PKCE). The gateway brokers every interactive provider
 * (OIDC, Nous Portal, password) behind `/auth/native/authorize`, so one flow
 * covers them all; the result is bearer tokens we then install as the same
 * session cookies the password login produces.
 */
object NativePkce {
    private val random = SecureRandom()

    /** 256 bits of base64url randomness — valid as a PKCE verifier (43 chars) and as `state`. */
    fun randomUrlSafe(): String = encode(ByteArray(32).also(random::nextBytes))

    /** RFC 7636 S256: base64url(sha256(ascii(verifier))), no padding. */
    fun challengeFor(verifier: String): String =
        encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Build the browser URL that starts a native login for [provider] on [endpoint]. */
fun nativeAuthorizeUrl(
    endpoint: ServerEndpoint,
    provider: String,
    codeChallenge: String,
    redirectUri: String,
    state: String,
): HttpUrl =
    endpoint
        .resolve("auth/native/authorize")
        .newBuilder()
        .addQueryParameter("provider", provider)
        .addQueryParameter("code_challenge", codeChallenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("state", state)
        .build()

sealed interface NativeCallback {
    data class Code(
        val code: String,
    ) : NativeCallback

    /** The gateway or IdP redirected back with an `error` instead of a code. */
    data class Denied(
        val error: String,
    ) : NativeCallback

    data object TimedOut : NativeCallback

    data object Closed : NativeCallback
}

/**
 * One-shot HTTP listener on the loopback interface. The gateway only accepts
 * `http://127.0.0.1:<port>/...` redirect URIs (IP literal, never `localhost`).
 * Requests with a wrong `state`, another path, or no result (favicon, port
 * scanners) are answered and ignored so a stray local connection cannot abort
 * or hijack a login in progress: the code is useless without our PKCE verifier.
 */
class LoopbackCallbackServer private constructor(
    private val socket: ServerSocket,
) : Closeable {
    val redirectUri: String get() = "http://127.0.0.1:${socket.localPort}$CALLBACK_PATH"

    suspend fun awaitCallback(
        expectedState: String,
        timeoutMs: Long,
    ): NativeCallback =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { close() }
            thread(isDaemon = true, name = "native-login-loopback") {
                val result =
                    try {
                        blockingAwait(expectedState, System.currentTimeMillis() + timeoutMs)
                    } catch (_: IOException) {
                        NativeCallback.Closed
                    }
                if (continuation.isActive) continuation.resume(result)
            }
        }

    private fun blockingAwait(
        expectedState: String,
        deadline: Long,
    ): NativeCallback {
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return NativeCallback.TimedOut
            socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val client =
                try {
                    socket.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    return NativeCallback.TimedOut
                }
            val result = client.use { handle(it, expectedState) }
            if (result != null) return result
        }
    }

    private fun handle(
        client: Socket,
        expectedState: String,
    ): NativeCallback? {
        client.soTimeout = CLIENT_READ_TIMEOUT_MS
        val requestLine =
            try {
                client.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
            } catch (_: IOException) {
                null
            }
        val url = parseRequestUrl(requestLine)
        if (url == null || url.encodedPath != CALLBACK_PATH || url.queryParameter("state") != expectedState) {
            respond(client, "404 Not Found", "Not found.")
            return null
        }
        val error = url.queryParameter("error")
        val code = url.queryParameter("code")
        return when {
            !error.isNullOrBlank() -> {
                respond(client, "200 OK", "Sign-in failed. You can close this tab and try again in Hermes Mobile.")
                NativeCallback.Denied(error)
            }

            !code.isNullOrBlank() -> {
                respond(client, "200 OK", "Signed in. You can close this tab and return to Hermes Mobile.")
                NativeCallback.Code(code)
            }

            else -> {
                respond(client, "400 Bad Request", "Missing authorization code.")
                null
            }
        }
    }

    private fun respond(
        client: Socket,
        status: String,
        message: String,
    ) {
        val body =
            "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>Hermes Mobile</title></head>" +
                "<body style=\"font-family:sans-serif;padding:2em\"><p>$message</p></body></html>"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head =
            "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        runCatching {
            client.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val CALLBACK_PATH = "/callback"
        private const val CLIENT_READ_TIMEOUT_MS = 5_000

        fun open(): LoopbackCallbackServer =
            LoopbackCallbackServer(ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")))

        /** `GET /callback?x=y HTTP/1.1` -> parsed URL, or null for anything else. */
        internal fun parseRequestUrl(requestLine: String?): HttpUrl? {
            val parts = requestLine?.split(' ') ?: return null
            if (parts.size < 2 || parts[0] != "GET" || !parts[1].startsWith("/")) return null
            return "http://127.0.0.1${parts[1]}".toHttpUrlOrNull()
        }
    }
}

/** Bearer tokens issued by `POST /auth/native/token`. */
class NativeTokens(
    val accessToken: String,
    val refreshToken: String,
    val provider: String,
) {
    override fun toString(): String = "NativeTokens(provider=$provider)"
}

sealed interface NativeTokenResult {
    data class Success(
        val tokens: NativeTokens,
    ) : NativeTokenResult

    data class HttpError(
        val code: Int,
    ) : NativeTokenResult

    data object Malformed : NativeTokenResult
}

/** Redeem the one-time loopback [code] with its PKCE [verifier]. Network errors propagate as [IOException]. */
suspend fun redeemNativeCode(
    client: OkHttpClient,
    endpoint: ServerEndpoint,
    code: String,
    verifier: String,
): NativeTokenResult {
    val body =
        JsonObject(mapOf("code" to JsonPrimitive(code), "code_verifier" to JsonPrimitive(verifier))).toString()
    val request =
        Request
            .Builder()
            .url(endpoint.resolve("auth/native/token"))
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    client.newCall(request).await().use { response ->
        if (!response.isSuccessful) return NativeTokenResult.HttpError(response.code)
        return parseNativeTokens(response.body.string())
    }
}

internal fun parseNativeTokens(json: String): NativeTokenResult {
    val node =
        runCatching { OkHttpProvider.json.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return NativeTokenResult.Malformed

    fun field(name: String) =
        node[name]
            ?.jsonPrimitive
            ?.takeIf { it.isString }
            ?.content
            .orEmpty()
    val access = field("access_token")
    if (access.isBlank()) return NativeTokenResult.Malformed
    return NativeTokenResult.Success(NativeTokens(access, field("refresh_token"), field("provider")))
}
