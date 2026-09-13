package com.m57.hermescontrol.ui.chat.tool

import kotlinx.serialization.json.JsonPrimitive

/**
 * Status + error-text heuristics for a completed tool call, ported from the
 * desktop engine. Key rules, in order:
 *
 * - running (or a missing result) is RUNNING
 * - an explicit `success: true` / `ok: true` beats every error heuristic,
 *   including a stale isError envelope
 * - a non-zero exit code alone is NOT an error: grep returns 1 on no-match,
 *   diff returns 1 on differences, piped commands surface the last stage's
 *   code — all routinely produce useful output. It only becomes an error
 *   when the command produced no real output to show.
 * - a rejected memory write is a budget negotiation → WARNING, not ERROR
 */
internal object ToolStatusResolver {
    fun errorText(
        call: ToolCall,
        isError: Boolean,
    ): String {
        val result = call.rawResult
        val resultRecord = call.result

        if (isError) {
            val extracted = ToolResultSummary.extractToolErrorMessage(result)
            if (extracted.isNotEmpty()) {
                return extracted
            }
            if (result is JsonPrimitive && result.isString && result.content.trim().isNotEmpty()) {
                return result.content.trim()
            }

            return "Tool returned an error."
        }

        val error = ToolJson.firstString(resultRecord, listOf("error"))
        if (error.isNotEmpty()) {
            return error
        }

        val extracted = ToolResultSummary.extractToolErrorMessage(result)
        if (extracted.isNotEmpty()) {
            return extracted
        }

        if (resultRecord != null) {
            if (ToolJson.boolFalse(resultRecord["success"]) || ToolJson.boolFalse(resultRecord["ok"])) {
                return ToolJson
                    .firstString(
                        resultRecord,
                        listOf("message", "reason", "detail"),
                    ).ifEmpty { "Tool returned success=false." }
            }

            val status = ToolJson.firstString(resultRecord, listOf("status"))
            if (Regex("\\b(error|failed|failure)\\b", RegexOption.IGNORE_CASE).containsMatchIn(status)) {
                return ToolJson
                    .firstString(
                        resultRecord,
                        listOf("message", "reason", "detail"),
                    ).ifEmpty { "Tool returned status \"$status\"." }
            }

            val exit = ToolJson.numberValue(resultRecord["exit_code"])
            if (exit != null && exit != 0.0) {
                val hasOutput =
                    listOf("output", "stdout", "stderr", "output_preview")
                        .any { k -> ToolJson.firstString(resultRecord, listOf(k)).isNotEmpty() }

                if (!hasOutput) {
                    return "Command failed with exit code ${exit.toInt()}."
                }
            }
        }

        return ""
    }

    fun status(
        call: ToolCall,
        isError: Boolean,
        running: Boolean,
    ): ToolViewStatus {
        if (running || call.rawResult == null) {
            return ToolViewStatus.RUNNING
        }

        // Explicit success wins over isError / nested-error heuristics.
        val resultRecord = call.result
        if (resultRecord != null &&
            (ToolJson.boolTrue(resultRecord["success"]) || ToolJson.boolTrue(resultRecord["ok"]))
        ) {
            return ToolViewStatus.SUCCESS
        }

        if (errorText(call, isError).isEmpty()) {
            return ToolViewStatus.SUCCESS
        }

        // A rejected memory write is a budget negotiation, not a failure.
        return if (call.name == "memory") ToolViewStatus.WARNING else ToolViewStatus.ERROR
    }
}
