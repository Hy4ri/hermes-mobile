package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource

/**
 * Parses user-authored or server-persisted `@image:` directives from message content into
 * gateway-backed [Attachment]s.
 *
 * Requirements:
 * - Recognizes whole trimmed lines `@image:/absolute/path` (spaces in path allowed).
 * - Ignores inline prose refs (e.g. "look at @image:/path in the middle of a sentence").
 * - Ignores empty or relative paths (must start with '/').
 * - Preserves unique paths across lines (deduplicates repeated references to the same path).
 * - Resolves each path via [mediaUrl]; drops attachments where [mediaUrl] returns null.
 * - Constructs [Attachment]s with source = [AttachmentSource.GATEWAY], resolved uri/gatewayUrl,
 *   size = 0, name = [mediaNameFromPath], mimeType = [mediaMimeForPath].
 */
internal fun userImageAttachments(
    content: String,
    mediaUrl: (String) -> String?,
): List<Attachment> {
    if (!content.contains("@image:")) return emptyList()

    val seenPaths = mutableSetOf<String>()
    val attachments = mutableListOf<Attachment>()

    for (rawLine in content.lines()) {
        val line = rawLine.trim()
        if (!line.startsWith("@image:")) continue
        val path = line.removePrefix("@image:").trim()
        if (!path.startsWith("/")) continue
        if (!seenPaths.add(path)) continue

        val url = mediaUrl(path) ?: continue
        attachments.add(
            Attachment(
                uri = url,
                name = mediaNameFromPath(path),
                mimeType = mediaMimeForPath(path),
                size = 0,
                gatewayUrl = url,
                source = AttachmentSource.GATEWAY,
            ),
        )
    }

    return attachments
}

/** Hides whole-line `@image:/abs/path` refs from bubble text once they render as attachments. */
internal fun hideImageRefLines(content: String): String {
    if (!content.contains("@image:")) return content
    return content
        .lines()
        .filterNot { line ->
            line.trim().let {
                it.startsWith("@image:") &&
                    it.removePrefix("@image:").trim().startsWith("/")
            }
        }.joinToString("\n")
        .trim()
}
