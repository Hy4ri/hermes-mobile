package com.m57.hermescontrol.ui.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds chat in-message search state and logic, extracted from [ChatViewModel]
 * to keep the god-object focused on messaging/session concerns.
 *
 * Behavior is identical to the original inline implementation: it mutates the
 * shared [uiState] flow for the four search-related fields and reads `messages`
 * from it when computing matches.
 *
 * @param dispatcher The [CoroutineDispatcher] used for the (CPU-bound) search
 *   work. Defaults to [Dispatchers.Default] — the original behavior — but can
 *   be injected to reuse a caller's context or customize per environment.
 */
class ChatSearchDelegate(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val searchController: ChatSearchController = ChatSearchController(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var searchJob: Job? = null

    fun toggleSearch() {
        val current = uiState.value
        if (current.isSearchActive) {
            clearSearch()
        } else {
            uiState.update { it.copy(isSearchActive = true, searchQuery = "") }
        }
    }

    fun setSearchQuery(query: String) {
        searchJob?.cancel()

        if (query.isBlank()) {
            uiState.update {
                it.copy(
                    searchQuery = query,
                    searchMatchIndices = emptyList(),
                    currentSearchMatchIndex = -1,
                )
            }
            return
        }

        // Keep local state in sync immediately so UI feels responsive.
        uiState.update {
            it.copy(searchQuery = query)
        }

        searchJob = scope.launch(dispatcher) { runSearch(query) }
    }

    private fun runSearch(query: String) {
        val messages = uiState.value.messages
        val indices = searchController.findMatches(messages, query)

        uiState.update {
            // Only update indices if the search query hasn't changed in the meantime
            if (it.searchQuery == query) {
                it.copy(
                    searchMatchIndices = indices,
                    currentSearchMatchIndex = if (indices.isNotEmpty()) 0 else -1,
                )
            } else {
                it
            }
        }
    }

    fun navigateSearchMatch(direction: Int) {
        uiState.update { state ->
            val indices = state.searchMatchIndices
            if (indices.isEmpty()) return@update state
            val newIdx =
                searchController.navigate(
                    currentIndex = state.currentSearchMatchIndex,
                    matchCount = indices.size,
                    direction = direction,
                )
            state.copy(currentSearchMatchIndex = newIdx)
        }
    }

    fun clearSearch() {
        uiState.update {
            it.copy(
                isSearchActive = false,
                searchQuery = "",
                searchMatchIndices = emptyList(),
                currentSearchMatchIndex = -1,
            )
        }
    }
}
