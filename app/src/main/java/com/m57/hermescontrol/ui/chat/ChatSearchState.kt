package com.m57.hermescontrol.ui.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Snapshot-backed holder for in-chat search state, owned by
 * [ChatSearchDelegate] and read directly by composables.
 *
 * Being a STABLE instance with mutable [androidx.compose.runtime.State]
 * fields — the LazyListState pattern — means search updates never change
 * the instance itself, so composables that take it as a parameter (the
 * full-bleed chat list) are SKIPPED on search changes; only the scopes
 * that actually read a field (visible item lambdas, the scroll effect)
 * recompose. That was the last big cost: search state used to live inside
 * [ChatUiState], so every debounced scan re-executed the entire list body
 * (turns loop + item registrations) on the main thread.
 */
@Stable
class ChatSearchState {
    var isActive by mutableStateOf(false)
        internal set

    var query by mutableStateOf("")
        internal set

    /** Stored matches (capped at [ChatSearchController.MAX_SEARCH_MATCHES]). */
    var matchIndices by mutableStateOf(emptyList<Int>())
        internal set

    /** Parallel to [matchIndices]: character offset of each occurrence. */
    var matchOffsets by mutableStateOf(emptyList<Int>())
        internal set

    var currentIndex by mutableStateOf(-1)
        internal set

    /** Exact total before the cap; [matchCapped] renders the counter as `N+`. */
    var matchTotal by mutableStateOf(0)
        internal set

    var matchCapped by mutableStateOf(false)
        internal set

    /**
     * Ids of messages containing at least one match. Bubbles outside this
     * set skip their highlight scan entirely.
     */
    var matchedIds by mutableStateOf(emptySet<String>())
        internal set

    /** Id of the message holding the CURRENT match (O(1) highlight lookup). */
    var currentMatchId by mutableStateOf<String?>(null)
        internal set
}
