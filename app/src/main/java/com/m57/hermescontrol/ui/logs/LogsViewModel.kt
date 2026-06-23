package com.m57.hermescontrol.ui.logs

import androidx.lifecycle.ViewModel
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LogsUiState(
    val isLoading: Boolean = false,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class LogsViewModel : ViewModel(), ToastHost {
    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    fun loadLogs() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getLogs() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                val body = data
                val logsList = body.lines ?: body.logs ?: emptyList()
                _uiState.update { it.copy(isLoading = false, logs = logsList) }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load logs: $errorMsg",
                    )
                }
            },
        )
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
