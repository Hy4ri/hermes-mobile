package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource

/**
 * #1696: gateway attachments for every resolvable `MEDIA:` directive in [raw].
 * Unresolvable ones (no gateway configured) are dropped, matching the REST
 * hydration parity codified in ChatServerMessageMapperPurityTest.
 */
internal fun hostMediaAttachments(
    raw: String,
    mediaUrl: (path: String) -> String?,
): List<Attachment> =
    HostMediaExtractor.extract(raw).mapNotNull { item ->
        val url = mediaUrl(item.path) ?: return@mapNotNull null
        Attachment(
            uri = url,
            name = mediaNameFromPath(item.path),
            mimeType = mediaMimeForPath(item.path),
            size = 0,
            gatewayUrl = url,
            source = AttachmentSource.GATEWAY,
            contentOffset = item.offset,
        )
    }

/**
 * #1696: one derivation for live WS, Room cache and REST rows — the visible
 * text never keeps a parsed directive, while [ChatMessage.persistedContent]
 * keeps the raw text so persistence and the next load can re-resolve
 * connection-bound URLs. Idempotent: re-deriving a derived row changes
 * nothing, and a row whose directive is already gone is returned as-is.
 */
internal fun ChatMessage.withHostMedia(mediaUrl: (path: String) -> String?): ChatMessage {
    if (role != MessageRole.ASSISTANT) return this
    val raw = persistedContent ?: content
    if (HostMediaExtractor.extract(raw).isEmpty()) return this
    val merged =
        (attachments.orEmpty() + hostMediaAttachments(raw, mediaUrl))
            .distinctBy { it.gatewayUrl ?: it.uri }
    return copy(
        content = HostMediaExtractor.strip(raw),
        attachments = merged.takeIf { it.isNotEmpty() },
        persistedContent = raw,
    )
}
