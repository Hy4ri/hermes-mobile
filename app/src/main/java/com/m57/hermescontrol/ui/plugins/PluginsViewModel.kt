package com.m57.hermescontrol.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.PluginInfo
import com.m57.hermescontrol.data.remote.ApiClient
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

class PluginsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(PluginsUiState())
    val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()

    fun loadPlugins() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getPlugins()
                    }
                if (response.isSuccessful) {
                    val plugins = response.body()?.plugins.orEmpty()
                    _uiState.update { it.copy(isLoading = false, plugins = plugins) }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load plugins: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to load plugins: ${e.message}") }
            }
        }
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
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        if (targetEnabled) {
                            ApiClient.hermesApi.enablePlugin(plugin.name)
                        } else {
                            ApiClient.hermesApi.disablePlugin(plugin.name)
                        }
                    }
                if (!response.isSuccessful) {
                    revertPluginToggle(plugin.name, originalEnabled, "Failed to toggle plugin: HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                revertPluginToggle(plugin.name, originalEnabled, "Failed to toggle plugin: ${e.message}")
            }
        }
    }

    fun installPlugin(name: String) {
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.installPlugin(
                            com.m57.hermescontrol.data.model
                                .AgentPluginInstallBody(name),
                        )
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(toastMessage = "Plugin installed successfully") }
                    loadPlugins()
                } else {
                    _uiState.update { it.copy(toastMessage = "Failed to install plugin: HTTP ${response.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Failed to install plugin: ${e.message}") }
            }
        }
    }

    fun uninstallPlugin(name: String) {
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.uninstallPlugin(name)
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(toastMessage = "Plugin uninstalled successfully") }
                    loadPlugins()
                } else {
                    _uiState.update { it.copy(toastMessage = "Failed to uninstall plugin: HTTP ${response.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Failed to uninstall plugin: ${e.message}") }
            }
        }
    }

    fun updatePlugin(name: String) {
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.updatePlugin(name)
                    }
                if (response.isSuccessful) {
                    _uiState.update { it.copy(toastMessage = "Plugin updated successfully") }
                    loadPlugins()
                } else {
                    _uiState.update { it.copy(toastMessage = "Failed to update plugin: HTTP ${response.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Failed to update plugin: ${e.message}") }
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

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
