package com.m57.hermescontrol.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.UpdateProfileModelRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    val pinnedModels: List<PinnedModel> = emptyList(),
)

class ModelViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ModelUiState())
    val uiState: StateFlow<ModelUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(pinnedModels = AuthManager.getPinnedModels()) }
    }

    fun loadModelOptions() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val responseResultDeferred =
                async(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getModelOptions() }
                }
            val activeProfileNameResResultDeferred =
                async(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getActiveProfile() }
                }
            val profilesResResultDeferred =
                async(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getProfiles() }
                }

            val responseResult = responseResultDeferred.await()
            val activeProfileNameResResult = activeProfileNameResResultDeferred.await()
            val profilesResResult = profilesResResultDeferred.await()

            when (responseResult) {
                is NetworkResult.Success -> {
                    val activeName =
                        if (activeProfileNameResResult is NetworkResult.Success) {
                            activeProfileNameResResult.data.active
                        } else {
                            null
                        }
                    val activeProfile =
                        if (profilesResResult is NetworkResult.Success && activeName != null) {
                            profilesResResult.data.profiles.find { it.name == activeName }
                        } else {
                            null
                        }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            providers = responseResult.data.providers.orEmpty(),
                            activeProfile = activeProfile,
                            pinnedModels = AuthManager.getPinnedModels(),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load model options: ${responseResult.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun selectModel(
        providerSlug: String,
        modelName: String,
    ) {
        viewModelScope.launch {
            val activeProfileNameResResult =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getActiveProfile() }
                }
            when (activeProfileNameResResult) {
                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage =
                                "Failed to fetch active profile: ${activeProfileNameResResult.error.message}",
                        )
                    }
                    return@launch
                }

                is NetworkResult.Success -> {
                    val activeProfileName = activeProfileNameResResult.data.active
                    val updateResResult =
                        withContext(Dispatchers.IO) {
                            safeApiCall {
                                ApiClient.hermesApi.updateProfileModel(
                                    activeProfileName,
                                    UpdateProfileModelRequest(providerSlug, modelName),
                                )
                            }
                        }
                    when (updateResResult) {
                        is NetworkResult.Success -> {
                            _uiState.update { it.copy(toastMessage = "Successfully set model to $modelName") }
                            loadModelOptions()
                        }

                        is NetworkResult.Failure -> {
                            _uiState.update {
                                it.copy(
                                    toastMessage = "Failed to set model: ${updateResResult.error.message}",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    companion object {
        private const val MAX_PINNED_MODELS = 15
    }

    fun pinModel(
        providerSlug: String,
        modelName: String,
    ) {
        val currentPinned = _uiState.value.pinnedModels.toMutableList()
        val newPin = PinnedModel(providerSlug, modelName)
        if (currentPinned.contains(newPin)) return
        if (currentPinned.size >= MAX_PINNED_MODELS) {
            _uiState.update {
                it.copy(toastMessage = "Maximum of $MAX_PINNED_MODELS pinned models reached")
            }
            return
        }
        currentPinned.add(newPin)
        AuthManager.savePinnedModels(currentPinned)
        _uiState.update { it.copy(pinnedModels = currentPinned) }
    }

    fun unpinModel(
        providerSlug: String,
        modelName: String,
    ) {
        val currentPinned = _uiState.value.pinnedModels.toMutableList()
        val pinToRemove = PinnedModel(providerSlug, modelName)
        if (currentPinned.remove(pinToRemove)) {
            AuthManager.savePinnedModels(currentPinned)
            _uiState.update { it.copy(pinnedModels = currentPinned) }
        }
    }
}
