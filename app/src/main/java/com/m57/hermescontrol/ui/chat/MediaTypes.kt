package com.m57.hermescontrol.ui.chat

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * File-kind classification for gateway-hosted files, mirroring the desktop app's
 * `MEDIA_BY_EXT` table (`apps/desktop/src/lib/media.ts`). Lets UI code decide
 * how to present a downloaded file — inline image, audio player, video player,
 * or a generic file card — without re-deriving the mapping at every call site.
 *
 * Extend the table (not the call sites) when new extensions are needed.
 */
enum class MediaKind {
    IMAGE,
    AUDIO,
    VIDEO,
    FILE,
}

private val MEDIA_BY_EXT: Map<String, Pair<MediaKind, String>> =
    mapOf(
        "avi" to (MediaKind.VIDEO to "video/x-msvideo"),
        "bmp" to (MediaKind.IMAGE to "image/bmp"),
        "csv" to (MediaKind.FILE to "text/csv"),
        "flac" to (MediaKind.AUDIO to "audio/flac"),
        "gif" to (MediaKind.IMAGE to "image/gif"),
        "jpeg" to (MediaKind.IMAGE to "image/jpeg"),
        "jpg" to (MediaKind.IMAGE to "image/jpeg"),
        "m4a" to (MediaKind.AUDIO to "audio/mp4"),
        "mkv" to (MediaKind.VIDEO to "video/x-matroska"),
        "mov" to (MediaKind.VIDEO to "video/quicktime"),
        "mp3" to (MediaKind.AUDIO to "audio/mpeg"),
        "mp4" to (MediaKind.VIDEO to "video/mp4"),
        "ogg" to (MediaKind.AUDIO to "audio/ogg"),
        "opus" to (MediaKind.AUDIO to "audio/ogg; codecs=opus"),
        "pdf" to (MediaKind.FILE to "application/pdf"),
        "png" to (MediaKind.IMAGE to "image/png"),
        "svg" to (MediaKind.IMAGE to "image/svg+xml"),
        "wav" to (MediaKind.AUDIO to "audio/wav"),
        "webm" to (MediaKind.VIDEO to "video/webm"),
        "webp" to (MediaKind.IMAGE to "image/webp"),
    )

/** Classify a path/URL by extension. Unknown extensions fall back to [MediaKind.FILE]. */
fun mediaKindForPath(path: String): MediaKind {
    val ext =
        path
            .split('?', limit = 2)[0]
            .split('.')
            .lastOrNull()
            ?.lowercase()
            .orEmpty()
    return MEDIA_BY_EXT[ext]?.first ?: MediaKind.FILE
}

/** Best-guess MIME type for a path/URL by extension. Falls back to octet-stream. */
fun mediaMimeForPath(path: String): String {
    val ext =
        path
            .split('?', limit = 2)[0]
            .split('.')
            .lastOrNull()
            ?.lowercase()
            .orEmpty()
    return MEDIA_BY_EXT[ext]?.second ?: "application/octet-stream"
}

/** Trailing filename from a path or URL.
 *
 * Handles both a bare gateway path (`/tmp/report.pdf`) and a full
 * `/api/files/download?path=<enc>` URL, where the real filename lives in the
 * percent-encoded `path=` query parameter. */
fun mediaNameFromPath(path: String): String {
    val queryPathMatch = Regex("""[?&]path=([^&]+)""", RegexOption.IGNORE_CASE).find(path)
    if (queryPathMatch != null) {
        val targetPath =
            runCatching {
                URLDecoder.decode(queryPathMatch.groupValues[1], StandardCharsets.UTF_8.name())
            }.getOrDefault(queryPathMatch.groupValues[1])
        targetPath.split('/', '\\').lastOrNull { it.isNotBlank() }?.let { return it }
    }
    val cleanPath = path.split('?', limit = 2)[0]
    return runCatching {
        java.net
            .URI(cleanPath)
            .path
            .split('/')
            .lastOrNull { it.isNotBlank() }
    }.getOrNull()
        ?: cleanPath.split('/', '\\').lastOrNull { it.isNotBlank() }
        ?: path
}
