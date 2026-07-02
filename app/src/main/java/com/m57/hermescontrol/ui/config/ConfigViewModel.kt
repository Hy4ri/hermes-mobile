package com.m57.hermescontrol.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.data.model.ConfigUpdateRequest
import com.m57.hermescontrol.data.model.UpdateRawConfigRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.OkHttpProvider
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

data class ConfigUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val config: Map<String, JsonElement>? = null,
    val schema: ConfigSchemaResponse? = null,
    val defaults: Map<String, JsonElement>? = null,
    val path: String? = null,
    val yamlText: String? = null,
    val modifiedKeys: Set<String> = emptySet(),
    val activeCategory: String = "",
    val searchQuery: String = "",
    val yamlMode: Boolean = false,
    val yamlIsLoading: Boolean = false,
    val yamlIsSaving: Boolean = false,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class ConfigViewModel : ViewModel(), ToastHost {
    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    private val gson: Gson = OkHttpProvider.gson
    private val pendingChanges = mutableMapOf<String, JsonElement>()

    init {
        loadAll()
    }

    fun loadAll() {
        safeLaunchLoad(
            apiCall = {
                safeApiCall { ApiClient.hermesApi.getConfig() }
            },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { configData ->
                viewModelScope.launch {
                    val schemaResult =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getConfigSchema() }
                        }
                    val defaultsResult =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getConfigDefaults() }
                        }

                    val schema = (schemaResult as? NetworkResult.Success)?.data
                    val defaults = (defaultsResult as? NetworkResult.Success)?.data
                    val rawResult =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getRawConfig() }
                        }
                    val path = (rawResult as? NetworkResult.Success)?.data?.path

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            config = configData,
                            schema = schema,
                            defaults = defaults,
                            path = path,
                            activeCategory =
                                if (schema?.category_order?.isNotEmpty() == true) {
                                    schema.category_order.first()
                                } else {
                                    ""
                                },
                        )
                    }
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Failed to load config: $errorMsg")
                }
            },
        )
    }

    fun updateField(
        key: String,
        value: JsonElement,
    ) {
        pendingChanges[key] = value
        val state = _uiState.value

        // Apply the change locally so the UI reflects it immediately
        val updatedConfig =
            state.config?.let { config ->
                applyNestedValue(config, key, value)
            }

        _uiState.update {
            it.copy(
                config = updatedConfig,
                modifiedKeys = pendingChanges.keys.toSet(),
            )
        }
    }

    fun setActiveCategory(category: String) {
        _uiState.update { it.copy(activeCategory = category) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleYamlMode() {
        val current = _uiState.value
        if (current.yamlMode) {
            _uiState.update { it.copy(yamlMode = false, yamlText = null) }
        } else {
            _uiState.update { it.copy(yamlMode = true, yamlIsLoading = true) }
            viewModelScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        safeApiCall { ApiClient.hermesApi.getRawConfig() }
                    }
                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update {
                            it.copy(
                                yamlIsLoading = false,
                                yamlText = result.data.yaml ?: "",
                            )
                        }
                    }
                    is NetworkResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                yamlIsLoading = false,
                                toastMessage = "Failed to load YAML: ${result.error.message}",
                            )
                        }
                    }
                }
            }
        }
    }

    fun setYamlText(text: String) {
        _uiState.update { it.copy(yamlText = text) }
    }

    fun saveConfig() {
        if (pendingChanges.isEmpty()) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val changeset = buildChangeset(pendingChanges)
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.updateConfig(
                            ConfigUpdateRequest(config = changeset),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    pendingChanges.clear()
                    val configResult =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getConfig() }
                        }
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            config = (configResult as? NetworkResult.Success)?.data ?: it.config,
                            modifiedKeys = emptySet(),
                            toastMessage = "Configuration saved successfully",
                        )
                    }
                }
                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            toastMessage = "Failed to save: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun saveYamlConfig() {
        val yamlText = _uiState.value.yamlText ?: return
        _uiState.update { it.copy(yamlIsSaving = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.updateRawConfig(
                            UpdateRawConfigRequest(yaml_text = yamlText),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val configResult =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getConfig() }
                        }
                    _uiState.update {
                        it.copy(
                            yamlIsSaving = false,
                            config = (configResult as? NetworkResult.Success)?.data ?: it.config,
                            toastMessage = "YAML configuration saved",
                        )
                    }
                }
                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            yamlIsSaving = false,
                            toastMessage = "Failed to save YAML: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun resetCategoryToDefaults(category: String) {
        val state = _uiState.value
        val schema = state.schema ?: return
        val defaults = state.defaults ?: return

        val categoryFields =
            schema.fields.filter { (_, field) ->
                field.category == category
            }

        var count = 0
        for ((key, _) in categoryFields) {
            val defaultVal = defaults[key]
            if (defaultVal != null) {
                pendingChanges[key] = defaultVal
                count++
            }
        }
        _uiState.update {
            it.copy(modifiedKeys = pendingChanges.keys.toSet())
        }

        if (count > 0) {
            _uiState.update {
                it.copy(toastMessage = "Reset $count field(s) to defaults (tap Save to apply)")
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    /** Apply a value at a dot-path like "terminal.backend" into a flat config map. */
    private fun applyNestedValue(
        config: Map<String, JsonElement>,
        dotPath: String,
        value: JsonElement,
    ): Map<String, JsonElement> {
        val parts = dotPath.split(".")
        if (parts.size == 1) {
            val mutable = config.toMutableMap()
            mutable[parts[0]] = value
            return mutable
        }
        val topKey = parts.first()
        val rest = parts.drop(1).joinToString(".")
        val topValue = config[topKey]
        val nestedJson = topValue?.asJsonObject ?: JsonObject()
        val updatedNested =
            applyNestedValue(
                nestedJson.entrySet().associate { it.key to it.value },
                rest,
                value,
            )
        val nestedObj = JsonObject()
        updatedNested.forEach { (k, v) -> nestedObj.add(k, v) }
        val mutable = config.toMutableMap()
        mutable[topKey] = nestedObj
        return mutable
    }

    /** Build a nested JSON changeset from dot-path pending changes. */
    private fun buildChangeset(changes: Map<String, JsonElement>): Map<String, JsonElement> {
        val root = JsonObject()
        for ((dotPath, value) in changes) {
            val parts = dotPath.split(".")
            var current = root
            for (i in 0 until parts.size - 1) {
                val key = parts[i]
                val existing = current.get(key)
                if (existing == null || !existing.isJsonObject) {
                    val newObj = JsonObject()
                    current.add(key, newObj)
                    current = newObj
                } else {
                    current = existing.asJsonObject
                }
            }
            current.add(parts.last(), value)
        }
        return root.entrySet().associate { it.key to it.value as JsonElement }
    }
}
