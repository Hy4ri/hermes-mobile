package com.m57.hermescontrol.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.data.model.ConfigUpdateRequest
import com.m57.hermescontrol.data.model.UpdateRawConfigRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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

class ConfigViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    private val pendingChanges = mutableMapOf<String, JsonElement>()

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                coroutineScope {
                    val configDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getConfig() } }
                    val schemaDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getConfigSchema() } }
                    val defaultsDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getConfigDefaults() } }
                    val rawDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getRawConfig() } }

                    val configResult = configDeferred.await()
                    val schemaResult = schemaDeferred.await()
                    val defaultsResult = defaultsDeferred.await()
                    val rawResult = rawDeferred.await()

                    if (configResult is NetworkResult.Success) {
                        val configData = configResult.data
                        val schema = (schemaResult as? NetworkResult.Success)?.data
                        val defaults = (defaultsResult as? NetworkResult.Success)?.data
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
                    } else {
                        val errorMsg = (configResult as? NetworkResult.Failure)?.error?.message ?: "Unknown error"
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = "Failed to load config: $errorMsg")
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Failed to load config: ${e.message}")
                }
            }
        }
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
                (field.category ?: "general") == category
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
        val nestedJson = (topValue as? JsonObject) ?: JsonObject(emptyMap())
        val updatedNested =
            applyNestedValue(
                nestedJson,
                rest,
                value,
            )
        val nestedObj = JsonObject(updatedNested)
        val mutable = config.toMutableMap()
        mutable[topKey] = nestedObj
        return mutable
    }

    /** Build a nested JSON changeset from dot-path pending changes. */
    private fun buildChangeset(changes: Map<String, JsonElement>): Map<String, JsonElement> {
        val root = mutableMapOf<String, Any>()
        for ((dotPath, value) in changes) {
            val parts = dotPath.split(".")
            var current = root
            for (i in 0 until parts.size - 1) {
                val key = parts[i]
                val existing = current[key]
                if (existing !is MutableMap<*, *>) {
                    val newMap = mutableMapOf<String, Any>()
                    current[key] = newMap
                    current = newMap
                } else {
                    @Suppress("UNCHECKED_CAST")
                    current = existing as MutableMap<String, Any>
                }
            }
            current[parts.last()] = value
        }

        fun toJsonObject(map: Map<String, Any>): JsonObject {
            val content =
                map.mapValues { (_, v) ->
                    if (v is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        toJsonObject(v as Map<String, Any>)
                    } else {
                        v as JsonElement
                    }
                }
            return JsonObject(content)
        }

        return toJsonObject(root)
    }
}
