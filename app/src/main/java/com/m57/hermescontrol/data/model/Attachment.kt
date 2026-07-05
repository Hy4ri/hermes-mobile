package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

/**
 * Represents a file attached to a chat message.
 *
 * @param uri Content URI or file path to the attachment
 * @param name Display name of the file
 * @param mimeType MIME type (e.g., "image/jpeg", "application/pdf")
 * @param size File size in bytes
 * @param localPath Optional local file system path for direct access
 */
@Serializable
data class Attachment(
    val uri: String,
    val name: String,
    val mimeType: String,
    val size: Long,
) {
    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    val formattedSize: String
        get() =
            when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "${size / 1024} KB"
                else -> "%.1f MB".format(size.toDouble() / (1024 * 1024))
            }

    val fileExtension: String
        get() = name.substringAfterLast('.', "").lowercase()
}
