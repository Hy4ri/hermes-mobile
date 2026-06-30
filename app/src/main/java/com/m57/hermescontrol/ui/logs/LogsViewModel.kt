package com.m57.hermescontrol.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LogsUiState(
    val isLoading: Boolean = false,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class LogsViewModel : ViewModel(), ToastHost {
    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    @Volatile
    private var loadInProgress = false

    private var autoRefreshJob: Job? = null

    fun loadLogs() {
        if (loadInProgress) return
        loadInProgress = true
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getLogs(lines = 1000) } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                val body = data
                val logsList = body.lines ?: body.logs ?: emptyList()
                _uiState.update { it.copy(isLoading = false, logs = logsList) }
                loadInProgress = false
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load logs: $errorMsg",
                    )
                }
                loadInProgress = false
            },
        )
    }

    /** Start auto-refreshing logs every 5 seconds. */
    fun startAutoRefresh() {
        if (autoRefreshJob?.isActive == true) return
        autoRefreshJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(5_000)
                    loadLogs()
                }
            }
    }

    /** Stop auto-refreshing logs. */
    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
