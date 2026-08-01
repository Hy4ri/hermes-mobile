package com.m57.hermescontrol.ui.chat.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Heuristic JSON → human summary engine for tool results.
 *
 * Ported from the Hermes desktop app's `lib/tool-result-summary.ts`
 * (verified against its vitest suite). Pure functions over a parsed
 * [JsonElement] — no UI deps, fully unit-testable.
 *
 * Two exports:
 * - [formatToolResultSummary]: best-effort one-line/block human summary of
 *   any tool result payload (wrapper unwrap, priority keys, depth caps).
 * - [extractToolErrorMessage]: deep hunt for a real error message through
 *   nested wrappers, with placeholder-value filtering.
 */
object ToolResultSummary {
    private val WRAPPER_KEYS = listOf("data", "result", "output", "response", "payload")

    private val PRIORITY_KEYS =
        listOf(
            "title",
            "name",
            "path",
            "file",
            "filepath",
            "url",
            "href",
            "link",
            "status",
            "id",
            "message",
            "summary",
            "description",
        )

    private val ERROR_KEYS = listOf("error", "errors", "failure", "exception")

    // 'stderr' deliberately excluded: many CLIs emit informational lines on
    // stderr (npm progress, git's hint:, gcc's `In file included from`) that
    // aren't errors. Treating those as error signal flipped tool cards into
    // destructive styling for healthy commands.
    private val ERROR_MSG_KEYS = listOf("message", "reason", "detail")
    private val NON_ERROR_TEXT = setOf("", "0", "false", "none", "null", "nil", "ok", "success", "n/a", "na")

    // ── helpers ──────────────────────────────────────────────────────────

    private fun normalize(value: String): String = value.lowercase().trim()

