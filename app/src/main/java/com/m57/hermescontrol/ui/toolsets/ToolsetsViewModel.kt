package com.m57.hermescontrol.ui.toolsets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.Toolset
import com.m57.hermescontrol.data.model.ToolsetToggleRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ToolsetsUiState(
    val isLoading: Boolean = false,
    val toolsets: List<Toolset> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class ToolsetsViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ToolsetsUiState())
    val uiState: StateFlow<ToolsetsUiState> = _uiState.asStateFlow()

    init {
        // Re-fetch on profile switch (see AuthManager.selectedProfileIdFlow).
        // The screen's LaunchedEffect(Unit) performs the initial load.
        AuthManager.selectedProfileIdFlow
            .drop(1)
            .onEach { loadToolsets() }
            .launchIn(viewModelScope)
    }

    fun loadToolsets() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getToolsets() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update { it.copy(isLoading = false, toolsets = data.orEmpty()) }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load toolsets: $errorMsg",
                    )
                }
            },
        )
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

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
