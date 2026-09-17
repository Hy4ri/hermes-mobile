package com.m57.hermescontrol.ui.chat

import android.content.Context
import android.net.Uri
import com.m57.hermescontrol.data.remote.MediaDataSourceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.Base64

object MediaBytesResolver {
    sealed interface Result {
        data class Bytes(
            val bytes: ByteArray,
            val mimeType: String,
            val extension: String,
        ) : Result

        data class Error(
            val message: String,
        ) : Result
    }

    suspend fun resolve(
        context: Context,
        model: String,
        fallbackMime: String = "application/octet-stream",
    ): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                when {
                    model.startsWith("data:") -> decodeDataUrl(model, fallbackMime)
                    model.startsWith("content://") -> readContentUri(context, model, fallbackMime)
                    model.startsWith("file://") -> readFileUri(model, fallbackMime)
                    model.startsWith("/") -> readLocalFile(model, fallbackMime)
                    model.startsWith("https://") || model.startsWith("http://") -> fetchHttp(model, fallbackMime)
                    else -> Result.Error("Unsupported media source")
                }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                Result.Error("Failed to load media")
            }
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
                .substringAfter("data:", fallbackMime)
                .substringBefore(";")
                .ifBlank { fallbackMime }
        if (!meta.contains(";base64", ignoreCase = true)) {
            return Result.Error("Only base64 data URLs are supported")
        }
        val bytes =
            runCatching { Base64.getDecoder().decode(data) }
                .getOrElse { return Result.Error("Could not decode media data") }
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
                ?: return Result.Error("Could not open local media")
        val mime = context.contentResolver.getType(uri) ?: fallbackMime
        return Result.Bytes(bytes, mime, extensionForMime(mime))
    }

    private fun readFileUri(
        model: String,
        fallbackMime: String,
    ): Result {
        val file = File(Uri.parse(model).path ?: return Result.Error("Invalid file path"))
        if (!file.exists()) return Result.Error("File not found")
        val bytes = file.readBytes()
        val mime = mediaMimeForPath(file.name).takeUnless { it == "application/octet-stream" } ?: fallbackMime
        return Result.Bytes(bytes, mime, extensionForMime(mime))
    }

    private fun readLocalFile(
        model: String,
        fallbackMime: String,
    ): Result {
        val file = File(model)
        if (!file.exists()) return Result.Error("File not found")
        val bytes = file.readBytes()
        val mime = mediaMimeForPath(file.name).takeUnless { it == "application/octet-stream" } ?: fallbackMime
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
            MediaDataSourceProvider.mediaOkHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return Result.Error("Download failed (HTTP ${resp.code})")
                val bytes = resp.body.bytes()
                val headerMime =
                    resp
                        .header("Content-Type")
                        ?.substringBefore(";")
                        ?.trim()
                val mime =
                    if (!headerMime.isNullOrBlank() && headerMime != "application/octet-stream") {
                        headerMime
                    } else if (fallbackMime.isNotBlank() && fallbackMime != "application/octet-stream") {
                        fallbackMime
                    } else {
                        mediaMimeForPath(model)
                    }
                Result.Bytes(bytes, mime, extensionForMime(mime))
            }
        }.getOrElse { e -> Result.Error(e.message ?: "Could not download media") }
    }
}
