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
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionStats(
    val total: Int = 0,
    val active: Int = 0,
)

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
    val deletingSessionIds: Set<String> = emptySet(),
    val showPruneDialog: Boolean = false,
    val isPruning: Boolean = false,
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

class SessionsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var statsJob: Job? = null

    /** Page size sent to the server — matches the default the gateway uses. */
    private companion object {
        const val PAGE_SIZE = 20
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    /** Load (or reload) sessions from page 0. Used by pull-to-refresh and initial load. */
    fun loadSessions() {
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
                            sessions = data?.sessions.orEmpty(),
                            total = data?.total ?: 0,
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
                            sessions = it.sessions + data.sessions,
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
                                searchResults = result.data?.results.orEmpty(),
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
                                data?.let { SessionStats(total = it.total, active = it.active) }
                                    ?: SessionStats(),
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

    fun exitSelecting() {
        _uiState.update { it.copy(isSelecting = false, selectedIds = emptySet()) }
    }

    fun toggleSessionSelection(id: String) {
        _uiState.update {
            val updated = it.selectedIds.toMutableSet()
            if (updated.contains(id)) updated.remove(id) else updated.add(id)
            it.copy(selectedIds = updated)
        }
    }

    fun selectAll() {
        _uiState.update {
            it.copy(selectedIds = it.sessions.map { s -> s.id }.toSet())
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    // ── Rename ───────────────────────────────────────────────────────────

    fun startRenaming(sessionId: String) {
        _uiState.update { it.copy(renamingSessionId = sessionId) }
    }

    fun cancelRenaming() {
        _uiState.update { it.copy(renamingSessionId = null) }
    }

    fun renameSession(
        sessionId: String,
        newTitle: String,
    ) {
        if (newTitle.isBlank()) {
            _uiState.update { it.copy(renamingSessionId = null, toastMessage = "Title cannot be empty") }
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
                            toastMessage = "Rename failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Copy prompt ──────────────────────────────────────────────────────

    fun copySessionPrompt(sessionId: String) {
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.getSessionPrompt(sessionId)
                }
            when (result) {
                is NetworkResult.Success -> {
                    val promptText = result.data?.prompt ?: "No prompt available"
                    _uiState.update { it.copy(toastMessage = promptText) }
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to get prompt: ${result.error.message}") }
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
                            total = it.total - 1,
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
                    _uiState.update {
                        it.copy(
                            isDeletingBulk = false,
                            isSelecting = false,
                            selectedIds = emptySet(),
                            sessions = it.sessions.filter { s -> s.id !in ids },
                            total = it.total - ids.size,
                            toastMessage = "${ids.size} session(s) deleted",
                        )
                    }
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

    // ── Toast ────────────────────────────────────────────────────────────

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
