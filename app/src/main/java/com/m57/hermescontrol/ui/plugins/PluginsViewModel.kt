package com.m57.hermescontrol.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.PluginInfo
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PluginsUiState(
    val isLoading: Boolean = false,
    val plugins: List<PluginInfo> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class PluginsViewModel : ViewModel(), ToastHost {
    private val _uiState = MutableStateFlow(PluginsUiState())
    val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()

    fun loadPlugins() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getPlugins() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                val plugins = data.plugins.orEmpty()
                _uiState.update { it.copy(isLoading = false, plugins = plugins) }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load plugins: $errorMsg",
                    )
                }
            },
        )
    }

    fun togglePlugin(plugin: PluginInfo) {
        val originalEnabled = plugin.enabled
        val targetEnabled = !originalEnabled

        // Optimistically update
        _uiState.update { state ->
            state.copy(
                plugins =
                    state.plugins.map {
                        if (it.name == plugin.name) {
                            it.copy(runtimeStatus = if (targetEnabled) "enabled" else "disabled")
                        } else {
                            it
                        }
                    },
            )
        }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    if (targetEnabled) {
                        safeApiCall { ApiClient.hermesApi.enablePlugin(plugin.name) }
                    } else {
                        safeApiCall { ApiClient.hermesApi.disablePlugin(plugin.name) }
                    }
                }
            if (result is NetworkResult.Failure) {
                revertPluginToggle(plugin.name, originalEnabled, "Failed to toggle plugin: ${result.error.message}")
            }
        }
    }

    fun installPlugin(name: String) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.installPlugin(
                            com.m57.hermescontrol.data.model
                                .AgentPluginInstallBody(name),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Plugin installed successfully") }
                    loadPlugins()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to install plugin: ${result.error.message}") }
                }
            }
        }
    }

    fun uninstallPlugin(name: String) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.uninstallPlugin(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Plugin uninstalled successfully") }
                    loadPlugins()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to uninstall plugin: ${result.error.message}") }
                }
            }
        }
    }

    fun updatePlugin(name: String) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updatePlugin(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Plugin updated successfully") }
                    loadPlugins()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to update plugin: ${result.error.message}") }
                }
            }
        }
    }

    private fun revertPluginToggle(
        name: String,
        originalEnabled: Boolean,
        errorMsg: String,
    ) {
        _uiState.update { state ->
            state.copy(
                plugins =
                    state.plugins.map {
                        if (it.name == name) {
                            it.copy(runtimeStatus = if (originalEnabled) "enabled" else "disabled")
                        } else {
                            it
                        }
                    },
                toastMessage = errorMsg,
            )
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
