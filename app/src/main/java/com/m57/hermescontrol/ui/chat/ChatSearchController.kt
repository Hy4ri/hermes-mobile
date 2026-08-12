package com.m57.hermescontrol.ui.chat

/**
 * One search hit: which message it lives in and where in that message's
 * visible content the word starts (character offset). The offset lets the
 * chat list scroll the actual word into view, not just its message.
 */
data class SearchMatch(
    val messageIndex: Int,
    val contentOffset: Int,
)

/** Result of a search scan, including the exact total before any cap. */
data class SearchResult(
    val matches: List<SearchMatch>,
    val totalMatches: Int,
    val capped: Boolean,
)

/**
 * Pure search logic for in-chat text search.
 *
 * Computes match indices and navigation without depending on ViewModel or Android.
 */
class ChatSearchController {
    /**
     * Find all WORD OCCURRENCES of [query] in the conversation's VISIBLE
     * text — user bubbles and agent prose only — flattened into one entry per
     * occurrence, carrying each hit's character offset in its message.
     *
     * Tool rows, system events, and reasoning text are excluded:
     * - they are not visible prose the user is looking for, so matches there
     *   were invisible (nothing highlighted) yet still navigated/scrolled to
     * - tool payloads dominate the chat's byte weight, so scanning them made
     *   search slow on long sessions
     * Reasoning lives in [ChatMessage.reasoningText] (not `content`), so it
     * was never matched — this keeps it that way explicitly.
     *
     * Matching is a literal, allocation-light [String.indexOf] scan
     * (`ignoreCase = true`) instead of a regex — queries are always quoted
     * literally, so regex was pure overhead: one MatchResult allocation per
     * hit plus a compiled Pattern per query. Scanning the ORIGINAL content
     * keeps offsets exact (no lowercase transform, which can change string
     * length on some scripts).
     *
     * Result is capped at [MAX_SEARCH_MATCHES] entries to bound allocations
     * and navigation cost on degenerate queries (single letter, "the");
     * [SearchResult.totalMatches] still reports the exact total.
     */
    fun findMatches(
        messages: List<ChatMessage>,
        query: String,
    ): SearchResult {
        if (query.isBlank()) return SearchResult(emptyList(), 0, capped = false)
        val result = mutableListOf<SearchMatch>()
        var total = 0
        for ((idx, message) in messages.withIndex()) {
            if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) continue
            var from = 0
            while (true) {
                val hit = message.content.indexOf(query, from, ignoreCase = true)
                if (hit < 0) break
                total++
                if (result.size < MAX_SEARCH_MATCHES) {
                    result.add(SearchMatch(idx, hit))
                }
                from = hit + query.length
            }
        }
        return SearchResult(result, total, capped = total > MAX_SEARCH_MATCHES)
    }

    /**
     * Compute the next/previous match index given the current position.
     */
    fun navigate(
        currentIndex: Int,
        matchCount: Int,
        direction: Int,
    ): Int {
        if (matchCount == 0) return -1
        return when (direction) {
            1 -> if (currentIndex >= matchCount - 1) 0 else currentIndex + 1
            -1 -> if (currentIndex <= 0) matchCount - 1 else currentIndex - 1
            else -> currentIndex
        }
    }

    companion object {
        /** Hard cap on stored match entries; totals beyond it show as `N+`. */
        const val MAX_SEARCH_MATCHES = 500
    }
}
