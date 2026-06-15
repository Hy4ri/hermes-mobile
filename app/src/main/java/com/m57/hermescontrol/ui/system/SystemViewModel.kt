package com.m57.hermescontrol.ui.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.DoctorResponse
import com.m57.hermescontrol.data.model.SystemStatsResponse
import com.m57.hermescontrol.data.remote.ApiClient
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

class SystemViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SystemUiState())
    val uiState: StateFlow<SystemUiState> = _uiState.asStateFlow()

    fun loadSystemData() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val statsResponse =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getSystemStats()
                    }
                val doctorResponse =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.runDoctor()
                    }

                if (statsResponse.isSuccessful && doctorResponse.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            stats = statsResponse.body(),
                            doctorReport = doctorResponse.body(),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage =
                                "Failed to load system data: HTTP " +
                                    "${statsResponse.code()} / ${doctorResponse.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load system data: ${e.message}",
                    )
                }
            }
        }
    }

    fun triggerBackup() {
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.triggerBackup()
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(toastMessage = "Backup triggered successfully") }
                } else {
                    _uiState.update { it.copy(toastMessage = "Failed to trigger backup: HTTP ${response.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Failed to trigger backup: ${e.message}") }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
