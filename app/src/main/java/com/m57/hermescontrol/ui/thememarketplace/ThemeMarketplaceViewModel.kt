package com.m57.hermescontrol.ui.thememarketplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.theme.import.ThemeApplier
import com.m57.hermescontrol.data.theme.import.VsixThemeParser
import com.m57.hermescontrol.data.theme.marketplace.MarketplaceThemeEntry
import com.m57.hermescontrol.data.theme.marketplace.ThemeAssets
import com.m57.hermescontrol.data.theme.marketplace.ThemeMarketplaceRepository
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ThemeMarketplaceUiState(
    /** Catalog rows for the current query, in gallery sort order. */
    val entries: List<MarketplaceThemeEntry> = emptyList(),
    /** True while a first-page search (initial load / retry / new query) is in flight. */
    val isLoading: Boolean = false,
    /** A full page came back, so another page may exist. */
    val canLoadMore: Boolean = false,
    val errorMessage: String? = null,
    val query: String = "",
    /** Extension id currently being downloaded/converted/applied. */
    val applyingExtensionId: String? = null,
    /** Last apply failure, shown on the card being applied. */
    val applyError: String? = null,
    /** Row the last [applyError] belongs to, so the failure stays visible after the apply stops. */
    val applyErrorExtensionId: String? = null,
    /** Currently selected entry for detail preview; null when no detail is open. */
    val selectedEntry: String? = null,
    /** Cached resolved assets per extensionId, keyed by extension ID. */
    val resolvedAssets: Map<String, ThemeAssets> = emptyMap(),
)

/**
 * Marketplace catalog browsing + theme apply (t_5316ccb7 catalog,
 * t_f3c6f528 apply pipeline).
 *
 * Queries the VS Code Gallery ExtensionQuery API directly — the same
 * endpoint the desktop's own "Install theme…" page browses, so the phone
 * needs no desktop host in the loop. Search is debounced; every
 * `query|page` result is cached in-memory by [ThemeMarketplaceRepository].
 * Row-level preview + download metadata is resolved lazily (also cached).
 * [applyTheme] downloads the `.vsix`, converts every contributed variant
 * with the desktop-ported seed+mix converter, and publishes the family via
 * [ThemeApplier] (persists + selects `ThemePreset.CUSTOM`).
 */
