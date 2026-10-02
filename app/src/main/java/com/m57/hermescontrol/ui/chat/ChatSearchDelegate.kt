package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fullbleed.currentMatchMessageId
import com.m57.hermescontrol.ui.chat.fullbleed.matchedMessageIds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var queryEpoch = 0L
    private var lastScannedMessages: List<ChatMessage>? = null

    init {
        // Queue transitions can change the presented row indices without changing the query.
        scope.launch {
            uiState.map(::searchableMessages).distinctUntilChanged().collect { messages ->
                // Do not bypass typing debounce; once it ends, scan the latest state.
                searchJob?.join()
                if (searchJob?.isActive == true) return@collect
                val query = searchState.query
                val latest = searchableMessages(uiState.value)
                if (query.isNotBlank() && latest != lastScannedMessages) {
                    runSearch(query, queryEpoch, preserveCurrent = true, messages = latest)
                }
            }
        }
    }

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
        queryEpoch++
        searchState.query = query
        lastScannedMessages = null

        if (query.isBlank()) {
            resetMatches()
            return
        }

        // Keep local state in sync immediately so UI feels responsive, then
        // debounce: cancel any pending scan and only run one after typing
        // pauses for debounceMs (fast typing = one scan, not one per char).
        val epoch = queryEpoch
        searchJob =
            scope.launch {
                delay(debounceMs)
                runSearch(query, epoch, preserveCurrent = false)
            }
    }

    private suspend fun runSearch(
        query: String,
        epoch: Long,
        preserveCurrent: Boolean,
        messages: List<ChatMessage> = searchableMessages(uiState.value),
    ) {
        val result = withContext(dispatcher) { searchController.findMatches(messages, query) }
        applySearchResult(query, epoch, preserveCurrent, messages, result)
    }

    /** Called on the caller/UI context; no suspension between validation and publication. */
    private fun applySearchResult(
        query: String,
        epoch: Long,
        preserveCurrent: Boolean,
        messages: List<ChatMessage>,
        result: SearchResult,
    ) {
        // A query can change away and back to the same text, or presentation can
        // change during a scan. Neither stale result may replace the current one.
        if (queryEpoch != epoch || searchState.query != query || searchableMessages(uiState.value) != messages) return

        val previousId = if (preserveCurrent) searchState.currentMatchId else null
        val previousTarget = if (preserveCurrent) searchState.matchTargets.getOrNull(searchState.currentIndex) else null
        val previousOffset = if (preserveCurrent) searchState.matchOffsets.getOrNull(searchState.currentIndex) else null
        val preservedIndex =
            result.matches
                .indexOfFirst { match ->
                    messages[match.messageIndex].id == previousId && match.target == previousTarget &&
                        match.contentOffset == previousOffset
                }.takeIf { it >= 0 } ?: result.matches
                .indexOfFirst { match ->
                    messages[match.messageIndex].id == previousId && match.target == previousTarget
                }.takeIf { it >= 0 }

        lastScannedMessages = messages
        searchState.matchIndices = result.matches.map { m -> m.messageIndex }
        searchState.matchOffsets = result.matches.map { m -> m.contentOffset }
        searchState.matchTargets = result.matches.map { m -> m.target }
        searchState.matchTotal = result.totalMatches
        searchState.matchCapped = result.capped
        searchState.currentIndex = if (result.matches.isNotEmpty()) preservedIndex ?: 0 else -1
        searchState.matchedIds = matchedMessageIds(messages, searchState.matchIndices)
        searchState.currentMatchId =
            currentMatchMessageId(messages, searchState.matchIndices, searchState.currentIndex)
    }

    fun navigateSearchMatch(direction: Int) {
        // A navigation click can arrive before the state-flow collector handles a queue transition.
        val query = searchState.query
        if (searchJob?.isActive == true) return
        val messages = searchableMessages(uiState.value)
        if (query.isNotBlank() && messages != lastScannedMessages) {
            val epoch = queryEpoch
            searchJob =
                scope.launch {
                    runSearch(query, epoch, preserveCurrent = true, messages = messages)
                    if (queryEpoch == epoch && searchState.query == query && lastScannedMessages == messages) {
                        applyNavigation(direction)
                    }
                }
            return
        }
        applyNavigation(direction)
    }

    /** Called on the UI scope, including after a worker rescan completes. */
    private fun applyNavigation(direction: Int) {
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
            currentMatchMessageId(searchableMessages(uiState.value), indices, newIdx)
    }

    // Keep search indices in the same presentation space as the transcript (custom queue UI).
    private fun searchableMessages(state: ChatUiState): List<ChatMessage> =
        messagesWithoutUnsentQueue(
            messagesWithoutUnconfirmedReceipts(state.messages, state.pendingSends),
            state.pendingSends,
        )

    fun clearSearch() {
        searchJob?.cancel()
        queryEpoch++
        searchState.isActive = false
        searchState.query = ""
        lastScannedMessages = null
        resetMatches()
    }

    private fun resetMatches() {
        searchState.matchIndices = emptyList()
        searchState.matchOffsets = emptyList()
        searchState.matchTargets = emptyList()
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
