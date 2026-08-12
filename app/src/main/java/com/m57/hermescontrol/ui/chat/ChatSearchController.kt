package com.m57.hermescontrol.ui.chat

import java.util.regex.Pattern

/**
 * One search hit: which message it lives in and where in that message's
 * visible content the word starts (character offset). The offset lets the
 * chat list scroll the actual word into view, not just its message.
 */
data class SearchMatch(
    val messageIndex: Int,
    val contentOffset: Int,
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
     */
    fun findMatches(
        messages: List<ChatMessage>,
        query: String,
    ): List<SearchMatch> {
        if (query.isBlank()) return emptyList()
        val pattern = Regex(Pattern.quote(query), RegexOption.IGNORE_CASE)
        val result = mutableListOf<SearchMatch>()
        messages.forEachIndexed { idx, message ->
            if (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT) {
                for (match in pattern.findAll(message.content)) {
                    result.add(SearchMatch(idx, match.range.first))
                }
            }
        }
        return result
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
}
