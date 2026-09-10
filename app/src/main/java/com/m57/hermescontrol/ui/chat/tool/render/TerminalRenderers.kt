package com.m57.hermescontrol.ui.chat.tool.render

import com.m57.hermescontrol.ui.chat.tool.CommandSummarizer
import com.m57.hermescontrol.ui.chat.tool.ToolCall
import com.m57.hermescontrol.ui.chat.tool.ToolJson
import com.m57.hermescontrol.ui.chat.tool.ToolRenderer
import com.m57.hermescontrol.ui.chat.tool.ToolViewExtras
import com.m57.hermescontrol.ui.chat.tool.ToolViewStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `terminal` and `execute_code`: shell-styled rows with a summarized command
 * title, first output line as subtitle, and split (or merged) streams for
 * the ANSI-aware expanded view. Exit code and the `$` command line are only
 * meaningful for `terminal`.
 */
internal object TerminalRenderer : ToolRenderer {
    private fun shellCommand(args: JsonObject?): String =
        ToolJson
            .firstString(args, listOf("command", "code"))
            .ifEmpty { ToolJson.firstString(args, listOf("context", "preview")) }
            .ifEmpty { ToolJson.contextValue(args) }

    private fun shellOutput(result: JsonObject?): String {
        val output = ToolJson.firstString(result, listOf("output", "stdout", "stderr"))
        val lines =
            (result?.get("lines") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.joinToString("\n")
                ?: ""

        return listOf(output, lines).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun commandTitle(
        call: ToolCall,
        runningVerb: Boolean,
    ): String? {
        val command = shellCommand(call.args)
        val verb =
            when {
                call.name == "execute_code" && runningVerb -> "Running code"
                call.name == "execute_code" -> "Ran code"
                runningVerb -> "Running"
                else -> "Ran"
            }

        if (command.isEmpty()) {
            return null
        }

        return "$verb ${ToolJson.compactPreview(CommandSummarizer.summarizeShellCommand(command), 160)}"
    }

    override fun pendingTitle(call: ToolCall): String? = commandTitle(call, runningVerb = true)

    override fun doneTitle(call: ToolCall): String? = commandTitle(call, runningVerb = false)

    override fun subtitle(call: ToolCall): String {
        val firstMeaningfulLine =
            shellOutput(call.result)
                .split("\n")
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }

        // A terminal row with no output shows its command in the title;
        // nothing more to add.
        return firstMeaningfulLine?.let { ToolJson.compactPreview(it, 160) } ?: ""
    }

    override fun detail(call: ToolCall): String {
        val output = shellOutput(call.result)

        return when {
            output.isNotEmpty() -> output
            call.name == "execute_code" -> ToolJson.fallbackDetailText(call.rawArgs, call.rawResult)
            // A terminal row with no output already shows its command in the
            // title; the generic fallback would repeat it.
            else -> ""
        }
    }

    override fun extras(
        call: ToolCall,
        status: ToolViewStatus,
    ): ToolViewExtras {
        // The gateway's terminal_tool emits a single merged `output` string
        // (stdout+stderr, ANSI-stripped); older payloads carried separate
        // `stdout`/`stderr`. Prefer a real stream split, fall back to the
        // merged output as the single renderable stream.
        val stdout = ToolJson.firstString(call.result, listOf("stdout", "output"))
        val stderr = ToolJson.firstString(call.result, listOf("stderr"))
        val hasSplitStreams = stdout.isNotEmpty() || stderr.isNotEmpty()

        return ToolViewExtras(
            stdout = if (hasSplitStreams) stdout else null,
            stderr = if (hasSplitStreams) stderr else null,
            exitCode = if (call.name == "terminal") ToolJson.intValue(call.result?.get("exit_code")) else null,
            terminalCommand = if (call.name == "terminal") shellCommand(call.args) else null,
        )
    }
}

/** `read_terminal`: a line-window over a live terminal session. */
internal object ReadTerminalRenderer : ToolRenderer {
    override fun subtitle(call: ToolCall): String {
        val result = call.result
        val error = ToolJson.firstString(result, listOf("error"))
        if (error.isNotEmpty()) {
            return "❌ $error"
        }

        val total = ToolJson.intValue(result?.get("total_lines")) ?: 0
        val start = ToolJson.intValue(result?.get("start")) ?: 0
        val end = ToolJson.intValue(result?.get("end")) ?: 0
        val text = ToolJson.firstString(result, listOf("text"))

        if (text.isEmpty()) {
            return "Terminal output"
        }

        val cursor = ToolJson.intValue(result?.get("cursor_row"))

        return "Lines $start-$end of $total${cursor?.let { ", cursor at row $it" } ?: ""}"
    }

    override fun detail(call: ToolCall): String {
        val result = call.result
        val error = ToolJson.firstString(result, listOf("error"))
        val text = ToolJson.firstString(result, listOf("text"))
        val total = ToolJson.intValue(result?.get("total_lines")) ?: 0
        val start = ToolJson.intValue(result?.get("start")) ?: 0
        val end = ToolJson.intValue(result?.get("end")) ?: 0
        val cursor = ToolJson.intValue(result?.get("cursor_row"))

        return buildString {
            if (error.isNotEmpty()) {
                append("❌ $error")
            } else if (text.isNotEmpty()) {
                append("━━━ Terminal ━━━     ($start:$end / $total)")
                if (cursor != null) append(" │ cursor row $cursor")
                append("\n$text")
            } else {
                append("No terminal output")
            }
        }
    }
}
