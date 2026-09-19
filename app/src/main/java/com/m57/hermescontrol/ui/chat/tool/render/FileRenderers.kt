package com.m57.hermescontrol.ui.chat.tool.render

import com.m57.hermescontrol.ui.chat.tool.DiffStats
import com.m57.hermescontrol.ui.chat.tool.ToolCall
import com.m57.hermescontrol.ui.chat.tool.ToolJson
import com.m57.hermescontrol.ui.chat.tool.ToolRenderer
import com.m57.hermescontrol.ui.chat.tool.ToolViewExtras
import com.m57.hermescontrol.ui.chat.tool.ToolViewStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Path/diff plumbing shared by the file-oriented renderers. */
internal object FileEditSupport {
    private val HTML_PATH_REGEX =
        Regex(
            "(?:^|\\s)(?:[ab]/)?([^\\s]+\\.html?)(?=\\s|$)",
            RegexOption.IGNORE_CASE,
        )

    fun basename(path: String): String {
        val normalized = path.replace('\\', '/').trim()

        return normalized.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: normalized
    }

    fun editPath(
        args: JsonObject?,
        result: JsonObject?,
    ): String {
        val fromArgs = ToolJson.firstString(args, listOf("path", "file", "filepath"))
        if (fromArgs.isNotEmpty()) {
            return fromArgs
        }

        val fromResult = ToolJson.firstString(result, listOf("path", "file", "filepath", "resolved_path"))
        if (fromResult.isNotEmpty()) {
            return fromResult
        }

        return htmlPathFromInlineDiff(ToolJson.firstString(result, listOf("inline_diff", "diff")))
    }

    private fun stripInlineDiffChrome(value: String): String =
        ToolJson
            .stripAnsi(value)
            .replace(Regex("^\\s*┊\\s*review diff\\s*\\n", RegexOption.IGNORE_CASE), "")
            .trim()

    fun inlineDiffFromResult(result: JsonElement?): String {
        val record = ToolJson.parseMaybeObject(result) ?: return ""

        for (key in listOf("inline_diff", "diff")) {
            val value = record[key]

            if (value is JsonPrimitive && value.isString && value.content.trim().isNotEmpty()) {
                return stripInlineDiffChrome(value.content)
            }
        }

        return ""
    }

    private fun htmlPathFromInlineDiff(value: String): String {
        val cleaned = stripInlineDiffChrome(value)

        for (match in HTML_PATH_REGEX.findAll(cleaned)) {
            val candidate = match.groupValues[1].trim()

            if (candidate.isNotEmpty()) {
                return candidate
            }
        }

        return ""
    }

    fun countDiffLineStats(diff: String): DiffStats {
        var added = 0
        var removed = 0

        for (line in diff.lineSequence()) {
            when {
                line.startsWith("+") && !line.startsWith("+++") -> added += 1
                line.startsWith("-") && !line.startsWith("---") -> removed += 1
            }
        }

        return DiffStats(added = added, removed = removed)
    }

    /** Subtitle shared by read_file and the edit family: the path, or a summary. */
    fun pathSubtitle(
        call: ToolCall,
        path: String,
    ): String = path.ifEmpty { ToolJson.fallbackDetailText(call.args, call.rawResult) }
}

/** `read_file`: "Read name.kt L10-14" title with the file content as detail. */
internal object ReadFileRenderer : ToolRenderer {
    private fun displayTarget(
        args: JsonObject?,
        result: JsonObject?,
    ): String {
        val inherited = ToolJson.firstString(args, listOf("context", "preview"))
        if (inherited.isNotEmpty()) {
            return inherited
        }

        val path = ToolJson.firstString(args, listOf("path", "file", "filepath"))
        if (path.isEmpty()) {
            return ""
        }

        val offset = ToolJson.intValue(args?.get("offset"))
        val limit = ToolJson.intValue(args?.get("limit"))
        var lineLabel = ""

        if (offset != null) {
            lineLabel = if (limit == null || limit <= 1) "L$offset" else "L$offset-${offset + limit - 1}"
        } else if (limit != null) {
            val content = ToolJson.firstString(result, listOf("content"))
            val lines =
                content
                    .split("\n")
                    .mapNotNull { line ->
                        Regex("^(\\d+)\\|")
                            .find(line)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                    }

            if (lines.isNotEmpty()) {
                lineLabel =
                    if (lines.first() == lines.last()) {
                        "L${lines.first()}"
                    } else {
                        "L${lines.first()}-${lines.last()}"
                    }
            }
        }

        return listOf(FileEditSupport.basename(path), lineLabel).filter { it.isNotEmpty() }.joinToString(" ")
    }

    override fun pendingTitle(call: ToolCall): String = "Reading ${displayTarget(call.args, null)}"

    override fun doneTitle(call: ToolCall): String = "Read ${displayTarget(call.args, call.result)}"

    override fun subtitle(call: ToolCall): String =
        FileEditSupport.pathSubtitle(call, ToolJson.firstString(call.args, listOf("path", "file", "filepath")))

    override fun detail(call: ToolCall): String {
        if (call.rawResult == null) {
            return ""
        }

        return ToolJson.firstString(call.result, listOf("content", "text", "data", "body"))
    }
}

/**
 * `edit_file` / `patch` / `write_file`: basename title, path subtitle, and
 * the inline diff (with +/− stats) as the primary payload. Text detail only
 * appears when there is no diff to render.
 */
internal object FileEditRenderer : ToolRenderer {
    override fun doneTitle(call: ToolCall): String? {
        val path = FileEditSupport.editPath(call.args, call.result)

        return if (path.isNotEmpty()) FileEditSupport.basename(path) else null
    }

    override fun subtitle(call: ToolCall): String =
        FileEditSupport.pathSubtitle(call, FileEditSupport.editPath(call.args, call.result))

    override fun detail(call: ToolCall): String {
        if (FileEditSupport.inlineDiffFromResult(call.rawResult).isNotEmpty()) {
            return ""
        }

        return ToolJson.firstString(call.result, listOf("message", "summary")).ifEmpty {
            if (FileEditSupport.editPath(call.args, call.result).isNotEmpty()) {
                ""
            } else {
                ToolJson.fallbackDetailText(call.rawArgs, call.rawResult)
            }
        }
    }

    override fun extras(
        call: ToolCall,
        status: ToolViewStatus,
    ): ToolViewExtras {
        val inlineDiff = FileEditSupport.inlineDiffFromResult(call.rawResult)

        if (inlineDiff.isEmpty()) {
            return ToolViewExtras.NONE
        }

        return ToolViewExtras(
            inlineDiff = inlineDiff,
            diffPath = FileEditSupport.editPath(call.args, call.result),
            diffStats = FileEditSupport.countDiffLineStats(inlineDiff),
        )
    }
}
