package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

private val IMAGE_EXTENSIONS = setOf("gif", "jpeg", "jpg", "png", "svg", "webp")

internal data class DesktopMedia(
    val path: String,
) {
    val fileName: String = path.substringAfterLast('\\').substringAfterLast('/')
    val isImage: Boolean = fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    fun url(): String {
        val encodedPath =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(path.toByteArray(StandardCharsets.UTF_8))
        val token = URLEncoder.encode(AuthManager.getToken().orEmpty(), StandardCharsets.UTF_8.name())
        return "${AuthManager.baseUrl()}api/mobile-media/$encodedPath?token=$token"
    }

    companion object {
        fun parse(content: String): DesktopMedia? {
            val path = content.trim().removePrefix("MEDIA:").takeIf { content.trim().startsWith("MEDIA:") }
            return path?.takeIf { it.isNotBlank() }?.let(::DesktopMedia)
        }
    }
}
