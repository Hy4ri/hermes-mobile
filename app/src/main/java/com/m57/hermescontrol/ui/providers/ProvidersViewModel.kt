package com.m57.hermescontrol.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.OAuthPollResponse
import com.m57.hermescontrol.data.model.OAuthProvider
import com.m57.hermescontrol.data.model.OAuthStartResponse
import com.m57.hermescontrol.data.model.OAuthSubmitRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Re-auth flow phase shown in the OAuth dialog. */
enum class OAuthFlowPhase {
    IDLE,
    STARTING,

    /** pkce: browser opened, waiting for the user to paste the callback code. */
    WAITING_CODE,

    /** device_code: user_code + verification_url shown, background poll running. */
    POLLING,

    /** terminal state — close the dialog + reload. */
    DONE,
}

data class ProvidersUiState(
    val isLoading: Boolean = false,
    val providers: List<OAuthProvider> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val actingId: String? = null,
    // ── Re-auth flow ──
    val flowPhase: OAuthFlowPhase = OAuthFlowPhase.IDLE,
    val flowProvider: OAuthProvider? = null,
    val flowStart: OAuthStartResponse? = null,
    val flowSessionId: String = "",
    val flowProviderId: String = "",
    val flowCodeInput: String = "",
    val flowStatus: String = "pending",
    val flowErrorMessage: String? = null,
    val flowExpiresIn: String = "",
)

class ProvidersViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ProvidersUiState())
    val uiState: StateFlow<ProvidersUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    fun load() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getOAuthProviders() } },
            onStart = {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        providers = data?.providers.orEmpty(),
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load providers: $errorMsg",
                    )
                }
            },
        )
    }

    /** Disconnect a provider whose credentials are app-managed. */
    fun disconnectProvider(providerId: String) {
        _uiState.update { it.copy(actingId = providerId) }
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.disconnectOAuthProvider(providerId) } },
            onStart = {},
            onSuccess = {
                _uiState.update {
                    it.copy(
                        actingId = null,
                        toastMessage = "Disconnected",
                    )
                }
                load()
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        actingId = null,
                        toastMessage = "Disconnect failed: $error",
                    )
                }
            },
        )
    }

    // ── Re-auth flow ────────────────────────────────────────────────────

    fun startOAuthFlow(provider: OAuthProvider) {
        val phase = _uiState.value.flowPhase
        if (phase == OAuthFlowPhase.STARTING || phase == OAuthFlowPhase.WAITING_CODE ||
            phase == OAuthFlowPhase.POLLING
        ) {
            return
        }
        _uiState.update {
            it.copy(
                flowPhase = OAuthFlowPhase.STARTING,
                flowProvider = provider,
                flowProviderId = provider.id,
                flowStart = null,
                flowSessionId = "",
                flowCodeInput = "",
                flowStatus = "pending",
                flowErrorMessage = null,
            )
        }
        viewModelScope.launch {
            val result = safeApiCall { ApiClient.hermesApi.startOAuthLogin(provider.id) }
            when (result) {
                is NetworkResult.Success -> {
                    val start =
                        result.data ?: run {
                            _uiState.update {
                                it.copy(
                                    flowPhase = OAuthFlowPhase.IDLE,
                                    flowErrorMessage = "Empty start response",
                                )
                            }
                            return@launch
                        }
                    _uiState.update {
                        it.copy(
                            flowPhase =
                                when (start.flow) {
                                    "pkce" -> OAuthFlowPhase.WAITING_CODE
                                    "device_code" -> OAuthFlowPhase.POLLING
                                    else -> OAuthFlowPhase.IDLE
                                },
                            flowStart = start,
                            flowSessionId = start.sessionId,
                            flowExpiresIn = formatExpiry(start.expiresIn),
                        )
                    }
                    if (start.flow == "device_code") {
                        pollSession(provider.id, start.sessionId)
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            flowPhase = OAuthFlowPhase.IDLE,
                            flowErrorMessage = result.error.message,
                        )
                    }
                }
            }
        }
    }

    fun onCodeInputChange(code: String) {
        _uiState.update { it.copy(flowCodeInput = code) }
    }

    fun submitOAuthCode() {
        val state = _uiState.value
        val code = state.flowCodeInput.trim()
        if (code.isEmpty()) {
            _uiState.update { it.copy(flowErrorMessage = "Paste the authorization code first.") }
            return
        }
        _uiState.update { it.copy(flowStatus = "pending", flowErrorMessage = null) }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.submitOAuthCode(
                        state.flowProviderId,
                        OAuthSubmitRequest(sessionId = state.flowSessionId, code = code),
                    )
                }
            when (result) {
                is NetworkResult.Success -> {
                    val submit = result.data
                    if (submit?.ok == true) {
                        finishFlow("approved")
                    } else {
                        _uiState.update {
                            it.copy(
                                flowStatus = submit?.status ?: "error",
                                flowErrorMessage = submit?.message ?: "Submission rejected",
                            )
                        }
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            flowStatus = "error",
                            flowErrorMessage = result.error.message,
                        )
                    }
                }
            }
        }
    }

    private fun pollSession(
        providerId: String,
        sessionId: String,
    ) {
        pollJob?.cancel()
        pollJob =
            viewModelScope.launch {
                while (_uiState.value.flowPhase == OAuthFlowPhase.POLLING) {
                    delay((_uiState.value.flowStart?.pollInterval ?: 5).toLong() * 1000)
                    val result =
                        safeApiCall {
                            ApiClient.hermesApi.pollOAuthSession(providerId, sessionId)
                        }
                    when (result) {
                        is NetworkResult.Success -> {
                            val poll: OAuthPollResponse? = result.data
                            val status = poll?.status ?: "pending"
                            _uiState.update { it.copy(flowStatus = status) }
                            when (status) {
                                "approved" -> {
                                    finishFlow("approved")
                                    return@launch
                                }
                                "denied", "expired", "error" -> {
                                    _uiState.update {
                                        it.copy(
                                            flowPhase = OAuthFlowPhase.IDLE,
                                            flowErrorMessage =
                                                poll?.errorMessage
                                                    ?: "OAuth flow ended ($status)",
                                        )
                                    }
                                    return@launch
                                }
                                else -> { /* still pending — keep polling */ }
                            }
                        }

                        is NetworkResult.Failure -> {
                            // Tokenless endpoint; a transient failure shouldn't kill the flow.
                            // Continue polling unless it's a hard 4xx.
                            val code = (result.error as? NetworkError.Http)?.code ?: -1
                            if (code in 400..499) {
                                _uiState.update {
                                    it.copy(
                                        flowPhase = OAuthFlowPhase.IDLE,
                                        flowErrorMessage = result.error.message,
                                    )
                                }
                                return@launch
                            }
                        }
                    }
                }
            }
    }

    private fun finishFlow(status: String) {
        pollJob?.cancel()
        _uiState.update {
            it.copy(
                flowPhase = OAuthFlowPhase.DONE,
                flowStatus = status,
                toastMessage = "OAuth connected",
            )
        }
        load()
        // Reset to IDLE after the dialog dismisses.
        _uiState.update { it.copy(flowPhase = OAuthFlowPhase.IDLE) }
    }

    fun cancelOAuthFlow() {
        val sessionId = _uiState.value.flowSessionId
        pollJob?.cancel()
        _uiState.update {
            it.copy(
                flowPhase = OAuthFlowPhase.IDLE,
                flowStart = null,
                flowSessionId = "",
                flowCodeInput = "",
                flowStatus = "pending",
                flowErrorMessage = null,
            )
        }
        if (sessionId.isNotEmpty()) {
            viewModelScope.launch {
                safeApiCall { ApiClient.hermesApi.cancelOAuthSession(sessionId) }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun formatExpiry(seconds: Int?): String {
        if (seconds == null) return ""
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "$m min ${s}s" else "${s}s"
    }
}
