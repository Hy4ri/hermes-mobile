package com.m57.hermescontrol.ui.achievements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.SwrCache
import com.m57.hermescontrol.data.model.Achievement
import com.m57.hermescontrol.data.model.AchievementsResponse
import com.m57.hermescontrol.data.model.RecentUnlock
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.remote.safeApiRead
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import com.m57.hermescontrol.ui.common.safeLaunchSwrLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

internal const val SCAN_POLL_INTERVAL_MS = 3_000L
internal const val SCAN_POLL_MAX_ATTEMPTS = 20

internal fun isScanRunning(data: AchievementsResponse): Boolean = data.scanMeta?.status?.state == "running"

class AchievementsViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(AchievementsUiState())
    val uiState: StateFlow<AchievementsUiState> = _uiState.asStateFlow()

    private val api get() = ApiClient.hermesApi
    private val achievementsCache = SwrCache<String, AchievementsResponse>()
    private var pollJob: Job? = null

    fun clearScopeOwnedState() {
        pollJob?.cancel()
        _uiState.update {
            it.copy(
                isLoading = false,
                achievements = emptyList(),
                unlockedCount = 0,
                discoveredCount = 0,
                secretCount = 0,
                totalCount = 0,
                categories = emptyList(),
                recentUnlocks = emptyList(),
                errorMessage = null,
            )
        }
    }

    fun loadAchievements(forceRefresh: Boolean = false) {
        safeLaunchSwrLoad(
            cache = achievementsCache,
            forceRefresh = forceRefresh,
            onCacheHit = { data ->
                applyResponse(data)
                pollWhileScanning(data)
            },
            apiCall = { safeApiRead { api.getAchievements() } },
            onStart = {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            },
            onSuccess = { data ->
                applyResponse(data)
                pollWhileScanning(data)
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

    private fun applyResponse(data: AchievementsResponse) {
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
                errorMessage = null,
            )
        }
    }

    /**
     * The gateway answers with the pre-scan snapshot (`is_stale`, scan state `running`) and
     * finishes the scan in the background. A single GET would leave "Scanning…" and "Cached"
     * up forever, so re-read until the scan reports a finished state.
     */
    private fun pollWhileScanning(data: AchievementsResponse) {
        pollJob?.cancel()
        if (!isScanRunning(data)) return
        val requestScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
        pollJob =
            viewModelScope.launch {
                repeat(SCAN_POLL_MAX_ATTEMPTS) {
                    delay(SCAN_POLL_INTERVAL_MS)
                    val result = safeApiRead { api.getAchievements() }
                    val currentScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
                    if (currentScope != requestScope) return@launch
                    if (result is NetworkResult.Success) {
                        val fresh = result.data
                        if (requestScope != null) {
                            achievementsCache.put(requestScope.scopedKey("default"), fresh)
                        }
                        applyResponse(fresh)
                        if (!isScanRunning(fresh)) return@launch
                    }
                }
            }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    fun loadRecentUnlocks() {
        safeLaunchLoad(
            apiCall = { safeApiRead { api.getRecentUnlocks() } },
            onStart = { /* silent background refresh */ },
            onSuccess = { unlocks ->
                _uiState.update { it.copy(recentUnlocks = unlocks) }
            },
            onError = { /* silent — recent unlocks is non-critical */ },
        )
    }

    fun rescan() {
        val requestScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
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
                // A successful rescan is a mutation: publish the fresh payload into the
                // scoped cache, otherwise the next SWR revisit replays the pre-rescan list.
                val currentScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
                if (requestScope != null && currentScope == requestScope) {
                    achievementsCache.put(requestScope.scopedKey("default"), data)
                }
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
                loadAchievements(forceRefresh = true)
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

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
