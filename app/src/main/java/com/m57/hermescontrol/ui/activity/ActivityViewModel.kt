package com.m57.hermescontrol.ui.activity

import androidx.lifecycle.ViewModel
import com.m57.hermescontrol.data.model.ActivityItem
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ActivityUiState(
    val isLoading: Boolean = false,
    val items: List<ActivityItem> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class ActivityViewModel : ViewModel(), ToastHost {
    private val _uiState = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> = _uiState.asStateFlow()

    fun loadActivity() {
        safeLaunchLoad(
            apiCall = {
                safeApiCall { ApiClient.hermesApi.getSessions(source = "cron", limit = 30) }
            },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                val items =
                    data.sessions
                        .mapNotNull { it.toActivityItem() }
                        .sortedByDescending { it.timestamp }
                _uiState.update { it.copy(isLoading = false, items = items) }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load activity: $errorMsg",
                    )
                }
            },
        )
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
