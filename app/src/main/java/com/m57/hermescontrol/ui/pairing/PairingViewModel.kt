package com.m57.hermescontrol.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.SwrCache
import com.m57.hermescontrol.data.model.PairingApproveRequest
import com.m57.hermescontrol.data.model.PairingResponse
import com.m57.hermescontrol.data.model.PairingRevokeRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.remote.safeApiRead
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.refreshOnChange
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import com.m57.hermescontrol.ui.common.safeLaunchSwrLoad
import kotlinx.coroutines.CoroutineDispatcher
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
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val pairingCache = SwrCache<String, PairingResponse>()
    private var actionGeneration = 0L

    fun clearScopeOwnedState() {
        actionGeneration++
        _uiState.update { it.copy(isLoading = false, pairing = null, errorMessage = null, actionKey = null) }
    }

    init {
        // Issue #784: gateway broadcasts pairing.changed — refresh silently
        // (no spinner) instead of waiting for a manual pull.
        refreshOnChange(
            eventType = ChangeEvents.PAIRING,
            apiCall = { safeApiRead { ApiClient.hermesApi.getPairing() } },
            onSuccess = { data ->
                val requestScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
                if (requestScope != null) pairingCache.put(requestScope.inMemoryKey("default"), data)
                _uiState.update { it.copy(pairing = data) }
            },
        )
    }

    fun loadPairing(forceRefresh: Boolean = false) {
        launchJob =
            safeLaunchSwrLoad(
                cache = pairingCache,
                forceRefresh = forceRefresh,
                currentJob = launchJob,
                onCacheHit = { cached ->
                    _uiState.update { it.copy(isLoading = false, pairing = cached, errorMessage = null) }
                },
                apiCall = { safeApiRead { ApiClient.hermesApi.getPairing() } },
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
        val capturedProfile = AuthManager.activeProfileId.value
        val capturedApi = ApiClient.hermesApi
        runAction(
            actionKey = "approve:$requestId",
            onSuccess = { it.copy(toastMessage = "Pairing request approved") },
            apiCall = {
                safeApiCall {
                    capturedApi.approvePairing(
                        PairingApproveRequest(
                            platform = platform,
                            requestId = requestId,
                            profile = capturedProfile,
                        ),
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
        val capturedProfile = AuthManager.activeProfileId.value
        val capturedApi = ApiClient.hermesApi
        runAction(
            actionKey = "revoke:$platform:$userId",
            onSuccess = { it.copy(toastMessage = "Access revoked for $userId") },
            apiCall = {
                safeApiCall {
                    capturedApi.revokePairing(
                        PairingRevokeRequest(
                            platform = platform,
                            userId = userId,
                            profile = capturedProfile,
                        ),
                    )
                }
            },
        )
    }

    /** Clear every pending pairing request. */
    fun clearPending() {
        val capturedApi = ApiClient.hermesApi
        runAction(
            actionKey = "clear",
            onSuccess = { it.copy(toastMessage = "Pending requests cleared") },
            apiCall = { safeApiCall { capturedApi.clearPendingPairing() } },
        )
    }

    private fun runAction(
        actionKey: String,
        onSuccess: (PairingUiState) -> PairingUiState,
        apiCall: suspend () -> NetworkResult<Unit>,
    ) {
        if (_uiState.value.actionKey != null) return
        val actionScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
        val actionProfile = AuthManager.activeProfileId.value
        val generation = ++actionGeneration
        _uiState.update { it.copy(actionKey = actionKey) }
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { apiCall() }
                val currentScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
                if (generation != actionGeneration || currentScope != actionScope ||
                    AuthManager.activeProfileId.value != actionProfile
                ) {
                    return@launch
                }
                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update { onSuccess(it.copy(actionKey = null)) }
                        loadPairing(forceRefresh = true)
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
            } finally {
                _uiState.update {
                    if (generation == actionGeneration) it.copy(actionKey = null) else it
                }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
