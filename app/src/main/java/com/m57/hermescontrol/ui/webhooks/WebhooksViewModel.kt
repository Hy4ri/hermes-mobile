package com.m57.hermescontrol.ui.webhooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.WebhookSubscription
import com.m57.hermescontrol.data.model.WebhooksResponse
import com.m57.hermescontrol.data.model.WebhooksToggleRequest
import com.m57.hermescontrol.data.remote.ApiClient
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
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getWebhooks()
                    }
                if (response.isSuccessful) {
                    val body = response.body()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            enabled = body?.enabled ?: false,
                            baseUrl = body?.base_url,
                            subscriptions = body?.subscriptions.orEmpty(),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load webhooks: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load webhooks: ${e.message}",
                    )
                }
            }
        }
    }

    fun toggleWebhooks(targetEnabled: Boolean) {
        val originalEnabled = _uiState.value.enabled
        _uiState.update { it.copy(enabled = targetEnabled) }

        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.toggleWebhooks(WebhooksToggleRequest(targetEnabled))
                    }
                if (response.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Webhooks global status ${if (targetEnabled) "enabled" else "disabled"}",
                        )
                    }
                    loadWebhooks()
                } else {
                    _uiState.update {
                        it.copy(
                            enabled = originalEnabled,
                            toastMessage = "Failed to toggle webhooks: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        enabled = originalEnabled,
                        toastMessage = "Failed to toggle webhooks: ${e.message}",
                    )
                }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
