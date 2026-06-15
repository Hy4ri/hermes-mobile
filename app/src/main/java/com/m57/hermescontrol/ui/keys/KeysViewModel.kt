package com.m57.hermescontrol.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class KeysUiState(
    val isLoading: Boolean = false,
    val envVars: Map<String, String> = emptyMap(),
    val isRevealed: Boolean = false,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class KeysViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(KeysUiState())
    val uiState: StateFlow<KeysUiState> = _uiState.asStateFlow()

    fun loadKeys(reveal: Boolean = false) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        if (reveal) {
                            ApiClient.hermesApi.revealEnvVars()
                        } else {
                            ApiClient.hermesApi.getEnvVars()
                        }
                    }
                if (response.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            envVars = response.body().orEmpty(),
                            isRevealed = reveal,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load keys: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to load keys: ${e.message}") }
            }
        }
    }

    fun updateKey(
        key: String,
        value: String,
    ) {
        viewModelScope.launch {
            try {
                val updatedVars =
                    _uiState.value.envVars.toMutableMap().apply {
                        put(key, value)
                    }
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.updateEnvVar(updatedVars)
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(toastMessage = "Key updated successfully") }
                    loadKeys(_uiState.value.isRevealed)
                } else {
                    _uiState.update { it.copy(toastMessage = "Failed to update key: HTTP ${response.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Failed to update key: ${e.message}") }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
