package com.m57.hermescontrol.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.MessagingPlatform
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChannelsUiState(
    val isLoading: Boolean = false,
    val platforms: List<MessagingPlatform> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val restartNeeded: Boolean = false,
    val isRestarting: Boolean = false,
    val togglingId: String? = null,
    val testingId: String? = null,
)

class ChannelsViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ChannelsUiState())
    val uiState: StateFlow<ChannelsUiState> = _uiState.asStateFlow()

    fun loadPlatforms() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getMessagingPlatforms() } },
            onStart = {
                _uiState.update {
                    it.copy(isLoading = true, errorMessage = null)
                }
            },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(isLoading = false, platforms = data?.platforms.orEmpty())
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load platforms: $errorMsg",
                    )
                }
            },
        )
    }

    /** Toggle a platform's enabled state without a full reload.
     *  On success the local state is updated optimistically and
     *  restartNeeded is set. */
    fun togglePlatform(
        platformId: String,
        enabled: Boolean,
    ) {
        _uiState.update { it.copy(togglingId = platformId) }
        safeLaunchLoad(
            apiCall = {
                safeApiCall {
                    ApiClient.hermesApi.configurePlatform(
                        platformId,
                        MessagingPlatformUpdate(enabled = enabled),
                    )
                }
            },
            onStart = {},
            onSuccess = {
                _uiState.update { state ->
                    state.copy(
                        platforms =
                            state.platforms.map { p ->
                                if (p.id == platformId) {
                                    p.copy(
                                        enabled = enabled,
                                        state = if (enabled) "pending_restart" else "disabled",
                                    )
                                } else {
                                    p
                                }
                            },
                        restartNeeded = true,
                        togglingId = null,
                    )
                }
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        togglingId = null,
                        toastMessage = "Toggle failed: $error",
                    )
                }
            },
        )
    }

    /** Configure a platform's env vars and/or settings.  Reloads on success. */
    fun configurePlatform(
        platformId: String,
        update: MessagingPlatformUpdate,
    ) {
        safeLaunchLoad(
            apiCall = {
                safeApiCall {
                    ApiClient.hermesApi.configurePlatform(platformId, update)
                }
            },
            onStart = {
                _uiState.update {
                    it.copy(isLoading = true, errorMessage = null)
                }
            },
            onSuccess = {
                val message = "$platformId configured successfully — restart the gateway for changes to take effect"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        restartNeeded = true,
                        toastMessage = message,
                    )
                }
                loadPlatforms()
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = "Failed to configure: $errorMsg",
                    )
                }
            },
        )
    }

    /** Convenience: configure a platform's env vars via a Map<String,String>. */
    fun configurePlatform(
        platformId: String,
        config: Map<String, String>,
    ) {
        configurePlatform(platformId, MessagingPlatformUpdate(env = config))
    }

    /** Test connectivity for a platform. */
    fun testPlatform(platformId: String) {
        _uiState.update { it.copy(testingId = platformId) }
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.testMessagingPlatform(platformId) } },
            onStart = {},
            onSuccess = { result ->
                val msg = result?.message ?: "Test complete"
                _uiState.update {
                    it.copy(testingId = null, toastMessage = msg)
                }
            },
            onError = { error ->
                _uiState.update {
                    it.copy(testingId = null, toastMessage = "Test failed: $error")
                }
            },
        )
    }

    /** Restart the whole gateway.  Reloads platforms after a 4s delay. */
    fun restartGateway() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.restartGateway() } },
            onStart = {
                _uiState.update {
                    it.copy(isRestarting = true, errorMessage = null)
                }
            },
            onSuccess = {
                _uiState.update {
                    it.copy(isRestarting = false, restartNeeded = false)
                }
                viewModelScope.launch {
                    delay(4000)
                    loadPlatforms()
                }
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        isRestarting = false,
                        toastMessage = "Restart failed: $error",
                    )
                }
            },
        )
    }

    fun dismissRestartNeeded() {
        _uiState.update { it.copy(restartNeeded = false) }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
