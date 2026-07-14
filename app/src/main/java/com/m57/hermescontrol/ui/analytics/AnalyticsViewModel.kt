package com.m57.hermescontrol.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val errorMessage: String? = null,
)

class AnalyticsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AnalyticsUiState())
    val uiState: StateFlow<AnalyticsUiState> = _uiState.asStateFlow()

    private val api get() = ApiClient.hermesApi

    private var loadJob: Job? = null

    /** In-memory cache keyed by days for instant tab switching. */
    private data class CacheEntry(
        val usage: AnalyticsResponse,
        val models: ModelsAnalyticsResponse?,
    )

    private val cache = mutableMapOf<Int, CacheEntry>()

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
                    errorMessage = null,
                )
            }
            // Refresh in background silently.
            fetchAndCache(days, profile, showLoading = false)
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        fetchAndCache(days, profile, showLoading = true)
    }

    private fun fetchAndCache(
        days: Int,
        profile: String?,
        showLoading: Boolean,
    ) {
        loadJob =
            viewModelScope.launch {
                val usageDeferred =
                    async { safeApiCall<AnalyticsResponse> { api.getAnalytics(days, profile) } }
                val modelsDeferred =
                    async { safeApiCall<ModelsAnalyticsResponse> { api.getModelsAnalytics(days, profile) } }
                val usageResult = usageDeferred.await()
                val modelsResult = modelsDeferred.await()
                // On failure keep previously-loaded data visible instead of wiping it.
                val usage = (usageResult as? NetworkResult.Success)?.data ?: _uiState.value.usage
                val models = (modelsResult as? NetworkResult.Success)?.data ?: _uiState.value.models
                // Cache successful results.
                if (usage != null) {
                    cache[days] = CacheEntry(usage, models)
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
                            models = models,
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
