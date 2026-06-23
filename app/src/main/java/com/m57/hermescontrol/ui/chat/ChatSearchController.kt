package com.m57.hermescontrol.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatSearchController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatUiState>,
) {
    private var searchJob: Job? = null

    fun toggleSearch() {
        val current = state.value
        if (current.isSearchActive) {
            clearSearch()
        } else {
            state.update { it.copy(isSearchActive = true, searchQuery = "") }
        }
    }

    fun setSearchQuery(query: String) {
        searchJob?.cancel()

        if (query.isBlank()) {
            state.update {
                it.copy(
                    searchQuery = query,
                    searchMatchIndices = emptyList(),
                    currentSearchMatchIndex = -1,
                )
            }
            return
        }

        // Keep local state in sync immediately so UI feels responsive.
        state.update {
            it.copy(searchQuery = query)
        }

        searchJob =
            scope.launch(Dispatchers.Default) {
                val messages = state.value.messages
                val indices =
                    messages.indices.filter { idx ->
                        messages[idx].content.contains(query, ignoreCase = true)
                    }

                state.update {
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
    }

    fun navigateSearchMatch(direction: Int) {
        state.update { currentState ->
            val indices = currentState.searchMatchIndices
            if (indices.isEmpty()) return@update currentState
            val current = currentState.currentSearchMatchIndex
            val newIdx =
                when (direction) {
                    1 -> if (current >= indices.lastIndex) 0 else current + 1
                    -1 -> if (current <= 0) indices.lastIndex else current - 1
                    else -> current
                }
            currentState.copy(currentSearchMatchIndex = newIdx)
        }
    }

    fun clearSearch() {
        state.update {
            it.copy(
                isSearchActive = false,
                searchQuery = "",
                searchMatchIndices = emptyList(),
                currentSearchMatchIndex = -1,
            )
        }
    }
}
