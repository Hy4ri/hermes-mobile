package com.m57.hermescontrol.ui.gateway

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GatewayUiState(
    val isLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    val status: StatusResponse? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class GatewayViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(GatewayUiState())
    val uiState: StateFlow<GatewayUiState> = _uiState.asStateFlow()

    fun loadStatus() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getStatus()
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(isLoading = false, status = response.body()) }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load status: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load status: ${e.message}",
                    )
                }
            }
        }
    }

    fun startGateway() {
        runGatewayAction("start") { ApiClient.hermesApi.startGateway() }
    }

    fun stopGateway() {
        runGatewayAction("stop") { ApiClient.hermesApi.stopGateway() }
    }

    fun restartGateway() {
        runGatewayAction("restart") { ApiClient.hermesApi.restartGateway() }
    }

    private fun runGatewayAction(
        actionName: String,
        apiCall: suspend () -> retrofit2.Response<Unit>,
    ) {
        _uiState.update { it.copy(isActionRunning = true) }
        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { apiCall() }
                if (response.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Gateway ${actionName}ed successfully",
                        )
                    }
                    loadStatus()
                } else {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Failed to $actionName gateway: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionRunning = false,
                        toastMessage = "Failed to $actionName gateway: ${e.message}",
                    )
                }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
