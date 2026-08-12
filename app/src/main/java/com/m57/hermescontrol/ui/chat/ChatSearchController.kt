package com.m57.hermescontrol.ui.chat

/**
 * Pure search logic for in-chat text search.
 *
 * Computes match indices and navigation without depending on ViewModel or Android.
 */
class ChatSearchController {
    /**
     * Find all message indices where [query] appears in the message's VISIBLE
     * text — user bubbles and agent prose only.
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
    ): List<Int> {
        if (query.isBlank()) return emptyList()
        return messages.indices.filter { idx ->
            val message = messages[idx]
            (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT) &&
                message.content.contains(query, ignoreCase = true)
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
