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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    private var debounceJob: Job? = null
    private var applyJob: Job? = null
    private var loadedPage = 0

    init {
        runSearch(query = "", page = 1)
    }

    override fun clearToast() {
        _uiState.update { it.copy(errorMessage = null, applyError = null) }
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

    /** Resolve preview/download metadata for a row on demand (cached). */
    suspend fun resolveAssets(extensionId: String): ThemeAssets? =
        (repository.resolveAssets(extensionId) as? NetworkResult.Success)?.data

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
                _uiState.update { it.copy(applyingExtensionId = entry.extensionId, applyError = null) }
                val failure = applyNow(entry)
                _uiState.update { it.copy(applyingExtensionId = null, applyError = failure) }
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
                return "Could not read theme package: ${e.message}"
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
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = page == 1,
                    canLoadMore = page > 1 && it.canLoadMore,
                    errorMessage = null,
                )
            }
            when (val result = repository.search(query = query, limit = PAGE_SIZE, page = page)) {
                is NetworkResult.Success -> {
                    val entries = result.data
                    loadedPage = page
                    _uiState.update {
                        it.copy(
                            entries = if (page == 1) entries else it.entries + entries,
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
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val PAGE_SIZE = 20
    }
}
