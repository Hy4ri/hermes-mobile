package com.m57.hermescontrol.ui.chat

import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/** Keeps the encoded JSON-RPC frame safely below the WebSocket's 16 MiB limit. */
internal const val MAX_CHAT_ATTACHMENT_BYTES = 10L * 1024L * 1024L

internal enum class AttachmentSizeResult {
    WITHIN_LIMIT,
    TOO_LARGE,
}

/** Streams Base64 to [output] while reading at most [maxBytes] of raw input. */
internal fun encodeAttachmentBase64(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long = MAX_CHAT_ATTACHMENT_BYTES,
): AttachmentSizeResult {
    require(maxBytes >= 0) { "maxBytes must be non-negative" }

    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0L

    Base64.getEncoder().wrap(output).use { encodedOutput ->
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) return AttachmentSizeResult.WITHIN_LIMIT
            totalBytes += bytesRead
            if (totalBytes > maxBytes) return AttachmentSizeResult.TOO_LARGE
            encodedOutput.write(buffer, 0, bytesRead)
        }
    }
}
