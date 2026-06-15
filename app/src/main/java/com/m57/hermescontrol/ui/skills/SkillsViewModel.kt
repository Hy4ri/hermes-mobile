package com.m57.hermescontrol.ui.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.Skill
import com.m57.hermescontrol.data.model.ToggleSkillRequest
import com.m57.hermescontrol.data.remote.ApiClient
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
)

class SkillsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SkillsUiState())
    val uiState: StateFlow<SkillsUiState> = _uiState.asStateFlow()

    fun loadSkills() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getSkills()
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(isLoading = false, skills = response.body().orEmpty()) }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load skills: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to load skills: ${e.message}") }
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
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.toggleSkill(ToggleSkillRequest(skill.name, targetEnabled))
                    }
                if (!response.isSuccessful) {
                    revertSkillToggle(skill.name, originalEnabled, "Failed to toggle skill: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                revertSkillToggle(skill.name, originalEnabled, "Failed to toggle skill: ${e.message}")
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

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
