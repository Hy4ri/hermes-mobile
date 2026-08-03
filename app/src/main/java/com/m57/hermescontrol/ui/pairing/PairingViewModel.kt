package com.m57.hermescontrol.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.PairingApproveRequest
import com.m57.hermescontrol.data.model.PairingResponse
import com.m57.hermescontrol.data.model.PairingRevokeRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PairingUiState(
    val isLoading: Boolean = false,
    val pairing: PairingResponse? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    /** Key of the item whose approve/revoke/clear action is in flight. */
    val actionKey: String? = null,
)

class PairingViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    private var launchJob: Job? = null

    fun loadPairing() {
        launchJob =
            safeLaunchLoad(
                currentJob = launchJob,
                apiCall = { safeApiCall { ApiClient.hermesApi.getPairing() } },
                onStart = {
                    _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                },
                onSuccess = { data ->
                    _uiState.update { it.copy(isLoading = false, pairing = data) }
                },
                onError = { errorMsg ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load pairing: $errorMsg",
                        )
                    }
                },
            )
    }

    /** Approve a pending pairing request by its server-side `request_id`. */
    fun approvePairing(
        platform: String,
        requestId: String,
    ) {
        runAction(
            actionKey = "approve:$requestId",
            onSuccess = { it.copy(toastMessage = "Pairing request approved") },
            apiCall = {
                safeApiCall {
                    ApiClient.hermesApi.approvePairing(
                        PairingApproveRequest(platform = platform, requestId = requestId),
                    )
                }
            },
        )
    }

    /** Remove a user from the approved whitelist. */
    fun revokePairing(
        platform: String,
        userId: String,
    ) {
        runAction(
            actionKey = "revoke:$platform:$userId",
            onSuccess = { it.copy(toastMessage = "Access revoked for $userId") },
            apiCall = {
                safeApiCall {
                    ApiClient.hermesApi.revokePairing(
                        PairingRevokeRequest(platform = platform, userId = userId),
                    )
                }
            },
        )
    }

    /** Clear every pending pairing request. */
    fun clearPending() {
        runAction(
            actionKey = "clear",
            onSuccess = { it.copy(toastMessage = "Pending requests cleared") },
            apiCall = { safeApiCall { ApiClient.hermesApi.clearPendingPairing() } },
        )
    }

    private fun runAction(
        actionKey: String,
        onSuccess: (PairingUiState) -> PairingUiState,
        apiCall: suspend () -> NetworkResult<Unit>,
    ) {
        if (_uiState.value.actionKey != null) return
        _uiState.update { it.copy(actionKey = actionKey) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { apiCall() }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { onSuccess(it.copy(actionKey = null)) }
                    loadPairing()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            actionKey = null,
                            toastMessage = "Action failed: ${result.error.message}",
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