    private fun capitalize(value: String): String =
        value.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun titleCase(key: String): String =
        key
            .split(Regex("[_\\-.]+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ") { capitalize(it) }

    private fun pluralize(
        n: Int,
        noun: String,
    ): String = "$n $noun${if (n == 1) "" else "s"}"

    /** Trim + collapse whitespace, truncate with ellipsis. */
    private fun clipInline(
        value: String,
        max: Int = 180,
    ): String {
        val c = value.replace(Regex("\\s+"), " ").trim()

        return if (c.length > max) "${c.take(max - 1)}…" else c
    }

    /** First N lines + char cap, with a trailing ellipsis when clipped. */
    private fun clipBlock(
        value: String,
        maxChars: Int = 1800,
        maxLines: Int = 18,
    ): String {
        val t = value.trim()

        if (t.isEmpty()) {
            return ""
        }

        val lines = t.split("\n")
        var text = lines.take(maxLines).joinToString("\n")
        val clipped = lines.size > maxLines || text.length > maxChars

        if (text.length > maxChars) {
            text = text.take(maxChars - 1).trimEnd()
        }

        return if (clipped && !text.endsWith("…")) "$text…" else text
    }

    private fun firstString(
        record: JsonObject,
        keys: List<String>,
    ): String {
        for (k in keys) {
            val v = record[k] ?: continue
            if (v is JsonPrimitive && v.isString && v.content.trim().isNotEmpty()) {
                return v.content.trim()
            }
        }

        return ""
    }

    /** Re-parse stringified JSON payloads; everything else passes through. */
    private fun norm(value: JsonElement?): JsonElement? {
        val v = value ?: return null
        if (v is JsonNull) {
            return null
        }
        if (v !is JsonPrimitive || !v.isString) {
            return v
        }
        val t = v.content.trim()
        if (t.isEmpty() || !(t.startsWith("{") || t.startsWith("[") || t.startsWith("\""))) {
            return v
        }

        return try {
            Json.parseToJsonElement(t)
        } catch (_: Exception) {
            v
        }
    }

    private fun isWrapperKey(k: String): Boolean = k in WRAPPER_KEYS

    /** `success: true` / `ok: true` are noise — never rendered. */
    private fun skipField(
        k: String,
        v: JsonElement?,
    ): Boolean {
        if (isWrapperKey(k)) {
            return true
        }

        if ((k == "success" || k == "ok") && v is JsonPrimitive && !v.isString && v.content == "true") {
            return true
        }

        return false
    }

    private fun scalarString(v: JsonElement?): String? {
        if (v is JsonPrimitive && v.isString) {
            return v.content
        }

        if (v is JsonPrimitive && !v.isString) {
            val c = v.content
            if (c == "true" || c == "false" || c.toDoubleOrNull() != null) {
                return c
            }
        }

        return null
    }

    private fun summarizeScalar(v: JsonElement?): String {
        if (v is JsonPrimitive && v.isString) {
            return clipInline(v.content)
        }

        if (v is JsonPrimitive && !v.isString) {
            return v.content
        }

        return ""
    }

    private fun orderedKeys(keys: List<String>): List<String> {
        val priority = PRIORITY_KEYS.filter { it in keys }
        val rest = keys.filter { it !in PRIORITY_KEYS }

        return priority + rest
    }

    private fun summarizeRecordInline(
        record: JsonObject,
        depth: Int,
    ): String {
        if (depth > 3) {
            return pluralize(record.keys.size, "field")
        }

        val title =
            firstString(record, listOf("title", "name", "path", "file", "filepath", "url", "href", "link", "id"))
        val status = firstString(record, listOf("status", "category", "type"))
        val message = firstString(record, listOf("snippet", "summary", "description", "message"))

        if (title.isNotEmpty() && status.isNotEmpty()) {
            return "${clipInline(title, 110)} (${clipInline(status, 54)})"
        }

        if (title.isNotEmpty() && message.isNotEmpty() && title != message) {
            return "${clipInline(title, 90)} - ${clipInline(message, 84)}"
        }

        if (title.isNotEmpty()) {
            return clipInline(title, 150)
        }

        val pairs =
            orderedKeys(record.keys.toList())
                .filter { k -> !skipField(k, record[k]) }
                .mapNotNull { k ->
                    val s = summarizeScalar(record[k])
                    if (s.isEmpty()) null else "${titleCase(k)}: $s"
                }
                .take(2)

        return if (pairs.isNotEmpty()) pairs.joinToString(" · ") else pluralize(record.keys.size, "field")
    }

    private fun summarizeListItem(
        item: JsonElement?,
        depth: Int,
    ): String {
        val v = norm(item)
        if (v is JsonNull || v == null) {
            return ""
        }

        val scalar = scalarString(v)
        if (scalar != null) {
            return clipInline(scalar)
        }

        if (v is JsonArray) {
            return pluralize(v.size, "item")
        }

        if (v is JsonObject) {
            return summarizeRecordInline(v, depth + 1)
        }

        return clipInline(v.toString())
    }

    private fun formatFieldValue(
        value: JsonElement?,
        depth: Int,
    ): String {
        val v = norm(value)
        val scalar = summarizeScalar(v)

        if (scalar.isNotEmpty()) {
            return scalar
        }

        if (v == null || v is JsonNull) {
            return ""
        }

        if (v is JsonArray) {
            if (v.isEmpty()) {
                return ""
            }

            val scalars = v.mapNotNull { summarizeScalar(it).takeIf { s -> s.isNotEmpty() } }

            if (scalars.size == v.size && v.size <= 4) {
                return clipInline(scalars.joinToString(", "))
            }

            val first = summarizeListItem(v.firstOrNull(), depth + 1)

            return if (first.isNotEmpty()) "${pluralize(v.size, "item")} ($first)" else pluralize(v.size, "item")
        }

        if (v is JsonObject) {
            return summarizeRecordInline(v, depth + 1)
        }

        return clipInline(v.toString())
    }

    /**
     * "Returned N items" / "0 items" / "Returned an empty object" are all
     * noise — better to render nothing and let the title carry the signal.
     */
    private fun formatArraySummary(
        value: JsonArray,
        depth: Int,
    ): String {
        if (value.isEmpty()) {
            return ""
        }

        val max = 6
        val lines =
            value
                .take(max)
                .mapNotNull { item -> summarizeListItem(item, depth + 1).takeIf { it.isNotEmpty() } }
                .map { "- $it" }
                .toMutableList()

        if (lines.isEmpty()) {
            return ""
        }

        if (value.size > max) {
            val remaining = value.size - max
            lines += "- … $remaining more ${if (remaining == 1) "item" else "items"}"
        }

        return lines.joinToString("\n")
    }

    private fun formatRecordSummary(
        record: JsonObject,
        depth: Int,
    ): String {
        val keys = record.keys.toList()

        if (keys.isEmpty()) {
            return ""
        }

        if (depth <= 2) {
            val direct = firstString(record, listOf("message", "summary", "description", "preview", "text", "content"))
            val meaningful = keys.filter { k -> !skipField(k, record[k]) && !isWrapperKey(k) }

            if (direct.isNotEmpty() && meaningful.size <= 1) {
                return clipBlock(direct)
            }
        }

        val candidates = orderedKeys(keys).filter { k -> !skipField(k, record[k]) }
        val max = 8
        val lines = mutableListOf<String>()

        for (k in candidates) {
            val v = formatFieldValue(record[k], depth + 1)

            if (v.isEmpty()) {
                continue
            }

            lines += "- ${titleCase(k)}: $v"

            if (lines.size >= max) {
                break
            }
        }

        if (lines.isEmpty()) {
            return ""
        }

        if (candidates.size > lines.size) {
            val remaining = candidates.size - lines.size
            lines += "- … ${pluralize(remaining, "field")}"
        }

        return lines.joinToString("\n")
    }

    private fun formatSummaryValue(
        value: JsonElement?,
        depth: Int,
    ): String {
        if (depth > 4) {
            return ""
        }

        val v = norm(value)

        if (v is JsonPrimitive && v.isString) {
            return clipBlock(v.content)
        }

        if (v is JsonPrimitive && !v.isString) {
            return v.content
        }

        if (v == null || v is JsonNull) {
            return ""
        }

        if (v is JsonArray) {
            return formatArraySummary(v, depth + 1)
        }

        if (v is JsonObject) {
            return formatRecordSummary(v, depth + 1)
        }

        return clipInline(v.toString())
    }

    /** Peel up to 4 wrapper layers (`data` / `result` / `output` / …). */
    private fun unwrapPayload(value: JsonElement?): JsonElement? {
        var cur: JsonElement? = norm(value)

        for (i in 0 until 4) {
            val record = cur as? JsonObject ?: return cur
            val key = WRAPPER_KEYS.firstOrNull { k -> record[k] != null && record[k] !is JsonNull } ?: return record

            cur = norm(record[key])
        }

        return cur
    }

    // ── public API ───────────────────────────────────────────────────────

    fun formatToolResultSummary(value: JsonElement?): String {
        val unwrapped = formatSummaryValue(unwrapPayload(value), 0)

        return if (unwrapped.isNotEmpty()) unwrapped else formatSummaryValue(value, 0)
    }

    // ── error extraction ─────────────────────────────────────────────────

    private fun hasMeaningfulErrorValue(value: JsonElement?): Boolean {
        val v = norm(value)

        if (v == null || v is JsonNull) {
            return false
        }

        if (v is JsonPrimitive) {
            if (v.isString) {
                return normalize(v.content) !in NON_ERROR_TEXT
            }

            return v.content != "0" && v.content != "false"
        }

        if (v is JsonArray) {
            return v.any { hasMeaningfulErrorValue(it) }
        }

        if (v is JsonObject) {
            return v.keys.isNotEmpty()
        }

        return true
    }

    private fun hasErrorSignal(record: JsonObject): Boolean {
        val status = (record["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

        return (
            (record["success"] as? JsonPrimitive)?.let { !it.isString && it.content == "false" } == true ||
                (record["ok"] as? JsonPrimitive)?.let { !it.isString && it.content == "false" } == true ||
                Regex(
                    "\\b(error|failed|failure|fatal|exception)\\b",
                    RegexOption.IGNORE_CASE,
                ).containsMatchIn(status) ||
                ERROR_KEYS.any { k -> hasMeaningfulErrorValue(record[k]) }
        )
    }

    private fun valueErrorText(value: JsonElement?): String {
        val v = norm(value)

        if (v is JsonPrimitive && v.isString) {
            return if (hasMeaningfulErrorValue(v)) clipBlock(v.content, 700, 12) else ""
        }

        if (v is JsonArray) {
            return clipBlock(
                v.mapNotNull {
                    valueErrorText(it).takeIf {
                            s ->
                        s.isNotEmpty()
                    }
                }.take(3).joinToString("; "),
                700,
                12,
            )
        }

        if (v is JsonObject) {
            val direct = firstString(v, ERROR_MSG_KEYS)

            if (direct.isNotEmpty()) {
                return clipBlock(direct, 700, 12)
            }
        }

        return ""
    }

    private fun findNestedError(
        value: JsonElement?,
        depth: Int,
        seen: MutableSet<JsonElement>,
    ): String {
        if (depth > 5) {
            return ""
        }

        val v = norm(value) ?: return ""

        if (v is JsonNull) {
            return ""
        }

        if (v in seen) {
            return ""
        }
        seen.add(v)

        if (v is JsonArray) {
            for (item in v) {
                val nested = findNestedError(item, depth + 1, seen)

                if (nested.isNotEmpty()) {
                    return nested
                }
            }

            return ""
        }

        if (v !is JsonObject) {
            return ""
        }

        for (k in ERROR_KEYS) {
            if (!hasMeaningfulErrorValue(v[k])) {
                continue
            }

            val text = valueErrorText(v[k])

            if (text.isNotEmpty()) {
                return text
            }
        }

        if (hasErrorSignal(v)) {
            val direct = firstString(v, ERROR_MSG_KEYS)

            if (direct.isNotEmpty()) {
                return clipBlock(direct, 700, 12)
            }
        }

        for (k in ERROR_KEYS + WRAPPER_KEYS + listOf("details", "meta")) {
            val nested = findNestedError(v[k], depth + 1, seen)

            if (nested.isNotEmpty()) {
                return nested
            }
        }

        return ""
    }

    fun extractToolErrorMessage(value: JsonElement?): String = findNestedError(value, 0, mutableSetOf())
}
