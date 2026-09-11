package com.m57.hermescontrol.ui.chat.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared parsing/normalization primitives for the tool-display engine.
 *
 * Everything in here is payload-shape plumbing — no per-tool knowledge.
 * Per-tool formatting lives in the renderers under [render][com.m57.hermescontrol.ui.chat.tool.render].
 */
internal object ToolJson {
    private val URL_PATTERN = Regex("https?://[^\\s'\"<>)\\]]+", RegexOption.IGNORE_CASE)

    const val MAX_TOOL_RENDER_CHARS = 20_000

    fun clampForDisplay(
        value: String,
        max: Int = MAX_TOOL_RENDER_CHARS,
    ): String {
        if (value.length <= max) return value
        val omitted = value.length - max
        return value.take(max) +
            "\n… $omitted more characters truncated — use Copy for the full output."
    }

    /** First non-blank string value among [keys], trimmed. */
    fun firstString(
        record: JsonObject?,
        keys: List<String>,
    ): String {
        val r = record ?: return ""

        for (k in keys) {
            val v = r[k] ?: continue
            if (v is JsonPrimitive && v.isString && v.content.trim().isNotEmpty()) {
                return v.content.trim()
            }
        }

        return ""
    }

    fun numberValue(value: JsonElement?): Double? {
        val v = value ?: return null
        if (v is JsonNull) {
            return null
        }
        if (v !is JsonPrimitive) {
            return null
        }

        // TS source does `Number(value)` — numeric STRINGS ("0.0", "12") are
        // accepted, matching the desktop engine and the gateway's habit of
        // shipping exit_code as a string or float.
        return v.content.toDoubleOrNull()
    }

    fun intValue(value: JsonElement?): Int? {
        val n = numberValue(value) ?: return null

        return n.toInt()
    }

