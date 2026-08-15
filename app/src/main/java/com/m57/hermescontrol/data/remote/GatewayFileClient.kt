package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext

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

    /** Downloads are reused for this long before a fresh fetch is made. */
    private const val CACHE_REUSE_TTL_MS = 10 * 60 * 1000L

    /** Path → content-type map from the last download of that path, so cache
     * hits can still hand the viewer the right type (the header is only known
     * when the file is actually fetched). */
    private val mimeByPath = ConcurrentHashMap<String, String>()

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
        // Gated (basic-auth) dashboards authenticate the download via the
        // session cookie in the shared CookieJar — a `?token=` query param is
        // not consulted (the WS ticket the app stores is meaningless here), so
        // don't stamp a bogus token into the URL. Loopback mode still needs it:
        // there the query token IS the auth (auth_middleware compares it to the
        // injected _SESSION_TOKEN).
        val authQuery =
            if (token.isNotBlank() && !AuthManager.isGatedMode()) {
                val encToken = URLEncoder.encode(token, StandardCharsets.UTF_8.name()).replace("+", "%20")
                "&token=$encToken"
            } else {
                ""
            }
        return "$trimmedBase$DOWNLOAD_PATH?path=$encPath$authQuery"
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

    /** Fetch a gateway-hosted file using the current [AuthManager] credentials,
     * streaming the response body straight to [cacheDir] so large files never
     * sit in the app heap (a ~100 MB download used to OOM: the whole body was
     * slurped into one [ByteArray] first). */
    suspend fun fetch(
        path: String,
        cacheDir: File,
    ): GatewayFileResult = fetch(path, AuthManager.getBaseUrl(), AuthManager.getToken().orEmpty(), cacheDir)

    /** Fetch a gateway-hosted file with explicit credentials (testable). */
    suspend fun fetch(
        path: String,
        baseUrl: String,
        token: String,
        cacheDir: File,
    ): GatewayFileResult {
        val url =
            buildDownloadUrl(baseUrl, token, path)
                ?: return GatewayFileResult.Failure(IllegalArgumentException("not an absolute gateway path: $path"))
        // Cache fast path: a recent download of this path is reused as-is, so
        // repeat opens skip the network entirely. Stale entries fall through
        // to a fresh fetch, which overwrites the cache file.
        val cacheName = cacheFileNameFor(path)
        val fresh = File(cacheDir, cacheName).takeIf { isFreshCacheFile(it) }
        if (fresh != null) {
            return GatewayFileResult.Success(
                GatewayFile(
                    name = fileNameFromPath(path),
                    mimeType = mimeByPath[path] ?: "application/octet-stream",
                    cacheFile = fresh,
                ),
            )
        }
        val call = OkHttpProvider.base.newCall(Request.Builder().url(url).build())
        val resp = call.execute()
        return try {
            classifyStatus(resp.code)?.let { return it }
            if (!resp.isSuccessful) {
                return GatewayFileResult.Failure(IOException("HTTP ${resp.code}"))
            }
            val name =
                resp.header("Content-Disposition")?.let { parseFilename(it) }
                    ?: fileNameFromPath(path)
            val mime = resp.header("Content-Type") ?: "application/octet-stream"
            val cacheFile =
                streamBodyToCache(resp, cacheDir, cacheName)
                    ?: return GatewayFileResult.Failure(IOException("failed to write $name to cache"))
            mimeByPath[path] = mime
            GatewayFileResult.Success(GatewayFile(name, mime, cacheFile))
        } catch (e: CancellationException) {
            // Never swallow cancellation — the caller's job was cancelled.
            throw e
        } catch (e: Throwable) {
            GatewayFileResult.Failure(e)
        } finally {
            resp.close()
        }
    }

    /** Deterministic cache filename for a gateway path: the same path always
     * maps to the same file, so repeat opens reuse the download instead of
     * re-fetching (a random name would orphan every previous copy). */
    internal fun cacheFileNameFor(path: String): String {
        val key = UUID.nameUUIDFromBytes(path.toByteArray(StandardCharsets.UTF_8)).toString().take(12)
        val safeName = fileNameFromPath(path).replace(Regex("[/\\\\]"), "_").ifBlank { "file" }
        return "$key-$safeName"
    }

    /** True when [file] is a regular file young enough to reuse (TTL). */
    internal fun isFreshCacheFile(
        file: File,
        now: Long = System.currentTimeMillis(),
    ): Boolean = file.isFile && now - file.lastModified() < CACHE_REUSE_TTL_MS

    /** Stream the response body into the deterministic [cacheName] file under
     * [cacheDir] in 64 KiB chunks (peak heap stays ~constant no matter the
     * file size), sweeping cache files older than a day first. The body is
     * written to a temp file and renamed into place only on success, so a
     * failed re-download never destroys a previously-good cache entry.
     * Cancellation-safe: the copy loop checks the calling coroutine's job,
     * and a cancelled fetch deletes the partial temp file. */
    private suspend fun streamBodyToCache(
        resp: okhttp3.Response,
        cacheDir: File,
        cacheName: String,
    ): File? {
        val body = resp.body ?: return null
        val dir = cacheDir.also { it.mkdirs() }
        sweepOldCacheFiles(dir)
        val target = File(dir, cacheName)
        val tmp = File(dir, ".$cacheName.tmp-${UUID.randomUUID().toString().take(8)}")
        return try {
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    copyChunked(input, output)
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
            target
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Throwable) {
            tmp.delete()
            null
        }
    }

    /** Copy [input] to [output] in 64 KiB chunks so peak heap stays constant
     * regardless of file size, aborting if the calling coroutine is cancelled
     * (used by both the download stream and save-to-document). */
    internal suspend fun copyChunked(
        input: java.io.InputStream,
        output: java.io.OutputStream,
    ) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    /** Best-effort cleanup of gateway cache files older than a day (the cache
     * dir is evictable by the OS anyway; this keeps it from growing forever). */
    private fun sweepOldCacheFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
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

/** A file fetched from the gateway, already streamed to disk under
 * [cacheFile] (never held in memory — see [GatewayFileClient.fetch]). */
data class GatewayFile(
    val name: String,
    val mimeType: String,
    val cacheFile: File,
)

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
