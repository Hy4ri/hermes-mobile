package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fullbleed.isHiddenTool
import com.m57.hermescontrol.ui.chat.fullbleed.isSyntheticSystemRow
import com.m57.hermescontrol.ui.chat.fullbleed.isTimelineMarker
import com.m57.hermescontrol.ui.chat.tool.ToolViewCache

/** The rendered transcript surface where a search occurrence belongs. */
enum class SearchTarget {
    CONTENT,
    REASONING,
    TOOL,
}

/** Search only the tool header and collapsed summary; raw payload is hidden until expanded. */
internal fun searchableToolText(message: ChatMessage): String {
    val view = ToolViewCache.getOrParse(message.content, message.toolName, message.isToolRunning)
    val summary = view?.let { composeToolSummaryLines(it, message.toolName) }
    return listOfNotNull(
        (view?.serverDisplayName ?: message.toolName)?.takeIf { it.isNotBlank() },
        summary?.first,
        summary?.second,
        message.progressPreview?.takeIf { message.isToolRunning && it.isNotBlank() },
    ).joinToString("\n")
}

internal fun searchTextFor(
    message: ChatMessage,
    target: SearchTarget,
): String =
    when (target) {
        SearchTarget.CONTENT -> message.content
        SearchTarget.REASONING -> message.reasoningText
        SearchTarget.TOOL -> searchableToolText(message)
    }

/**
 * One search hit: which message it lives in and where in that message's
 * visible content the word starts (character offset). The offset lets the
 * chat list scroll the actual word into view, not just its message.
 */
data class SearchMatch(
    val messageIndex: Int,
    val contentOffset: Int,
    val target: SearchTarget = SearchTarget.CONTENT,
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
     * Find literal occurrences in rendered user/assistant prose, reasoning,
     * and the visible tool header/summary. Never scan hidden raw tool payloads.
     * Offsets are relative to each target surface, not the whole message.
     * Scans the original strings so case folding cannot shift offsets; stores
     * at most [MAX_SEARCH_MATCHES] hits while retaining the exact total.
     */
    fun findMatches(
        messages: List<ChatMessage>,
        query: String,
    ): SearchResult {
        if (query.isBlank()) return SearchResult(emptyList(), 0, capped = false)
        val result = mutableListOf<SearchMatch>()
        var total = 0

        fun scan(
            index: Int,
            text: String,
            target: SearchTarget,
        ) {
            var from = 0
            while (true) {
                val hit = text.indexOf(query, from, ignoreCase = true)
                if (hit < 0) break
                total++
                if (result.size < MAX_SEARCH_MATCHES) result.add(SearchMatch(index, hit, target))
                from = hit + query.length
            }
        }
        for ((idx, message) in messages.withIndex()) {
            when (message.role) {
                MessageRole.USER -> {
                    if (!message.isTimelineMarker() && !message.isSyntheticSystemRow()) {
                        scan(idx, message.content, SearchTarget.CONTENT)
                    }
                }

                MessageRole.ASSISTANT -> {
                    if (message.reasoningText.isNotBlank()) scan(idx, message.reasoningText, SearchTarget.REASONING)
                    if (message.content.isNotBlank()) scan(idx, message.content, SearchTarget.CONTENT)
                }

                MessageRole.TOOL -> {
                    if (!message.isHiddenTool()) scan(idx, searchableToolText(message), SearchTarget.TOOL)
                }

                MessageRole.SYSTEM -> {
                    // System rows are not part of in-chat search.
                }
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
