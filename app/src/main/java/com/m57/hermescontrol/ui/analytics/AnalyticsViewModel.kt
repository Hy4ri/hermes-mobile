package com.m57.hermescontrol.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.AnalyticsResponse
import com.m57.hermescontrol.data.model.ModelsAnalyticsResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    /** Reload both analytics endpoints for the current [days]/[profile]. */
    fun load() {
        loadJob?.cancel() // cancel any in-flight load so a chip change isn't dropped
        val days = _uiState.value.days
        val profile = _uiState.value.profile
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        loadJob =
            viewModelScope.launch {
                // Retrofit suspends are already main-safe; no need for async(Dispatchers.IO).
                val usageResult = safeApiCall<AnalyticsResponse> { api.getAnalytics(days, profile) }
                val modelsResult = safeApiCall<ModelsAnalyticsResponse> { api.getModelsAnalytics(days, profile) }
                // On failure keep previously-loaded data visible instead of wiping it.
                val usage = (usageResult as? NetworkResult.Success)?.data ?: _uiState.value.usage
                val models = (modelsResult as? NetworkResult.Success)?.data ?: _uiState.value.models
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
                        usage == null && models == null ->
                            failure?.message ?: "Failed to load analytics"
                        usage == null ->
                            failure?.message ?: "Failed to load usage analytics"
                        models == null ->
                            failure?.message ?: "Failed to load model analytics"
                        else -> null
                    }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        usage = usage,
                        models = models,
                        errorMessage = error,
                    )
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
