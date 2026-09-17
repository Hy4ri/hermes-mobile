package com.m57.hermescontrol.ui.sessions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionSearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

/**
 * Converts a backend search hit into a display model.
 * When the backend returns an actual session title, it is mapped cleanly.
 * When title is missing/null, title is kept null so [SearchResultCard] shows
 * the honest fallback "Match" layout.
 */
internal fun SessionSearchResult.toSessionInfo(): SessionInfo =
    SessionInfo(
        id = session_id,
        title = title?.takeIf(String::isNotBlank),
        preview = snippet,
        source = source,
        model = model,
        started_at = session_started,
        last_active = last_active,
        message_count = message_count,
        lineageRootId = lineage_root,
    )

/**
 * Formats a backend epoch-seconds timestamp into a friendly, local relative/absolute
 * string. Used for search results where the session name is unknown or when displaying recency.
 */
internal fun formatPlayedAt(epochSeconds: Double?): String? {
    if (epochSeconds == null || epochSeconds <= 0.0) return null
    val instant =
        try {
            Instant.ofEpochSecond(epochSeconds.toLong())
        } catch (_: Exception) {
            return null
        }
    val now = Instant.now()
    val diffSec = abs(now.epochSecond - instant.epochSecond)
    val absFormatter =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
    return when {
        diffSec < 60 -> "just now"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        diffSec < 86400 -> "${diffSec / 3600}h ago"
        diffSec < 7 * 86400 -> "${diffSec / 86400}d ago"
        else -> absFormatter.format(instant)
    }
}

private val searchSnippetJson = Json { isLenient = true }

/**
 * Makes a backend FTS snippet safe to present as prose. Some indexed messages are stored as
 * JSON, so prefer their human-readable text fields rather than rendering the entire payload.
 */
fun cleanSearchSnippet(snippet: String): String {
    val withoutHighlightMarkers = snippet.replace(">>>", "").replace("<<<", "").trim()
    val parsedSnippet =
        runCatching { searchSnippetJson.parseToJsonElement(withoutHighlightMarkers) }.getOrNull()
    val displayText = parsedSnippet?.searchText() ?: withoutHighlightMarkers
    return displayText
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun JsonElement.searchText(): String? =
    when (this) {
        is JsonPrimitive -> {
            contentOrNull?.takeIf(String::isNotBlank)
        }

        is JsonArray -> {
            mapNotNull { it.searchText() }
                .joinToString(" ")
                .takeIf(String::isNotBlank)
        }

        is JsonObject -> {
            val textFieldNames =
                listOf("content", "text", "message", "body", "prompt", "summary", "title")
            val wrapperFieldNames = listOf("data", "result", "payload", "parts")
            textFieldNames
                .firstNotNullOfOrNull { fieldName -> this[fieldName]?.searchText() }
                ?: wrapperFieldNames.firstNotNullOfOrNull { fieldName -> this[fieldName]?.searchText() }
        }
    }

private val SEARCH_TOKEN_REGEX = Regex("\"([^\"]+)\"|(\\S+)")

/**
 * Builds an AnnotatedString highlighting terms from [query] in [text].
 * Supports multi-word queries, quoted phrases ("like this"), case-insensitivity,
 * overlapping matches, and Unicode characters safely without offset drift.
 * Renders as plain text (no HTML interpretation).
 */
fun highlightSearchText(
    text: String,
    query: String,
    highlightBackground: Color,
    highlightForeground: Color,
): AnnotatedString {
    if (query.isBlank() || text.isEmpty()) {
        return AnnotatedString(text)
    }
    val terms =
        SEARCH_TOKEN_REGEX
            .findAll(query)
            .mapNotNull { match ->
                (match.groups[1]?.value ?: match.groups[2]?.value)?.trim()?.takeIf(String::isNotEmpty)
            }.distinct()
            .toList()

    if (terms.isEmpty()) {
        return AnnotatedString(text)
    }

    val ranges = mutableListOf<IntRange>()
    for (term in terms) {
        var startIndex = 0
        while (startIndex < text.length) {
            val found = text.indexOf(term, startIndex = startIndex, ignoreCase = true)
            if (found == -1) break
            ranges.add(found until (found + term.length))
            startIndex = found + term.length
        }
    }

    if (ranges.isEmpty()) {
        return AnnotatedString(text)
    }

    ranges.sortBy { it.first }
    val merged = mutableListOf<IntRange>()
    var current = ranges[0]
    for (i in 1 until ranges.size) {
        val next = ranges[i]
        if (next.first <= current.last + 1) {
            current = current.first..maxOf(current.last, next.last)
        } else {
            merged.add(current)
            current = next
        }
    }
    merged.add(current)

    return buildAnnotatedString {
        var cursor = 0
        for (range in merged) {
            if (range.first > cursor) {
                append(text.substring(cursor, range.first))
            }
            withStyle(
                SpanStyle(
                    background = highlightBackground,
                    color = highlightForeground,
                    fontWeight = FontWeight.Bold,
                ),
            ) {
                append(text.substring(range.first, range.last + 1))
            }
            cursor = range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}
