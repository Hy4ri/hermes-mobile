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
     * Streams characters directly from a [CharSequence] without allocating substring copies.
     * Skips whitespace to tolerate formatted Base64 data URLs.
     */
    internal class CharSequenceInputStream(
        private val value: CharSequence,
        startIndex: Int,
    ) : InputStream() {
        private var index = startIndex

        override fun read(): Int {
            while (index < value.length) {
                val c = value[index++]
                if (!c.isWhitespace()) {
                    return c.code and 0xFF
                }
            }
            return -1
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            var bytesRead = 0
            while (bytesRead < len) {
                val c = read()
                if (c == -1) {
                    return if (bytesRead == 0) -1 else bytesRead
                }
                b[off + bytesRead] = c.toByte()
                bytesRead++
            }
            return bytesRead
        }
    }

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

                    uriString.startsWith("file:", ignoreCase = true) -> {
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
        val rawStream = CharSequenceInputStream(dataUrl, comma + 1)
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
        val resolvedMime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val mime = resolvedMime?.takeIf { it.isNotBlank() } ?: fallbackMime
        val length =
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    pfd.statSize.takeIf { it >= 0 }
                }
            }.getOrNull()
        val stream =
            runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
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
        val parsed = runCatching { java.net.URI(uriString).path }.getOrNull() ?: Uri.parse(uriString).path
        val file = File(parsed ?: throw IOException("Invalid file URI: $uriString"))
        if (!file.exists() || !file.isFile) throw FileNotFoundException("Not a valid file: ${file.path}")
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
        if (!file.exists() || !file.isFile) throw FileNotFoundException("Not a valid file: $filePath")
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
