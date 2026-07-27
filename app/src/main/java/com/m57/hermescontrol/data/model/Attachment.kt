package com.m57.hermescontrol.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

enum class AttachmentSource {
    LOCAL_FILE,
    GATEWAY,
}

@Immutable
@Serializable
data class Attachment(
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long = 0,
    val source: AttachmentSource = AttachmentSource.LOCAL_FILE,
) {
    val fileExtension: String
        get() = name.substringAfterLast('.', "").lowercase()

    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    val isGif: Boolean
        get() =
            mimeType.equals("image/gif", ignoreCase = true) ||
                fileExtension == "gif" ||
                uri.contains(".gif", ignoreCase = true) ||
                uri.startsWith("data:image/gif", ignoreCase = true)

    val isVideo: Boolean
        get() =
            mimeType.startsWith("video/") ||
                fileExtension in listOf("mp4", "webm", "mkv", "mov", "avi", "3gp") ||
                uri.contains(".mp4", ignoreCase = true) ||
                uri.contains(".webm", ignoreCase = true)

    val isGateway: Boolean
        get() = source == AttachmentSource.GATEWAY
}
