package com.m57.hermescontrol.ui.chat.tool.render

import com.m57.hermescontrol.ui.chat.tool.ToolCall
import com.m57.hermescontrol.ui.chat.tool.ToolJson
import com.m57.hermescontrol.ui.chat.tool.ToolRenderer
import com.m57.hermescontrol.ui.chat.tool.ToolViewExtras
import com.m57.hermescontrol.ui.chat.tool.ToolViewStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * `search_files`: ripgrep-backed content/file search.
 * Handles both result shapes: dense path-grouped `matches_text` string (≥5 hits)
 * and `matches:[{path,line,content}]` (<5 hits), plus `files:[path]`.
 */
internal object SearchFilesRenderer : ToolRenderer {
    private fun pattern(call: ToolCall) = ToolJson.firstString(call.args, listOf("pattern"))

    override fun pendingTitle(call: ToolCall): String? =
        pattern(call).takeIf { it.isNotEmpty() }?.let { "Searching “${ToolJson.compactPreview(it, 48)}”" }

    override fun doneTitle(call: ToolCall): String? {
        val p = pattern(call).takeIf { it.isNotEmpty() } ?: return null
        val total = ToolJson.intValue(call.result?.get("total_count"))
        val suffix = total?.let { " · $it match${if (it == 1) "" else "es"}" } ?: ""
        return "Searched “${ToolJson.compactPreview(p, 48)}”$suffix"
    }

    override fun subtitle(call: ToolCall): String {
        val path = ToolJson.firstString(call.args, listOf("path")).takeIf { it != "." && it.isNotEmpty() } ?: ""
        val glob = ToolJson.firstString(call.args, listOf("file_glob"))
        return listOf(path, glob).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    override fun detail(call: ToolCall): String {
        val result = call.result ?: return ""
        // Dense ≥5-match block: already ripgrep-grouped, use verbatim.
        val dense = ToolJson.firstString(result, listOf("matches_text"))
        val body =
            if (dense.isNotEmpty()) {
                dense
            } else {
                (result["matches"] as? JsonArray)
                    ?.mapNotNull { el ->
                        val m = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                        val path = ToolJson.firstString(m, listOf("path"))
                        val line = ToolJson.intValue(m["line"])
                        val content = ToolJson.firstString(m, listOf("content"))
                        "$path:${line ?: "?"}: ${content.trim()}"
                    }?.joinToString("\n")
                    ?: (result["files"] as? JsonArray)
                        ?.mapNotNull {
                            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                        }?.joinToString("\n") ?: ""
            }
        val hint =
            if (ToolJson.boolTrue(result["truncated"])) {
                ToolJson.firstString(result, listOf("_hint")).ifEmpty { "Results truncated" }
            } else {
                ""
            }
        return listOf(body, hint).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    override fun extras(
        call: ToolCall,
        status: ToolViewStatus,
    ): ToolViewExtras = ToolViewExtras(detailLabel = "Matches")
}
