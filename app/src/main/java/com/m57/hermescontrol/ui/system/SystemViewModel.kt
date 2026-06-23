package com.m57.hermescontrol.ui.system

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.data.model.DoctorResponse
import com.m57.hermescontrol.data.model.SystemStatsResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SystemUiState(
    val isLoading: Boolean = false,
    val stats: SystemStatsResponse? = null,
    val doctorReport: DoctorResponse? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class SystemViewModel : ViewModel(), ToastHost {
    companion object {
        private const val TAG = "SystemViewModel"
    }

    private val _uiState = MutableStateFlow(SystemUiState())
    val uiState: StateFlow<SystemUiState> = _uiState.asStateFlow()

    fun loadSystemData() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getSystemStats() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        stats = data,
                    )
                }
                // Doctor is optional; many servers do not expose it.
                loadDoctorReport()
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load system stats: $errorMsg",
                    )
                }
            },
        )
    }

    private fun loadDoctorReport() {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.runDoctor() }
                }
            if (result is NetworkResult.Success) {
                _uiState.update { it.copy(doctorReport = result.data) }
            } else if (result is NetworkResult.Failure && BuildConfig.DEBUG) {
                Log.w(TAG, "doctor endpoint unavailable: ${result.error.message}")
            }
        }
    }

    fun triggerBackup() {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.triggerBackup() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Backup triggered successfully") }
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to trigger backup: ${result.error.message}") }
                }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
