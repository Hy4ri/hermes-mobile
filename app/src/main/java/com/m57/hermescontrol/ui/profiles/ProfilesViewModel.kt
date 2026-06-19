package com.m57.hermescontrol.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.SetActiveProfileRequest
import com.m57.hermescontrol.data.model.UpdateProfileModelRequest
import com.m57.hermescontrol.data.model.UpdateProfileSoulRequest
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

data class ProfilesUiState(
    val isLoading: Boolean = false,
    val profiles: List<ProfileInfo> = emptyList(),
    val activeProfileName: String? = null,
    val selectedSoulContent: String? = null,
    val isLoadingSoul: Boolean = false,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class ProfilesViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ProfilesUiState())
    val uiState: StateFlow<ProfilesUiState> = _uiState.asStateFlow()

    fun loadProfiles() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val profilesResult =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getProfiles() }
                }
            val activeResult =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getActiveProfile() }
                }

            if (profilesResult is NetworkResult.Success && activeResult is NetworkResult.Success) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        profiles = profilesResult.data.profiles.orEmpty(),
                        activeProfileName = activeResult.data.active,
                    )
                }
            } else {
                val profilesError = (profilesResult as? NetworkResult.Failure)?.error?.message ?: "Success"
                val activeError = (activeResult as? NetworkResult.Failure)?.error?.message ?: "Success"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load profiles/active: Profiles: $profilesError, Active: $activeError",
                    )
                }
            }
        }
    }

    fun selectActiveProfile(name: String) {
        val originalActive = _uiState.value.activeProfileName
        // Optimistically update active profile name
        _uiState.update { it.copy(activeProfileName = name) }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.setActiveProfile(SetActiveProfileRequest(name)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Switched to profile $name") }
                    loadProfiles()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            activeProfileName = originalActive,
                            toastMessage = "Failed to switch profile: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun loadSoul(profileName: String) {
        _uiState.update { it.copy(isLoadingSoul = true, selectedSoulContent = null) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getProfileSoul(profileName) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSoul = false,
                            selectedSoulContent = result.data.content,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSoul = false,
                            toastMessage = "Failed to load soul: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun saveSoul(
        profileName: String,
        content: String,
    ) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.updateProfileSoul(
                            profileName,
                            UpdateProfileSoulRequest(content),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            selectedSoulContent = null,
                            toastMessage = "Soul updated successfully",
                        )
                    }
                    loadProfiles()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Failed to save soul: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun updateModel(
        profileName: String,
        provider: String,
        model: String,
    ) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.updateProfileModel(
                            profileName,
                            UpdateProfileModelRequest(provider, model),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Model settings updated") }
                    loadProfiles()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Failed to update model settings: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun closeSoulDialog() {
        _uiState.update { it.copy(selectedSoulContent = null) }
    }
}
