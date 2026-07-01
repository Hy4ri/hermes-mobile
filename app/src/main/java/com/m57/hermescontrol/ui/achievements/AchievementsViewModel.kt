package com.m57.hermescontrol.ui.achievements

import androidx.lifecycle.ViewModel
import com.m57.hermescontrol.data.model.Achievement
import com.m57.hermescontrol.data.model.RecentUnlock
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AchievementsUiState(
    val isLoading: Boolean = false,
    val achievements: List<Achievement> = emptyList(),
    val unlockedCount: Int = 0,
    val discoveredCount: Int = 0,
    val secretCount: Int = 0,
    val totalCount: Int = 0,
    val isStale: Boolean = false,
    val generatedAt: Long? = null,
    val errorMessage: String? = null,
    // Scan status
    val scanState: String = "idle",
    val scanLastError: String? = null,
    val scanLastDurationMs: Long? = null,
    val scanRunCount: Int = 0,
    // Recent unlocks
    val recentUnlocks: List<RecentUnlock> = emptyList(),
    // Filters
    val categories: List<String> = emptyList(),
    val activeCategory: String? = null,
    val activeState: String? = null,
    // Action states
    val isRescanning: Boolean = false,
    val isResetting: Boolean = false,
    val toastMessage: String? = null,
)

class AchievementsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AchievementsUiState())
    val uiState: StateFlow<AchievementsUiState> = _uiState.asStateFlow()

    private val api get() = ApiClient.hermesApi

    fun loadAchievements() {
        safeLaunchLoad(
            apiCall = { safeApiCall { api.getAchievements() } },
            onStart = {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            },
            onSuccess = { data ->
                val categories =
                    data.achievements
                        ?.mapNotNull { it.category }
                        ?.distinct()
                        ?.sorted() ?: emptyList()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        achievements = data.achievements.orEmpty(),
                        unlockedCount = data.unlockedCount,
                        discoveredCount = data.discoveredCount,
                        secretCount = data.secretCount,
                        totalCount = data.totalCount,
                        isStale = data.isStale,
                        generatedAt = data.generatedAt,
                        scanState = data.scanMeta?.status?.state ?: "idle",
                        scanLastError = data.scanMeta?.status?.lastError,
                        scanLastDurationMs = data.scanMeta?.status?.lastDurationMs,
                        scanRunCount = data.scanMeta?.status?.runCount ?: 0,
                        categories = categories,
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load achievements: $errorMsg",
                    )
                }
            },
        )
    }

    fun loadScanStatus() {
        safeLaunchLoad(
            apiCall = { safeApiCall { api.getAchievementScanStatus() } },
            onSuccess = { status ->
                _uiState.update {
                    it.copy(
                        scanState = status.state,
                        scanLastError = status.lastError,
                        scanLastDurationMs = status.lastDurationMs,
                        scanRunCount = status.runCount,
                    )
                }
            },
        )
    }

    fun loadRecentUnlocks() {
        safeLaunchLoad(
            apiCall = { safeApiCall { api.getRecentUnlocks() } },
            onSuccess = { unlocks ->
                _uiState.update { it.copy(recentUnlocks = unlocks) }
            },
        )
    }

    fun rescan() {
        safeLaunchLoad(
            apiCall = { safeApiCall { api.rescanAchievements() } },
            onStart = {
                _uiState.update { it.copy(isRescanning = true) }
            },
            onSuccess = { data ->
                val categories =
                    data.achievements
                        ?.mapNotNull { it.category }
                        ?.distinct()
                        ?.sorted() ?: emptyList()
                _uiState.update {
                    it.copy(
                        isRescanning = false,
                        achievements = data.achievements.orEmpty(),
                        unlockedCount = data.unlockedCount,
                        discoveredCount = data.discoveredCount,
                        secretCount = data.secretCount,
                        totalCount = data.totalCount,
                        isStale = data.isStale,
                        generatedAt = data.generatedAt,
                        scanState = data.scanMeta?.status?.state ?: "idle",
                        scanLastError = data.scanMeta?.status?.lastError,
                        scanLastDurationMs = data.scanMeta?.status?.lastDurationMs,
                        scanRunCount = data.scanMeta?.status?.runCount ?: 0,
                        categories = categories,
                        toastMessage = "Rescan complete",
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isRescanning = false,
                        toastMessage = "Rescan failed: $errorMsg",
                    )
                }
            },
        )
    }

    fun resetState() {
        safeLaunchLoad(
            apiCall = { safeApiCall { api.resetAchievementState() } },
            onStart = {
                _uiState.update { it.copy(isResetting = true) }
            },
            onSuccess = {
                // After reset, clear everything and reload
                _uiState.update {
                    AchievementsUiState(
                        isLoading = true,
                        toastMessage = "Achievement state reset",
                    )
                }
                loadAchievements()
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isResetting = false,
                        toastMessage = "Reset failed: $errorMsg",
                    )
                }
            },
        )
    }

    fun setActiveCategory(category: String?) {
        _uiState.update { it.copy(activeCategory = category) }
    }

    fun setActiveState(state: String?) {
        _uiState.update { it.copy(activeState = state) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
