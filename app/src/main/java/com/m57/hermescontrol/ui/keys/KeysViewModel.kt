package com.m57.hermescontrol.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.EnvVarConfig
import com.m57.hermescontrol.data.model.EnvVarRevealRequest
import com.m57.hermescontrol.data.model.EnvVarUpdate
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class KeysUiState(
    val isLoading: Boolean = false,
    val envVars: Map<String, EnvVarConfig> = emptyMap(),
    val revealedValues: Map<String, String> = emptyMap(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class KeysViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(KeysUiState())
    val uiState: StateFlow<KeysUiState> = _uiState.asStateFlow()

    fun loadKeys(reveal: Boolean = false) {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getEnvVars() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        envVars = data.orEmpty(),
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load keys: $errorMsg",
                    )
                }
            },
        )
    }

    fun revealKey(key: String) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.revealEnvVar(EnvVarRevealRequest(key)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val body = result.data
                    _uiState.update { state ->
                        state.copy(
                            revealedValues = state.revealedValues + (body.key to body.value),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to reveal key: ${result.error.message}") }
                }
            }
        }
    }

    fun hideKey(key: String) {
        _uiState.update { state ->
            state.copy(
                revealedValues = state.revealedValues.toMutableMap().apply { remove(key) },
            )
        }
    }

    fun updateKey(
        key: String,
        value: String,
    ) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updateEnvVar(EnvVarUpdate(key, value)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Key updated successfully") }
                    loadKeys()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to update key: ${result.error.message}") }
                }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
