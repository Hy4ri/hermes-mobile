package com.m57.hermescontrol.ui.chat.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Per-tool display engine — a port of the desktop app's `buildToolView`
 * (fallback-model), extended with mobile-specific tool cases.
 *
 * One pure function turns a tool call's args/result into a [ToolView] the
 * renderer can paint without touching raw JSON. Structure:
 *
 * - status heuristics (running / success / error / warning) with the
 *   desktop's error-detection rules (non-zero exit alone is NOT an error)
 * - per-tool title/subtitle/detail optimizations (terminal, file edits,
 *   web/browser tools, memory, cron, …)
 * - count-label extraction, search-hit parsing, stdout/stderr split,
 *   inline diff extraction, duration formatting
 * - generic fallback via [ToolResultSummary] so unknown tools still read
 *   as a human summary instead of a raw JSON dump
 */
object ToolViewBuilder {
    private val HTML_PATH_REGEX =
        Regex(
            "(?:^|\\s)(?:[ab]/)?([^\\s]+\\.html?)(?=\\s|$)",
            RegexOption.IGNORE_CASE,
        )

    // ── shared field helpers ─────────────────────────────────────────────

    private fun firstString(
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

    private fun numberValue(value: JsonElement?): Double? {
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

    private fun intValue(value: JsonElement?): Int? {
        val n = numberValue(value) ?: return null

        return n.toInt()
    }

    private fun compactPreview(
        value: String,
        max: Int = 72,
    ): String {
        val line = value.replace(Regex("\\s+"), " ").trim()

        return if (line.length > max) "${line.take(max - 1)}…" else line
    }

    private fun compactPreview(
        value: JsonElement?,
        max: Int = 72,
    ): String {
        var raw: String? = null

        if (value is JsonPrimitive && value.isString) {
            raw = value.content
        } else {
            raw = firstString(parseMaybeObject(value), listOf("context"))
        }

        return compactPreview(raw ?: "", max)
    }

    private fun contextValue(value: JsonElement?): String {
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

    private fun parseMaybeObject(value: JsonElement?): JsonObject? =
        when (value) {
            is JsonObject -> value
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
            else -> null
        }

    private fun unwrapToolPayload(value: JsonElement?): JsonElement? {
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

    private fun looksLikeUrl(value: String): Boolean =
        Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(value)

    private val URL_PATTERN = Regex("https?://[^\\s'\"<>)\\]]+", RegexOption.IGNORE_CASE)

    private fun findFirstUrl(vararg sources: JsonElement?): String {
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

    private fun hostnameOf(value: String): String =
        try {
            val url = java.net.URI(value)
            val path = url.path?.takeIf { it.isNotEmpty() && it != "/" } ?: ""
            "${url.host ?: value}$path"
        } catch (_: Exception) {
            value
        }

    private fun formatDurationSeconds(seconds: Double): String {
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

    private fun durationLabel(result: JsonObject?): String? {
        val seconds = numberValue(result?.get("duration_s")) ?: return null

        return formatDurationSeconds(seconds)
    }

    // ── file edit helpers ────────────────────────────────────────────────

    private val FILE_EDIT_TOOL_NAMES = setOf("edit_file", "patch", "write_file")

    private fun isFileEditTool(toolName: String): Boolean = toolName in FILE_EDIT_TOOL_NAMES

    private fun fileEditBasename(path: String): String {
        val normalized = path.replace('\\', '/').trim()

        return normalized.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: normalized
    }

    private fun fileEditPath(
        args: JsonObject?,
        result: JsonObject?,
    ): String {
        val fromArgs = firstString(args, listOf("path", "file", "filepath"))
        if (fromArgs.isNotEmpty()) {
            return fromArgs
        }

        val fromResult = firstString(result, listOf("path", "file", "filepath", "resolved_path"))
        if (fromResult.isNotEmpty()) {
            return fromResult
        }

        return htmlPathFromInlineDiff(firstString(result, listOf("inline_diff", "diff")))
    }

    private fun stripAnsi(value: String): String = value.replace(Regex("\u001B\\[[0-9;]*m"), "")

    private fun stripInlineDiffChrome(value: String): String =
        stripAnsi(value)
            .replace(Regex("^\\s*┊\\s*review diff\\s*\\n", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun inlineDiffFromResult(result: JsonElement?): String {
        val record = parseMaybeObject(result) ?: return ""

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

    private fun countDiffLineStats(diff: String): DiffStats {
        var added = 0
        var removed = 0

        for (line in diff.split("\n")) {
            when {
                line.startsWith("+") && !line.startsWith("+++") -> added += 1
                line.startsWith("-") && !line.startsWith("---") -> removed += 1
            }
        }

        return DiffStats(added = added, removed = removed)
    }

    // ── status + error detection ─────────────────────────────────────────

    private fun toolErrorText(
        toolName: String,
        isError: Boolean,
        result: JsonElement?,
        resultRecord: JsonObject?,
    ): String {
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

        val error = firstString(resultRecord, listOf("error"))
        if (error.isNotEmpty()) {
            return error
        }

        val extracted = ToolResultSummary.extractToolErrorMessage(result)
        if (extracted.isNotEmpty()) {
            return extracted
        }

        if (resultRecord != null) {
            val successFalse =
                (resultRecord["success"] as? JsonPrimitive)?.let { !it.isString && it.content == "false" }
            val okFalse = (resultRecord["ok"] as? JsonPrimitive)?.let { !it.isString && it.content == "false" }

            if (successFalse == true || okFalse == true) {
                return firstString(
                    resultRecord,
                    listOf("message", "reason", "detail"),
                ).ifEmpty { "Tool returned success=false." }
            }

            val status = firstString(resultRecord, listOf("status"))
            if (Regex("\\b(error|failed|failure)\\b", RegexOption.IGNORE_CASE).containsMatchIn(status)) {
                return firstString(
                    resultRecord,
                    listOf("message", "reason", "detail"),
                ).ifEmpty { "Tool returned status \"$status\"." }
            }

            // A non-zero exit code alone is a weak failure signal: grep
            // returns 1 on no-match, diff returns 1 on differences, piped
            // commands surface the last stage's code — all routinely produce
            // useful output. Only treat it as an error when the command
            // produced no real output to show.
            val exit = numberValue(resultRecord["exit_code"])
            if (exit != null && exit != 0.0) {
                val hasOutput =
                    listOf("output", "stdout", "stderr", "output_preview")
                        .any { k -> firstString(resultRecord, listOf(k)).isNotEmpty() }

                if (!hasOutput) {
                    return "Command failed with exit code ${exit.toInt()}."
                }
            }
        }

        return ""
    }

    private fun toolStatus(
        toolName: String,
        result: JsonElement?,
        resultRecord: JsonObject?,
        isError: Boolean,
        running: Boolean,
    ): ToolViewStatus {
        if (running || result == null) {
            return ToolViewStatus.RUNNING
        }

        // Explicit success wins over isError / nested-error heuristics.
        if (resultRecord != null) {
            val successTrue = (resultRecord["success"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" }
            val okTrue = (resultRecord["ok"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" }

            if (successTrue == true || okTrue == true) {
                return ToolViewStatus.SUCCESS
            }
        }

        if (toolErrorText(toolName, isError, result, resultRecord).isEmpty()) {
            return ToolViewStatus.SUCCESS
        }

        // A rejected memory write is a budget negotiation, not a failure.
        return if (toolName == "memory") ToolViewStatus.WARNING else ToolViewStatus.ERROR
    }

    // ── titles ───────────────────────────────────────────────────────────

    /**
     * The generic humanized title for a tool name ("fact_store" -> "Fact
     * Store"). Exposed so the view can drop the title from the collapsed
     * summary when it adds nothing over the header row's own tool name.
     */
    internal fun genericTitleFor(toolName: String?): String = titleForTool(toolName ?: "tool")

    private fun titleForTool(name: String): String {
        val normalized = name.replace(Regex("^browser_"), "").replace(Regex("^web_"), "")

        return normalized
            .split("_")
            .filter { it.isNotEmpty() }
            .joinToString(
                " ",
            ) { part -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
            .ifEmpty { name }
    }

    private fun shellCommand(args: JsonObject?): String =
        firstString(args, listOf("command", "code"))
            .ifEmpty { firstString(args, listOf("context", "preview")) }
            .ifEmpty { contextValue(args) }

    private fun summarizeBrowserSnapshot(snapshot: String): String {
        fun count(re: Regex): Int = re.findAll(snapshot).count()

        val stats =
            listOf(
                "${count(Regex("""button\s+"[^"]+"""))} buttons",
                "${count(Regex("""link\s+"[^"]+"""))} links",
                "${count(Regex("""(?:textbox|combobox|searchbox)\s+"[^"]+"""))} inputs",
            ).joinToString(" · ")

        val labels =
            Regex("""(?:button|link|combobox|textbox)\s+"([^"]+)"""")
                .findAll(snapshot)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .take(4)
                .toList()

        return if (labels.isNotEmpty()) "$stats\nTop controls: ${labels.joinToString(", ")}" else stats
    }

    private fun readFileDisplayTarget(
        args: JsonObject?,
        result: JsonObject?,
    ): String {
        val inherited = firstString(args, listOf("context", "preview"))
        if (inherited.isNotEmpty()) {
            return inherited
        }

        val path = firstString(args, listOf("path", "file", "filepath"))
        if (path.isEmpty()) {
            return ""
        }

        val offset = intValue(args?.get("offset"))
        val limit = intValue(args?.get("limit"))
        var lineLabel = ""

        if (offset != null) {
            lineLabel = if (limit == null || limit <= 1) "L$offset" else "L$offset-${offset + limit - 1}"
        } else if (limit != null) {
            val content = firstString(result, listOf("content"))
            val lines =
                content
                    .split("\n")
                    .mapNotNull { line -> Regex("^(\\d+)\\|").find(line)?.groupValues?.get(1)?.toIntOrNull() }

            if (lines.isNotEmpty()) {
                lineLabel =
                    if (lines.first() == lines.last()) {
                        "L${lines.first()}"
                    } else {
                        "L${lines.first()}-${lines.last()}"
                    }
            }
        }

        return listOf(fileEditBasename(path), lineLabel).filter { it.isNotEmpty() }.joinToString(" ")
    }

    // ── count extraction ─────────────────────────────────────────────────

    private val COUNT_FIELD_KEYS =
        listOf(
            "count",
            "total",
            "result_count",
            "results_count",
            "num_results",
            "match_count",
            "matches_count",
            "file_count",
            "files_count",
            "item_count",
            "items_count",
            "search_count",
            "searches_count",
            "source_count",
            "sources_count",
            "document_count",
            "documents_count",
            "updated",
            "added",
            "removed",
            "deleted",
            "created",
            "changed",
            "processed",
            "steps",
        )

    private val COUNT_ARRAY_KEYS = listOf("results", "items", "matches", "files", "documents", "sources", "rows")

    private val COUNT_EXCLUDED_KEYS = setOf("duration_s", "exit_code", "status_code")

    private fun countFromUnknown(value: JsonElement?): Int? {
        if (value is JsonArray) {
            return if (value.isNotEmpty()) value.size else null
        }

        val n = numberValue(value) ?: return null

        if (n <= 0) {
            return null
        }

        return Math.round(n).toInt()
    }

    private fun singularizeNoun(noun: String): String {
        val normalized = noun.lowercase()

        if (normalized.isEmpty()) {
            return ""
        }

        if (normalized.endsWith("ies") && normalized.length > 3) {
            return "${normalized.dropLast(3)}y"
        }

        if (Regex("(xes|zes|ches|shes|sses)$").containsMatchIn(normalized) && normalized.length > 3) {
            return normalized.dropLast(2)
        }

        if (normalized.endsWith("s") && normalized.length > 2 && !normalized.endsWith("ss")) {
            return normalized.dropLast(1)
        }

        return normalized
    }

    private fun pluralizeNoun(
        noun: String,
        count: Int,
    ): String {
        if (count == 1) {
            return noun
        }

        if (noun == "search") {
            return "searches"
        }

        if (noun.endsWith(
                "y",
            ) && noun.length > 1 && !Regex("[aeiou]y$", RegexOption.IGNORE_CASE).containsMatchIn(noun)
        ) {
            return "${noun.dropLast(1)}ies"
        }

        if (Regex("(s|x|z|ch|sh)$", RegexOption.IGNORE_CASE).containsMatchIn(noun)) {
            return "${noun}es"
        }

        return "${noun}s"
    }

    private fun countMetric(
        count: Int,
        noun: String,
    ): Pair<Int, String> = count to singularizeNoun(noun).ifEmpty { "item" }

    private fun countFromRecord(
        record: JsonObject,
        fallbackNoun: String,
    ): Pair<Int, String>? {
        val nounByField =
            mapOf(
                "result_count" to "result",
                "results_count" to "result",
                "num_results" to "result",
                "match_count" to "match",
                "matches_count" to "match",
                "file_count" to "file",
                "files_count" to "file",
                "item_count" to "item",
                "items_count" to "item",
                "search_count" to "search",
                "searches_count" to "search",
                "source_count" to "source",
                "sources_count" to "source",
                "document_count" to "document",
                "documents_count" to "document",
                "updated" to "item",
                "added" to "item",
                "removed" to "item",
                "deleted" to "item",
                "created" to "item",
                "changed" to "item",
                "processed" to "item",
                "steps" to "step",
            )
        val nounByArray =
            mapOf(
                "documents" to "document",
                "files" to "file",
                "items" to "item",
                "matches" to "match",
                "results" to "result",
                "rows" to "row",
                "sources" to "source",
            )

        for (key in COUNT_FIELD_KEYS) {
            val count = countFromUnknown(record[key]) ?: continue

            return countMetric(count, nounByField[key] ?: fallbackNoun)
        }

        for (key in COUNT_ARRAY_KEYS) {
            val count = countFromUnknown(record[key]) ?: continue

            return countMetric(count, nounByArray[key] ?: fallbackNoun)
        }

        for ((key, value) in record) {
            if (key in COUNT_EXCLUDED_KEYS) {
                continue
            }
            if (!Regex("_count$|_total$").containsMatchIn(key)) {
                continue
            }

            val count = countFromUnknown(value) ?: continue
            val stripped = key.lowercase().replace(Regex("_(count|total)$"), "").replace(Regex("^num_"), "")

            return countMetric(count, singularizeNoun(stripped).ifEmpty { fallbackNoun })
        }

        return null
    }

    private fun countFromText(
        text: String,
        fallbackNoun: String,
    ): Pair<Int, String>? {
        val t = text.trim()
        if (t.isEmpty()) {
            return null
        }

        val unitMatch =
            Regex(
                """\b(\d+)\s+(results?|items?|files?|matches?|documents?|sources?|searches?|steps?|rows?)\b""",
                RegexOption.IGNORE_CASE,
            )
                .find(t)
                ?: Regex(
                    """\b(?:did|found|returned|listed|searched|matched|updated|created|deleted|processed)\s+(\d+)\b""",
                    RegexOption.IGNORE_CASE,
                )
                    .find(t)

        val n = unitMatch?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val noun = unitMatch.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: fallbackNoun

        return if (n > 0) countMetric(n, noun) else null
    }

    private fun collectResultItems(value: JsonElement?): List<JsonElement> {
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

    private fun cleanVisibleText(text: String): String {
        return text
            .replace(Regex("`{3,}"), "")
            .replace(Regex("(?<=[\\p{L}\\p{N})\\].,!?:;\"'”’])\\[(?:\\d+(?:\\s*,\\s*\\d+)*)\\](?!\\()"), "")
            .replace(Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)")) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }
    }

    private fun extractSearchResults(
        result: JsonElement?,
        limit: Int = 6,
    ): List<SearchHit> {
        return collectResultItems(result)
            .mapNotNull { item ->
                val r = parseMaybeObject(item) ?: return@mapNotNull null

                SearchHit(
                    title = cleanVisibleText(firstString(r, listOf("title", "name"))),
                    url = firstString(r, listOf("url", "href", "link")),
                    snippet = cleanVisibleText(firstString(r, listOf("snippet", "description", "body"))),
                )
            }
            .filter { it.title.isNotEmpty() || it.url.isNotEmpty() }
            .take(limit)
    }

    // ── detail helpers ───────────────────────────────────────────────────

    private fun looksRedundant(
        title: String,
        detail: String,
    ): Boolean {
        if (detail.isEmpty()) {
            return true
        }

        val norm: (String) -> String = { input -> input.lowercase().replace(Regex("\\s+"), " ").trim() }

        return norm(title) == norm(detail)
    }

    private fun fallbackDetailText(
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

    private fun minimalValueSummary(value: JsonElement?): String {
        if (value == null || value is JsonNull) {
            return ""
        }

        if (value is JsonPrimitive) {
            return value.content
        }

        return ""
    }

    private fun shellOutput(result: JsonObject?): String {
        val output = firstString(result, listOf("output", "stdout", "stderr"))
        val lines =
            (result?.get("lines") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.joinToString("\n")
                ?: ""

        return listOf(output, lines).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun stripDividerLines(value: String): String =
        value
            .split("\n")
            .filter { !Regex("^[-=]{3,}\\s*$").matches(it.trim()) }
            .joinToString("\n")
            .trim()

    // ── the builder ──────────────────────────────────────────────────────

    fun build(
        toolName: String,
        args: JsonElement?,
        result: JsonElement?,
        isError: Boolean = false,
        running: Boolean = false,
    ): ToolView {
        val argsRecord = parseMaybeObject(args)
        val resultRecord = parseMaybeObject(result)
        val status = toolStatus(toolName, result, resultRecord, isError, running)
        val error =
            if (status == ToolViewStatus.SUCCESS) {
                ""
            } else {
                toolErrorText(toolName, isError, result, resultRecord)
            }

        val title =
            when {
                running -> pendingTitle(toolName, argsRecord)
                error.isNotEmpty() -> errorTitle(toolName, resultRecord)
                else -> doneTitle(toolName, argsRecord, resultRecord)
            }

        val subtitle = subtitleFor(toolName, argsRecord, resultRecord, result, error)
        val detailBody = stripDividerLines(detailFor(toolName, argsRecord, resultRecord, result, args))
        val detail =
            if (error.isNotEmpty()) {
                listOf(error, detailBody)
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString("\n\n")
            } else if (looksRedundant(title, detailBody) || looksRedundant(subtitle, detailBody)) {
                // Detail that repeats the header is noise — e.g. a read_file
                // whose content equals its own title. The header carries it.
                ""
            } else {
                detailBody
            }

        val rendersAnsi = toolName == "terminal" || toolName == "execute_code"
        // The gateway's terminal_tool emits a single merged `output` string
        // (stdout+stderr, ANSI-stripped); older payloads carried separate
        // `stdout`/`stderr`. Prefer a real stream split, fall back to the
        // merged output as the single renderable stream.
        val stdout = if (rendersAnsi) firstString(resultRecord, listOf("stdout", "output")) else ""
        val stderrRaw = if (rendersAnsi) firstString(resultRecord, listOf("stderr")) else ""
        val hasSplitStreams = rendersAnsi && (stdout.isNotEmpty() || stderrRaw.isNotEmpty())

        val searchHits =
            if (toolName == "web_search" && status != ToolViewStatus.ERROR) {
                extractSearchResults(result)
            } else {
                emptyList()
            }

        val searchQuery =
            if (toolName == "web_search") {
                firstString(argsRecord, listOf("search_term", "query")).ifEmpty { contextValue(argsRecord) }
            } else {
                ""
            }

        val countLabel =
            if (status == ToolViewStatus.ERROR) {
                null
            } else {
                countLabelFor(
                    toolName,
                    argsRecord,
                    resultRecord,
                    result,
                )
            }
        val inlineDiff = if (isFileEditTool(toolName)) inlineDiffFromResult(result) else ""
        val diffStats = if (inlineDiff.isNotEmpty()) countDiffLineStats(inlineDiff) else null

        return ToolView(
            status = status,
            title = title,
            subtitle = subtitle,
            detail = detail,
            detailLabel = if (toolName == "web_search" && searchHits.isNotEmpty()) "Search results" else null,
            countLabel = countLabel?.let { (count, noun) -> "$count ${pluralizeNoun(noun, count)}" },
            durationLabel = durationLabel(resultRecord),
            stdout = if (hasSplitStreams) stdout else null,
            stderr = if (hasSplitStreams) stderrRaw else null,
            exitCode = if (toolName == "terminal") intValue(resultRecord?.get("exit_code")) else null,
            terminalCommand = if (toolName == "terminal") shellCommand(argsRecord) else null,
            inlineDiff = inlineDiff.ifEmpty { null },
            diffPath = if (inlineDiff.isNotEmpty()) fileEditPath(argsRecord, resultRecord) else null,
            diffStats = diffStats,
            imageUrl = imageUrlFor(argsRecord, resultRecord),
            searchHits = searchHits.ifEmpty { null },
            searchQuery = searchQuery.ifEmpty { null },
            error = error.ifEmpty { null },
        )
    }

    // ── titles per tool ──────────────────────────────────────────────────

    private fun pendingTitle(
        toolName: String,
        args: JsonObject?,
    ): String {
        return when (toolName) {
            "web_extract" -> "Reading ${targetHostname(toolName, args, null)}"
            "browser_navigate" -> "Opening ${targetHostname(toolName, args, null)}"
            "web_search" -> "Searching \"${compactPreview(searchQueryFor(args), 48)}\""
            "read_file" -> "Reading ${readFileDisplayTarget(args, null)}"
            "terminal", "execute_code" -> {
                val command = shellCommand(args)
                val verb = if (toolName == "execute_code") "Running code" else "Running"
                if (command.isNotEmpty()) {
                    "$verb ${compactPreview(
                        CommandSummarizer.summarizeShellCommand(command),
                        160,
                    )}"
                } else {
                    titleForTool(toolName)
                }
            }
            "memory" -> "Saving"
            else -> titleForTool(toolName)
        }
    }

    private fun doneTitle(
        toolName: String,
        args: JsonObject?,
        result: JsonObject?,
    ): String {
        return when (toolName) {
            "web_extract" -> "Read ${targetHostname(toolName, args, result)}"
            "browser_navigate" -> "Opened ${targetHostname(toolName, args, result)}"
            "web_search" -> "Searched \"${compactPreview(searchQueryFor(args), 48)}\""
            "read_file" -> "Read ${readFileDisplayTarget(args, result)}"
            "terminal", "execute_code" -> {
                val command = shellCommand(args)
                val verb = if (toolName == "execute_code") "Ran code" else "Ran"
                if (command.isNotEmpty()) {
                    "$verb ${compactPreview(
                        CommandSummarizer.summarizeShellCommand(command),
                        160,
                    )}"
                } else {
                    titleForTool(toolName)
                }
            }
            "memory" -> {
                val action = firstString(args, listOf("action")).lowercase()
                val target = firstString(args, listOf("target"))
                val base =
                    when (action) {
                        "replace", "update" -> "Memory Updated"
                        "remove", "delete" -> "Memory Removed"
                        "add" -> "Memory Saved"
                        else -> "Memory ${firstString(args, listOf("action"))}"
                    }

                // Keep the target visible — "Memory Saved (user)" — like the
                // pre-engine mobile summary did.
                if (target.isNotEmpty()) "$base ($target)" else base
            }
            "cronjob" -> "Cron ${firstString(args, listOf("action")).ifEmpty { "manage" }}"
            "edit_file", "patch", "write_file" -> {
                val path = fileEditPath(args, result)
                if (path.isNotEmpty()) fileEditBasename(path) else titleForTool(toolName)
            }
            else -> titleForTool(toolName)
        }
    }

    private fun errorTitle(
        toolName: String,
        result: JsonObject?,
    ): String {
        return when (toolName) {
            "browser_navigate" -> {
                val url = findFirstUrl(result).ifEmpty { result?.get("url")?.toString() ?: "" }
                if (url.isNotEmpty()) "Failed to open ${hostnameOf(url)}" else titleForTool(toolName)
            }
            else -> titleForTool(toolName)
        }
    }

    private fun targetHostname(
        toolName: String,
        args: JsonObject?,
        result: JsonObject?,
    ): String {
        val url =
            firstString(args, listOf("url", "target"))
                .ifEmpty { firstString(result, listOf("url")) }
                .ifEmpty { findFirstUrl(args, result) }

        return if (url.isNotEmpty()) hostnameOf(url) else "page"
    }

    private fun searchQueryFor(args: JsonObject?): String =
        firstString(args, listOf("search_term", "query")).ifEmpty { contextValue(args) }

    // ── subtitles per tool ───────────────────────────────────────────────

    private fun subtitleFor(
        toolName: String,
        args: JsonObject?,
        result: JsonObject?,
        rawResult: JsonElement?,
        error: String,
    ): String {
        if (error.isNotEmpty()) {
            return error
        }

        return when (toolName) {
            "browser_navigate" -> {
                val url =
                    firstString(args, listOf("url", "target"))
                        .ifEmpty { firstString(result, listOf("url")) }
                        .ifEmpty { findFirstUrl(args, result) }

                if (url.isNotEmpty()) hostnameOf(url) else "Navigated in browser"
            }
            "browser_snapshot" -> {
                val snapshot = firstString(result, listOf("snapshot"))
                if (snapshot.isNotEmpty()) {
                    summarizeBrowserSnapshot(
                        snapshot,
                    )
                } else {
                    "Captured a browser accessibility snapshot"
                }
            }
            "browser_click" -> {
                val clicked =
                    firstString(
                        result,
                        listOf("clicked"),
                    ).ifEmpty { firstString(args, listOf("ref", "target")) }

                if (clicked.isEmpty()) {
                    "Clicked on page"
                } else if (clicked.startsWith("@")) {
                    "Clicked page element (internal ref $clicked)"
                } else {
                    "Clicked $clicked"
                }
            }
            "browser_fill", "browser_type" -> {
                val field = firstString(args, listOf("label", "field", "ref", "target"))
                val value = firstString(args, listOf("value", "text"))
                listOf(
                    field.takeIf { it.isNotEmpty() }?.let { "Field: $it" },
                    value.takeIf { it.isNotEmpty() }?.let { "Value: ${compactPreview(it, 42)}" },
                )
                    .filterNotNull()
                    .joinToString(" · ")
                    .ifEmpty { "Filled page input" }
            }
            "web_search" ->
                searchQueryFor(
                    args,
                ).takeIf { it.isNotEmpty() }?.let { "Query: $it" } ?: "Queried web sources"
            "terminal", "execute_code" -> {
                val output = shellOutput(result)
                val firstMeaningfulLine =
                    output
                        .split("\n")
                        .map { it.trim() }
                        .firstOrNull { it.isNotEmpty() }

                if (firstMeaningfulLine != null) {
                    compactPreview(firstMeaningfulLine, 160)
                } else {
                    // A terminal row with no output shows its command in the
                    // title; nothing more to add.
                    ""
                }
            }
            "read_file", "edit_file", "patch", "write_file" -> {
                val path =
                    if (isFileEditTool(toolName)) {
                        fileEditPath(args, result)
                    } else {
                        firstString(args, listOf("path", "file", "filepath"))
                    }

                if (path.isNotEmpty()) {
                    path
                } else {
                    fallbackDetailText(args, rawResult)
                }
            }
            "web_extract" -> {
                val url =
                    firstString(args, listOf("url"))
                        .ifEmpty { firstString(result, listOf("url")) }
                        .ifEmpty { findFirstUrl(args, result) }

                if (url.isNotEmpty()) hostnameOf(url) else "Fetched webpage"
            }
            "memory" -> firstString(result, listOf("message", "error"))
            "cronjob" -> cronjobSubtitle(args, result)
            "todo" -> todoSubtitle(result)
            "fact_store" -> factStoreSubtitle(args, result)
            "session_search" -> sessionSearchSubtitle(result)
            "skills_list" -> {
                val category = firstString(args, listOf("category"))
                val count =
                    (result?.get("skills") as? JsonArray)?.size
                        ?: (result?.get("results") as? JsonArray)?.size
                        ?: 0
                if (count > 0) {
                    "$count skills${category.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}"
                } else {
                    "No skills found"
                }
            }
            "skill_view" -> firstString(args, listOf("name"))
            "skill_manage" -> {
                val action = firstString(args, listOf("action"))
                val name = firstString(args, listOf("name"))
                if (name.isNotEmpty()) "Skill $action: $name" else "Skill $action"
            }
            "process" -> {
                val action = firstString(args, listOf("action"))
                val procId = firstString(args, listOf("session_id"))
                if (procId.isNotEmpty()) "$action: $procId" else action
            }
            "x_search" -> {
                val query = firstString(args, listOf("query"))
                val degraded =
                    (
                        result?.get(
                            "degraded",
                        ) as? JsonPrimitive
                    )?.let { !it.isString && it.content == "true" } == true
                if (query.isNotEmpty()) "$query${if (degraded) " (no citations)" else ""}" else "Queried X"
            }
            "vision_analyze" -> firstString(args, listOf("image_url")).take(60)
            "tool_search" -> {
                val query = firstString(args, listOf("query"))
                val matches = (result?.get("matches") as? JsonArray)?.size
                if (matches != null) "$query ($matches matches)" else query
            }
            "image_generate" -> {
                val imageUrl = firstString(result, listOf("image")).ifEmpty { firstString(args, listOf("image_url")) }
                val modality = firstString(result, listOf("modality"))
                listOf(imageUrl.take(60), modality.takeIf { it.isNotEmpty() }?.let { "($it)" })
                    .filterNotNull()
                    .joinToString(" ")
            }
            "project_list" -> {
                val projects = (result?.get("projects") as? JsonArray)?.size
                val active = firstString(result, listOf("active_id"))
                if (projects != null) {
                    "$projects projects${active.takeIf { it.isNotEmpty() }?.let { " (1 active)" } ?: ""}"
                } else {
                    "Projects"
                }
            }
            "project_create", "project_switch" -> {
                val actionLabel = if (toolName == "project_create") "Created" else "Switched to"
                val name = firstString(result, listOf("name"))
                if (name.isNotEmpty()) "$actionLabel $name" else actionLabel
            }
            "read_terminal" -> {
                val error = firstString(result, listOf("error"))
                if (error.isNotEmpty()) {
                    "❌ $error"
                } else {
                    val total = intValue(result?.get("total_lines")) ?: 0
                    val start = intValue(result?.get("start")) ?: 0
                    val end = intValue(result?.get("end")) ?: 0
                    val text = firstString(result, listOf("text"))
                    if (text.isNotEmpty()) {
                        val cursor = intValue(result?.get("cursor_row"))
                        "Lines $start-$end of $total${cursor?.let { ", cursor at row $it" } ?: ""}"
                    } else {
                        "Terminal output"
                    }
                }
            }
            "computer_use" -> computerUseSubtitle(args, result)
            else -> {
                val summary = ToolResultSummary.formatToolResultSummary(rawResult)
                val firstLine = summary.split("\n").firstOrNull()?.takeIf { it.isNotEmpty() } ?: ""
                compactPreview(firstLine, 120)
                    .ifEmpty { compactPreview(result, 120) }
                    .ifEmpty { compactPreview(args, 120) }
                    .ifEmpty { fallbackDetailText(args, rawResult) }
            }
        }
    }

    private fun cronjobSubtitle(
        args: JsonObject?,
        result: JsonObject?,
    ): String {
        val jobs = result?.get("jobs") as? JsonArray

        if (jobs != null) {
            return if (jobs.isNotEmpty()) {
                "${jobs.size} cron ${if (jobs.size == 1) "job" else "jobs"}"
            } else {
                "No cron jobs"
            }
        }

        val message = firstString(result, listOf("message"))
        if (message.isNotEmpty()) {
            return message
        }

        val action = firstString(args, listOf("action")).ifEmpty { "manage" }
        val name = firstString(result, listOf("name")).ifEmpty { firstString(args, listOf("name", "job_id")) }
        val label = action.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        return if (name.isNotEmpty()) "$label $name" else "Cron $action"
    }

    private fun todoSubtitle(result: JsonObject?): String {
        val summary = result?.get("summary") as? JsonObject ?: return ""
        val total = intValue(summary["total"]) ?: 0
        val pending = intValue(summary["pending"]) ?: 0
        val inProgress = intValue(summary["in_progress"]) ?: 0
        val completed = intValue(summary["completed"]) ?: 0
        val cancelled = intValue(summary["cancelled"]) ?: 0

        val parts =
            listOf(
                pending.takeIf { it > 0 }?.let { "$it pending" },
                inProgress.takeIf { it > 0 }?.let { "$it in_progress" },
                completed.takeIf { it > 0 }?.let { "$it completed" },
                cancelled.takeIf { it > 0 }?.let { "$it cancelled" },
            ).filterNotNull()

        val itemWord = if (total == 1) "item" else "items"

        return if (parts.isEmpty()) "0 items" else "$total $itemWord (${parts.joinToString(", ")})"
    }

    private fun factStoreSubtitle(
        args: JsonObject?,
        result: JsonObject?,
    ): String {
        val action = firstString(args, listOf("action")).ifEmpty { firstString(result, listOf("action")) }
        val entity = firstString(args, listOf("entity"))
        val query = firstString(args, listOf("query"))
        val facts =
            (result?.get("results") as? JsonArray)
                ?: (result?.get("facts") as? JsonArray)
        val count = intValue(result?.get("count")) ?: facts?.size ?: 0
        val status = firstString(result, listOf("status"))
        val factId = intValue(result?.get("fact_id"))
        val removed = (result?.get("removed") as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
        val updated = (result?.get("updated") as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true

        val actionContext =
            when {
                entity.isNotEmpty() -> " ($action: $entity)"
                query.isNotEmpty() -> " ($action: $query)"
                else -> " ($action)"
            }

        return when {
            status == "added" && factId != null -> "Fact added (ID: $factId)"
            status == "added" -> "Fact added"
            removed -> "Fact removed"
            updated -> "Fact updated"
            facts != null -> "$count facts$actionContext"
            else -> action
        }
    }

    private fun sessionSearchSubtitle(result: JsonObject?): String {
        val mode = firstString(result, listOf("mode"))
        val query = firstString(result, listOf("query"))
        val count = intValue(result?.get("count"))
        val msgCount = intValue(result?.get("message_count"))
        val messages = result?.get("messages") as? JsonArray
        val truncated =
            (
                result?.get(
                    "truncated",
                ) as? JsonPrimitive
            )?.let { !it.isString && it.content == "true" } == true

        return when (mode) {
            "discover" -> "${count ?: 0} session${if (count == 1) "" else "s"}${query.takeIf { it.isNotEmpty() }?.let {
                ": ${it.take(
                    60,
                )}"
            } ?: ""}"
            "scroll" -> "${messages?.size ?: 0} messages (scroll)"
            "read" -> "${msgCount ?: messages?.size ?: 0} messages${if (truncated) " (truncated)" else ""}"
            "browse" -> "${count ?: 0} recent sessions"
            else -> "session_search"
        }
    }

    private fun computerUseSubtitle(
        args: JsonObject?,
        result: JsonObject?,
    ): String {
        val error = firstString(result, listOf("error"))
        if (error.isNotEmpty()) {
            return "❌ $error"
        }

        val apps = result?.get("apps") as? JsonArray
        if (apps != null) {
            return "${apps.size} apps"
        }

        val elements = result?.get("elements") as? JsonArray
        if (elements != null) {
            val mode = firstString(result, listOf("mode"))
            val width = intValue(result?.get("width"))
            val height = intValue(result?.get("height"))
            val modePart = mode.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
            val sizePart = if (width != null && height != null) " ${width}x$height" else ""
            return "${elements.size} elements$modePart$sizePart"
        }

        val action = firstString(args, listOf("action"))
        if (action.isNotEmpty()) {
            val ok = (result?.get("ok") as? JsonPrimitive)?.let { !it.isString && it.content == "true" }
            val fail = (result?.get("ok") as? JsonPrimitive)?.let { !it.isString && it.content == "false" }
            return "$action${when {
                ok == true -> " ✓"
                fail == true -> " ✗"
                else -> ""
            }}"
        }

        return "Computer use"
    }

    // ── details per tool ─────────────────────────────────────────────────

    private fun detailFor(
        toolName: String,
        args: JsonObject?,
        result: JsonObject?,
        rawResult: JsonElement?,
        rawArgs: JsonElement?,
    ): String {
        return when (toolName) {
            "browser_snapshot" -> {
                val snapshot = firstString(result, listOf("snapshot"))
                if (snapshot.isNotEmpty()) {
                    summarizeBrowserSnapshot(
                        snapshot,
                    )
                } else {
                    fallbackDetailText(rawArgs, rawResult)
                }
            }
            "terminal", "execute_code" -> {
                val output = shellOutput(result)
                if (output.isNotEmpty()) {
                    output
                } else if (toolName == "execute_code") {
                    fallbackDetailText(rawArgs, rawResult)
                } else {
                    // A terminal row with no output already shows its command
                    // in the title; the generic fallback would repeat it.
                    ""
                }
            }
            "web_extract" -> {
                val direct = firstString(result, listOf("content", "text", "markdown", "body", "summary", "message"))
                if (direct.isNotEmpty()) {
                    return direct.replace(Regex("\\s*in\\s+\\d+(?:\\.\\d+)?s\\s*$"), "").trim()
                }

                val results = result?.get("results") as? JsonArray
                if (results != null) {
                    return results
                        .mapNotNull { item ->
                            val row = parseMaybeObject(item) ?: return@mapNotNull null
                            firstString(row, listOf("content", "text", "markdown", "body"))
                        }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n\n---\n\n")
                }

                fallbackDetailText(rawArgs, rawResult)
            }
            "read_file" -> {
                if (rawResult == null) {
                    return ""
                }
                firstString(result, listOf("content", "text", "data", "body"))
            }
            "memory" -> firstString(result, listOf("message", "error"))
            "edit_file", "patch", "write_file" -> {
                if (inlineDiffFromResult(rawResult).isNotEmpty()) {
                    ""
                } else {
                    firstString(result, listOf("message", "summary")).ifEmpty {
                        if (fileEditPath(args, result).isNotEmpty()) "" else fallbackDetailText(rawArgs, rawResult)
                    }
                }
            }
            "web_search" -> fallbackDetailText(rawArgs, rawResult)
            "cronjob" -> cronjobDetail(args, result)
            "todo" -> todoDetail(result) ?: ""
            "fact_store" -> factStoreDetail(result) ?: ""
            "session_search" -> sessionSearchDetail(result) ?: ""
            "skills_list" -> {
                val skills =
                    (result?.get("skills") as? JsonArray)
                        ?: (result?.get("results") as? JsonArray)
                skills
                    ?.mapNotNull { el ->
                        val s = parseMaybeObject(el) ?: return@mapNotNull null
                        val name = firstString(s, listOf("name"))
                        val desc = firstString(s, listOf("description"))
                        val cat = firstString(s, listOf("category"))
                        val lines = mutableListOf("📌 $name")
                        if (desc.isNotEmpty()) lines += "     $desc"
                        if (cat.isNotEmpty()) lines += "     [$cat]"
                        lines.joinToString("\n")
                    }
                    ?.joinToString("\n\n")
                    ?: "No skills found"
            }
            "skill_view" -> {
                val content = firstString(result, listOf("content"))
                val linkedFiles = result?.get("linked_files") as? JsonObject
                val contentPreview =
                    if (content.length > 500) {
                        "${content.take(
                            500,
                        )}\n... [${content.length - 500} more chars]"
                    } else {
                        content
                    }
                val filesInfo =
                    linkedFiles
                        ?.entries
                        ?.joinToString("\n") { (k, v) ->
                            val paths =
                                when (v) {
                                    is JsonArray ->
                                        v.joinToString(
                                            ", ",
                                        ) { (it as? JsonPrimitive)?.content ?: it.toString() }
                                    is JsonPrimitive -> v.content
                                    else -> v.toString()
                                }
                            "     📎 $k: $paths"
                        }

                buildString {
                    if (contentPreview.isNotEmpty()) append(contentPreview)
                    if (filesInfo != null) {
                        if (contentPreview.isNotEmpty()) append("\n\n")
                        append("━━━ Linked Files ━━━\n$filesInfo")
                    }
                    if (content.isEmpty() && linkedFiles == null) append("No content available")
                }
            }
            "skill_manage" -> {
                val error = firstString(result, listOf("error"))
                if (error.isNotEmpty()) {
                    "❌ $error"
                } else {
                    val msg = firstString(result, listOf("message"))
                    val success =
                        (
                            result?.get(
                                "success",
                            ) as? JsonPrimitive
                        )?.let { !it.isString && it.content == "false" } == true
                    when {
                        !success -> "❌ Skill operation failed"
                        msg.isNotEmpty() -> "✅ $msg"
                        else -> "✅ Skill operation done"
                    }
                }
            }
            "process" -> processDetail(args, result)
            "x_search" -> xSearchDetail(result)
            "vision_analyze" -> {
                val error = firstString(result, listOf("error"))
                val description = firstString(result, listOf("description"))
                val content = firstString(result, listOf("content"))
                when {
                    error.isNotEmpty() -> "❌ $error"
                    description.isNotEmpty() -> description
                    content.isNotEmpty() -> content
                    else -> "No description available"
                }
            }
            "tool_search" -> {
                val matches = result?.get("matches") as? JsonArray
                matches
                    ?.mapNotNull { el ->
                        val m = parseMaybeObject(el) ?: return@mapNotNull null
                        val name = firstString(m, listOf("name"))
                        val desc = firstString(m, listOf("description"))
                        val lines = mutableListOf("🔧 $name")
                        if (desc.isNotEmpty()) lines += "     $desc"
                        lines.joinToString("\n")
                    }
                    ?.joinToString("\n\n")
                    ?: "No matching tools"
            }
            "image_generate" -> {
                val error = firstString(result, listOf("error"))
                val imageUrl = firstString(result, listOf("image"))
                val prompt = firstString(args, listOf("prompt"))

                buildString {
                    if (error.isNotEmpty()) {
                        append("❌ $error")
                        if (imageUrl.isNotEmpty()) append("\n🔗 $imageUrl")
                    } else if (imageUrl.isNotEmpty()) {
                        append("🖼️ ")
                        if (prompt.isNotEmpty()) append("$prompt\n")
                        append("\n🔗 $imageUrl")
                    } else {
                        append("✅ Generated")
                    }
                }
            }
            "project_list" -> {
                val projects = result?.get("projects") as? JsonArray
                projects
                    ?.mapNotNull { el ->
                        val p = parseMaybeObject(el) ?: return@mapNotNull null
                        val id = firstString(p, listOf("id"))
                        val name = firstString(p, listOf("name"))
                        val slug = firstString(p, listOf("slug"))
                        val path = firstString(p, listOf("primary_path"))
                        val isActive =
                            (p["active"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
                        val lines = mutableListOf("${if (isActive) "⭐ " else "📁 "}$name")
                        if (slug.isNotEmpty()) lines += "     🏷️ $slug"
                        if (path.isNotEmpty()) lines += "     📍 $path"
                        if (isActive) lines += "     ✅ ACTIVE PROJECT"
                        lines.joinToString("\n")
                    }
                    ?.joinToString("\n\n")
                    ?: ""
            }
            "project_create", "project_switch" -> {
                val error = firstString(result, listOf("error"))
                val actionLabel = if (toolName == "project_create") "Created" else "Switched to"
                val name = firstString(result, listOf("name"))
                val slug = firstString(result, listOf("slug"))
                val path = firstString(result, listOf("primary_path"))

                buildString {
                    if (error.isNotEmpty()) {
                        append("❌ $error")
                    } else {
                        append("✅ $actionLabel")
                        if (name.isNotEmpty()) append(": $name")
                        if (slug.isNotEmpty()) append("\n   🏷️ $slug")
                        if (path.isNotEmpty()) append("\n   📍 $path")
                    }
                }
            }
            "read_terminal" -> readTerminalDetail(result)
            "computer_use" -> computerUseDetail(args, result)
            else -> fallbackDetailText(rawArgs, rawResult)
        }
    }

    private fun cronjobDetail(
        args: JsonObject?,
        result: JsonObject?,
    ): String {
        val jobs = result?.get("jobs") as? JsonArray

        if (jobs != null) {
            if (jobs.isEmpty()) {
                return "No cron jobs scheduled"
            }

            return jobs
                .take(20)
                .mapNotNull { job ->
                    val row = parseMaybeObject(job) ?: return@mapNotNull null
                    val name = firstString(row, listOf("name", "id")).ifEmpty { "job" }
                    val sched = firstString(row, listOf("schedule_display", "schedule"))
                    if (sched.isNotEmpty()) "- $name · $sched" else "- $name"
                }
                .joinToString("\n")
        }

        val rows =
            listOf(
                "Schedule" to firstString(result, listOf("schedule")),
                "Repeat" to firstString(result, listOf("repeat")),
                "Delivery" to firstString(result, listOf("deliver")),
                "Next run" to firstString(result, listOf("next_run_at")),
            ).filter { it.second.isNotEmpty() }

        return if (rows.isNotEmpty()) {
            rows.joinToString(
                "\n",
            ) { (k, v) -> "$k: $v" }
        } else {
            fallbackDetailText(args, result)
        }
    }

    private fun todoDetail(result: JsonObject?): String? {
        val todos = result?.get("todos") as? JsonArray ?: return null

        return todos
            .mapNotNull { el ->
                val item = parseMaybeObject(el) ?: return@mapNotNull null
                val id = firstString(item, listOf("id"))
                val content = firstString(item, listOf("content"))
                val status = firstString(item, listOf("status")).ifEmpty { "pending" }
                val marker =
                    when (status) {
                        "completed" -> "[x]"
                        "in_progress" -> "[>]"
                        "cancelled" -> "[~]"
                        else -> "[ ]"
                    }
                "$marker $id. $content"
            }
            .joinToString("\n")
            .takeIf { it.isNotEmpty() }
    }

    private fun factStoreDetail(result: JsonObject?): String? {
        val facts =
            (result?.get("results") as? JsonArray)
                ?: (result?.get("facts") as? JsonArray)
                ?: return null

        return facts
            .mapNotNull { el ->
                val item = parseMaybeObject(el) ?: return@mapNotNull null
                val fid = intValue(item["fact_id"]) ?: 0
                val content = firstString(item, listOf("content"))
                val category = firstString(item, listOf("category"))
                val trust = numberValue(item["trust_score"])
                val tags = firstString(item, listOf("tags"))

                val metaParts = mutableListOf<String>()
                if (category.isNotEmpty()) metaParts += "[$category]"
                if (trust != null) metaParts += "trust: ${"%.2f".format(trust)}"
                if (tags.isNotEmpty()) metaParts += "🏷️ $tags"

                if (metaParts.isNotEmpty()) {
                    "#$fid  $content\n      ${metaParts.joinToString("  ")}"
                } else {
                    "#$fid  $content"
                }
            }
            .joinToString("\n")
            .takeIf { it.isNotEmpty() }
    }

    private fun sessionSearchDetail(result: JsonObject?): String? {
        val results = result?.get("results") as? JsonArray
        val messages = result?.get("messages") as? JsonArray

        val formattedResults =
            results
                ?.mapIndexedNotNull { idx, el ->
                    val item = parseMaybeObject(el) ?: return@mapIndexedNotNull null
                    val whenField = firstString(item, listOf("when", "started_at"))
                    val source = firstString(item, listOf("source"))
                    val title = firstString(item, listOf("title"))
                    val snippet = firstString(item, listOf("snippet")).ifEmpty { firstString(item, listOf("preview")) }
                    val model = firstString(item, listOf("model"))
                    val matchedRole = firstString(item, listOf("matched_role"))
                    val sessionMsgCount = intValue(item["message_count"])

                    val header = whenField.takeIf { it.isNotEmpty() }?.let { "📅 $it" } ?: ""
                    val sourceTag = source.takeIf { it.isNotEmpty() }?.let { "[$it]" } ?: ""
                    val titleLine = title.takeIf { it.isNotEmpty() }?.let { "\n     📄 $it" } ?: ""
                    val modelLine = model.takeIf { it.isNotEmpty() }?.let { "\n     🤖 $it" } ?: ""
                    val matchedLine = matchedRole.takeIf { it.isNotEmpty() }?.let { "\n     🎯 matched: $it" } ?: ""
                    val countLine = sessionMsgCount?.let { "\n     $it msgs" } ?: ""
                    val snippetLine =
                        snippet.takeIf { it.isNotEmpty() }?.let { "\n     ┃ ${it.take(200).replace("\n", " ")}" } ?: ""

                    "━━━ #${idx + 1}  $header$sourceTag$titleLine$modelLine$matchedLine$countLine$snippetLine"
                }
                ?.joinToString("\n")
                ?.takeIf { it.isNotEmpty() }

        val anchorId = intValue(result?.get("around_message_id"))

        val formattedMessages =
            messages
                ?.mapNotNull { el ->
                    val item = parseMaybeObject(el) ?: return@mapNotNull null
                    val msgId = intValue(item["id"])
                    val role = firstString(item, listOf("role")).ifEmpty { "?" }
                    val content = firstString(item, listOf("content"))
                    val toolName = firstString(item, listOf("tool_name"))

                    val roleEmoji =
                        when (role) {
                            "user" -> "👤"
                            "assistant" -> "🤖"
                            "tool" -> "🔧"
                            else -> "❓"
                        }
                    val namePart = if (toolName.isNotEmpty()) " ($toolName)" else ""
                    val anchor = if (anchorId != null && msgId == anchorId) "  ⬅️" else ""
                    val cleanContent = content.take(300).replace("\n", " ")

                    "[$msgId] $roleEmoji $role$namePart$anchor\n     $cleanContent"
                }
                ?.joinToString("\n")
                ?.takeIf { it.isNotEmpty() }

        return formattedResults ?: formattedMessages
    }

    private fun processDetail(
        args: JsonObject?,
        result: JsonObject?,
    ): String {
        val error = firstString(result, listOf("error"))
        if (error.isNotEmpty()) {
            return "❌ $error"
        }

        val processes = result?.get("processes") as? JsonArray
        if (processes != null) {
            return processes
                .mapNotNull { el ->
                    val p = parseMaybeObject(el) ?: return@mapNotNull null
                    val pid = firstString(p, listOf("session_id")).ifEmpty { firstString(p, listOf("id")) }
                    val pStatus = firstString(p, listOf("status"))
                    val cmd = firstString(p, listOf("command"))
                    val running = (p["running"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" }
                    val parts = mutableListOf("📌 $pid${cmd.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}")
                    if (pStatus.isNotEmpty()) parts += "     Status: $pStatus"
                    if (running != null) parts += "     Running: $running"
                    parts.joinToString("\n")
                }
                .joinToString("\n\n")
        }

        val output = firstString(result, listOf("output"))
        if (output.isNotEmpty()) {
            val status = firstString(result, listOf("status"))
            return "${status.takeIf { it.isNotEmpty() }?.let { "Status: $it\n" } ?: ""}$output"
        }

        val status = firstString(result, listOf("status"))
        if (status.isNotEmpty()) {
            return "✅ $status"
        }

        val action = firstString(args, listOf("action"))
        return "✅ ${action.ifEmpty { "done" }} done"
    }

    private fun xSearchDetail(result: JsonObject?): String {
        val error = firstString(result, listOf("error"))
        val answer = firstString(result, listOf("answer"))
        val citations = result?.get("citations") as? JsonArray
        val degraded = (result?.get("degraded") as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true

        return when {
            error.isNotEmpty() -> "❌ $error"
            answer.isNotEmpty() -> {
                val lines = mutableListOf(answer)
                if (citations != null && citations.isNotEmpty()) {
                    lines += "\n━━━ Citations ━━━"
                    citations.forEachIndexed { idx, cit ->
                        val c = parseMaybeObject(cit) ?: return@forEachIndexed
                        val url = firstString(c, listOf("url"))
                        val title = firstString(c, listOf("title"))
                        lines += "${idx + 1}. ${title.ifEmpty { url.ifEmpty { "source" } }}"
                        if (title.isNotEmpty() && url.isNotEmpty()) lines += "   $url"
                    }
                }
                if (degraded) lines += "\n⚠️ No citations — answer based on model's knowledge"
                lines.joinToString("\n")
            }
            else -> "No results"
        }
    }

    private fun readTerminalDetail(result: JsonObject?): String {
        val error = firstString(result, listOf("error"))
        val text = firstString(result, listOf("text"))
        val total = intValue(result?.get("total_lines")) ?: 0
        val start = intValue(result?.get("start")) ?: 0
        val end = intValue(result?.get("end")) ?: 0
        val cursor = intValue(result?.get("cursor_row"))

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

    private fun computerUseDetail(
        args: JsonObject?,
        result: JsonObject?,
    ): String {
        val error = firstString(result, listOf("error"))
        if (error.isNotEmpty()) {
            return "❌ $error"
        }

        val isMultimodal =
            (
                result?.get(
                    "_multimodal",
                ) as? JsonPrimitive
            )?.let { !it.isString && it.content == "true" } == true
        val content = result?.get("content") as? JsonArray
        val multimodalText =
            if (isMultimodal && content != null) {
                content
                    .mapNotNull { el ->
                        val e = parseMaybeObject(el) ?: return@mapNotNull null
                        if (firstString(e, listOf("type")) == "text") firstString(e, listOf("text")) else null
                    }
                    .joinToString("\n")
            } else {
                ""
            }

        val textSummary = firstString(result, listOf("summary"))
        val visionAnalysis = firstString(result, listOf("vision_analysis"))
        val apps = result?.get("apps") as? JsonArray
        val elements = result?.get("elements") as? JsonArray
        val mode = firstString(result, listOf("mode"))
        val width = intValue(result?.get("width"))
        val height = intValue(result?.get("height"))
        val action = firstString(args, listOf("action"))
        val ok = (result?.get("ok") as? JsonPrimitive)?.let { !it.isString && it.content == "true" }
        val fail = (result?.get("ok") as? JsonPrimitive)?.let { !it.isString && it.content == "false" }
        val message = firstString(result, listOf("message"))

        val body =
            buildString {
                val textPart =
                    multimodalText.ifEmpty {
                        firstString(
                            result,
                            listOf("text_summary"),
                        )
                    }.ifEmpty { textSummary }
                if (textPart.isNotEmpty()) {
                    append(textPart)
                    if (textPart == multimodalText && textSummary.isNotEmpty() && multimodalText != textSummary) {
                        append("\n\n$textSummary")
                    }
                }

                if (apps != null) {
                    if (isNotEmpty()) append("\n\n")
                    apps.forEachIndexed { idx, el ->
                        val obj = parseMaybeObject(el)
                        val name = firstString(obj, listOf("name")).ifEmpty { "?" }
                        val pid = intValue(obj?.get("pid"))
                        append("${idx + 1}. $name")
                        if (pid != null) append(" (PID: $pid)")
                        append("\n")
                    }
                } else if (elements != null) {
                    if (isNotEmpty()) append("\n\n")
                    append("🖥️ ${elements.size} interactable elements")
                    if (mode.isNotEmpty()) append(" (mode: $mode)")
                    if (width != null && height != null) append(" • ${width}x$height")
                    if (textSummary.isNotEmpty()) append("\n\n$textSummary")
                    val previewCount = minOf(elements.size, 5)
                    if (previewCount > 0) {
                        append("\n\n")
                        for (i in 0 until previewCount) {
                            val obj = parseMaybeObject(elements[i])
                            val idx = intValue(obj?.get("index"))
                            val role = firstString(obj, listOf("role"))
                            val label = firstString(obj, listOf("label"))
                            append("  #$idx ${role}${label.takeIf { it.isNotEmpty() }?.let { " '$it'" } ?: ""}\n")
                        }
                        if (elements.size > previewCount) {
                            append("  ... and ${elements.size - previewCount} more")
                        }
                    }
                } else if (action.isNotEmpty() && multimodalText.isEmpty() &&
                    firstString(
                        result,
                        listOf("text_summary"),
                    ).isEmpty() && textSummary.isEmpty()
                ) {
                    append("$action:")
                    when {
                        ok == true -> append(" ✅ ok")
                        fail == true -> append(" ❌ failed")
                    }
                    if (message.isNotEmpty()) append("\n$message")
                } else if (isEmpty()) {
                    append("Computer use action")
                }
            }

        return if (visionAnalysis.isNotEmpty()) "$body\n\n━━━ Vision Analysis ━━━\n$visionAnalysis" else body
    }

    // ── count label ──────────────────────────────────────────────────────

    private fun countLabelFor(
        toolName: String,
        args: JsonObject?,
        result: JsonObject?,
        rawResult: JsonElement?,
    ): Pair<Int, String>? {
        if (rawResult == null) {
            return null
        }

        val fallbackNoun =
            when (toolName) {
                "browser_snapshot", "todo" -> "item"
                "list_files" -> "file"
                "search_files", "session_search_recall", "web_search" -> "result"
                "skills_list" -> "skill"
                else -> "item"
            }

        if (toolName == "web_search") {
            val hits = collectResultItems(rawResult)

            if (hits.isNotEmpty()) {
                return countMetric(hits.size, "result")
            }
        }

        val direct = countFromRecord(result ?: JsonObject(emptyMap()), fallbackNoun)
        if (direct != null) {
            return if (toolName == "web_search") countMetric(direct.first, "result") else direct
        }

        val payload = unwrapToolPayload(rawResult)
        if (payload !== rawResult && payload is JsonObject) {
            val payloadCount = countFromRecord(payload, fallbackNoun)

            if (payloadCount != null) {
                return if (toolName == "web_search") countMetric(payloadCount.first, "result") else payloadCount
            }
        }

        val summaryText =
            firstString(result, listOf("summary", "message", "detail")).ifEmpty { fallbackDetailText(args, rawResult) }
        val textMetric = countFromText(summaryText, fallbackNoun)

        return textMetric
    }

    private fun imageUrlFor(
        args: JsonObject?,
        result: JsonObject?,
    ): String? {
        val candidate =
            firstString(result, listOf("image_url", "url", "path", "image_path"))
                .ifEmpty { firstString(args, listOf("image_url", "url", "path")) }

        if (candidate.isEmpty()) {
            return null
        }

        val isDataImage = candidate.lowercase().startsWith("data:image/")
        val isRemoteImage =
            Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(candidate) &&
                Regex("\\.(png|jpe?g|gif|webp|bmp|svg)(\\?|#|$)", RegexOption.IGNORE_CASE).containsMatchIn(candidate)

        return if (isDataImage || isRemoteImage) candidate else null
    }
}
