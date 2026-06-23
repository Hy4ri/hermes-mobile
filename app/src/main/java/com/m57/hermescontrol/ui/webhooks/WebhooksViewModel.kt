package com.m57.hermescontrol.ui.webhooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.WebhookSubscription
import com.m57.hermescontrol.data.model.WebhooksToggleRequest
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

data class WebhooksUiState(
    val isLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    val enabled: Boolean = false,
    val baseUrl: String? = null,
    val subscriptions: List<WebhookSubscription> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class WebhooksViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(WebhooksUiState())
    val uiState: StateFlow<WebhooksUiState> = _uiState.asStateFlow()

    fun loadWebhooks() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getWebhooks() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val body = result.data
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            enabled = body.enabled,
                            baseUrl = body.base_url,
                            subscriptions = body.subscriptions.orEmpty(),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load webhooks: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun toggleWebhooks(targetEnabled: Boolean) {
        val originalEnabled = _uiState.value.enabled
        _uiState.update { it.copy(enabled = targetEnabled) }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.toggleWebhooks(WebhooksToggleRequest(targetEnabled)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Webhooks global status ${if (targetEnabled) "enabled" else "disabled"}",
                        )
                    }
                    loadWebhooks()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            enabled = originalEnabled,
                            toastMessage = "Failed to toggle webhooks: ${result.error.message}",
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
