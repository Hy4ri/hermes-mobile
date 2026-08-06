package com.m57.hermescontrol.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.MemoryProviderConfigResponse
import com.m57.hermescontrol.data.model.MemoryProviderConfigUpdateRequest
import com.m57.hermescontrol.data.model.MemoryProviderSetupRequest
import com.m57.hermescontrol.data.model.MemoryProviderSetupResponse
import com.m57.hermescontrol.data.model.MemoryProviderStatusRow
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemoryProviderDetailUiState(
    val isLoading: Boolean = false,
    val providerName: String = "",
    val config: MemoryProviderConfigResponse? = null,
    val status: MemoryProviderStatusRow? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    /** In-progress edits, keyed by field key. Secrets only hold typed text. */
    val edits: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    val runningSetup: Boolean = false,
    val setupResult: MemoryProviderSetupResponse? = null,
)

/**
 * Per-memory-provider management — issue #783. Contracts verified against
 * hermes-agent web_server.py (`/api/memory/providers/{name}/config` GET/PUT,
 * `POST .../setup`); mirrors the desktop provider-config-panel (schema fields
 * with existing values, secrets write-only, one-shot setup with inline
 * results). Deliberately avoids an explicit IO hop: Retrofit suspend calls
 * already run off the caller thread, keeping tests free of the static
 * Dispatchers mock that bleeds across test classes.
 *
 * NOTE: this ViewModel is shared across all memory-provider detail nav
 * entries — the app's NavDisplay uses the activity-level ViewModelStoreOwner,
 * so class-keyed `viewModel {}` returns the same instance for every entry.
 * The provider name is therefore NOT a constructor arg; the screen drives
 * [setProvider] + [load] per entry instead (same bug/fix as #782's
 * ToolsetDetailViewModel).
 */
class MemoryProviderDetailViewModel : ViewModel(), ToastHost {
    private val _uiState = MutableStateFlow(MemoryProviderDetailUiState())
    val uiState: StateFlow<MemoryProviderDetailUiState> = _uiState.asStateFlow()

    private var providerName: String = ""

    /**
     * Point the shared instance at [name] and drop all state from the
     * previously viewed provider (config, edits, setup result) so one
     * provider's data never bleeds into another.
     */
    fun setProvider(name: String) {
        if (name == providerName) return
        providerName = name
        _uiState.value = MemoryProviderDetailUiState(providerName = name)
    }

    fun load() {
        val name = providerName
        if (name.isEmpty()) return
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            // GET /api/memory for the provider's live status row.
            val statusResult = safeApiCall { ApiClient.hermesApi.getMemory() }
            val status =
                when (statusResult) {
                    is NetworkResult.Success ->
                        statusResult.data.providers.firstOrNull { it.name == name }
                    else -> null
                }
            val configResult = safeApiCall { ApiClient.hermesApi.getMemoryProviderConfig(name) }
            when (configResult) {
                is NetworkResult.Success ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            providerName = name,
                            config = configResult.data,
                            status = status ?: it.status,
                            edits = seedEdits(configResult.data),
                        )
                    }
                is NetworkResult.Failure ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            providerName = name,
                            status = status ?: it.status,
                            errorMessage = configResult.error.message,
                        )
                    }
            }
        }
    }

    /** Field text values; secrets start blank (write-only, never echoed). */
    private fun seedEdits(config: MemoryProviderConfigResponse): Map<String, String> =
        config.fields
            .filter { it.kind != "secret" }
            .associate { it.key to it.value }

    fun setFieldValue(
        key: String,
        value: String,
    ) {
        _uiState.update { it.copy(edits = it.edits + (key to value)) }
    }

    /**
     * Save the edited fields via PUT config. A blank secret means "leave the
     * stored value untouched"; the backend also activates the provider when
     * the write succeeds, so the status row is refreshed afterwards.
     */
    fun saveConfig() {
        val name = providerName
        if (name.isEmpty() || _uiState.value.saving) return
        val state = _uiState.value
        val config = state.config ?: return
        _uiState.update { it.copy(saving = true, errorMessage = null) }
        viewModelScope.launch {
            val values =
                config.fields.associate { field ->
                    when {
                        field.kind == "secret" -> field.key to (state.edits[field.key] ?: "")
                        else -> field.key to (state.edits[field.key] ?: field.value)
                    }
                }
            val result =
                safeApiCall {
                    ApiClient.hermesApi.updateMemoryProviderConfig(
                        name,
                        MemoryProviderConfigUpdateRequest(values),
                    )
                }
            when (result) {
                is NetworkResult.Success ->
                    _uiState.update {
                        it.copy(
                            saving = false,
                            edits =
                                it.edits.filterKeys { key ->
                                    config.fields.none { f -> f.key == key && f.kind == "secret" }
                                },
                            toastMessage = "Memory provider settings saved",
                        )
                    }
                is NetworkResult.Failure ->
                    _uiState.update {
                        it.copy(
                            saving = false,
                            toastMessage = "Failed to save settings: ${result.error.message}",
                        )
                    }
            }
            refreshStatus(name)
        }
    }

    /**
     * Run the provider's setup (installs pip/external dependencies). The
     * backend executes it synchronously and returns per-step results inline.
     */
    fun runSetup() {
        val name = providerName
        if (name.isEmpty() || _uiState.value.runningSetup) return
        _uiState.update { it.copy(runningSetup = true, errorMessage = null) }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.setupMemoryProvider(name, MemoryProviderSetupRequest())
                }
            when (result) {
                is NetworkResult.Success ->
                    _uiState.update {
                        it.copy(
                            runningSetup = false,
                            setupResult = result.data,
                            toastMessage =
                                if (result.data.ok) {
                                    "Setup completed"
                                } else {
                                    "Setup finished with failures"
                                },
                        )
                    }
                is NetworkResult.Failure ->
                    _uiState.update {
                        it.copy(
                            runningSetup = false,
                            toastMessage = "Setup failed: ${result.error.message}",
                        )
                    }
            }
            refreshStatus(name)
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun refreshStatus(name: String) {
        viewModelScope.launch {
            val result = safeApiCall { ApiClient.hermesApi.getMemory() }
            if (result is NetworkResult.Success) {
                val row = result.data.providers.firstOrNull { it.name == name }
                if (row != null) {
                    _uiState.update { it.copy(status = row) }
                }
            }
        }
    }
}

/** Status pill mapping — matches the backend's provider status values. */
fun memoryProviderStatusLabel(status: String): String =
    when (status) {
        "ready" -> "Ready"
        "needs_config" -> "Needs config"
        "unavailable" -> "Unavailable"
        "missing" -> "Missing"
        else -> status.replace('_', ' ')
    }
