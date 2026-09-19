package com.m57.hermescontrol.notification

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.ui.chat.HostMediaExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Resolves the one persisted REST assistant row produced by a completed
 * mobile-originated turn.
 *
 * Design rules (all of them are load-bearing):
 * - Only rows strictly above [TurnBoundary.beforeMessageId] are candidates, so
 *   historical duplicates can never win.
 * - The FULL completion text is compared, never the 100-character notification
 *   preview.
 * - Exactly one candidate resolves. Zero candidates retry (the row may not be
 *   persisted yet). More than one candidate is ambiguous and fails closed —
 *   `firstOrNull`, `lastOrNull`, `maxBy` and "newest match" are all guessing.
 * - Never compares Android notification time with server timestamps.
 */
internal class TurnRowResolver(
    private val fetchLatestRows: suspend (String) -> List<SessionMessage>?,
    /** Attempt delays; the first attempt runs immediately. */
    private val retryDelaysMs: List<Long> = listOf(0L, 200L, 400L),
) {
    suspend fun resolve(
        sessionId: String,
        boundary: TurnBoundary,
        completionText: String,
    ): Int? {
        val wanted = assistantTextKey(completionText)
        if (wanted.isEmpty) return null
        if (sessionId.isBlank()) return null

        for ((attempt, delayMs) in retryDelaysMs.withIndex()) {
            if (attempt > 0) delay(delayMs)
            val rows = fetchRows(sessionId) ?: continue
            val candidates =
                rows.mapNotNull { row ->
                    val id = row.id ?: return@mapNotNull null
                    if (id <= boundary.beforeMessageId) return@mapNotNull null
                    if (!row.role.equals("assistant", ignoreCase = true)) return@mapNotNull null
                    if (assistantTextKey(row.contentText) != wanted) return@mapNotNull null
                    id
                }
            if (candidates.size == 1) return candidates.single()
            // Two rows above the boundary match this completion: the turn's own
            // row cannot be told apart from the other. Leave the notification
            // active rather than bind it to a coin flip.
            if (candidates.size > 1) return null
        }
        return null
    }

    private suspend fun fetchRows(sessionId: String): List<SessionMessage>? =
        try {
            fetchLatestRows(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
}

/**
 * Normalized comparison key for an assistant row.
 *
 * Text replies compare their trimmed full text. `MEDIA:` replies compare the
 * directive-stripped text PLUS the extracted host paths, using the same
 * canonicalization the chat mapper applies before rendering — so a MEDIA row
 * and the matching completion agree on both halves, and a MEDIA-only reply
 * (which strips to an empty string) still compares by its paths instead of
 * degenerating into "any assistant row".
 */
internal data class AssistantTextKey(
    val text: String,
    val media: List<String>,
) {
    val isEmpty: Boolean
        get() = text.isBlank() && media.isEmpty()
}

private const val MEDIA_DIRECTIVE = "MEDIA:"

internal fun assistantTextKey(raw: String): AssistantTextKey {
    val trimmed = raw.trim()
    if (!trimmed.contains(MEDIA_DIRECTIVE)) return AssistantTextKey(trimmed, emptyList())
    return AssistantTextKey(
        text = HostMediaExtractor.strip(trimmed).trim(),
        media = HostMediaExtractor.extract(trimmed).map { it.path },
    )
}
