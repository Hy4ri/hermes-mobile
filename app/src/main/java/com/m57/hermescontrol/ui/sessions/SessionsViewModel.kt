package com.m57.hermescontrol.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.local.SessionListCacheStore
import com.m57.hermescontrol.data.local.SwrCache
import com.m57.hermescontrol.data.model.BulkDeleteRequest
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.model.PruneRequest
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionListResponse
import com.m57.hermescontrol.data.model.SessionLiveStatus
import com.m57.hermescontrol.data.model.SessionRenameRequest
import com.m57.hermescontrol.data.model.SessionSearchResult
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.ChangeEventHub
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesProjectsSource
import com.m57.hermescontrol.data.ws.HermesSessionLiveStatusSource
import com.m57.hermescontrol.data.ws.ProjectsSource
import com.m57.hermescontrol.data.ws.SessionLiveStatusSource
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.refreshOnChange
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class SessionStats(
    val total: Int = 0,
    val messages: Int = 0,
)

enum class HistorySection {
    CONVERSATIONS,
    AUTOMATIONS,
}

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

data class SessionsUiState(
    val section: HistorySection = HistorySection.CONVERSATIONS,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val sessions: List<SessionInfo> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
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
    val isLoadingMoreSearch: Boolean = false,
    val searchHasMore: Boolean = false,
    val searchNextOffset: Int? = null,
    val searchLoadMoreError: String? = null,
    val showHidden: Boolean = false,
    val pinnedExpanded: Boolean = true,
    val liveStatuses: Map<String, SessionLiveStatus> = emptyMap(),
    // Named projects used to label each row with the workspace it belongs to.
    val projects: List<ProjectInfo> = emptyList(),
) {
    val isSearchMode: Boolean get() = searchQuery.isNotBlank()

    val hasHiddenSessions: Boolean
        get() = sessions.any { it.hidden == true }

    val displaySessions: List<SessionInfo>
        get() = if (showHidden) sessions else sessions.filter { it.hidden != true }

    val pinnedSessions: List<SessionInfo>
        get() = displaySessions.filter { it.pinned == true }
}

