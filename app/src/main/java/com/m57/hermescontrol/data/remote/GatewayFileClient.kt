package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Client for the gateway's managed-files download endpoint
 * (`GET /api/files/download?path=<enc>&token=<enc>`), which streams the raw
 * bytes of any file that lives on the *gateway* host (images, audio, video,
 * CSV, PDF, arbitrary attachments).
 *
 * This is the mobile equivalent of the desktop app's
 * `mediaExternalUrl()` (`apps/desktop/src/lib/media.ts`): a gateway-local
 * path is rewritten into an authenticated URL the client can fetch over HTTP.
 * Unlike a host-local file read it works on a **remote phone** too.
 *
 * The endpoint is gated by the same gateway session token (passed as the
 * `?token=` query param, exactly like the WebSocket auth), and server-side
 * guards apply: path resolution (`_resolve_managed_path`), a sensitive-path
 * denylist (403), and a size cap (`_MANAGED_FILE_MAX_BYTES`, 413).
 *
 * Mobile-only, backend untouched.
 */
object GatewayFileClient {
    private const val DOWNLOAD_PATH = "/api/files/download"

    /**
     * Pure builder for the authenticated download URL.
     *
     * @return the full URL, or `null` if [baseUrl]/[token] are blank or
     * [path] is not an absolute (or `~/`) host path.
     */
    fun buildDownloadUrl(
        baseUrl: String,
        token: String,
        path: String,
    ): String? {
        val trimmedBase = baseUrl.trimEnd('/')
        if (trimmedBase.isBlank()) return null
        val norm = normalizePath(path) ?: return null
        val encPath = URLEncoder.encode(norm, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return if (token.isNotBlank()) {
            val encToken = URLEncoder.encode(token, StandardCharsets.UTF_8.name()).replace("+", "%20")
            "$trimmedBase$DOWNLOAD_PATH?path=$encPath&token=$encToken"
        } else {
            "$trimmedBase$DOWNLOAD_PATH?path=$encPath"
        }
    }

    /**
     * Strip surrounding quotes/backticks, expand a leading `~`, and require an
     * absolute path (`/...` or `X:\...`/`X:/...`). Returns `null` for paths
     * that are not resolvable on the gateway host.
     */
    internal fun normalizePath(raw: String): String? {
        val trimmed =
            raw
                .trim()
                .removeSurrounding("`")
                .removeSurrounding("\"")
                .removeSurrounding("'")
        val expanded =
            if (trimmed.startsWith("~")) {
                val home = System.getenv("HOME") ?: return null
                home + trimmed.removePrefix("~")
            } else {
                trimmed
            }
        if (!expanded.startsWith("/") &&
            !Pattern.compile("^[A-Za-z]:[/\\\\]").matcher(expanded).find()
        ) {
            return null
        }
        return expanded
    }

    /** Map an HTTP status to a non-success result; `null` means "let the
     * caller treat the body as a successful file." */
    internal fun classifyStatus(code: Int): GatewayFileResult? =
        when (code) {
            401 -> GatewayFileResult.Unauthorized
            403 -> GatewayFileResult.Forbidden
            404 -> GatewayFileResult.NotFound
            413 -> GatewayFileResult.TooLarge
            else -> null
        }

    /** Fetch a gateway-hosted file using the current [AuthManager] credentials. */
    suspend fun fetch(path: String): GatewayFileResult =
        fetch(path, AuthManager.getBaseUrl(), AuthManager.getToken().orEmpty())

    /** Fetch a gateway-hosted file with explicit credentials (testable). */
    suspend fun fetch(
        path: String,
        baseUrl: String,
        token: String,
    ): GatewayFileResult {
        val url =
            buildDownloadUrl(baseUrl, token, path)
                ?: return GatewayFileResult.Failure(IllegalArgumentException("not an absolute gateway path: $path"))
        return try {
            val request = Request.Builder().url(url).build()
            OkHttpProvider.base.newCall(request).execute().use { resp ->
                classifyStatus(resp.code)?.let { return it }
                if (!resp.isSuccessful) {
                    return GatewayFileResult.Failure(IOException("HTTP ${resp.code}"))
                }
                val body = resp.body.bytes()
                val name =
                    resp.header("Content-Disposition")?.let { parseFilename(it) }
                        ?: fileNameFromPath(path)
                val mime = resp.header("Content-Type") ?: "application/octet-stream"
                GatewayFileResult.Success(GatewayFile(name, mime, body))
            }
        } catch (e: Throwable) {
            GatewayFileResult.Failure(e)
        }
    }

    /** Best-effort filename pull from a `Content-Disposition: ...; filename="x"`
     * or `filename*=UTF-8''<pct-enc>` header. The captured value is URL-decoded
     * so `filename*=UTF-8''a%20b.png` yields `a b.png`. */
    internal fun parseFilename(header: String): String? {
        val m = FILENAME_RE.find(header) ?: return null
        val raw = m.groupValues[1].takeIf { it.isNotBlank() } ?: return null
        return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)
    }

    private fun fileNameFromPath(path: String): String =
        path
            .split('/', '\\')
            .lastOrNull()
            ?.takeIf { it.isNotBlank() } ?: "file"

    private val FILENAME_RE = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
}

/** A file fetched from the gateway. */
data class GatewayFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GatewayFile) return false
        return name == other.name && mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var r = name.hashCode()
        r = 31 * r + mimeType.hashCode()
        r = 31 * r + bytes.contentHashCode()
        return r
    }
}

/** Outcome of [GatewayFileClient.fetch]. */
sealed interface GatewayFileResult {
    data class Success(
        val file: GatewayFile,
    ) : GatewayFileResult

    data object NotFound : GatewayFileResult // 404 — file missing on gateway

    data object Forbidden : GatewayFileResult // 403 — sensitive path denied

    data object TooLarge : GatewayFileResult // 413 — exceeds managed-file cap

    data object Unauthorized : GatewayFileResult // 401 — bad/expired token

    data class Failure(
        val throwable: Throwable,
    ) : GatewayFileResult // network / unexpected
}
