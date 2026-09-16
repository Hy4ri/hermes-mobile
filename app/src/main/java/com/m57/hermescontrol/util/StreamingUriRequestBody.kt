package com.m57.hermescontrol.util

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException

/**
 * A streaming OkHttp [RequestBody] backed by an Android SAF [Uri] and [ContentResolver].
 *
 * Streams bytes directly from the open stream into OkHttp's [BufferedSink] without
 * buffering the entire file into heap memory (avoiding OutOfMemoryError on large archives).
 */
class StreamingUriRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val contentType: MediaType?,
    private val contentLength: Long = -1L,
) : RequestBody() {
    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = contentLength

    override fun writeTo(sink: BufferedSink) {
        val inputStream =
            contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open input stream for URI: $uri")
        inputStream.use { stream ->
            stream.source().use { source ->
                sink.writeAll(source)
            }
        }
    }
}
