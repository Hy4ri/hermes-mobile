package com.m57.hermescontrol.ui.chat.components

/**
 * Plain-text preparation for per-message read-aloud (TTS speak button).
 *
 * Pure Kotlin — no Android imports — so it stays unit-testable on the JVM.
 * Agent message content is Markdown; speaking raw markup (code fences, link
 * targets, emphasis markers) sounds wrong, so callers strip first. The server
 * TTS engine handles its own long-form chunking; [splitForSpeech] is kept as
 * an utterance-boundary helper for callers that need bounded chunks.
 */
object SpeechText {
    const val MAX_CHUNK_LENGTH = 3500

    fun stripMarkdownForSpeech(text: String): String {
        var out = text
        // Fenced code blocks: keep the code, drop the fences.
        out = out.replace(Regex("```\\w*\\n?"), "")
        // Inline code: keep the content.
        out = out.replace(Regex("`([^`]*)`"), "$1")
        // Images: keep alt text, drop the URL.
        out = out.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        // Links: keep visible text, drop the target.
        out = out.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        // Autolinks / bare URLs: drop the URL, keep surrounding prose.
        out = out.replace(Regex("https?://\\S+"), " ")
        // HTML tags.
        out = out.replace(Regex("<[^>]+>"), " ")
        // Headings, blockquotes, list markers, rules, table pipes.
        out = out.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        out = out.replace(Regex("(?m)^\\s*>\\s?"), "")
        out = out.replace(Regex("(?m)^\\s*(?:[-*+]|\\d+[.)])\\s+"), "")
        out = out.replace(Regex("(?m)^\\s*([-*_])\\1{2,}\\s*$"), " ")
        out = out.replace("|", " ")
        // Emphasis / strikethrough markers.
        out = out.replace(Regex("(\\*\\*|__)(.*?)\\1"), "$2")
        out = out.replace(Regex("(\\*|_)(.*?)\\1"), "$2")
        out = out.replace(Regex("~~(.*?)~~"), "$1")
        // Newlines become pauses; collapse the rest.
        out = out.replace(Regex("\\n+"), ". ")
        out = out.replace(Regex("\\s+"), " ").trim()
        // Tidy spaces before punctuation left behind by marker removal.
        out = out.replace(Regex("\\s+([.,!?;:])"), "$1")
        return out
    }

    fun splitForSpeech(
        text: String,
        maxChunkLength: Int = MAX_CHUNK_LENGTH,
    ): List<String> {
        if (text.length <= maxChunkLength) return listOf(text)
        val chunks = mutableListOf<String>()
        var remaining = text
        val boundary = Regex("(?<=[.!?;:]\\s)|(?<=\\.\\s)")
        while (remaining.length > maxChunkLength) {
            val window = remaining.take(maxChunkLength + 1)
            val candidates =
                boundary.findAll(window).map { it.range.first }.toList()
            val cut =
                candidates.lastOrNull { it >= maxChunkLength / 2 }
                    ?: window.lastIndexOf(' ').takeIf { it >= maxChunkLength / 2 }
                    ?: maxChunkLength
            chunks.add(remaining.take(cut).trim())
            remaining = remaining.drop(cut).trim()
        }
        if (remaining.isNotEmpty()) chunks.add(remaining)
        return chunks.filter { it.isNotEmpty() }
    }
}
