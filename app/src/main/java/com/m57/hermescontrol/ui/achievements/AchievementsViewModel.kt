package com.m57.hermescontrol.ui.achievements

import androidx.lifecycle.ViewModel
import com.m57.hermescontrol.data.model.Achievement
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
    val errorMessage: String? = null,
)

class AchievementsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AchievementsUiState())
    val uiState: StateFlow<AchievementsUiState> = _uiState.asStateFlow()

    fun loadAchievements() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getAchievements() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        achievements = data.achievements.orEmpty(),
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
}
