package com.m57.hermescontrol.ui.chat.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Count-label extraction ("3 results", "2 files") for the collapsed row.
 *
 * Hunts for a count in this order: known count fields, known list arrays,
 * `*_count`/`*_total` field names, then plain-text "Found N matches"
 * phrasing in summary/message fields. web_search always counts its hits and
 * always reports "results".
 */
internal object ToolCounts {
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

    private val NOUN_BY_FIELD =
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

    private val NOUN_BY_ARRAY =
        mapOf(
            "documents" to "document",
            "files" to "file",
            "items" to "item",
            "matches" to "match",
            "results" to "result",
            "rows" to "row",
            "sources" to "source",
        )

    private fun countFromUnknown(value: JsonElement?): Int? {
        if (value is JsonArray) {
            return if (value.isNotEmpty()) value.size else null
        }

        val n = ToolJson.numberValue(value) ?: return null

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

    fun pluralizeNoun(
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
        for (key in COUNT_FIELD_KEYS) {
            val count = countFromUnknown(record[key]) ?: continue

            return countMetric(count, NOUN_BY_FIELD[key] ?: fallbackNoun)
        }

        for (key in COUNT_ARRAY_KEYS) {
            val count = countFromUnknown(record[key]) ?: continue

            return countMetric(count, NOUN_BY_ARRAY[key] ?: fallbackNoun)
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
            ).find(t)
                ?: Regex(
                    """\b(?:did|found|returned|listed|searched|matched|updated|created|deleted|processed)\s+(\d+)\b""",
                    RegexOption.IGNORE_CASE,
                ).find(t)

        val n = unitMatch?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val noun = unitMatch.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: fallbackNoun

        return if (n > 0) countMetric(n, noun) else null
    }

    fun countLabelFor(call: ToolCall): Pair<Int, String>? {
        val rawResult = call.rawResult ?: return null
        val toolName = call.name
        val result = call.result

        val fallbackNoun =
            when (toolName) {
                "browser_snapshot", "todo" -> "item"
                "list_files" -> "file"
                "search_files", "session_search_recall", "web_search" -> "result"
                "skills_list" -> "skill"
                else -> "item"
            }

        if (toolName == "web_search") {
            val hits = ToolJson.collectResultItems(rawResult)

            if (hits.isNotEmpty()) {
                return countMetric(hits.size, "result")
            }
        }

        val direct = countFromRecord(result ?: JsonObject(emptyMap()), fallbackNoun)
        if (direct != null) {
            return if (toolName == "web_search") countMetric(direct.first, "result") else direct
        }

        val payload = ToolJson.unwrapToolPayload(rawResult)
        if (payload !== rawResult && payload is JsonObject) {
            val payloadCount = countFromRecord(payload, fallbackNoun)

            if (payloadCount != null) {
                return if (toolName == "web_search") countMetric(payloadCount.first, "result") else payloadCount
            }
        }

        val summaryText =
            ToolJson
                .firstString(result, listOf("summary", "message", "detail"))
                .ifEmpty { ToolJson.fallbackDetailText(call.args, rawResult) }

        return countFromText(summaryText, fallbackNoun)
    }
}
