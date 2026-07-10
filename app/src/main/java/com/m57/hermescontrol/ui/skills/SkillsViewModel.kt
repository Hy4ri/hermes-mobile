package com.m57.hermescontrol.ui.skills

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.HubSkill
import com.m57.hermescontrol.data.model.SaveSkillContentRequest
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.SkillHubInstallRequest
import com.m57.hermescontrol.data.model.SkillHubUninstallRequest
import com.m57.hermescontrol.data.model.ToggleSkillRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SkillsViewMode {
    INSTALLED,
    HUB,
}

enum class SkillFilter(
    val labelRes: Int,
) {
    ALL_STATUSES(R.string.skills_status_all),
    ENABLED(R.string.skills_status_enabled),
    DISABLED(R.string.skills_status_disabled),
}

enum class CategoryFilter {
    ALL,
}

data class SkillsUiState(
    val isLoading: Boolean = false,
    val skills: List<Skill> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val isLoadingContent: Boolean = false,
    val editingSkillName: String? = null,
    val skillContent: String? = null,
    val isSavingContent: Boolean = false,
    val saveContentSuccess: Boolean = false,
    // View mode
    val viewMode: SkillsViewMode = SkillsViewMode.INSTALLED,
    // Hub search
    val hubQuery: String = "",
    val hubResults: List<HubSkill> = emptyList(),
    val isHubSearching: Boolean = false,
    val hubSearchError: String? = null,
    // Hub detail preview (full description/content via preview endpoint)
    val hubPreviewIdentifier: String? = null,
    val hubPreviewContent: String? = null,
    val isHubPreviewing: Boolean = false,
    val hubPreviewError: String? = null,
    // Hub install
    val isInstalling: Boolean = false,
    val installingSkillName: String? = null,
    val isUninstalling: Boolean = false,
    val uninstallingSkillName: String? = null,
    // Preview
    val previewSkillName: String? = null,
    val previewSkillContent: String? = null,
    val isLoadingPreview: Boolean = false,
    // Source filter for installed tab
    val sourceFilter: String? = null,
)

