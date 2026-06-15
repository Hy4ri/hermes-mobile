package com.m57.hermescontrol.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.SetActiveProfileRequest
import com.m57.hermescontrol.data.model.UpdateProfileDescriptionRequest
import com.m57.hermescontrol.data.model.UpdateProfileModelRequest
import com.m57.hermescontrol.data.model.UpdateProfileSoulRequest
import com.m57.hermescontrol.data.remote.ApiClient
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
            try {
                val profilesRes = withContext(Dispatchers.IO) { ApiClient.hermesApi.getProfiles() }
                val activeRes = withContext(Dispatchers.IO) { ApiClient.hermesApi.getActiveProfile() }

                if (profilesRes.isSuccessful && activeRes.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            profiles = profilesRes.body()?.profiles.orEmpty(),
                            activeProfileName = activeRes.body()?.active,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage =
                                "Failed to load profiles/active: HTTP " +
                                    "${profilesRes.code()} / ${activeRes.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Error loading profiles: ${e.message}",
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
            try {
                val res =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.setActiveProfile(SetActiveProfileRequest(name))
                    }
                if (res.isSuccessful) {
                    _uiState.update { it.copy(toastMessage = "Switched to profile $name") }
                    loadProfiles()
                } else {
                    _uiState.update {
                        it.copy(
                            activeProfileName = originalActive,
                            toastMessage = "Failed to switch profile: HTTP ${res.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        activeProfileName = originalActive,
                        toastMessage = "Failed to switch profile: ${e.message}",
                    )
                }
            }
        }
    }

    fun loadSoul(profileName: String) {
        _uiState.update { it.copy(isLoadingSoul = true, selectedSoulContent = null) }
        viewModelScope.launch {
            try {
                val res =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getProfileSoul(profileName)
                    }
                if (res.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isLoadingSoul = false,
                            selectedSoulContent = res.body()?.content ?: "",
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoadingSoul = false,
                            toastMessage = "Failed to load soul: HTTP ${res.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingSoul = false,
                        toastMessage = "Failed to load soul: ${e.message}",
                    )
                }
            }
        }
    }

    fun saveSoul(
        profileName: String,
        content: String,
    ) {
        viewModelScope.launch {
            try {
                val res =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.updateProfileSoul(profileName, UpdateProfileSoulRequest(content))
                    }
                if (res.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            selectedSoulContent = null,
                            toastMessage = "Soul updated successfully",
                        )
                    }
                    loadProfiles()
                } else {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Failed to save soul: HTTP ${res.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        toastMessage = "Failed to save soul: ${e.message}",
                    )
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
            try {
                val res =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.updateProfileModel(profileName, UpdateProfileModelRequest(provider, model))
                    }
                if (res.isSuccessful) {
                    _uiState.update { it.copy(toastMessage = "Model settings updated") }
                    loadProfiles()
                } else {
                    _uiState.update { it.copy(toastMessage = "Failed to update model settings: HTTP ${res.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Failed to update model settings: ${e.message}") }
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
