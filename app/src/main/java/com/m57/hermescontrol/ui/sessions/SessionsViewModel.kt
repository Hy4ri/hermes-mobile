package com.m57.hermescontrol.ui.sessions

import androidx.lifecycle.ViewModel
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SessionsUiState(
    val isLoading: Boolean = false,
    val sessions: List<SessionInfo> = emptyList(),
    val errorMessage: String? = null,
)

class SessionsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    fun loadSessions() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getSessions() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        sessions = data?.sessions.orEmpty(),
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load sessions: $errorMsg",
                    )
                }
            },
        )
    }
}
