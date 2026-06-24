package com.m57.hermescontrol.ui.chat

/**
 * Pure search logic for in-chat text search.
 *
 * Computes match indices and navigation without depending on ViewModel or Android.
 */
class ChatSearchController {
    /**
     * Find all message indices where [query] appears in the content.
     */
    fun findMatches(
        messages: List<ChatMessage>,
        query: String,
    ): List<Int> {
        if (query.isBlank()) return emptyList()
        return messages.indices.filter { idx ->
            messages[idx].content.contains(query, ignoreCase = true)
        }
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
