package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment

internal data class ReconciledImages(
    val attachments: List<Attachment>?,
    val content: String,
)

/**
 * #1459: a local `file:`/`content:` image is only a preview source. Once history confirms the SAME message
 * with a complete gateway image set, the gateway copy replaces the local images, because the local file
 * can be released or revoked and would then fail on every lazy re-entry. Non-image attachments are kept.
 * A partial confirmed set never drops a local image (no filename/caption guessing across images).
 *
 * The matching `@image:` reference lines are carried into the content so the Room row and cold-cache
 * restore can rebuild the attachment; the model-facing `[screenshot]` placeholder is not.
 */
internal fun reconcileUserImages(
    local: List<Attachment>?,
    localContent: String,
    confirmed: List<Attachment>?,
    confirmedContent: String,
): ReconciledImages {
    val localList = local.orEmpty()
    val confirmedImages = confirmed.orEmpty().filter { it.isImage && it.isGateway }
    if (confirmedImages.isEmpty()) {
        return ReconciledImages(localList.takeIf { it.isNotEmpty() } ?: confirmed, localContent)
    }
    // A partial confirmed set never drops a local image.
    if (confirmedImages.size < localList.count { it.isImage }) return ReconciledImages(local, localContent)
    val knownRefs = imageRefLines(localContent).toSet()
    val missing = imageRefLines(confirmedContent).filterNot { it in knownRefs }
    val content = if (missing.isEmpty()) localContent else localContent.trimEnd() + "\n" + missing.joinToString("\n")
    // No local attachments at all: a caption-only alias adopts the confirmed set (all kinds) and its refs.
    val attachments = if (localList.isEmpty()) confirmed else confirmedImages + localList.filterNot { it.isImage }
    return ReconciledImages(attachments, content)
}

private fun imageRefLines(content: String): List<String> =
    if (!content.contains("@image:")) {
        emptyList()
    } else {
        content
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("@image:") && it.removePrefix("@image:").trim().startsWith("/") }
    }
