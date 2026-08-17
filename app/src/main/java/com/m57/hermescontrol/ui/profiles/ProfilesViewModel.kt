package com.m57.hermescontrol.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.CreateProfileRequest
import com.m57.hermescontrol.data.model.HubSkill
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.model.ProfileDescribeAutoRequest
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.RenameProfileRequest
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.UpdateProfileDescriptionRequest
import com.m57.hermescontrol.data.model.UpdateProfileModelRequest
import com.m57.hermescontrol.data.model.UpdateProfileSoulRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.CoroutineDispatcher
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
    // Rename / delete / auto-describe / setup-command states
    val isAutoDescribing: Boolean = false,
    val setupCommand: String? = null,
    val isLoadingSetupCommand: Boolean = false,
    // Model picker (issue #781 — Set Model uses the shared ModelPickerDialog)
    val modelPickerPinned: List<PinnedModel> = emptyList(),
    // Profile Builder states
    val modelProviders: List<ModelProvider> = emptyList(),
    val isLoadingBuilderData: Boolean = false,
    val availableSkills: List<Skill> = emptyList(),
    val hubSearchResults: List<HubSkill> = emptyList(),
    val isSearchingHub: Boolean = false,
)

class ProfilesViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ProfilesUiState())
    val uiState: StateFlow<ProfilesUiState> = _uiState.asStateFlow()

    fun loadProfiles() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val profilesDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getProfiles() } }
                    val activeDeferred =
                        async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getActiveProfile() } }

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
                                errorMessage =
                                    "Failed to load profiles/active: " +
                                        "Profiles: $profilesError, Active: $activeError",
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
            // The coordinator performs the whole re-home atomically: server
            // flip → local selection persist → switch broadcast → socket
            // re-dial (desktop's requestFreshSession + socket swap).
            when (val result = ProfileSwitchCoordinator.switchProfile(name)) {
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
                withContext(ioDispatcher) {
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
                withContext(ioDispatcher) {
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
                withContext(ioDispatcher) {
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
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.createProfile(
                            CreateProfileRequest(
                                name = newProfileName,
                                clone_from = sourceProfileName,
                            ),
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
                withContext(ioDispatcher) {
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

    fun renameProfile(
        oldName: String,
        newName: String,
    ) {
        if (oldName == newName) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.renameProfile(
                            oldName,
                            RenameProfileRequest(newName),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Profile '$oldName' renamed to '$newName'",
                        )
                    }
                    loadProfiles()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Failed to rename profile '$oldName': ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun deleteProfile(name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.deleteProfile(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Profile '$name' deleted",
                        )
                    }
                    loadProfiles()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = "Failed to delete profile '$name': ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun autoDescribeProfile(name: String) {
        _uiState.update { it.copy(isAutoDescribing = true) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.describeProfileAuto(
                            name,
                            ProfileDescribeAutoRequest(overwrite = true),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val body = result.data
                    if (body.ok) {
                        _uiState.update {
                            it.copy(
                                isAutoDescribing = false,
                                toastMessage = "Auto-described profile '$name'",
                            )
                        }
                        loadProfiles()
                    } else {
                        // Backend returns generation failures as ok:false + reason,
                        // NOT an HTTP error — surface the reason to the user.
                        _uiState.update {
                            it.copy(
                                isAutoDescribing = false,
                                toastMessage =
                                    "Auto-describe failed for '$name': " +
                                        (body.reason ?: "unknown reason"),
                            )
                        }
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isAutoDescribing = false,
                            toastMessage = "Failed to auto-describe profile '$name': ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun fetchSetupCommand(name: String) {
        _uiState.update { it.copy(isLoadingSetupCommand = true, setupCommand = null) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.getProfileSetupCommand(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSetupCommand = false,
                            setupCommand = result.data.command,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSetupCommand = false,
                            toastMessage = "Failed to fetch setup command: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun loadModelOptions() {
        _uiState.update { it.copy(isLoadingBuilderData = true, errorMessage = null) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.getModelOptions() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoadingBuilderData = false,
                            modelProviders = result.data.providers,
                            modelPickerPinned = AuthManager.getPinnedModels(),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    // Toast, not errorMessage — a picker-load failure must NOT
                    // blank the profiles list with the ErrorState branch.
                    _uiState.update {
                        it.copy(
                            isLoadingBuilderData = false,
                            toastMessage = "Failed to load models: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun togglePinModel(
        providerSlug: String,
        modelName: String,
    ) {
        val target = PinnedModel(providerSlug, modelName)
        val current = AuthManager.getPinnedModels().toMutableList()
        if (target in current) {
            current.remove(target)
        } else {
            current.add(target)
        }
        AuthManager.savePinnedModels(current)
        _uiState.update { it.copy(modelPickerPinned = current) }
    }

    fun loadBuilderData() {
        _uiState.update { it.copy(isLoadingBuilderData = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val modelsDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getModelOptions() } }
                    val skillsDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getSkills() } }

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
                                errorMessage =
                                    "Failed to load builder data: " +
                                        "Models: $modelsError, Skills: $skillsError",
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
                withContext(ioDispatcher) {
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
                withContext(ioDispatcher) {
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
