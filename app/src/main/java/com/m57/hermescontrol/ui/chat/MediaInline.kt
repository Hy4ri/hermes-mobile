package com.m57.hermescontrol.ui.chat

import java.io.File
import java.util.Base64

/**
 * Issue #724: inline host-path MEDIA: images into base64 data: URLs.
 *
 * The gateway's WebSocket stream delivers the same raw `MEDIA:<path>` directive
 * the desktop app resolves locally (readFileDataUrl). The mobile renderer
 * (MarkdownText) only shows `data:image/...` URLs or http(s), never a bare host
 * path, so an agent-sent image is invisible on mobile unless we inline the
 * bytes ourselves. This mirrors desktop's resolveMediaDisplaySrc and is a
 * mobile-only fix; the backend is untouched.
 *
 * The regex anchors on an absolute path (or ~/ ) ending in a known image
 * extension, matching the backend's MEDIA_TAG_CLEANUP_RE semantics. A tag may
 * be wrapped in quotes/backticks; we strip those before resolving.
 *
 * Pure + JVM-only (no Android deps) so it can be unit-tested without a device.
 */
internal object MediaInline {
    /** Absolute (or ~/ ) host path + known image extension, optional quotes/backticks. */
    private val MEDIA_TAG_RE =
        Regex(
            """[`"']?MEDIA:\s*((?:~|/|/[A-Za-z]:)[^\s`"')]*?\.(png|jpe?g|gif|webp|bmp))[`"']?""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * Pure transform: replace every `MEDIA:<path>` directive with an inline
     * `![image](data:image/<ext>;base64,...)` markdown tag. Tags whose file
     * cannot be read are dropped entirely (so the raw path never reaches the
     * screen). Returns the input unchanged when there is nothing to inline.
     */
    fun inlineLocalMediaText(text: String): String {
        if (!text.contains("MEDIA:")) return text
        return MEDIA_TAG_RE.replace(text) { m ->
            val rawPath = m.groupValues[1]
            val ext = m.groupValues[2].lowercase()
            val path = resolveMediaPath(rawPath)
            val bytes =
                try {
                    path?.let { File(it).takeIf { f -> f.isFile }?.readBytes() }
                } catch (_: Throwable) {
                    null
                }
            if (bytes == null) {
                "" // unreachable on this device — drop the directive
            } else {
                val b64 = Base64.getEncoder().encodeToString(bytes)
                "![image](data:image/$ext;base64,$b64)"
            }
        }
    }

    /** Expand ~/ and verify the path is absolute. Null if not an absolute path. */
    fun resolveMediaPath(raw: String): String? {
        val trimmed =
            raw
                .trim()
                .removeSurrounding("`")
                .removeSurrounding("\"")
                .removeSurrounding("'")
        val expanded =
            if (trimmed.startsWith("~")) {
                val home = System.getenv("HOME") ?: return null
                home + trimmed.removePrefix("~")
            } else {
                trimmed
            }
        if (!expanded.startsWith("/") &&
            !Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(expanded)
        ) {
            return null
        }
        return expanded
    }
}
