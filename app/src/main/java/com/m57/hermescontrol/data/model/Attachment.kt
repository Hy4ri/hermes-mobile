package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Represents a file attached to a chat message.
 *
 * @param uri Content URI or file path to the attachment. For gateway-sourced
 *   attachments this is the authenticated `/api/files/download?...` URL, which
 *   Coil can load directly for images.
 * @param name Display name of the file
 * @param mimeType MIME type (e.g., "image/jpeg", "application/pdf")
 * @param size File size in bytes (0 when unknown, e.g. gateway-hosted files)
 * @param gatewayUrl When non-null, the authenticated gateway download URL for a
 *   file that lives on the *gateway host* (agent-delivered `MEDIA:` directive).
 *   Null for locally-picked attachments.
 * @param source Where the attachment originated.
 */
@Serializable
data class Attachment(
    val uri: String,
    val name: String,
    val mimeType: String,
    val size: Long = 0,
    val gatewayUrl: String? = null,
    val source: AttachmentSource = AttachmentSource.LOCAL,
) {
    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    val isGif: Boolean
        get() =
            mimeType.equals("image/gif", ignoreCase = true) ||
                fileExtension == "gif" ||
                uri.contains(".gif", ignoreCase = true) ||
                uri.startsWith("data:image/gif", ignoreCase = true)

    val isGateway: Boolean
        get() = source == AttachmentSource.GATEWAY

    val formattedSize: String
        get() =
            when {
                size <= 0 -> "—"
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "${size / 1024} KB"
                else -> "%.1f MB".format(size.toDouble() / (1024 * 1024))
            }

    val fileExtension: String
        get() = name.substringAfterLast('.', "").lowercase()
}

/** Origin of an [Attachment]. */
@Serializable
enum class AttachmentSource {
    /** Picked by the user on this device (content URI / local path). */
    LOCAL,

    /** Delivered by the agent via a gateway-hosted `MEDIA:` directive. */
    GATEWAY,
}