class ThemeMarketplaceViewModel(
    private val repository: ThemeMarketplaceRepository = ThemeMarketplaceRepository(),
    private val vsixParser: VsixThemeParser = VsixThemeParser(),
    private val applier: ThemeApplier = ThemeApplier,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ThemeMarketplaceUiState())
    val uiState: StateFlow<ThemeMarketplaceUiState> = _uiState.asStateFlow()

    /** Applied marketplace theme id; drives the active badge. */
    val activeCustomThemeId: StateFlow<String?> = applier.activeCustomThemeId

    /** Applied marketplace theme display name (spec M5: name + source id). */
    val activeCustomThemeName: StateFlow<String?> = applier.activeCustomThemeName

    private var debounceJob: Job? = null
    private var applyJob: Job? = null
    private var assetsJob: Job? = null
    private var searchJob: Job? = null
    private var loadedPage = 0

    init {
        runSearch(query = "", page = 1)
    }

    override fun clearToast() {
        _uiState.update { it.copy(errorMessage = null, applyError = null, applyErrorExtensionId = null) }
    }

    /** Debounced search-as-you-type. */
    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        debounceJob?.cancel()
        debounceJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                runSearch(query = query, page = 1)
            }
    }

    /** Retry the last query from page 1 (used by the error/empty states). */
    fun retry() {
        runSearch(query = _uiState.value.query, page = 1)
    }

    /** Append the next page when one is available. */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || !state.canLoadMore) return
        runSearch(query = state.query, page = loadedPage + 1)
    }

    /**
     * Select an entry for the detail dialog. This never touches the applied
     * theme — only [applyTheme] does (spec M4). Preview/download metadata is
     * resolved once per entry and kept in [ThemeMarketplaceUiState.resolvedAssets].
     */
    fun selectEntry(entry: MarketplaceThemeEntry?) {
        val extensionId = entry?.extensionId
        _uiState.update { it.copy(selectedEntry = extensionId) }
        if (extensionId == null) return
        if (_uiState.value.resolvedAssets.containsKey(extensionId)) return
        assetsJob?.cancel()
        assetsJob =
            viewModelScope.launch {
                val assets =
                    (repository.resolveAssets(extensionId) as? NetworkResult.Success)?.data
                        ?: return@launch
                _uiState.update { it.copy(resolvedAssets = it.resolvedAssets + (extensionId to assets)) }
            }
    }

    /**
     * Apply a marketplace theme: resolve the `.vsix` URL, download, parse
     * every contributed variant, convert, and publish. Surfaces row-level
     * progress via [ThemeMarketplaceUiState.applyingExtensionId] and failures via
     * [ThemeMarketplaceUiState.applyError].
     */
    fun applyTheme(entry: MarketplaceThemeEntry) {
        if (_uiState.value.applyingExtensionId != null) return
        applyJob?.cancel()
        applyJob =
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        applyingExtensionId = entry.extensionId,
                        applyError = null,
                        applyErrorExtensionId = null,
                    )
                }
                val failure = withContext(Dispatchers.IO) { applyNow(entry) }
                _uiState.update {
                    it.copy(
                        applyingExtensionId = null,
                        applyError = failure,
                        applyErrorExtensionId = failure?.let { _ -> entry.extensionId },
                    )
                }
            }
    }

    private suspend fun applyNow(entry: MarketplaceThemeEntry): String? {
        val downloadUrl =
            when (val assets = repository.resolveAssets(entry.extensionId)) {
                is NetworkResult.Success -> assets.data.downloadUrl
                is NetworkResult.Failure -> return "Could not resolve download: ${assets.error.message}"
            }
        val variants =
            vsixParser.parseVsix(downloadUrl).getOrElse { e ->
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                return "Could not read theme package: $detail"
            }
        if (variants.isEmpty() || variants.all { it.colors.isEmpty() }) {
            return "Theme package has no colors to import"
        }
        applier.applyFamily(entry.extensionId, entry.displayName, variants)
        return applier.applyError.value
    }

    private fun runSearch(
        query: String,
        page: Int,
    ) {
        // A first-page search supersedes any in-flight query so a slow stale
        // response can never clobber a newer search or list (spec M3: no stale
        // replacement). Load-more appends are serialized behind it.
        if (page == 1) {
            searchJob?.cancel()
        }
        searchJob =
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        isLoading = page == 1,
                        canLoadMore = page > 1 && it.canLoadMore,
                        errorMessage = null,
                    )
                }
                val result = repository.search(query = query, limit = PAGE_SIZE, page = page)
                // Drop the response if THIS coroutine was superseded/cancelled
                // by a newer first-page search while we were in flight.
                val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                if (job == null || job.isActive == false) return@launch
                when (result) {
                    is NetworkResult.Success -> {
                        val entries = result.data
                        loadedPage = page
                        _uiState.update {
                            it.copy(
                                entries =
                                    if (page == 1) {
                                        entries
                                    } else {
                                        // Deduplicate on extensionId so paging
                                        // never appends rows already shown.
                                        (it.entries + entries).distinctBy { row -> row.extensionId }
                                    },
                                canLoadMore = entries.size >= PAGE_SIZE,
                                isLoading = false,
                                errorMessage = null,
                            )
                        }
                    }

                    is NetworkResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                // Keep stale rows on load-more failure; only surface
                                // the error when there is nothing to show.
                                errorMessage =
                                    if (it.entries.isEmpty()) {
                                        "Failed to load themes: ${result.error.message}"
                                    } else {
                                        it.errorMessage
                                    },
                            )
                        }
                    }
                }
            }
        // Keep the active query in the UI (load-more follows it).
        _uiState.update { it.copy(query = query) }
        if (page == 1) {
            loadedPage = page
        }
    }

    /** `internal` so the offline unit tests can assert the real debounce/page sizes. */
    companion object {
        internal const val SEARCH_DEBOUNCE_MS = 300L
        internal const val PAGE_SIZE = 20
    }
}
