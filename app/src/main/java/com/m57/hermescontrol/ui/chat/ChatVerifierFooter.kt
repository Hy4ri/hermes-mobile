package com.m57.hermescontrol.ui.chat

/**
 * Utility to recognize and extract File-mutation verifier footers appended to
 * assistant responses on live channels.
 *
 * Pattern emitted by backend `turn_explainers._format_file_mutation_failure_footer`:
 * ⚠️ File-mutation verifier: N file edit(s) FAILED this turn despite any wording above that may suggest otherwise. Run `git status` or `read_file` to confirm what actually landed.
 *   • `path` — [tool] error_preview
 *   • … and N more
 */
internal object ChatVerifierFooter {
    private const val HEADER_PREFIX = "⚠️ File-mutation verifier:"
    private const val HEADER_NO_EMOJI_PREFIX = "File-mutation verifier:"

    data class SplitResult(
        val body: String,
        val footer: String,
    )

    /**
     * Splits [content] into (body, footer) if it cleanly ends with a recognized
     * File-mutation verifier block. If not, returns null.
     */
    fun split(content: String): SplitResult? {
        val trimmed = content.trimEnd()
        if (trimmed.isBlank()) return null

        val headerIdx =
            trimmed.lastIndexOf(HEADER_PREFIX).let { idx ->
                if (idx >= 0) idx else trimmed.lastIndexOf(HEADER_NO_EMOJI_PREFIX)
            }
        if (headerIdx <= 0) return null

        // Must be preceded by a newline (standalone block)
        val charBefore = trimmed[headerIdx - 1]
        if (charBefore != '\n') return null

        val headerAndRest = trimmed.substring(headerIdx)
        val lines = headerAndRest.lines()
        val firstLine = lines.first().trim()

        // Verify first line structure: "⚠️ File-mutation verifier: ... FAILED this turn..."
        if (!firstLine.contains("file edit(s) FAILED this turn") &&
            !firstLine.contains("file(s) were NOT modified this turn")
        ) {
            return null
        }

        // Remaining lines must be bullet items or continuation lines
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val trimmedLine = line.trimStart()
            // Bullets typically start with •
            if (!trimmedLine.startsWith("•") && !trimmedLine.startsWith("-") && !line.startsWith("  ")) {
                return null
            }
        }

        val body = trimmed.substring(0, headerIdx).trimEnd()
        if (body.isBlank()) return null

        val footer = trimmed.substring(headerIdx)
        return SplitResult(body = body, footer = footer)
    }

    /**
     * Returns true if [a] and [b] have the same underlying text once any
     * trailing file-mutation verifier footer is removed.
     */
    fun matchesBase(
        a: String,
        b: String,
    ): Boolean {
        val cleanA = split(a)?.body ?: a.trim()
        val cleanB = split(b)?.body ?: b.trim()
        return cleanA == cleanB
    }
}
