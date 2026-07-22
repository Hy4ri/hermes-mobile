package com.m57.hermescontrol.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.CloneProfileRequest
import com.m57.hermescontrol.data.model.CreateProfileRequest
import com.m57.hermescontrol.data.model.HubSkill
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.SetActiveProfileRequest
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.UpdateProfileDescriptionRequest
import com.m57.hermescontrol.data.model.UpdateProfileModelRequest
import com.m57.hermescontrol.data.model.UpdateProfileSoulRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    // Profile Builder states
    val modelProviders: List<ModelProvider> = emptyList(),
    val isLoadingBuilderData: Boolean = false,
    val availableSkills: List<Skill> = emptyList(),
    val hubSearchResults: List<HubSkill> = emptyList(),
    val isSearchingHub: Boolean = false,
)

class ProfilesViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ProfilesUiState())
    val uiState: StateFlow<ProfilesUiState> = _uiState.asStateFlow()

    fun loadProfiles() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val profilesDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getProfiles() } }
                    val activeDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getActiveProfile() } }

                    val profilesResult = profilesDeferred.await()
                    val activeResult = activeDeferred.await()

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
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Failed to load profiles: ${e.message}")
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

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun closeSoulDialog() {
        _uiState.update { it.copy(selectedSoulContent = null) }
    }

    fun cloneProfile(
        sourceProfileName: String,
        newProfileName: String,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.cloneProfile(
                            sourceProfileName,
                            CloneProfileRequest(newProfileName),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Profile '$sourceProfileName' cloned successfully to '$newProfileName'",
                        )
                    }
                    loadProfiles()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Failed to clone profile '$sourceProfileName': ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun updateProfileDescription(
        profileName: String,
        description: String,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.updateProfileDescription(
                            profileName,
                            UpdateProfileDescriptionRequest(description),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Profile '$profileName' description updated",
                        )
                    }
                    loadProfiles()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Failed to update description for '$profileName': ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun loadBuilderData() {
        _uiState.update { it.copy(isLoadingBuilderData = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val modelsDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getModelOptions() } }
                    val skillsDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getSkills() } }

                    val modelsResult = modelsDeferred.await()
                    val skillsResult = skillsDeferred.await()

                    if (modelsResult is NetworkResult.Success && skillsResult is NetworkResult.Success) {
                        _uiState.update {
                            it.copy(
                                isLoadingBuilderData = false,
                                modelProviders = modelsResult.data.providers,
                                availableSkills = skillsResult.data,
                            )
                        }
                    } else {
                        val modelsError = (modelsResult as? NetworkResult.Failure)?.error?.message ?: "Success"
                        val skillsError = (skillsResult as? NetworkResult.Failure)?.error?.message ?: "Success"
                        _uiState.update {
                            it.copy(
                                isLoadingBuilderData = false,
                                errorMessage = "Failed to load builder data: Models: $modelsError, Skills: $skillsError",
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingBuilderData = false,
                        errorMessage = "Failed to load builder data: ${e.message}",
                    )
                }
            }
        }
    }

    fun searchHub(query: String) {
        if (query.isBlank()) return
        _uiState.update { it.copy(isSearchingHub = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.searchSkillsHub(query) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSearchingHub = false,
                            hubSearchResults = result.data.results.orEmpty(),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isSearchingHub = false,
                            toastMessage = "Failed to search skills hub: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun createProfile(
        request: CreateProfileRequest,
        onSuccess: () -> Unit,
    ) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.createProfile(request) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Profile ${request.name} created successfully",
                        )
                    }
                    loadProfiles()
                    onSuccess()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Failed to create profile: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }
}
