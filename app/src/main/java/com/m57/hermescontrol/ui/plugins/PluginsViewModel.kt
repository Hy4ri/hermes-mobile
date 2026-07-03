package com.m57.hermescontrol.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.PluginInfo
import com.m57.hermescontrol.data.model.PluginProvidersPutRequest
import com.m57.hermescontrol.data.model.ProviderOption
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
    val orphanPlugins: List<PluginInfo> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    // Install state
    val installUrl: String = "",
    val installForce: Boolean = false,
    val installEnable: Boolean = true,
    val installBusy: Boolean = false,
    // Provider selection state
    val memoryProvider: String = "",
    val memoryOptions: List<ProviderOption> = emptyList(),
    val contextEngine: String = "compressor",
    val contextOptions: List<ProviderOption> = emptyList(),
    val providerBusy: Boolean = false,
    // Plugin row operation state
    val rowBusy: String? = null,
    // Confirm remove dialog
    val removeConfirmPlugin: String? = null,
    // Rescan
    val rescanBusy: Boolean = false,
) {
    /** Built-in memory provider sentinel — empty string means "use config defaults" */
    val isMemoryBuiltin: Boolean
        get() = memoryProvider.isEmpty()

    companion object {
        const val MEMORY_PROVIDER_BUILTIN = ""
    }
}

class PluginsViewModel : ViewModel(), ToastHost {
    private val _uiState = MutableStateFlow(PluginsUiState())
    val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()

    fun loadPlugins() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getPlugins() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                val plugins = data.plugins.orEmpty()
                val providers = data.providers
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        plugins = plugins,
                        orphanPlugins =
                            data.orphanDashboardPlugins.orEmpty().map {
                                PluginInfo(
                                    name = it.name ?: "unknown",
                                    description = it.description ?: it.label,
                                )
                            },
                        memoryProvider = providers?.memoryProvider ?: "",
                        memoryOptions = providers?.memoryOptions.orEmpty(),
                        contextEngine = providers?.contextEngine ?: "compressor",
                        contextOptions = providers?.contextOptions.orEmpty(),
                    )
                }
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

    fun updateInstallUrl(url: String) {
        _uiState.update { it.copy(installUrl = url) }
    }

    fun updateInstallForce(force: Boolean) {
        _uiState.update { it.copy(installForce = force) }
    }

    fun updateInstallEnable(enable: Boolean) {
        _uiState.update { it.copy(installEnable = enable) }
    }

    fun installPluginFromUrl() {
        val url = _uiState.value.installUrl.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(toastMessage = "Please enter a plugin identifier") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(installBusy = true) }
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.installPlugin(
                            com.m57.hermescontrol.data.model.AgentPluginInstallBody(
                                identifier = url,
                                force = _uiState.value.installForce,
                                enable = _uiState.value.installEnable,
                            ),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            installUrl = "",
                            installBusy = false,
                            toastMessage = "Plugin installed successfully",
                        )
                    }
                    loadPlugins()
                }
                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            installBusy = false,
                            toastMessage = "Failed to install plugin: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun updateMemoryProvider(provider: String) {
        _uiState.update { it.copy(memoryProvider = provider) }
    }

    fun updateContextEngine(engine: String) {
        _uiState.update { it.copy(contextEngine = engine) }
    }

    fun savePluginProviders() {
        viewModelScope.launch {
            _uiState.update { it.copy(providerBusy = true) }
            val state = _uiState.value
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.savePluginProviders(
                            PluginProvidersPutRequest(
                                memoryProvider = if (state.isMemoryBuiltin) "" else state.memoryProvider,
                                contextEngine = state.contextEngine,
                            ),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(providerBusy = false, toastMessage = "Plugin providers saved") }
                    loadPlugins()
                }
                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            providerBusy = false,
                            toastMessage = "Failed to save providers: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun rescanPlugins() {
        viewModelScope.launch {
            _uiState.update { it.copy(rescanBusy = true) }
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.rescanPlugins() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(rescanBusy = false, toastMessage = "Plugins rescanned") }
                    loadPlugins()
                }
                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            rescanBusy = false,
                            toastMessage = "Failed to rescan: ${result.error.message}",
                        )
                    }
                }
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

    fun activatePlugin(plugin: PluginInfo) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.enablePlugin(plugin.name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Plugin enabled successfully") }
                    loadPlugins()
                }
                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to enable plugin: ${result.error.message}") }
                }
            }
        }
    }

    /** Show confirmation dialog for removing a plugin */
    fun requestRemovePlugin(name: String) {
        _uiState.update { it.copy(removeConfirmPlugin = name) }
    }

    /** Cancel the remove confirmation dialog */
    fun cancelRemovePlugin() {
        _uiState.update { it.copy(removeConfirmPlugin = null) }
    }

    fun confirmRemovePlugin() {
        val name = _uiState.value.removeConfirmPlugin ?: return
        _uiState.update { it.copy(removeConfirmPlugin = null) }
        viewModelScope.launch {
            setRowBusy(name)
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.uninstallPlugin(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Plugin uninstalled successfully") }
                    clearRowBusy(name)
                    loadPlugins()
                }
                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to uninstall plugin: ${result.error.message}") }
                    clearRowBusy(name)
                }
            }
        }
    }

    fun updatePlugin(name: String) {
        viewModelScope.launch {
            setRowBusy(name)
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updatePlugin(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Plugin updated successfully") }
                    clearRowBusy(name)
                    loadPlugins()
                }
                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to update plugin: ${result.error.message}") }
                    clearRowBusy(name)
                }
            }
        }
    }

    fun togglePluginVisibility(plugin: PluginInfo) {
        viewModelScope.launch {
            setRowBusy(plugin.name)
            val targetHidden = !plugin.userHidden
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.setPluginVisibility(
                            plugin.name,
                            mapOf("hidden" to targetHidden),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Plugin visibility updated") }
                    clearRowBusy(plugin.name)
                    loadPlugins()
                }
                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to update visibility: ${result.error.message}") }
                    clearRowBusy(plugin.name)
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

    private fun setRowBusy(name: String) {
        _uiState.update { it.copy(rowBusy = name) }
    }

    private fun clearRowBusy(name: String) {
        _uiState.update { if (it.rowBusy == name) it.copy(rowBusy = null) else it }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
