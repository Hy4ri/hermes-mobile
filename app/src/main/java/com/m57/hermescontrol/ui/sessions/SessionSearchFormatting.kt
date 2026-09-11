package com.m57.hermescontrol.ui.sessions

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
 * Converts a backend search hit into a display model WITHOUT faking a title.
 * The backend returns no session name, only a matched `snippet` + metadata, so the
 * card must render the snippet as a match excerpt (never as the title). `title` is
 * deliberately left null so [SearchResultCard] shows the honest "Match" layout.
 */
internal fun SessionSearchResult.toSessionInfo(): SessionInfo =
    SessionInfo(
        id = session_id,
        title = null,
        preview = snippet,
        source = source,
        model = model,
        started_at = session_started,
        // message_count/status aren't in the search payload; leave null so the
        // search card hides those normal-list affordances.
    )

/**
 * Formats a backend epoch-seconds timestamp into a friendly, local relative/absolute
 * string. Used for search results where the session name is unknown.
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
