package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment

/** Issue #1367: one in-order piece of an agent message body. */
internal sealed interface MessageSegment {
    data class Text(
        val text: String,
    ) : MessageSegment

    data class Media(
        val attachment: Attachment,
    ) : MessageSegment
}

/**
 * Interleave [attachments] into [content] at the position their `MEDIA:` directive occupied
 * ([Attachment.contentOffset]). Attachments without an offset trail the text, as before.
 *
 * Offsets snap forward to the end of their line, and past a still-open code fence, so media
 * never splits a sentence's markdown, list item, or fenced block.
 */
internal fun splitByMedia(
    content: String,
    attachments: List<Attachment>?,
): List<MessageSegment> {
    if (attachments.isNullOrEmpty()) {
        return if (content.isBlank()) emptyList() else listOf(MessageSegment.Text(content))
    }
    val inline = attachments.filter { it.contentOffset != null }.sortedBy { it.contentOffset }
    val trailing = attachments.filter { it.contentOffset == null }
    val out = mutableListOf<MessageSegment>()
    var cursor = 0
    inline.forEach { attachment ->
        val pos = snapToBlockEnd(content, attachment.contentOffset ?: 0).coerceAtLeast(cursor)
        addText(out, content.substring(cursor, pos))
        out += MessageSegment.Media(attachment)
        cursor = pos
    }
    addText(out, content.substring(cursor))
    trailing.forEach { out += MessageSegment.Media(it) }
    return out
}

private fun addText(
    out: MutableList<MessageSegment>,
    text: String,
) {
    if (text.isNotBlank()) out += MessageSegment.Text(text.trim('\n'))
}

private fun snapToBlockEnd(
    content: String,
    offset: Int,
): Int {
    var pos = offset.coerceIn(0, content.length)
    pos = lineEnd(content, pos)
    // Inside an unclosed fence? Advance to the end of the closing fence line.
    while (fenceOpenBefore(content, pos)) {
        val next = pos + 1
        if (next > content.length) return content.length
        pos = lineEnd(content, next)
    }
    return pos
}

private fun lineEnd(
    content: String,
    from: Int,
): Int {
    val idx = content.indexOf('\n', from)
    return if (idx < 0) content.length else idx
}

private fun fenceOpenBefore(
    content: String,
    pos: Int,
): Boolean {
    var open = false
    content.substring(0, pos).lineSequence().forEach { line ->
        if (line.trimStart().startsWith("```")) open = !open
    }
    return open
}
