package com.m57.hermescontrol.util

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
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
    private val maxBytes: Long = MAX_UPLOAD_BYTES,
    private val onProgress: (Long, Long) -> Unit = { _, _ -> },
    private val isCancelled: () -> Boolean = { false },
) : RequestBody() {
    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = contentLength

    // #1554: selected provider streams are not a transport-level replay contract.
    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        if (contentLength > maxBytes) throw IOException("Selected file exceeds the upload size limit")
        if (isCancelled()) throw IOException("Upload cancelled")
        val inputStream =
            contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open input stream for selected file")
        inputStream.use { stream ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var written = 0L
            onProgress(written, contentLength)
            while (true) {
                if (isCancelled()) throw IOException("Upload cancelled")
                val count = stream.read(buffer)
                if (count < 0) break
                if (isCancelled()) throw IOException("Upload cancelled")
                if (count.toLong() > maxBytes - written) {
                    throw IOException("Selected file exceeds the upload size limit")
                }
                sink.write(buffer, 0, count)
                written += count
                onProgress(written, contentLength)
            }
        }
    }

    companion object {
        /** Same ceiling as the gateway's managed-file and backup-import streaming handlers. */
        const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 8192
    }
}