class SkillsViewModel(
    application: Application,
) : AndroidViewModel(application),
    ToastHost {
    private val _uiState = MutableStateFlow(SkillsUiState())
    val uiState: StateFlow<SkillsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    fun loadSkills() {
        loadJob =
            safeLaunchLoad(
                currentJob = loadJob,
                apiCall = { safeApiCall { ApiClient.hermesApi.getSkills() } },
                onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
                onSuccess = { data ->
                    _uiState.update { it.copy(isLoading = false, skills = data.orEmpty()) }
                },
                onError = { errorMsg ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load skills: $errorMsg",
                        )
                    }
                },
            )
    }

    fun setViewMode(mode: SkillsViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
        if (mode == SkillsViewMode.INSTALLED && _uiState.value.skills.isEmpty()) {
            loadSkills()
        }
    }

    fun setSourceFilter(source: String?) {
        _uiState.update { it.copy(sourceFilter = source) }
    }

    fun setHubQuery(query: String) {
        _uiState.update { it.copy(hubQuery = query) }
    }

    // ── Hub search ──────────────────────────────────────────────────────────

    fun searchHub(query: String) {
        _uiState.update { it.copy(hubQuery = query, hubSearchError = null) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(hubResults = emptyList(), isHubSearching = false) }
            return
        }
        searchJob =
            safeLaunchLoad(
                currentJob = searchJob,
                apiCall = {
                    safeApiCall { ApiClient.hermesApi.searchSkillsHub(q = query) }
                },
                onStart = { _uiState.update { it.copy(isHubSearching = true) } },
                onSuccess = { data ->
                    _uiState.update { it.copy(isHubSearching = false, hubResults = data?.results.orEmpty()) }
                },
                onError = { errorMsg ->
                    _uiState.update {
                        it.copy(
                            isHubSearching = false,
                            hubSearchError = "Search failed: $errorMsg",
                        )
                    }
                },
            )
    }

    fun clearHubSearch() {
        _uiState.update {
            it.copy(
                hubQuery = "",
                hubResults = emptyList(),
                hubSearchError = null,
            )
        }
    }

    // ── Hub detail preview ──────────────────────────────────────────────────

    /**
     * Fetches the full skill content via the preview endpoint so the detail
     * dialog can show the complete description (search results only carry a
     * truncated snippet).
     */
    fun previewHubSkill(identifier: String) {
        if (identifier.isBlank()) return
        _uiState.update {
            it.copy(
                hubPreviewIdentifier = identifier,
                isHubPreviewing = true,
                hubPreviewError = null,
                hubPreviewContent = null,
            )
        }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.previewHubSkill(identifier = identifier) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isHubPreviewing = false,
                            hubPreviewContent = result.data.content,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isHubPreviewing = false,
                            hubPreviewError = result.error.message,
                        )
                    }
                }
            }
        }
    }

    fun clearHubPreview() {
        _uiState.update {
            it.copy(
                hubPreviewIdentifier = null,
                hubPreviewContent = null,
                isHubPreviewing = false,
                hubPreviewError = null,
            )
        }
    }

    // ── Hub install ─────────────────────────────────────────────────────────

    fun installSkill(identifier: String) {
        _uiState.update {
            it.copy(
                isInstalling = true,
                installingSkillName = identifier,
            )
        }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.installHubSkill(SkillHubInstallRequest(identifier = identifier)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isInstalling = false,
                            installingSkillName = null,
                            toastMessage = "Installed: $identifier",
                        )
                    }
                    // Refresh installed skills
                    loadSkills()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isInstalling = false,
                            installingSkillName = null,
                            toastMessage = "Failed to install $identifier: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun uninstallSkill(name: String) {
        _uiState.update {
            it.copy(
                isUninstalling = true,
                uninstallingSkillName = name,
            )
        }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.uninstallHubSkill(SkillHubUninstallRequest(name = name)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isUninstalling = false,
                            uninstallingSkillName = null,
                            toastMessage = "Uninstalled: $name",
                        )
                    }
                    loadSkills()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isUninstalling = false,
                            uninstallingSkillName = null,
                            toastMessage = "Failed to uninstall $name: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── SKILL.md preview ────────────────────────────────────────────────────

    fun previewSkill(skillName: String) {
        _uiState.update {
            it.copy(
                previewSkillName = skillName,
                previewSkillContent = null,
                isLoadingPreview = true,
            )
        }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getSkillContent(skillName) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoadingPreview = false,
                            previewSkillContent = result.data?.content,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoadingPreview = false,
                            toastMessage = "Failed to load preview: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun clearPreview() {
        _uiState.update {
            it.copy(
                previewSkillName = null,
                previewSkillContent = null,
                isLoadingPreview = false,
            )
        }
    }

    // ── Existing functionality ──────────────────────────────────────────────

    fun toggleSkill(skill: Skill) {
        val originalEnabled = skill.enabled
        val targetEnabled = !originalEnabled

        _uiState.update { state ->
            state.copy(
                skills =
                    state.skills.map {
                        if (it.name == skill.name) it.copy(enabled = targetEnabled) else it
                    },
            )
        }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.toggleSkill(ToggleSkillRequest(skill.name, targetEnabled)) }
                }
            if (result is NetworkResult.Failure) {
                revertSkillToggle(
                    skill.name,
                    originalEnabled,
                    "Failed to toggle skill: ${result.error.message}",
                )
            }
        }
    }

    private fun revertSkillToggle(
        name: String,
        originalEnabled: Boolean,
        errorMsg: String,
    ) {
        _uiState.update { state ->
            state.copy(
                skills =
                    state.skills.map {
                        if (it.name == name) it.copy(enabled = originalEnabled) else it
                    },
                toastMessage = errorMsg,
            )
        }
    }

    fun loadSkillContent(skillName: String) {
        _uiState.update {
            it.copy(
                isLoadingContent = true,
                editingSkillName = skillName,
                skillContent = null,
                saveContentSuccess = false,
            )
        }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getSkillContent(skillName) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoadingContent = false,
                            skillContent = result.data?.content.orEmpty(),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoadingContent = false,
                            editingSkillName = null,
                            toastMessage = "Failed to load skill content: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun saveSkillContent(
        skillName: String,
        content: String,
    ) {
        _uiState.update { it.copy(isSavingContent = true, saveContentSuccess = false) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.saveSkillContent(
                            SaveSkillContentRequest(
                                name = skillName,
                                content = content,
                            ),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSavingContent = false,
                            saveContentSuccess = true,
                            skillContent = content,
                            toastMessage = "Skill saved successfully",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isSavingContent = false,
                            toastMessage = "Failed to save skill content: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun updateSkillsFromHub() {
        viewModelScope.launch {
            _uiState.update { it.copy(toastMessage = "Updating skills from hub…") }
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updateSkillsFromHub() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Skills updated") }
                    loadSkills()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Update failed: ${result.error.message}") }
                }
            }
        }
    }

    fun clearEditor() {
        _uiState.update {
            it.copy(
                editingSkillName = null,
                skillContent = null,
                isLoadingContent = false,
                isSavingContent = false,
                saveContentSuccess = false,
            )
        }
    }

    fun clearSaveSuccess() {
        _uiState.update { it.copy(saveContentSuccess = false) }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
