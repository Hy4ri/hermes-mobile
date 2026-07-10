package com.m57.hermescontrol.ui.gateway

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

class GatewayViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(GatewayUiState())
    val uiState: StateFlow<GatewayUiState> = _uiState.asStateFlow()

    init {
        // Re-fetch on profile switch (see AuthManager.selectedProfileIdFlow).
        // The screen's LaunchedEffect(Unit) performs the initial load.
        AuthManager.selectedProfileIdFlow
            .drop(1)
            .onEach { loadStatus() }
            .launchIn(viewModelScope)
    }

    fun loadStatus() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getStatus() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update { it.copy(isLoading = false, status = data) }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load status: $errorMsg",
                    )
                }
            },
        )
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

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
