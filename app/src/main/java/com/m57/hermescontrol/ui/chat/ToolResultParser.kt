package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.ui.chat.tool.ToolView
import com.m57.hermescontrol.ui.chat.tool.ToolViewBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses the tool.complete JSON payload into a [ToolView] via the
 * desktop-ported display engine ([ToolViewBuilder]).
 *
 * This is a thin adapter: it normalizes the payload shape (args/result
 * sub-objects, legacy top-level fallback, name resolution) and hands the
 * parts to the engine. All per-tool formatting lives in the engine.
 *
 * Returns null for non-JSON or non-object content — the view falls back
 * to the raw JSON dump in that case.
 */
fun parseToolOutput(
    content: String,
    toolName: String?,
    isRunning: Boolean,
): ToolView? {
    val trimmed = content.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
        return null
    }

    return try {
        val element = OkHttpProvider.json.parseToJsonElement(trimmed)
        if (element !is JsonObject) {
            return null
        }

        // Resolve tool name — from the message first, then from the payload
        // (old sessions carried it in the body).
        val resolvedToolName =
            toolName
                ?: (element["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        // Extract args sub-object if present (new tool.complete format) and
        // the result sub-object, falling back to the top-level payload (old
        // format where result fields sat at the root).
        val args = element["args"] as? JsonObject
        val result = element["result"] as? JsonObject ?: element

        ToolViewBuilder.build(
            toolName = resolvedToolName ?: "tool",
            args = args,
            result = result,
            isError = false,
            running = isRunning,
        )
    } catch (_: Exception) {
        null
    }
}
