package com.m57.hermescontrol.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.PairingApproveRequest
import com.m57.hermescontrol.data.model.PairingResponse
import com.m57.hermescontrol.data.model.PairingRevokeRequest
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

data class PairingUiState(
    val isLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    val pairing: PairingResponse? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class PairingViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    fun loadPairing() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getPairing() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, pairing = result.data) }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load pairing info: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun approvePairing(
        platform: String,
        code: String,
    ) {
        runAction("Approve") {
            safeApiCall { ApiClient.hermesApi.approvePairing(PairingApproveRequest(platform, code)) }
        }
    }

    fun revokePairing(
        platform: String,
        userId: String,
    ) {
        runAction("Revoke") {
            safeApiCall { ApiClient.hermesApi.revokePairing(PairingRevokeRequest(platform, userId)) }
        }
    }

    fun clearPending() {
        runAction("Clear Pending") {
            safeApiCall { ApiClient.hermesApi.clearPendingPairing() }
        }
    }

    private fun runAction(
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
                            toastMessage = "Pairing action '$actionName' succeeded",
                        )
                    }
                    loadPairing()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Action '$actionName' failed: ${result.error.message}",
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
