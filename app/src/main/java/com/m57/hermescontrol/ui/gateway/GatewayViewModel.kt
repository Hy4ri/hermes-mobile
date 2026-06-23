package com.m57.hermescontrol.ui.gateway

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
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
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getStatus() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, status = result.data) }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load status: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun startGateway() {
        runGatewayAction("start") { safeApiCall { ApiClient.hermesApi.startGateway() } }
    }

    fun stopGateway() {
        runGatewayAction("stop") { safeApiCall { ApiClient.hermesApi.stopGateway() } }
    }

    fun restartGateway() {
        runGatewayAction("restart") { safeApiCall { ApiClient.hermesApi.restartGateway() } }
    }

    private fun runGatewayAction(
        actionName: String,
        apiCall: suspend () -> NetworkResult<Unit>,
    ) {
        _uiState.update { it.copy(isActionRunning = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { apiCall() }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Gateway ${actionName}ed successfully",
                        )
                    }
                    loadStatus()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Failed to $actionName gateway: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
