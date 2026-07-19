package com.m57.hermescontrol.ui.analytics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AnalyticsCacheStore
import com.m57.hermescontrol.data.model.AnalyticsResponse
import com.m57.hermescontrol.data.model.ModelsAnalyticsResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val DAY_OPTIONS = listOf(7, 30, 90)

/**
 * Drives the Analytics screen (issue #537): historical usage + per-model stats
 * from `GET /api/analytics/usage` and `GET /api/analytics/models`.
 *
 * `days` selects the trailing window (7 / 30 / 90). `profile` is optional and
 * forwarded to the backend for multi-profile parity (#5) — `null` means the
 * active/default profile, matching the desktop `getManagementProfile()` default.
 */
data class AnalyticsUiState(
    val isLoading: Boolean = false,
    val days: Int = 30,
    val profile: String? = null,
    val usage: AnalyticsResponse? = null,
    val models: ModelsAnalyticsResponse? = null,
    // Issue #537 follow-up (B): the two endpoints have very different latencies —
    // /models is fast (~0.1s) while /usage runs the insights engine and can take
    // tens of seconds on a cold backend. Split the render so the fast half
    // (models + totals) shows immediately and the slow half (usage charts) streams
    // in behind a slim placeholder instead of holding the whole tab hostage.
    val usageLoading: Boolean = false,
    val errorMessage: String? = null,
)

class AnalyticsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(AnalyticsUiState())
    val uiState: StateFlow<AnalyticsUiState> = _uiState.asStateFlow()

    private val cacheStore = AnalyticsCacheStore(application)

    private val api get() = ApiClient.hermesApi

    private var loadJob: Job? = null

    /** In-memory cache keyed by days for instant tab switching. */
    private data class CacheEntry(
        val usage: AnalyticsResponse,
        val models: ModelsAnalyticsResponse?,
    )

    private val cache = mutableMapOf<Int, CacheEntry>()

    init {
        // Issue #537 follow-up (C): seed the in-memory cache from disk so a cold
        // app launch renders last session's analytics instantly, then refreshes.
        // Disk read is async (viewModelScope) so we never block the UI thread
        // on startup — the first load() call falls back to on-demand fetch if
        // the seed hasn't landed yet.
        viewModelScope.launch {
            DAY_OPTIONS.forEach { days ->
                cacheStore.load(days, _uiState.value.profile)?.let { (usage, models) ->
                    cache[days] = CacheEntry(usage, models)
                }
            }
        }
    }

    /** Reload both analytics endpoints for the current [days]/[profile]. */
    fun load() {
        loadJob?.cancel()
        val days = _uiState.value.days
        val profile = _uiState.value.profile

        // Serve from cache instantly if available.
        cache[days]?.let { cached ->
            _uiState.update {
                it.copy(
                    isLoading = false,
                    usage = cached.usage,
                    models = cached.models,
                    usageLoading = false,
                    errorMessage = null,
                )
            }
            // Refresh in background silently.
            fetchAndCache(days, profile, showLoading = false)
            return
        }

        _uiState.update { it.copy(isLoading = true, usageLoading = true, errorMessage = null) }
        fetchAndCache(days, profile, showLoading = true)
    }

    private fun fetchAndCache(
        days: Int,
        profile: String?,
        showLoading: Boolean,
    ) {
        loadJob =
            viewModelScope.launch {
                // Issue #537 follow-up (B): fetch the two endpoints independently and
                // surface the fast one (/models, ~0.1s, carries the totals card) the
                // instant it lands, while /usage streams in behind a placeholder. The
                // usage call is what's slow on a cold backend, so we must not let it
                // block the models/totals render.
                val modelsDeferred =
                    async { safeApiCall<ModelsAnalyticsResponse> { api.getModelsAnalytics(days, profile) } }

                // Surface models immediately when it arrives (before usage finishes).
                val modelsResult = modelsDeferred.await()
                val models = (modelsResult as? NetworkResult.Success)?.data
                if (models != null) {
                    // Surface models now; only fold it into the cache entry if we
                    // already have usage (otherwise the usage block below creates
                    // the full entry). Never bail the coroutine on a missing usage.
                    cache[days]?.let { existing -> cache[days] = existing.copy(models = models) }
                    if (_uiState.value.days == days) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                models = models,
                                usageLoading = it.usage == null,
                                errorMessage = null,
                            )
                        }
                    }
                }

                // Now the slow usage call.
                val usageDeferred =
                    async { safeApiCall<AnalyticsResponse> { api.getAnalytics(days, profile) } }
                val usageResult = usageDeferred.await()
                val usage = (usageResult as? NetworkResult.Success)?.data ?: _uiState.value.usage

                if (usage != null) {
                    val finalModels = models ?: _uiState.value.models
                    cache[days] = CacheEntry(usage, finalModels)
                    // Write-through to disk so a cold app launch stays instant (C).
                    cacheStore.save(days, profile, usage, finalModels)
                }

                // Surface the REAL failure (HTTP code / connection / parse error)
                // instead of a generic string so the root cause is never hidden.
                val failure =
                    when {
                        usageResult is NetworkResult.Failure -> usageResult.error
                        modelsResult is NetworkResult.Failure -> modelsResult.error
                        else -> null
                    }
                val error =
                    when {
                        usage == null && models == null -> {
                            failure?.message ?: "Failed to load analytics"
                        }

                        usage == null -> {
                            failure?.message ?: "Failed to load usage analytics"
                        }

                        models == null -> {
                            failure?.message ?: "Failed to load model analytics"
                        }

                        else -> {
                            null
                        }
                    }
                // Only update UI if this is still the active day window.
                if (_uiState.value.days == days) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            usage = usage,
                            models = models ?: it.models,
                            usageLoading = false,
                            errorMessage = error,
                        )
                    }
                }
                // Prefetch other day windows in the background.
                if (showLoading) {
                    prefetchOtherWindows(days, profile)
                }
            }
    }

    /** Prefetch the other two day windows silently so tab switching is instant. */
    private fun prefetchOtherWindows(
        currentDays: Int,
        profile: String?,
    ) {
        DAY_OPTIONS.filter { it != currentDays }.forEach { otherDays ->
            if (cache.containsKey(otherDays)) return@forEach
            viewModelScope.launch {
                val usageDeferred =
                    async { safeApiCall<AnalyticsResponse> { api.getAnalytics(otherDays, profile) } }
                val modelsDeferred =
                    async { safeApiCall<ModelsAnalyticsResponse> { api.getModelsAnalytics(otherDays, profile) } }
                val usageResult = (usageDeferred.await() as? NetworkResult.Success)?.data
                val modelsResult = (modelsDeferred.await() as? NetworkResult.Success)?.data
                if (usageResult != null) {
                    cache[otherDays] = CacheEntry(usageResult, modelsResult)
                }
            }
        }
    }

    /** Change the trailing window and reload. */
    fun setDays(days: Int) {
        if (days == _uiState.value.days) return
        _uiState.update { it.copy(days = days) }
        load()
    }
}
