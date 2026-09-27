package com.m57.hermescontrol.ui.chat.components

/**
 * Plain-text preparation for per-message read-aloud (TTS speak button).
 *
 * Pure Kotlin — no Android imports — so it stays unit-testable on the JVM.
 * Strips Markdown formatting while preserving readable prose, inline code,
 * fenced code contents, link titles/alt texts, and Unicode/emojis.
 */
object SpeechText {
    // Fenced code blocks: ``` or ~~~ with optional info string, spanning to closing fence or EOF.
    private val FENCED_CODE_BLOCK_REGEX =
        Regex("(?m)^[ \\t]*(`{3,}|~{3,})[^\\r\\n`~]*\\r?\\n([\\s\\S]*?)(?:^[ \\t]*\\1[ \\t]*(?:\\r?\\n|\\Z)|\\Z)")

    // Placeholders with null byte to guarantee safety from standard markdown text
    private const val CODE_SPAN_PREFIX = "\u0000HERMESCODE"
    private const val CODE_SPAN_SUFFIX = "\u0000"

    // Inline code: `code` or multiple backticks ``code``
    private val INLINE_CODE_REGEX = Regex("(`+)(.+?)\\1")

    // Markdown structures
    private val IMAGE_LINK_REGEX = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
    private val LINK_REGEX = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
    private val URL_REGEX = Regex("https?://\\S+")
    private val HTML_TAG_REGEX = Regex("<[^>]+>")

    // Block elements (multiline)
    private val HEADING_REGEX = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
    private val BLOCKQUOTE_REGEX = Regex("(?m)^\\s*>\\s?")
    private val LIST_MARKER_REGEX = Regex("(?m)^\\s*(?:[-*+]|\\d+[.)])\\s+")
    private val HORIZONTAL_RULE_REGEX = Regex("(?m)^\\s*([-_*])(?:\\s*\\1){2,}\\s*$")

    // Emphasis / strikethrough markers
    private val BOLD_REGEX = Regex("(\\*\\*|__)(.*?)\\1")
    private val ITALIC_REGEX = Regex("(\\*|_)(.*?)\\1")
    private val STRIKETHROUGH_REGEX = Regex("~~(.*?)~~")

    // Punctuation set including Arabic question mark (\u061F) and semicolon (\u061B), and standard ones
    // Punctuations that can end a sentence or pause: . , ! ? ; : ؟ ؛
    private const val PUNCTUATION_CLASS = "[.,!?;:\\u061F\\u061B]"
    private val PUNCT_BEFORE_NEWLINE_REGEX = Regex("($PUNCTUATION_CLASS)[^\\S\\r\\n]*(?:\\r?\\n[^\\S\\r\\n]*)+")
    private val PLAIN_NEWLINE_REGEX = Regex("[^\\S\\r\\n]*(?:\\r?\\n[^\\S\\r\\n]*)+")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val SPACE_BEFORE_PUNCT_REGEX = Regex("\\s+($PUNCTUATION_CLASS)")

    fun stripMarkdownForSpeech(text: String): String {
        if (text.isBlank()) return ""

        val codeSpans = mutableListOf<String>()

        // 1. Protect fenced code blocks before prose processing.
        // Delimiters and info string are stripped; code content whitespace is normalized for speech.
        var out =
            FENCED_CODE_BLOCK_REGEX.replace(text) { matchResult ->
                val rawContent = matchResult.groupValues[2]
                val normalizedContent =
                    rawContent
                        .trim()
                        .split(
                            WHITESPACE_REGEX,
                        ).filter { it.isNotEmpty() }
                        .joinToString(" ")
                val index = codeSpans.size
                codeSpans.add(normalizedContent)
                "\n$CODE_SPAN_PREFIX$index$CODE_SPAN_SUFFIX\n"
            }

        // 2. Protect inline code spans before prose processing.
        out =
            INLINE_CODE_REGEX.replace(out) { matchResult ->
                val content = matchResult.groupValues[2].trim()
                val index = codeSpans.size
                codeSpans.add(content)
                "$CODE_SPAN_PREFIX$index$CODE_SPAN_SUFFIX"
            }

        // 3. Process prose markdown elements
        // Images: keep alt text, drop the URL.
        out = out.replace(IMAGE_LINK_REGEX, "$1")
        // Links: keep visible text, drop the target.
        out = out.replace(LINK_REGEX, "$1")
        // Autolinks / bare URLs: drop the URL, keep surrounding prose.
        out = out.replace(URL_REGEX, " ")
        // HTML tags.
        out = out.replace(HTML_TAG_REGEX, " ")
        // Headings, blockquotes, list markers, horizontal rules.
        out = out.replace(HEADING_REGEX, "")
        out = out.replace(BLOCKQUOTE_REGEX, "")
        out = out.replace(LIST_MARKER_REGEX, "")
        out = out.replace(HORIZONTAL_RULE_REGEX, " ")
        // Table pipes
        out = out.replace("|", " ")
        // Emphasis / strikethrough markers
        out = out.replace(BOLD_REGEX, "$2")
        out = out.replace(ITALIC_REGEX, "$2")
        out = out.replace(STRIKETHROUGH_REGEX, "$1")

        // 4. Normalise newlines into sensible pauses without accidental global punctuation mutation.
        // If line ends with punctuation, keep it with a trailing space.
        // Otherwise, replace newline with ". " to create a pause.
        out = out.replace(PUNCT_BEFORE_NEWLINE_REGEX, "$1 ")
        out = out.replace(PLAIN_NEWLINE_REGEX, ". ")

        // 5. Restore protected code spans (both fenced and inline)
        for ((index, code) in codeSpans.withIndex()) {
            out = out.replace("$CODE_SPAN_PREFIX$index$CODE_SPAN_SUFFIX", code)
        }

        // 6. Collapse multiple whitespaces and trim
        out = out.replace(WHITESPACE_REGEX, " ").trim()

        // 7. Tidy spaces before punctuation left behind by marker removal.
        out = out.replace(SPACE_BEFORE_PUNCT_REGEX, "$1")

        return out
    }
}
