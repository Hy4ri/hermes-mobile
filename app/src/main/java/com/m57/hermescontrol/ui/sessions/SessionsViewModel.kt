package com.m57.hermescontrol.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.BulkDeleteRequest
import com.m57.hermescontrol.data.model.PruneRequest
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionRenameRequest
import com.m57.hermescontrol.data.model.SessionSearchResult
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.refreshOnChange
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class SessionStats(
    val total: Int = 0,
    val messages: Int = 0,
)

/**
 * Compact count for stat cards: max 3 digits + unit letter.
 * 987 → "987", 100987 → "100k", 1234567 → "1.23m", 100000000 → "100m".
 */
internal fun formatCompactCount(value: Int): String {
    if (value < 1_000) return value.toString()
    val (divisor, suffix) =
        when {
            value < 1_000_000 -> 1_000 to "k"
            value < 1_000_000_000 -> 1_000_000 to "m"
            else -> 1_000_000_000 to "b"
        }
    val scaled = value.toDouble() / divisor
    val digits =
        when {
            scaled >= 100 -> scaled.toInt().toString()
            scaled >= 10 -> String.format(Locale.US, "%.1f", scaled).trimZeroes()
            else -> String.format(Locale.US, "%.2f", scaled).trimZeroes()
        }
    return "$digits$suffix"
}

private fun String.trimZeroes(): String = dropLastWhile { it == '0' }.trimEnd('.')

/**
 * Stable sort: pinned sessions first. The backend only back-fills pins into
 * page 1 (it doesn't lift them to the top), so the client owns the ordering.
 * Stability keeps recency order intact within each group.
 */
private fun List<SessionInfo>.pinnedFirst(): List<SessionInfo> = sortedBy { it.pinned != true }

data class SessionsUiState(
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val sessions: List<SessionInfo> = emptyList(),
    val total: Int = 0,
    val errorMessage: String? = null,
    val stats: SessionStats = SessionStats(),
    val isLoadingStats: Boolean = false,
    val statsError: String? = null,
    val isSelecting: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val renamingSessionId: String? = null,
    val renameDraft: String = "",
    val deletingSessionIds: Set<String> = emptySet(),
    val showPruneDialog: Boolean = false,
    val isPruning: Boolean = false,
    // Empty-session cleanup (issue #787): count of empty, ended, non-archived
    // sessions — button is disabled/grey when 0.
    val emptyCount: Int = 0,
    val showEmptyCleanupDialog: Boolean = false,
    val isCleaningEmpty: Boolean = false,
    val isDeletingBulk: Boolean = false,
    val toastMessage: String? = null,
    val sessionToDeleteConfirm: String? = null,
    val showBulkDeleteConfirm: Boolean = false,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<SessionSearchResult> = emptyList(),
    val searchError: String? = null,
) {
    val hasMore: Boolean get() = total > sessions.size
    val isSearchMode: Boolean get() = searchQuery.isNotBlank()
}

class SessionsViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var statsJob: Job? = null

    init {
        // Issue #784: gateway broadcasts sessions.changed — refresh the list
        // silently (no spinner, no selection reset) instead of blind polling.
        refreshOnChange(
            eventType = ChangeEvents.SESSIONS,
            apiCall = {
                safeApiCall {
                    ApiClient.hermesApi.getSessions(
                        limit = PAGE_SIZE,
                        offset = 0,
                        order = "recent",
                    )
                }
            },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        sessions = data.sessions.orEmpty().pinnedFirst(),
                        total = data.total,
                    )
                }
            },
        )
    }

    /**
     * Page size sent to the server. Matches the desktop sidebar's
     * SIDEBAR_SESSIONS_PAGE_SIZE (50); the backend caps `limit` at 100.
     */
    private companion object {
        const val PAGE_SIZE = 50
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    /** Load (or reload) sessions from page 0. Used by pull-to-refresh and initial load. */
    fun loadSessions() {
        loadEmptyCount()
        loadJob =
            safeLaunchLoad(
                currentJob = loadJob,
                apiCall = {
                    safeApiCall {
                        ApiClient.hermesApi.getSessions(
                            limit = PAGE_SIZE,
                            offset = 0,
                            order = "recent",
                        )
                    }
                },
                onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
                onSuccess = { data ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            sessions = data.sessions.orEmpty().pinnedFirst(),
                            total = data.total,
                            selectedIds = emptySet(),
                        )
                    }
                },
                onError = { errorMsg ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = "Failed to load sessions: $errorMsg",
                        )
                    }
                },
            )
    }

    /** Load the next page and append to the existing session list. */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || !state.hasMore) return

        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.getSessions(
                        limit = PAGE_SIZE,
                        offset = state.sessions.size,
                        order = "recent",
                    )
                }
            when (result) {
                is NetworkResult.Success -> {
                    val data = result.data
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            // distinctBy guards offset-pagination churn: if a new
                            // session lands on top between page loads, offsets
                            // shift and the next page can repeat an id we already
                            // have — LazyColumn would crash on the duplicate key.
                            // pinnedFirst keeps pinned sessions above the rest.
                            sessions =
                                (it.sessions + data.sessions)
                                    .distinctBy { s -> s.id }
                                    .pinnedFirst(),
                            total = data.total,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            errorMessage = "Failed to load more: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Search (server-backed FTS5) ──────────────────────────────────

    private var searchJob: Job? = null

    /**
     * Debounced server-side session search. A non-blank query schedules a search
     * call after [SEARCH_DEBOUNCE_MS]; a blank query returns to the normal
     * paginated list mode.
     */
    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update {
                it.copy(searchResults = emptyList(), searchError = null, isSearching = false)
            }
            return
        }
        searchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                _uiState.update { it.copy(isSearching = true, searchError = null) }
                val result =
                    safeApiCall {
                        ApiClient.hermesApi.searchSessions(q = query, profile = null)
                    }
                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isSearching = false,
                                searchResults = result.data.results.orEmpty(),
                                searchError = null,
                            )
                        }
                    }

                    is NetworkResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                isSearching = false,
                                searchResults = emptyList(),
                                searchError = "Search failed: ${result.error.message}",
                            )
                        }
                    }
                }
            }
    }

    // ── Stats ────────────────────────────────────────────────────────────

    fun loadStats() {
        statsJob =
            safeLaunchLoad(
                currentJob = statsJob,
                apiCall = {
                    safeApiCall { ApiClient.hermesApi.getSessionStats() }
                },
                onStart = { _uiState.update { it.copy(isLoadingStats = true, statsError = null) } },
                onSuccess = { data ->
                    _uiState.update {
                        it.copy(
                            isLoadingStats = false,
                            stats =
                                SessionStats(total = data.total, messages = data.messages),
                        )
                    }
                },
                onError = { errorMsg ->
                    _uiState.update {
                        it.copy(
                            isLoadingStats = false,
                            statsError = errorMsg,
                        )
                    }
                },
            )
    }

    // ── Bulk selection ───────────────────────────────────────────────────

    fun toggleSelecting() {
        _uiState.update {
            it.copy(
                isSelecting = !it.isSelecting,
                selectedIds = if (it.isSelecting) emptySet() else it.selectedIds,
            )
        }
    }

    fun toggleSessionSelection(id: String) {
        _uiState.update {
            val updated = it.selectedIds.toMutableSet()
            if (updated.contains(id)) updated.remove(id) else updated.add(id)
            it.copy(selectedIds = updated)
        }
    }

    fun selectAll(sessionIds: Set<String> = _uiState.value.sessions.mapTo(linkedSetOf()) { it.id }) {
        _uiState.update {
            it.copy(selectedIds = sessionIds.toSet())
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    // ── Rename ───────────────────────────────────────────────────────────

    fun openRenameDialog(
        sessionId: String,
        currentTitle: String,
    ) {
        _uiState.update { it.copy(renamingSessionId = sessionId, renameDraft = currentTitle) }
    }

    fun updateRenameDraft(value: String) {
        _uiState.update { it.copy(renameDraft = value) }
    }

    fun closeRenameDialog() {
        _uiState.update { it.copy(renamingSessionId = null, renameDraft = "") }
    }

    fun renameSession(
        sessionId: String,
        newTitle: String,
    ) {
        if (newTitle.isBlank()) {
            _uiState.update {
                it.copy(
                    renamingSessionId = null,
                    renameDraft = "",
                    toastMessage = "Title cannot be empty",
                )
            }
            return
        }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.renameSession(
                        sessionId = sessionId,
                        body = SessionRenameRequest(title = newTitle),
                    )
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            renamingSessionId = null,
                            renameDraft = "",
                            sessions =
                                it.sessions.map { s ->
                                    if (s.id == sessionId) s.copy(title = newTitle) else s
                                },
                            toastMessage = "Session renamed",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            renamingSessionId = null,
                            renameDraft = "",
                            toastMessage = "Rename failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Pin / unpin (durable "keep" flag, exempts from auto-archive) ──────

    /**
     * Toggle the pinned flag on a session via PATCH /api/sessions/{id}
     * ({pinned}). On success the session is flagged and re-sorted to the top
     * of the history list immediately; the backend back-fills pins into
     * page 1 on every load, so the pinned-first sort stays consistent.
     */
    fun togglePin(sessionId: String) {
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        val targetPinned = session.pinned != true
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.setSessionPinned(
                        sessionId = sessionId,
                        body = SessionRenameRequest(pinned = targetPinned),
                    )
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            sessions =
                                it.sessions
                                    .map { s -> if (s.id == sessionId) s.copy(pinned = targetPinned) else s }
                                    .pinnedFirst(),
                            toastMessage =
                                if (targetPinned) {
                                    "Session pinned"
                                } else {
                                    "Session unpinned"
                                },
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(toastMessage = "Pin failed: ${result.error.message}")
                    }
                }
            }
        }
    }

    // ── Delete (single) ──────────────────────────────────────────────────

    fun requestDeleteSession(sessionId: String) {
        _uiState.update { it.copy(sessionToDeleteConfirm = sessionId) }
    }

    fun cancelDeleteSession() {
        _uiState.update { it.copy(sessionToDeleteConfirm = null) }
    }

    fun confirmDeleteSession() {
        val sessionId = _uiState.value.sessionToDeleteConfirm ?: return
        _uiState.update {
            it.copy(
                sessionToDeleteConfirm = null,
                deletingSessionIds = it.deletingSessionIds + sessionId,
            )
        }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.deleteSession(sessionId)
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            deletingSessionIds = it.deletingSessionIds - sessionId,
                            sessions = it.sessions.filter { s -> s.id != sessionId },
                            searchResults = it.searchResults.filter { it.session_id != sessionId },
                            total = (it.total - 1).coerceAtLeast(0),
                            toastMessage = "Session deleted",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            deletingSessionIds = it.deletingSessionIds - sessionId,
                            toastMessage = "Delete failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Bulk delete ──────────────────────────────────────────────────────

    fun requestBulkDelete() {
        _uiState.update { it.copy(showBulkDeleteConfirm = true) }
    }

    fun cancelBulkDelete() {
        _uiState.update { it.copy(showBulkDeleteConfirm = false) }
    }

    fun confirmBulkDelete() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return

        _uiState.update { it.copy(showBulkDeleteConfirm = false, isDeletingBulk = true) }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.bulkDeleteSessions(
                        body = BulkDeleteRequest(ids = ids),
                    )
                }
            when (result) {
                is NetworkResult.Success -> {
                    val deletedCount = result.data.deleted
                    val toastMsg =
                        if (deletedCount > 0) {
                            "$deletedCount session(s) deleted"
                        } else {
                            "No sessions were deleted"
                        }
                    _uiState.update {
                        it.copy(
                            isDeletingBulk = false,
                            isSelecting = false,
                            selectedIds = emptySet(),
                            toastMessage = toastMsg,
                        )
                    }
                    loadSessions()
                    loadStats()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isDeletingBulk = false,
                            toastMessage = "Delete failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Prune ────────────────────────────────────────────────────────────

    fun showPruneDialog() {
        _uiState.update { it.copy(showPruneDialog = true) }
    }

    fun hidePruneDialog() {
        _uiState.update { it.copy(showPruneDialog = false) }
    }

    fun pruneSessions(days: Int) {
        if (days < 1) return
        _uiState.update { it.copy(isPruning = true, showPruneDialog = false) }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.pruneSessions(
                        body = PruneRequest(days = days),
                    )
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isPruning = false,
                            toastMessage = "Old sessions pruned",
                        )
                    }
                    loadSessions()
                    loadStats()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isPruning = false,
                            toastMessage = "Prune failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Empty-session cleanup (issue #787) ───────────────────────────────

    /** Refresh the empty-session count (REST; auxiliary — failures are silent). */
    fun loadEmptyCount() {
        viewModelScope.launch {
            val result =
                runCatching {
                    safeApiCall { ApiClient.hermesApi.getEmptySessionCount() }
                }.getOrNull()
            val count = (result as? NetworkResult.Success)?.data?.count
            if (count != null) {
                _uiState.update { it.copy(emptyCount = count) }
            }
        }
    }

    fun requestEmptyCleanup() {
        _uiState.update { it.copy(showEmptyCleanupDialog = true) }
    }

    fun hideEmptyCleanupDialog() {
        _uiState.update { it.copy(showEmptyCleanupDialog = false) }
    }

    fun confirmEmptyCleanup() {
        _uiState.update { it.copy(isCleaningEmpty = true, showEmptyCleanupDialog = false) }
        viewModelScope.launch {
            val result =
                runCatching {
                    safeApiCall { ApiClient.hermesApi.deleteEmptySessions() }
                }.getOrNull()
            when (result) {
                is NetworkResult.Success -> {
                    val deleted = result.data.deleted
                    _uiState.update {
                        it.copy(
                            isCleaningEmpty = false,
                            emptyCount = 0,
                            toastMessage =
                                if (deleted > 0) {
                                    "Deleted $deleted empty sessions"
                                } else {
                                    "No empty sessions to delete"
                                },
                        )
                    }
                    loadSessions()
                }

                else -> {
                    val message = (result as? NetworkResult.Failure)?.error?.message ?: "Unknown error"
                    _uiState.update {
                        it.copy(
                            isCleaningEmpty = false,
                            toastMessage = "Cleanup failed: $message",
                        )
                    }
                }
            }
        }
    }

    // ── Toast ────────────────────────────────────────────────────────────

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
