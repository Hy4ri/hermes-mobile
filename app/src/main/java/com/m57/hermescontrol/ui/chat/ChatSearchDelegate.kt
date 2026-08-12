package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fullbleed.currentMatchMessageId
import com.m57.hermescontrol.ui.chat.fullbleed.matchedMessageIds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Holds chat in-message search state and logic, extracted from [ChatViewModel]
 * to keep the god-object focused on messaging/session concerns.
 *
 * Search state lives in a dedicated snapshot-backed [ChatSearchState] holder
 * (NOT in [ChatUiState]): search updates then recompose only the scopes that
 * read its fields (search bar, matched bubbles, the scroll effect) instead of
 * the entire chat screen and list. [uiState] is still read for `messages`
 * when computing matches.
 *
 * @param dispatcher The [CoroutineDispatcher] used for the (CPU-bound) search
 *   work. Defaults to [Dispatchers.Default] — the original behavior — but can
 *   be injected to reuse a caller's context or customize per environment.
 * @param debounceMs How long to wait after the last keystroke before running
 *   the scan. Coalesces fast typing into one search instead of one full scan
 *   per character.
 */
class ChatSearchDelegate(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    val searchState: ChatSearchState = ChatSearchState(),
    private val searchController: ChatSearchController = ChatSearchController(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val debounceMs: Long = SEARCH_DEBOUNCE_MS,
) {
    private var searchJob: Job? = null

    fun toggleSearch() {
        if (searchState.isActive) {
            clearSearch()
        } else {
            searchState.isActive = true
            searchState.query = ""
        }
    }

    fun setSearchQuery(query: String) {
        searchJob?.cancel()
        searchState.query = query

        if (query.isBlank()) {
            resetMatches()
            return
        }

        // Keep local state in sync immediately so UI feels responsive, then
        // debounce: cancel any pending scan and only run one after typing
        // pauses for debounceMs (fast typing = one scan, not one per char).
        searchJob =
            scope.launch(dispatcher) {
                delay(debounceMs)
                runSearch(query)
            }
    }

    private fun runSearch(query: String) {
        val messages = uiState.value.messages
        val result = searchController.findMatches(messages, query)

        // Only update matches if the search query hasn't changed in the meantime.
        if (searchState.query != query) return

        searchState.matchIndices = result.matches.map { m -> m.messageIndex }
        searchState.matchOffsets = result.matches.map { m -> m.contentOffset }
        searchState.matchTotal = result.totalMatches
        searchState.matchCapped = result.capped
        searchState.currentIndex = if (result.matches.isNotEmpty()) 0 else -1
        searchState.matchedIds = matchedMessageIds(messages, searchState.matchIndices)
        searchState.currentMatchId =
            currentMatchMessageId(messages, searchState.matchIndices, searchState.currentIndex)
    }

    fun navigateSearchMatch(direction: Int) {
        val indices = searchState.matchIndices
        if (indices.isEmpty()) return
        val newIdx =
            searchController.navigate(
                currentIndex = searchState.currentIndex,
                matchCount = indices.size,
                direction = direction,
            )
        searchState.currentIndex = newIdx
        searchState.currentMatchId =
            currentMatchMessageId(uiState.value.messages, indices, newIdx)
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchState.isActive = false
        searchState.query = ""
        resetMatches()
    }

    private fun resetMatches() {
        searchState.matchIndices = emptyList()
        searchState.matchOffsets = emptyList()
        searchState.matchTotal = 0
        searchState.matchCapped = false
        searchState.currentIndex = -1
        searchState.matchedIds = emptySet()
        searchState.currentMatchId = null
    }

    private companion object {
        /** Typing pause (ms) before a search scan runs. */
        const val SEARCH_DEBOUNCE_MS = 150L
    }
}
