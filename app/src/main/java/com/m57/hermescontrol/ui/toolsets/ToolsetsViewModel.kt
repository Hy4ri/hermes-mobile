package com.m57.hermescontrol.ui.toolsets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.Toolset
import com.m57.hermescontrol.data.model.ToolsetToggleRequest
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ToolsetsUiState(
    val isLoading: Boolean = false,
    val toolsets: List<Toolset> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class ToolsetsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ToolsetsUiState())
    val uiState: StateFlow<ToolsetsUiState> = _uiState.asStateFlow()

    fun loadToolsets() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getToolsets()
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(isLoading = false, toolsets = response.body().orEmpty()) }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load toolsets: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load toolsets: ${e.message}",
                    )
                }
            }
        }
    }

    fun toggleToolset(toolset: Toolset) {
        val originalEnabled = toolset.enabled
        val targetEnabled = !originalEnabled

        // Optimistic UI update
        _uiState.update { state ->
            state.copy(
                toolsets =
                    state.toolsets.map {
                        if (it.name == toolset.name) it.copy(enabled = targetEnabled) else it
                    },
            )
        }

        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.toggleToolset(toolset.name, ToolsetToggleRequest(targetEnabled))
                    }
                if (!response.isSuccessful) {
                    revertToggle(toolset.name, originalEnabled, "Failed to toggle toolset: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                revertToggle(toolset.name, originalEnabled, "Failed to toggle toolset: ${e.message}")
            }
        }
    }

    private fun revertToggle(
        name: String,
        originalEnabled: Boolean,
        errorMsg: String,
    ) {
        _uiState.update { state ->
            state.copy(
                toolsets =
                    state.toolsets.map {
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
