package com.m57.hermescontrol.ui.chat

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * File-kind classification for media and gateway-hosted files, mirroring the desktop app's
 * `MEDIA_BY_EXT` table (`apps/desktop/src/lib/media.ts`). Lets UI code decide
 * how to present a file — inline image, audio player, video player,
 * or a generic file card — without re-deriving the mapping at every call site.
 */
enum class MediaKind {
    IMAGE,
    AUDIO,
    VIDEO,
    FILE,
}

private val MEDIA_BY_EXT: Map<String, Pair<MediaKind, String>> =
    mapOf(
        "3gp" to (MediaKind.VIDEO to "video/3gpp"),
        "aac" to (MediaKind.AUDIO to "audio/aac"),
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

/**
 * Extracts the file extension from a clean path or URL, ignoring query parameters and fragments.
 * Never searches queries for extensions to avoid false positives.
 */
fun extractFileExtension(pathOrUrl: String): String {
    val clean =
        pathOrUrl
            .substringBefore('#')
            .substringBefore('?')
            .trim()
    val lastSegment = clean.substringAfterLast('/').substringAfterLast('\\')
    val ext = lastSegment.substringAfterLast('.', "")
    return if (ext == lastSegment) "" else ext.lowercase()
}

/**
 * Extracts the file extension for a media source, handling gateway URLs where the path
 * is encoded inside the `path=` query parameter.
 */
fun extractExtensionFromMediaSource(source: String): String {
    val sourcePath = source.substringBefore('?').substringBefore('#')
    val gatewayPath =
        if (sourcePath.endsWith("/api/files/download") || sourcePath.endsWith("/api/files/stream")) {
            gatewayPathFromUrl(source)
        } else {
            null
        }
    gatewayPath?.let { gwPath ->
        val ext = extractFileExtension(gwPath)
        if (ext.isNotBlank()) return ext
    }
    return extractFileExtension(source)
}

/**
 * Robust classification of a media item by concrete MIME type first, then by file extension
 * from [name] or [uri]. Concrete MIME types take precedence (e.g. "audio/mp4" is recognized as AUDIO).
 */
fun classifyMedia(
    mimeType: String? = null,
    name: String? = null,
    uri: String? = null,
): MediaKind {
    val cleanMime =
        mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()
    if (cleanMime.isNotBlank() && cleanMime != "application/octet-stream" && cleanMime != "*/*") {
        if (cleanMime.startsWith("audio/") || cleanMime == "application/ogg" || cleanMime == "application/x-flac") {
            return MediaKind.AUDIO
        }
        if (cleanMime.startsWith("video/")) {
            return MediaKind.VIDEO
        }
        if (cleanMime.startsWith("image/")) {
            return MediaKind.IMAGE
        }
        return MediaKind.FILE
    }
    if (uri?.startsWith("data:", ignoreCase = true) == true) {
        return classifyMedia(mimeType = uri.substringAfter(':').substringBefore(';').substringBefore(','))
    }

    val extFromName = name?.let { extractFileExtension(it) }.orEmpty()
    if (extFromName.isNotBlank()) {
        MEDIA_BY_EXT[extFromName]?.first?.let { return it }
    }

    val extFromUri = uri?.let { extractExtensionFromMediaSource(it) }.orEmpty()
    if (extFromUri.isNotBlank()) {
        MEDIA_BY_EXT[extFromUri]?.first?.let { return it }
    }

    return MediaKind.FILE
}

/** Classify a path/URL by extension. Unknown extensions fall back to [MediaKind.FILE]. */
fun mediaKindForPath(path: String): MediaKind = classifyMedia(uri = path)

/** Best-guess MIME type for a path/URL by extension. Falls back to octet-stream. */
fun mediaMimeForPath(path: String): String {
    val ext = extractExtensionFromMediaSource(path)
    return MEDIA_BY_EXT[ext]?.second ?: "application/octet-stream"
}

/**
 * Normalizes a MIME type string for Android framework boundaries (MediaStore, Intent, SAF),
 * stripping parameters such as codecs (e.g. `audio/ogg; codecs=opus` -> `audio/ogg`).
 */
fun normalizeFrameworkMime(mime: String?): String =
    mime
        ?.substringBefore(';')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "application/octet-stream"

/** Returns the canonical file extension for a given MIME type. */
fun extensionForMime(mime: String): String {
    val clean = mime.substringBefore(';').trim().lowercase()
    for ((ext, pair) in MEDIA_BY_EXT) {
        if (pair.second
                .substringBefore(';')
                .trim()
                .lowercase() == clean
        ) {
            return ext
        }
    }
    return when (clean) {
        "image/jpeg", "image/jpg" -> {
            "jpg"
        }

        "video/mp4" -> {
            "mp4"
        }

        "video/x-matroska" -> {
            "mkv"
        }

        "video/quicktime" -> {
            "mov"
        }

        "audio/mpeg", "audio/mp3" -> {
            "mp3"
        }

        "audio/mp4", "audio/x-m4a" -> {
            "m4a"
        }

        "audio/wav", "audio/x-wav", "audio/wave" -> {
            "wav"
        }

        "audio/flac", "application/x-flac" -> {
            "flac"
        }

        "audio/ogg", "application/ogg" -> {
            "ogg"
        }

        "audio/aac" -> {
            "aac"
        }

        else -> {
            when {
                clean.startsWith("video/") -> "mp4"
                clean.startsWith("audio/") -> "mp3"
                clean.startsWith("image/") -> "img"
                else -> "bin"
            }
        }
    }
}

/** Decoded gateway file path carried in a `/api/files/download` or `/api/files/stream` URL. */
fun gatewayPathFromUrl(url: String): String? =
    Regex("""[?&]path=([^&]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.let {
        runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrDefault(it)
    }

/**
 * Trailing filename from a path or URL.
 * Handles both a bare gateway path (`/tmp/report.pdf`) and a full
 * `/api/files/download?path=<enc>` URL, where the real filename lives in the
 * percent-encoded `path=` query parameter.
 */
fun mediaNameFromPath(path: String): String {
    gatewayPathFromUrl(path)?.split('/', '\\')?.lastOrNull { it.isNotBlank() }?.let { return it }
    val cleanPath = path.substringBefore('#').substringBefore('?')
    return runCatching {
        java.net
            .URI(cleanPath)
            .path
            ?.split('/')
            ?.lastOrNull { it.isNotBlank() }
    }.getOrNull()
        ?: cleanPath.split('/', '\\').lastOrNull { it.isNotBlank() }
        ?: path
}
