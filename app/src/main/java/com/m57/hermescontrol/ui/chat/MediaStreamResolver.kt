package com.m57.hermescontrol.ui.chat

import android.content.Context
import android.net.Uri
import com.m57.hermescontrol.data.remote.MediaDataSourceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Base64

object MediaStreamResolver {
    data class StreamInfo(
        val mimeType: String,
        val extension: String,
        val length: Long?,
    )

    /**
     * Resolves the media source identified by [uriString] and streams its content
     * into [consumer]. Guarantees that all underlying streams and HTTP response bodies
     * are strictly closed via [use], and that no whole-media buffering occurs.
     */
    suspend fun <T> useStream(
        context: Context,
        uriString: String,
        fallbackMime: String = "application/octet-stream",
        consumer: suspend (InputStream, StreamInfo) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            try {
                when {
                    uriString.startsWith("data:", ignoreCase = true) -> {
                        openDataUrlStream(uriString, fallbackMime, consumer)
                    }

                    uriString.startsWith("content://", ignoreCase = true) -> {
                        openContentStream(context, uriString, fallbackMime, consumer)
                    }

                    uriString.startsWith("file://", ignoreCase = true) -> {
                        openFileUriStream(uriString, fallbackMime, consumer)
                    }

                    uriString.startsWith("/") -> {
                        openLocalFileStream(uriString, fallbackMime, consumer)
                    }

                    uriString.startsWith("https://", ignoreCase = true) ||
                        uriString.startsWith("http://", ignoreCase = true) -> {
                        openHttpStream(uriString, fallbackMime, consumer)
                    }

                    else -> {
                        throw IOException("Unsupported media source: $uriString")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            }
        }

    private suspend fun <T> openDataUrlStream(
        dataUrl: String,
        fallbackMime: String,
        consumer: suspend (InputStream, StreamInfo) -> T,
    ): T {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) throw IOException("Malformed data URL")
        val meta = dataUrl.substring(0, comma)
        val mime =
            meta
                .substringAfter("data:", fallbackMime)
                .substringBefore(";")
                .ifBlank { fallbackMime }
        if (!meta.contains(";base64", ignoreCase = true)) {
            throw IOException("Only base64 data URLs are supported")
        }
        val rawBase64 = dataUrl.substring(comma + 1)
        val rawStream =
            object : InputStream() {
                private var index = 0

                override fun read(): Int {
                    while (index < rawBase64.length) {
                        val c = rawBase64[index++]
                        if (!c.isWhitespace()) {
                            return c.code and 0xFF
                        }
                    }
                    return -1
                }
            }
        val decodingStream = Base64.getDecoder().wrap(rawStream)
        return decodingStream.use { stream ->
            consumer(
                stream,
                StreamInfo(
                    mimeType = mime,
                    extension = extensionForMime(mime),
                    length = null,
                ),
            )
        }
    }

    private suspend fun <T> openContentStream(
        context: Context,
        uriString: String,
        fallbackMime: String,
        consumer: suspend (InputStream, StreamInfo) -> T,
    ): T {
        val uri = Uri.parse(uriString)
        val mime = context.contentResolver.getType(uri) ?: fallbackMime
        val length =
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    pfd.statSize.takeIf { it >= 0 }
                }
            }.getOrNull()
        val stream =
            context.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open content URI: $uriString")
        return stream.use { inputStream ->
            consumer(
                inputStream,
                StreamInfo(
                    mimeType = mime,
                    extension = extensionForMime(mime),
                    length = length,
                ),
            )
        }
    }

    private suspend fun <T> openFileUriStream(
        uriString: String,
        fallbackMime: String,
        consumer: suspend (InputStream, StreamInfo) -> T,
    ): T {
        val file = File(Uri.parse(uriString).path ?: throw IOException("Invalid file URI: $uriString"))
        if (!file.exists()) throw FileNotFoundException("File not found: ${file.path}")
        val mime = mediaMimeForPath(file.name).takeUnless { it == "application/octet-stream" } ?: fallbackMime
        return file.inputStream().use { inputStream ->
            consumer(
                inputStream,
                StreamInfo(
                    mimeType = mime,
                    extension = extensionForMime(mime),
                    length = file.length().takeIf { it >= 0 },
                ),
            )
        }
    }

    private suspend fun <T> openLocalFileStream(
        filePath: String,
        fallbackMime: String,
        consumer: suspend (InputStream, StreamInfo) -> T,
    ): T {
        val file = File(filePath)
        if (!file.exists()) throw FileNotFoundException("File not found: $filePath")
        val mime = mediaMimeForPath(file.name).takeUnless { it == "application/octet-stream" } ?: fallbackMime
        return file.inputStream().use { inputStream ->
            consumer(
                inputStream,
                StreamInfo(
                    mimeType = mime,
                    extension = extensionForMime(mime),
                    length = file.length().takeIf { it >= 0 },
                ),
            )
        }
    }

    private suspend fun <T> openHttpStream(
        url: String,
        fallbackMime: String,
        consumer: suspend (InputStream, StreamInfo) -> T,
    ): T {
        val request = Request.Builder().url(url).build()
        val response = MediaDataSourceProvider.mediaOkHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Download failed (HTTP ${response.code})")
        }
        val body = response.body
        return response.use { resp ->
            val headerMime = resp.header("Content-Type")?.substringBefore(";")?.trim()
            val mime =
                when {
                    !headerMime.isNullOrBlank() && headerMime != "application/octet-stream" -> headerMime
                    fallbackMime.isNotBlank() && fallbackMime != "application/octet-stream" -> fallbackMime
                    else -> mediaMimeForPath(url)
                }
            val length = body.contentLength().takeIf { it >= 0 }
            body.byteStream().use { inputStream ->
                consumer(
                    inputStream,
                    StreamInfo(
                        mimeType = mime,
                        extension = extensionForMime(mime),
                        length = length,
                    ),
                )
            }
        }
    }
}
