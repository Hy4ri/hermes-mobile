package com.m57.hermescontrol.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.UpdateProfileModelRequest
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelUiState(
    val isLoading: Boolean = false,
    val providers: List<ModelProvider> = emptyList(),
    val activeProfile: ProfileInfo? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class ModelViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ModelUiState())
    val uiState: StateFlow<ModelUiState> = _uiState.asStateFlow()

    fun loadModelOptions() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getModelOptions()
                    }
                val activeProfileNameRes =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getActiveProfile()
                    }
                val profilesRes =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getProfiles()
                    }

                if (response.isSuccessful) {
                    val activeName =
                        if (activeProfileNameRes.isSuccessful) activeProfileNameRes.body()?.active else null
                    val activeProfile =
                        if (profilesRes.isSuccessful && activeName != null) {
                            profilesRes.body()?.profiles?.find { it.name == activeName }
                        } else {
                            null
                        }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            providers = response.body()?.providers.orEmpty(),
                            activeProfile = activeProfile,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load model options: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load model options: ${e.message}",
                    )
                }
            }
        }
    }

    fun selectModel(
        providerSlug: String,
        modelName: String,
    ) {
        viewModelScope.launch {
            try {
                val activeProfileNameRes =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getActiveProfile()
                    }
                if (!activeProfileNameRes.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Failed to fetch active profile: HTTP ${activeProfileNameRes.code()}",
                        )
                    }
                    return@launch
                }
                val activeProfileName = activeProfileNameRes.body()?.active
                if (activeProfileName == null) {
                    _uiState.update { it.copy(toastMessage = "No active profile found") }
                    return@launch
                }

                val updateRes =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.updateProfileModel(
                            activeProfileName,
                            UpdateProfileModelRequest(providerSlug, modelName),
                        )
                    }
                if (updateRes.isSuccessful) {
                    _uiState.update { it.copy(toastMessage = "Successfully set model to $modelName") }
                    loadModelOptions()
                } else {
                    _uiState.update { it.copy(toastMessage = "Failed to set model: HTTP ${updateRes.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Failed to set model: ${e.message}") }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
