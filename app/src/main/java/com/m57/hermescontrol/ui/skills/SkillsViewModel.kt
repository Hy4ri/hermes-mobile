package com.m57.hermescontrol.ui.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.ToggleSkillRequest
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
)

class SkillsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SkillsUiState())
    val uiState: StateFlow<SkillsUiState> = _uiState.asStateFlow()

    fun loadSkills() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getSkills() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, skills = result.data.orEmpty()) }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load skills: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun toggleSkill(skill: Skill) {
        val originalEnabled = skill.enabled
        val targetEnabled = !originalEnabled

        // Optimistically update
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
                revertSkillToggle(skill.name, originalEnabled, "Failed to toggle skill: ${result.error.message}")
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
                            com.m57.hermescontrol.data.model
                                .SaveSkillContentRequest(
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

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
