package com.m57.hermescontrol.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.EnvVarConfig
import com.m57.hermescontrol.data.model.EnvVarDeleteRequest
import com.m57.hermescontrol.data.model.EnvVarRevealRequest
import com.m57.hermescontrol.data.model.EnvVarUpdate
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

data class CategorySection(
    val name: String,
    val vars: Map<String, EnvVarConfig>,
    val expanded: Boolean,
)

data class KeysUiState(
    val isLoading: Boolean = false,
    val categories: List<CategorySection> = emptyList(),
    val revealedValues: Map<String, String> = emptyMap(),
    val newKeyName: String = "",
    val newKeyValue: String = "",
    val isAddingKey: Boolean = false,
    val deletingKeys: Set<String> = emptySet(),
    val keysChanged: Boolean = false,
    val isRestartingGateway: Boolean = false,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

private val CATEGORY_ORDER =
    listOf(
        "LLM Providers",
        "Tool API Keys",
        "Messaging Platforms",
        "Agent Settings",
    )

class KeysViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(KeysUiState())
    val uiState: StateFlow<KeysUiState> = _uiState.asStateFlow()

    private fun buildCategoryList(
        envVars: Map<String, EnvVarConfig>,
        expandedCategories: Set<String> = _uiState.value.categories.map { it.name }.toSet(),
    ): List<CategorySection> {
        // Group by category, uncategorized go to "Other"
        val grouped = mutableMapOf<String, MutableMap<String, EnvVarConfig>>()
        envVars.forEach { (key, config) ->
            val category = config.category?.ifBlank { null } ?: "Other"
            grouped.getOrPut(category) { mutableMapOf() }[key] = config
        }

        // Sort categories: known order first, then alphabetically
        val knownCategories = CATEGORY_ORDER.filter { it in grouped }
        val otherCategories = (grouped.keys - CATEGORY_ORDER.toSet()).sorted()

        return (knownCategories + otherCategories).map { name ->
            val currentExpanded =
                if (expandedCategories.isEmpty()) {
                    // First load — expand all by default
                    true
                } else {
                    name in expandedCategories
                }
            CategorySection(
                name = name,
                vars = grouped[name] ?: emptyMap(),
                expanded = currentExpanded,
            )
        }
    }

    fun loadKeys() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getEnvVars() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                val envVars = data.orEmpty()
                val currentCategories = _uiState.value.categories
                val expandedCategories = currentCategories.map { it.name to it.expanded }.toMap()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        categories = buildCategoryList(envVars, expandedCategories.keys),
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load keys: $errorMsg",
                    )
                }
            },
        )
    }

    fun toggleCategory(category: String) {
        _uiState.update { state ->
            state.copy(
                categories =
                    state.categories.map { section ->
                        if (section.name == category) {
                            section.copy(expanded = !section.expanded)
                        } else {
                            section
                        }
                    },
            )
        }
    }

    fun setNewKeyName(name: String) {
        _uiState.update { it.copy(newKeyName = name) }
    }

    fun setNewKeyValue(value: String) {
        _uiState.update { it.copy(newKeyValue = value) }
    }

    fun addKey() {
        val key = _uiState.value.newKeyName.trim()
        val value = _uiState.value.newKeyValue.trim()
        if (key.isBlank() || value.isBlank()) {
            _uiState.update { it.copy(toastMessage = "Key and value are required") }
            return
        }

        _uiState.update { it.copy(isAddingKey = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updateEnvVar(EnvVarUpdate(key, value)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isAddingKey = false,
                            newKeyName = "",
                            newKeyValue = "",
                            keysChanged = true,
                            toastMessage = "Key added successfully",
                        )
                    }
                    loadKeys()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isAddingKey = false,
                            toastMessage = "Failed to add key: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun deleteKey(key: String) {
        _uiState.update { it.copy(deletingKeys = it.deletingKeys + key) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.deleteEnvVar(EnvVarDeleteRequest(key)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            deletingKeys = it.deletingKeys - key,
                            keysChanged = true,
                            toastMessage = "Key deleted successfully",
                        )
                    }
                    loadKeys()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            deletingKeys = it.deletingKeys - key,
                            toastMessage = "Failed to delete key: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun revealKey(key: String) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.revealEnvVar(EnvVarRevealRequest(key)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val body = result.data
                    _uiState.update { state ->
                        state.copy(
                            revealedValues = state.revealedValues + (body.key to body.value),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to reveal key: ${result.error.message}") }
                }
            }
        }
    }

    fun hideKey(key: String) {
        _uiState.update { state ->
            state.copy(
                revealedValues = state.revealedValues.toMutableMap().apply { remove(key) },
            )
        }
    }

    fun updateKey(
        key: String,
        value: String,
    ) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updateEnvVar(EnvVarUpdate(key, value)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(keysChanged = true, toastMessage = "Key updated successfully") }
                    loadKeys()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to update key: ${result.error.message}") }
                }
            }
        }
    }

    fun restartGateway() {
        _uiState.update { it.copy(isRestartingGateway = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.restartGateway() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isRestartingGateway = false,
                            keysChanged = false,
                            toastMessage = "Gateway restart initiated",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isRestartingGateway = false,
                            toastMessage = "Failed to restart gateway: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
