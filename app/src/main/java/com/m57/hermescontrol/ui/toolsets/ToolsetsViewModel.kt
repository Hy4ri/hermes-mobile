package com.m57.hermescontrol.ui.toolsets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.Toolset
import com.m57.hermescontrol.data.model.ToolsetToggleRequest
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
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getToolsets() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, toolsets = result.data.orEmpty()) }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load toolsets: ${result.error.message}",
                        )
                    }
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
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.toggleToolset(toolset.name, ToolsetToggleRequest(targetEnabled)) }
                }
            if (result is NetworkResult.Failure) {
                revertToggle(toolset.name, originalEnabled, "Failed to toggle toolset: ${result.error.message}")
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
