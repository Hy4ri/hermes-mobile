package com.m57.hermescontrol.ui.chat.tool

/**
 * Reduce a verbose shell command to the "main" command, for display only.
 *
 * Ported from the desktop app's `lib/summarize-command.ts`. Agents wrap real
 * work in plumbing — `cd <dir> && <cmd> 2>&1 | tail -N; echo "x_exit=..."` —
 * which buries the command the user actually cares about. This peels that
 * wrapper off using small head-word allowlists instead of one giant regex:
 *
 * 1. split into segments on top-level `&&` `||` `;` (quote-aware)
 * 2. strip trailing pipe tails (`| head`, `| tail`, `| wc`, ...)
 * 3. clean env var prefixes / redirects
 * 4. drop setup/banner/status segments
 *
 * If one real command survives, show it. If multiple real commands survive,
 * show a short `first command + N commands` label instead of flooding the row
 * with every probe. The full command is always still available via Copy.
 */
object CommandSummarizer {
    private val SILENT_HEADS =
        setOf("cd", "pushd", "popd", "export", "set", "unset", "source", ".", "true", "false", ":")
    private val PIPE_TAIL_HEADS = setOf("head", "tail", "wc", "sort", "uniq")

    private fun basename(head: String): String = head.substringAfterLast('/').ifEmpty { head }

    /** Split on command-chain separators, but NOT pipe. */
    private fun splitCompoundCommand(input: String): List<String> {
        val segments = mutableListOf<String>()
        val buf = StringBuilder()
        var quote: Char? = null

        var i = 0
        while (i < input.length) {
            val ch = input[i]

            if (quote != null) {
                buf.append(ch)
                if (ch == quote && input.getOrNull(i - 1) != '\\') {
                    quote = null
                }
                i += 1
                continue
            }

            if (ch == '"' || ch == '\'') {
                quote = ch
                buf.append(ch)
                i += 1
                continue
            }

            val op =
                when {
                    input.startsWith("&&", i) || input.startsWith("||", i) -> input.substring(i, i + 2)
                    ch == ';' || ch == '\n' -> ch.toString()
                    else -> ""
                }

            if (op.isNotEmpty()) {
                segments += buf.toString()
                buf.clear()
                i += op.length
                continue
            }

            buf.append(ch)
            i += 1
        }

        segments += buf.toString()

        return segments.map { stripPipeTail(it.trim()) }.filter { it.isNotEmpty() }
    }

    private fun splitWords(segment: String): List<String> {
        val words = mutableListOf<String>()
        val buf = StringBuilder()
        var quote: Char? = null

        for ((idx, ch) in segment.withIndex()) {
            if (quote != null) {
                buf.append(ch)
                if (ch == quote && segment.getOrNull(idx - 1) != '\\') {
                    quote = null
                }
                continue
            }

            if (ch == '"' || ch == '\'') {
                quote = ch
                buf.append(ch)
                continue
            }

            if (ch.isWhitespace()) {
                if (buf.isNotEmpty()) {
                    words += buf.toString()
                    buf.clear()
                }
                continue
            }

            buf.append(ch)
        }

        if (buf.isNotEmpty()) {
            words += buf.toString()
        }

        return words
    }

    /** The command word of a segment, skipping any `FOO=bar` env assignments. */
    private fun headWord(segment: String): String {
        val tokens = splitWords(segment)
        var index = 0

        while (index < tokens.size && Regex("^[A-Za-z_]\\w*=").containsMatchIn(tokens[index])) {
            index += 1
        }

        return basename(tokens.getOrNull(index) ?: "")
    }

    private fun stripPipeTail(segment: String): String {
        val words = splitWords(segment)
        val out = mutableListOf<String>()

        for (i in words.indices) {
            val word = words[i]

            if (word == "|" && basename(words.getOrNull(i + 1) ?: "") in PIPE_TAIL_HEADS) {
                break
            }

            out += word
        }

        return out.joinToString(" ").trim()
    }

    private fun cleanSegment(segment: String): String {
        val words = splitWords(segment)
        val out = mutableListOf<String>()

        var i = 0
        while (i < words.size) {
            val word = words[i]

            // `> file`, `2> file`, `>> file`, `2>&1` — drop the operator + its target.
            if (Regex("^\\d*(?:>>?|<)$").matches(word)) {
                i += 2
                continue
            }

            if (Regex("^\\d*(?:>&|<&)\\d+$").matches(word)) {
                i += 1
                continue
            }

            out += word
            i += 1
        }

        return out.joinToString(" ").trim()
    }

    private fun isBoundaryEcho(segment: String): Boolean {
        val words = splitWords(segment)

        if (basename(words.firstOrNull() ?: "") != "echo") {
            return false
        }

        // Banner/status echoes are UI plumbing. Do not treat arbitrary
        // `echo $VALUE` as noise; it may be the command's actual output.
        val rest = words.drop(1).joinToString(" ")

        return Regex("-{2,}|_exit=|(?:^|\\s|=)\\$[?{]|PIPESTATUS").containsMatchIn(rest)
    }

    fun summarizeShellCommand(raw: String?): String {
        val original = (raw ?: "").trim()

        if (original.isEmpty()) {
            return ""
        }

        val segments = splitCompoundCommand(original)

        if (segments.size <= 1) {
            return cleanSegment(original).ifEmpty { original }
        }

        val core =
            segments
                .map { cleanSegment(it) }
                .filter { segment ->
                    val head = headWord(segment)

                    segment.isNotEmpty() && head !in SILENT_HEADS && !isBoundaryEcho(segment)
                }

        if (core.isEmpty()) {
            return original
        }

        if (core.size == 1) {
            return core[0]
        }

        return "${core[0]} + ${core.size - 1} ${if (core.size == 2) "command" else "commands"}"
    }
}
