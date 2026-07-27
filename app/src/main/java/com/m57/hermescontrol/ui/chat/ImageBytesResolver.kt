package com.m57.hermescontrol.ui.chat

import android.content.Context
import android.net.Uri
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.Base64

/**
 * Resolve a chat image [ImageViewerModel.model] into raw bytes so the viewer can
 * Save/Share it *locally on the device* — never back to the Hermes server
 * (issue #723).
 *
 * Every model kind handled here is exactly what Coil already renders in the
 * bubble, so a model that displays is also resolvable here:
 * - `data:image/...;base64,...` — agent-delivered inline media,
 * - `content://...` — a locally-picked user image,
 * - `http(s)://...` — gateway-served URL (carries the `?token=` auth param the
 *   same way the app fetches it; no backend change needed).
 *
 * All I/O runs on [Dispatchers.IO].
 */
object ImageBytesResolver {
    /** Outcome of [resolve]. */
    sealed interface Result {
        /** Resolved bytes plus the concrete MIME type and file extension. */
        data class Bytes(
            val bytes: ByteArray,
            val mimeType: String,
            val extension: String,
        ) : Result

        /** Could not load the image; [message] is safe to surface to the user. */
        data class Error(
            val message: String,
        ) : Result
    }

    suspend fun resolve(
        context: Context,
        model: String,
        fallbackMime: String,
    ): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                when {
                    model.startsWith("data:") -> decodeDataUrl(model, fallbackMime)
                    model.startsWith("content://") -> readContentUri(context, model, fallbackMime)
                    model.startsWith("https://") || model.startsWith("http://") -> fetchHttp(model, fallbackMime)
                    else -> Result.Error("Unsupported image source")
                }
            }.getOrElse { e -> Result.Error(e.message ?: "Failed to load image") }
        }

    private fun decodeDataUrl(
        model: String,
        fallbackMime: String,
    ): Result {
        val comma = model.indexOf(',')
        if (comma < 0) return Result.Error("Malformed data URL")
        val meta = model.substring(0, comma)
        val data = model.substring(comma + 1).replace(Regex("\\s+"), "")
        val mime =
            meta
                .substringAfter("data:", "image/*")
                .substringBefore(";")
                .ifBlank { fallbackMime }
        if (!meta.contains(";base64", ignoreCase = true)) {
            return Result.Error("Only base64 data URLs are supported")
        }
        val bytes =
            runCatching { Base64.getDecoder().decode(data) }
                .getOrElse { return Result.Error("Could not decode image data") }
        return Result.Bytes(bytes, mime, extensionForMime(mime))
    }

    private fun readContentUri(
        context: Context,
        model: String,
        fallbackMime: String,
    ): Result {
        val uri = Uri.parse(model)
        val bytes =
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                .getOrNull()
                ?: return Result.Error("Could not open local image")
        val mime = context.contentResolver.getType(uri) ?: fallbackMime
        return Result.Bytes(bytes, mime, extensionForMime(mime))
    }

    private fun fetchHttp(
        model: String,
        fallbackMime: String,
    ): Result {
        val request =
            Request
                .Builder()
                .url(model)
                .build()
        return runCatching {
            OkHttpProvider.base.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return Result.Error("Download failed (HTTP ${resp.code})")
                val bytes = resp.body.bytes()
                val mime =
                    resp
                        .header("Content-Type")
                        ?.substringBefore(";")
                        ?.ifBlank { fallbackMime }
                        ?: fallbackMime
                Result.Bytes(bytes, mime, extensionForMime(mime))
            }
        }.getOrElse { e -> Result.Error(e.message ?: "Could not download image") }
    }

    fun extensionForMime(mime: String): String =
        when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            "image/heic", "image/heif" -> "heic"
            "image/svg+xml" -> "svg"
            else -> "img"
        }
}