    /** True only for a literal JSON `true` (not the string "true"). */
    fun boolTrue(value: JsonElement?): Boolean =
        (value as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true

    /** True only for a literal JSON `false` (not the string "false"). */
    fun boolFalse(value: JsonElement?): Boolean =
        (value as? JsonPrimitive)?.let { !it.isString && it.content == "false" } == true

    fun compactPreview(
        value: String,
        max: Int = 72,
    ): String {
        val line = value.replace(Regex("\\s+"), " ").trim()

        return if (line.length > max) "${line.take(max - 1)}…" else line
    }

    fun compactPreview(
        value: JsonElement?,
        max: Int = 72,
    ): String {
        val raw: String =
            if (value is JsonPrimitive && value.isString) {
                value.content
            } else {
                firstString(parseMaybeObject(value), listOf("context"))
            }

        return compactPreview(raw, max)
    }

    fun contextValue(value: JsonElement?): String {
        val row = parseMaybeObject(value)
        val context = firstString(row, listOf("context"))
        val preview = firstString(row, listOf("preview"))

        if (context.isNotEmpty()) {
            return context
        }
        if (preview.isNotEmpty()) {
            return preview
        }

        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    }

    /** Objects pass through; stringified JSON objects are re-parsed. */
    fun parseMaybeObject(value: JsonElement?): JsonObject? =
        when (value) {
            is JsonObject -> {
                value
            }

            is JsonPrimitive -> {
                if (!value.isString || value.content.trim().isEmpty()) {
                    null
                } else {
                    try {
                        Json.parseToJsonElement(value.content) as? JsonObject
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            else -> {
                null
            }
        }

    /** Peel one `data`/`result`/`output`/`response`/`payload` wrapper layer. */
    fun unwrapToolPayload(value: JsonElement?): JsonElement? {
        val record = parseMaybeObject(value) ?: return value

        for (key in listOf("data", "result", "output", "response", "payload")) {
            val payload = record[key] ?: continue
            if (payload is JsonNull) {
                continue
            }

            return payload
        }

        return value
    }

    fun findFirstUrl(vararg sources: JsonElement?): String {
        for (src in sources) {
            if (src is JsonPrimitive && src.isString) {
                val m = URL_PATTERN.find(src.content)

                if (m != null) {
                    return m.value
                }
            } else if (src is JsonObject) {
                for (v in src.values) {
                    val found = findFirstUrl(v)

                    if (found.isNotEmpty()) {
                        return found
                    }
                }
            }
        }

        return ""
    }

    fun hostnameOf(value: String): String =
        try {
            val url = java.net.URI(value)
            val path = url.path?.takeIf { it.isNotEmpty() && it != "/" } ?: ""
            "${url.host ?: value}$path"
        } catch (_: Exception) {
            value
        }

    fun stripAnsi(value: String): String = value.replace(Regex("\u001B\\[[0-9;]*m"), "")

    fun stripDividerLines(value: String): String =
        value
            .split("\n")
            .filter { !Regex("^[-=]{3,}\\s*$").matches(it.trim()) }
            .joinToString("\n")
            .trim()

    fun cleanVisibleText(text: String): String =
        text
            .replace(Regex("`{3,}"), "")
            .replace(Regex("(?<=[\\p{L}\\p{N})\\].,!?:;\"'”’])\\[(?:\\d+(?:\\s*,\\s*\\d+)*)\\](?!\\()"), "")
            .replace(Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)")) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }

    /** Find the result-item array behind common list keys or wrappers. */
    fun collectResultItems(value: JsonElement?): List<JsonElement> {
        if (value is JsonArray) {
            return value.toList()
        }

        val record = parseMaybeObject(value) ?: return emptyList()

        val keys =
            listOf(
                "web",
                "results",
                "search_results",
                "sources",
                "web_sources",
                "items",
                "organic_results",
                "organic",
                "matches",
                "documents",
            )

        for (key in keys) {
            val candidate = record[key] ?: continue

            if (candidate is JsonArray) {
                return candidate.toList()
            }

            if (candidate is JsonObject) {
                val nested = collectResultItems(candidate)

                if (nested.isNotEmpty()) {
                    return nested
                }
            }
        }

        val payload = unwrapToolPayload(record)

        return if (payload === record) emptyList() else collectResultItems(payload)
    }

    /** Humanized generic title: "fact_store" → "Fact Store", sans browser_/web_ prefix. */
    fun titleForTool(name: String): String {
        val normalized = name.replace(Regex("^browser_"), "").replace(Regex("^web_"), "")

        return normalized
            .split("_")
            .filter { it.isNotEmpty() }
            .joinToString(
                " ",
            ) { part -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
            .ifEmpty { name }
    }

    /** Generic detail: prefer a fresh result context, then args, then the heuristic summary. */
    fun fallbackDetailText(
        args: JsonElement?,
        result: JsonElement?,
    ): String {
        val argContext = contextValue(args)
        val resultContext = contextValue(result)

        if (resultContext.isNotEmpty() && resultContext != argContext) {
            return resultContext
        }

        if (argContext.isNotEmpty()) {
            return argContext
        }

        if (result != null) {
            return ToolResultSummary.formatToolResultSummary(result)
        }

        return ToolResultSummary.formatToolResultSummary(args)
    }

    fun formatDurationSeconds(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) {
            return ""
        }

        if (seconds < 1) {
            val ms = maxOf(1, Math.round(seconds * 1000))
            return "${ms}ms"
        }

        if (seconds < 60) {
            return if (seconds >= 10) "${seconds.toInt()}s" else "${"%.1f".format(seconds)}s"
        }

        val wholeSeconds = Math.round(seconds)
        val minutes = wholeSeconds / 60
        val remSeconds = wholeSeconds % 60

        if (minutes < 60) {
            return if (remSeconds != 0L) "${minutes}m ${remSeconds}s" else "${minutes}m"
        }

        val hours = minutes / 60
        val remMinutes = minutes % 60

        return if (remMinutes != 0L) "${hours}h ${remMinutes}m" else "${hours}h"
    }

    fun durationLabel(result: JsonObject?): String? {
        val seconds =
            numberValue(result?.get("duration_s"))
                ?: numberValue(result?.get("duration_seconds"))
                ?: numberValue(result?.get("total_duration_seconds"))
                ?: return null

        return formatDurationSeconds(seconds)
    }
}