class SessionsViewModel(
    private val liveStatusSource: SessionLiveStatusSource = HermesSessionLiveStatusSource(),
    private val projectsSource: ProjectsSource = HermesProjectsSource(),
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var statsJob: Job? = null
    private var trackingJob: Job? = null
    private var projectsJob: Job? = null
    private var trackingGeneration: Long = 0
    private var liveTrackingState = SessionLiveTrackingState()
    private var liveStatusRefreshInFlight = false
    private var liveStatusRefreshPending = false
    private var generation: Long = 0
    private var rawPaginationOffset: Int = 0
    private val sessionsPageCache = SwrCache<String, SessionListResponse>()

    init {
        viewModelScope.launch {
            ProfileSwitchCoordinator.switched.collect {
                onDataScopeChanged()
            }
        }
        viewModelScope.launch {
            ProfileSwitchCoordinator.connectionSwitched.collect {
                onDataScopeChanged()
            }
        }

        // Issue #784: gateway broadcasts sessions.changed — refresh the list
        // silently (no spinner, no selection reset) instead of blind polling.
        refreshOnChange(
            eventType = ChangeEvents.SESSIONS,
            apiCall = {
                val requestGeneration = generation
                val section = _uiState.value.section
                val requestScope = runCatching { AuthManager.currentDataScope() }.getOrDefault(DataScope.EMPTY)
                when (
                    val result =
                        safeApiCall {
                            ApiClient.hermesApi.getSessions(
                                limit = PAGE_SIZE,
                                offset = 0,
                                order = "recent",
                                source = section.source,
                                excludeSources = section.excludeSources,
                            )
                        }
                ) {
                    is NetworkResult.Success -> {
                        NetworkResult.Success(
                            Triple(requestGeneration, requestScope, result.data),
                        )
                    }

                    is NetworkResult.Failure -> {
                        result
                    }
                }
            },
            onSuccess = { (requestGeneration, requestScope, data) ->
                val currentScope = runCatching { AuthManager.currentDataScope() }.getOrDefault(DataScope.EMPTY)
                if (requestGeneration == generation && currentScope == requestScope) {
                    val section = _uiState.value.section
                    val cacheKey = requestScope.scopedKey("${section.name}:${section.source}:${section.excludeSources}")
                    sessionsPageCache.put(cacheKey, data)
                    SessionListCacheStore.put(cacheKey, data)
                    rawPaginationOffset = data.nextOffset(0)
                    _uiState.update {
                        val newSessions = data.sessions.orEmpty()
                        val paging =
                            SessionsPaging.resolveInitialPaging(
                                receivedCount = minOf(data.sessions.size, PAGE_SIZE),
                                pageSize = PAGE_SIZE,
                                backendTotal = data.total,
                                accumulatedCount = newSessions.size,
                            )
                        it.copy(
                            sessions = newSessions,
                            total = paging.total,
                            hasMore = paging.hasMore,
                        )
                    }
                    stitchMissingParents(requestGeneration)
                }
            },
        )
    }

    private fun onDataScopeChanged() {
        generation++
        loadJob?.cancel()
        pageJob?.cancel()
        statsJob?.cancel()
        rawPaginationOffset = 0
        resolvedParentCache.clear()
        _uiState.update {
            it.copy(
                sessions = emptyList(),
                total = 0,
                hasMore = false,
                selectedIds = emptySet(),
                isSelecting = false,
                errorMessage = null,
            )
        }
        loadSessions()
    }

    /**
     * Page size sent to the server. Matches the desktop sidebar's
     * SIDEBAR_SESSIONS_PAGE_SIZE (50); the backend caps `limit` at 100.
     */
    private companion object {
        const val PAGE_SIZE = 50
        const val SEARCH_DEBOUNCE_MS = 300L
        const val MAX_STITCH_PER_PAGE = 8
        const val MAX_STITCH_ROUNDS = 3
        const val LIVE_STATUS_POLL_INTERVAL_MS = 30_000L
    }

    private val resolvedParentCache = mutableMapOf<String, SessionInfo>()

    fun togglePinnedExpanded() {
        _uiState.update { it.copy(pinnedExpanded = !it.pinnedExpanded) }
    }

    private fun stitchMissingParents(requestGeneration: Long) =
        viewModelScope.launch {
            repeat(MAX_STITCH_ROUNDS) {
                val loaded = _uiState.value.sessions
                val known =
                    buildSet {
                        loaded.forEach {
                            add(it.id)
                            it.lineageRootId?.let(::add)
                        }
                    }
                val missing =
                    loaded
                        .mapNotNull { it.parent_session_id?.trim()?.takeIf(String::isNotEmpty) }
                        .filter { it !in known }
                        .distinct()
                        .take(MAX_STITCH_PER_PAGE)
                if (missing.isEmpty()) return@launch

                val fetched =
                    missing.mapNotNull { pid ->
                        resolvedParentCache[pid] ?: when (
                            val r =
                                safeApiCall { ApiClient.hermesApi.getSessionInfo(pid) }
                        ) {
                            is NetworkResult.Success -> r.data?.also { resolvedParentCache[pid] = it }
                            is NetworkResult.Failure -> null
                        }
                    }
                if (requestGeneration != generation || fetched.isEmpty()) return@launch

                _uiState.update { st ->
                    st.copy(sessions = (st.sessions + fetched).distinctBy { it.id })
                }
            }
        }

    private val HistorySection.source: String?
        get() = if (this == HistorySection.AUTOMATIONS) "cron" else null

    private val HistorySection.excludeSources: String?
        get() = if (this == HistorySection.CONVERSATIONS) "cron" else null

    private fun com.m57.hermescontrol.data.model.SessionListResponse.nextOffset(requestOffset: Int): Int =
        if (limit > 0) offset + limit else requestOffset + PAGE_SIZE

    fun selectSection(section: HistorySection) {
        if (_uiState.value.section == section) return
        generation++
        loadJob?.cancel()
        pageJob?.cancel()
        searchJob?.cancel()
        searchPageJob?.cancel()
        searchGeneration++
        rawPaginationOffset = 0
        val query = _uiState.value.searchQuery
        _uiState.update {
            it.copy(
                section = section,
                isLoading = false,
                isLoadingMore = false,
                sessions = emptyList(),
                total = 0,
                hasMore = false,
                errorMessage = null,
                isSelecting = false,
                selectedIds = emptySet(),
                isSearching = false,
                searchResults = emptyList(),
                searchError = null,
            )
        }
        if (query.isBlank()) loadSessions() else setSearchQuery(query)
    }

    /** Load (or reload) sessions from page 0. Used by pull-to-refresh and initial load. */
    fun loadSessions(forceRefresh: Boolean = false) {
        val requestGeneration = generation
        val requestScope = runCatching { AuthManager.currentDataScope() }.getOrDefault(DataScope.EMPTY)
        val section = _uiState.value.section
        val cacheKey = requestScope.scopedKey("${section.name}:${section.source}:${section.excludeSources}")
        if (forceRefresh) {
            sessionsPageCache.remove(cacheKey)
            SessionListCacheStore.remove(cacheKey)
        }
        val cached =
            if (!forceRefresh) {
                sessionsPageCache.get(cacheKey)
                    ?: SessionListCacheStore.get(cacheKey)?.also { sessionsPageCache.put(cacheKey, it) }
            } else {
                null
            }
        if (cached != null) {
            val sessionsList = cached.sessions.orEmpty()
            val paging =
                SessionsPaging.resolveInitialPaging(
                    receivedCount = minOf(cached.sessions.size, PAGE_SIZE),
                    pageSize = PAGE_SIZE,
                    backendTotal = cached.total,
                    accumulatedCount = sessionsList.size,
                )
            _uiState.update {
                it.copy(
                    isLoading = false,
                    sessions = sessionsList,
                    total = paging.total,
                    hasMore = paging.hasMore,
                    errorMessage = null,
                )
            }
        }
        pageJob?.cancel()
        loadEmptyCount()
        if (trackingJob?.isActive == true) {
            refreshLiveStatuses()
            loadProjects()
        }
        loadJob =
            safeLaunchLoad(
                currentJob = loadJob,
                apiCall = {
                    safeApiCall {
                        ApiClient.hermesApi.getSessions(
                            limit = PAGE_SIZE,
                            offset = 0,
                            order = "recent",
                            source = section.source,
                            excludeSources = section.excludeSources,
                        )
                    }
                },
                onStart = {
                    if (cached == null) {
                        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                    }
                },
                onSuccess = { data ->
                    val currentScope = runCatching { AuthManager.currentDataScope() }.getOrDefault(DataScope.EMPTY)
                    if (requestGeneration != generation || currentScope != requestScope) return@safeLaunchLoad
                    sessionsPageCache.put(cacheKey, data)
                    SessionListCacheStore.put(cacheKey, data)
                    rawPaginationOffset = data.nextOffset(0)
                    val sessionsList = data.sessions.orEmpty()
                    val paging =
                        SessionsPaging.resolveInitialPaging(
                            receivedCount = minOf(data.sessions.size, PAGE_SIZE),
                            pageSize = PAGE_SIZE,
                            backendTotal = data.total,
                            accumulatedCount = sessionsList.size,
                        )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            sessions = sessionsList,
                            total = paging.total,
                            hasMore = paging.hasMore,
                            selectedIds = emptySet(),
                        )
                    }
                    stitchMissingParents(requestGeneration)
                },
                onError = { errorMsg ->
                    val currentScope = runCatching { AuthManager.currentDataScope() }.getOrDefault(DataScope.EMPTY)
                    if (requestGeneration != generation || currentScope != requestScope) return@safeLaunchLoad
                    if (cached == null || forceRefresh) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = "Failed to load sessions: $errorMsg",
                            )
                        }
                    }
                },
            )
    }

    /**
     * Refresh the project list; a failed fetch keeps the last good one so labels don't flicker.
     * Like live statuses, it only runs while the screen tracks the gateway (visible + connected).
     */
    private fun loadProjects() {
        projectsJob?.cancel()
        projectsJob =
            viewModelScope.launch {
                val projects = projectsSource.fetchProjects() ?: return@launch
                _uiState.update { it.copy(projects = projects) }
            }
    }

    /** Load the next page and append to the existing session list. */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore || state.isSearchMode) return
        val requestGeneration = generation
        val offset = rawPaginationOffset

        _uiState.update { it.copy(isLoadingMore = true) }
        pageJob =
            viewModelScope.launch {
                val result =
                    safeApiCall {
                        ApiClient.hermesApi.getSessions(
                            limit = PAGE_SIZE,
                            offset = offset,
                            order = "recent",
                            source = state.section.source,
                            excludeSources = state.section.excludeSources,
                        )
                    }
                if (requestGeneration != generation) return@launch
                when (result) {
                    is NetworkResult.Success -> {
                        val data = result.data
                        rawPaginationOffset = data.nextOffset(offset)
                        _uiState.update {
                            val newSessions =
                                (it.sessions + data.sessions)
                                    .distinctBy { s -> s.id }
                            val addedNewItems = newSessions.size > it.sessions.size
                            val paging =
                                SessionsPaging.resolveLoadMorePaging(
                                    receivedCount = minOf(data.sessions.size, PAGE_SIZE),
                                    pageSize = PAGE_SIZE,
                                    addedNewItems = addedNewItems,
                                    backendTotal = data.total,
                                    accumulatedCount = newSessions.size,
                                )
                            it.copy(
                                isLoadingMore = false,
                                sessions = newSessions,
                                total = paging.total,
                                hasMore = paging.hasMore,
                            )
                        }
                        stitchMissingParents(requestGeneration)
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
    private var searchPageJob: Job? = null
    private var searchGeneration = 0L

    /**
     * Debounced server-side session search. A non-blank query schedules a search
     * call after [SEARCH_DEBOUNCE_MS]; a blank query returns to the normal
     * paginated list mode.
     */
    fun setSearchQuery(query: String) {
        val requestGeneration = ++searchGeneration
        val section = _uiState.value.section
        searchJob?.cancel()
        searchPageJob?.cancel()
        _uiState.update {
            it.copy(
                searchQuery = query,
                searchResults = emptyList(),
                isSearching = query.isNotBlank(),
                searchError = null,
                isLoadingMoreSearch = false,
                searchHasMore = false,
                searchNextOffset = null,
                searchLoadMoreError = null,
                isSelecting = false,
                selectedIds = emptySet(),
            )
        }
        if (query.isBlank()) {
            if (_uiState.value.sessions.isEmpty()) loadSessions()
            return
        }
        searchJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                requestSearchPage(query, section, requestGeneration, 0)
            }
    }

    fun loadMoreSearch() {
        val state = _uiState.value
        val offset = state.searchNextOffset ?: return
        if (!state.isSearchMode || state.isSearching || state.isLoadingMoreSearch || !state.searchHasMore) return
        val requestGeneration = searchGeneration
        _uiState.update { it.copy(isLoadingMoreSearch = true, searchLoadMoreError = null) }
        searchPageJob =
            viewModelScope.launch {
                requestSearchPage(state.searchQuery, state.section, requestGeneration, offset)
            }
    }

    private suspend fun requestSearchPage(
        query: String,
        section: HistorySection,
        requestGeneration: Long,
        offset: Int,
    ) {
        val result =
            safeApiCall {
                ApiClient.hermesApi.searchSessions(
                    q = query,
                    profile = null,
                    source = section.source,
                    excludeSources = section.excludeSources,
                    limit = 20,
                    offset = offset,
                )
            }
        if (requestGeneration != searchGeneration || _uiState.value.section != section) return
        when (result) {
            is NetworkResult.Success -> {
                _uiState.update { state ->
                    val previous = if (offset == 0) emptyList() else state.searchResults
                    // Dedup by final surfaced session id: legacy backends can return
                    // the same continuation twice (distinct lineage roots), and
                    // LazyList keys session ids — duplicates crash the screen.
                    val results = (previous + result.data.results).distinctBy { it.session_id }
                    val next =
                        result.data.next_offset?.takeIf {
                            result.data.hasMore && it > offset && results.size > previous.size
                        }
                    state.copy(
                        isSearching = false,
                        isLoadingMoreSearch = false,
                        searchResults = results,
                        searchError = null,
                        searchLoadMoreError = null,
                        searchHasMore = next != null,
                        searchNextOffset = next,
                    )
                }
            }

            is NetworkResult.Failure -> {
                _uiState.update {
                    if (offset == 0) {
                        it.copy(isSearching = false, searchError = "Search failed: ${result.error.message}")
                    } else {
                        it.copy(isLoadingMoreSearch = false, searchLoadMoreError = "Failed to load more")
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

    fun toggleShowHidden() {
        _uiState.update { it.copy(showHidden = !it.showHidden) }
    }

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
                            searchResults =
                                it.searchResults.map { s ->
                                    if (s.session_id == sessionId) s.copy(title = newTitle) else s
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
                                    .map { s -> if (s.id == sessionId) s.copy(pinned = targetPinned) else s },
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

    // ── Hide / unhide (issue #1019) ──────────────────────────────────────

    /**
     * Toggle the hidden flag on a session via PATCH /api/sessions/{id} ({hidden}).
     */
    fun toggleHide(sessionId: String) {
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        val targetHidden = session.hidden != true
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.setSessionHidden(
                        sessionId = sessionId,
                        body = SessionRenameRequest(hidden = targetHidden),
                    )
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            sessions =
                                it.sessions
                                    .map { s -> if (s.id == sessionId) s.copy(hidden = targetHidden) else s },
                            toastMessage =
                                if (targetHidden) {
                                    "Session hidden"
                                } else {
                                    "Session unhidden"
                                },
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(toastMessage = "Hide failed: ${result.error.message}")
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
                    if (AuthManager.getLastOpenedSessionId() == sessionId) {
                        AuthManager.clearLastOpenedSessionId()
                    }
                    _uiState.update {
                        val updatedSessions = it.sessions.filter { s -> s.id != sessionId }
                        val newTotal = (it.total - 1).coerceAtLeast(0)
                        it.copy(
                            deletingSessionIds = it.deletingSessionIds - sessionId,
                            sessions = updatedSessions,
                            searchResults = it.searchResults.filter { it.session_id != sessionId },
                            total = newTotal,
                            hasMore = it.hasMore && newTotal > updatedSessions.size,
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
                    val lastOpened = AuthManager.getLastOpenedSessionId()
                    if (lastOpened != null && ids.contains(lastOpened)) {
                        AuthManager.clearLastOpenedSessionId()
                    }
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
                            searchResults = it.searchResults.filter { hit -> hit.session_id !in ids },
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

    // ── Live Session Status Tracking (issue #1101) ───────────────────────

    fun startLiveStatusTracking() {
        if (trackingJob?.isActive == true) return
        trackingJob =
            viewModelScope.launch {
                // Connection status observer: non-connected clears immediately, CONNECTED rehydrates.
                launch {
                    liveStatusSource.connectionStatus.collect { status ->
                        if (status != ConnectionStatus.CONNECTED) {
                            trackingGeneration++
                            liveTrackingState = SessionLiveStatusReducer.clear()
                            _uiState.update { it.copy(liveStatuses = emptyMap()) }
                        } else {
                            requestLiveStatusSnapshot(++trackingGeneration)
                            loadProjects()
                        }
                    }
                }

                // Live WebSocket events observer.
                launch {
                    liveStatusSource.events.collect { event ->
                        liveTrackingState = SessionLiveStatusReducer.applyWsEvent(liveTrackingState, event)
                        _uiState.update { it.copy(liveStatuses = liveTrackingState.liveStatuses) }
                    }
                }

                // Change events observer: sessions.changed triggers a snapshot refresh.
                launch {
                    ChangeEventHub.events
                        .filter { it.type == ChangeEvents.SESSIONS }
                        .collect {
                            requestLiveStatusSnapshot(trackingGeneration)
                        }
                }

                // Visible-only 30s poll backstop
                launch {
                    while (true) {
                        delay(LIVE_STATUS_POLL_INTERVAL_MS)
                        requestLiveStatusSnapshot(trackingGeneration)
                    }
                }
            }
    }

    fun stopLiveStatusTracking() {
        trackingGeneration++
        trackingJob?.cancel()
        trackingJob = null
        projectsJob?.cancel()
        projectsJob = null
        liveStatusRefreshInFlight = false
        liveStatusRefreshPending = false
        liveTrackingState = SessionLiveStatusReducer.clear()
        _uiState.update { it.copy(liveStatuses = emptyMap()) }
    }

    fun refreshLiveStatuses() {
        requestLiveStatusSnapshot(trackingGeneration)
    }

    private fun requestLiveStatusSnapshot(gen: Long) {
        if (trackingJob?.isActive != true ||
            gen != trackingGeneration ||
            liveStatusSource.connectionStatus.value != ConnectionStatus.CONNECTED
        ) {
            return
        }
        if (liveStatusRefreshInFlight) {
            liveStatusRefreshPending = true
            return
        }
        liveStatusRefreshInFlight = true
        viewModelScope.launch {
            try {
                val snapshot = liveStatusSource.fetchActiveSessionsSnapshot()
                if (trackingJob?.isActive == true &&
                    gen == trackingGeneration &&
                    liveStatusSource.connectionStatus.value == ConnectionStatus.CONNECTED &&
                    snapshot != null
                ) {
                    liveTrackingState = SessionLiveStatusReducer.applySnapshot(liveTrackingState, snapshot)
                    _uiState.update { it.copy(liveStatuses = liveTrackingState.liveStatuses) }
                }
            } finally {
                liveStatusRefreshInFlight = false
                if (liveStatusRefreshPending &&
                    trackingJob?.isActive == true &&
                    gen == trackingGeneration &&
                    liveStatusSource.connectionStatus.value == ConnectionStatus.CONNECTED
                ) {
                    liveStatusRefreshPending = false
                    requestLiveStatusSnapshot(gen)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveStatusTracking()
    }
}
