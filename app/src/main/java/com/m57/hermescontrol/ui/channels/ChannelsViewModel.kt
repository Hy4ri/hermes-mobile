package com.m57.hermescontrol.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.MessagingPlatform
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
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

data class ChannelsUiState(
    val isLoading: Boolean = false,
    val platforms: List<MessagingPlatform> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class ChannelsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChannelsUiState())
    val uiState: StateFlow<ChannelsUiState> = _uiState.asStateFlow()

    fun loadPlatforms() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getMessagingPlatforms() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, platforms = result.data?.platforms.orEmpty()) }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load platforms: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun configurePlatform(
        platformId: String,
        update: MessagingPlatformUpdate,
    ) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.configurePlatform(platformId, update) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, toastMessage = "$platformId configured successfully — restart the gateway for changes to take effect") }
                    loadPlatforms()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to configure platform: ${result.error.message}",
                            toastMessage = "Failed to configure platform: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun configurePlatform(
        platformId: String,
        config: Map<String, String>,
    ) {
        configurePlatform(platformId, MessagingPlatformUpdate(env = config))
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
