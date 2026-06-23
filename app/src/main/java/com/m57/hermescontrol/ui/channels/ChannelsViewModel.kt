package com.m57.hermescontrol.ui.channels

import androidx.lifecycle.ViewModel
import com.m57.hermescontrol.data.model.MessagingPlatform
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ChannelsUiState(
    val isLoading: Boolean = false,
    val platforms: List<MessagingPlatform> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class ChannelsViewModel : ViewModel(), ToastHost {
    private val _uiState = MutableStateFlow(ChannelsUiState())
    val uiState: StateFlow<ChannelsUiState> = _uiState.asStateFlow()

    fun loadPlatforms() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getMessagingPlatforms() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update { it.copy(isLoading = false, platforms = data?.platforms.orEmpty()) }
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

    fun configurePlatform(
        platformId: String,
        update: MessagingPlatformUpdate,
    ) {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.configurePlatform(platformId, update) } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                val message = "$platformId configured successfully — restart the gateway for changes to take effect"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = message,
                    )
                }
                loadPlatforms()
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to configure platform: $errorMsg",
                        toastMessage = "Failed to configure platform: $errorMsg",
                    )
                }
            },
        )
    }

    fun configurePlatform(
        platformId: String,
        config: Map<String, String>,
    ) {
        configurePlatform(platformId, MessagingPlatformUpdate(env = config))
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
