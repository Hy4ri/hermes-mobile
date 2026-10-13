package com.m57.hermescontrol.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.local.SwrCache
import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.data.model.ConfigUpdateRequest
import com.m57.hermescontrol.data.model.UpdateRawConfigRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.remote.safeApiRead
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.JsonPrimitive

data class ConfigUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    /** Flattened dot-path → value map (see [flattenConfig]). */
    val values: Map<String, JsonElement>? = null,
    val schema: ConfigSchemaResponse? = null,
    val defaults: Map<String, JsonElement>? = null,
    /** Config paths present but not covered by the schema (the "Other" tab). */
    val uncoveredPaths: List<String> = emptyList(),
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

class ConfigViewModel(
    // Injectable so tests drive IO on the virtual clock; the default keeps `ConfigViewModel()` callers unchanged.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    private val pendingChanges = mutableMapOf<String, JsonElement>()

    /** U1567: an explicit user category choice is never reset by late schema hydration. */
    private var userSelectedCategory = false
    private val schemaCache = SwrCache<String, ConfigSchemaResponse>(maxCapacity = 10)
    private val defaultsCache = SwrCache<String, Map<String, JsonElement>>(maxCapacity = 10)
    private var loadJob: Job? = null
    private var loadGeneration: Long = 0L

    init {
        // Scope transitions are driven by ProfileSwitchCoordinator (profile switch and
        // connection switch). An additional AuthManager.dataScopeFlow collector would
        // double-fire on the same transition and issue a duplicate loadAll().
        viewModelScope.launch {
            ProfileSwitchCoordinator.switched.collect {
                onDataScopeChanged(AuthManager.currentDataScope())
            }
        }
        viewModelScope.launch {
            ProfileSwitchCoordinator.connectionSwitched.collect {
                onDataScopeChanged(AuthManager.currentDataScope())
            }
        }
        loadAll()
    }

    private fun onDataScopeChanged(newScope: DataScope?) {
        pendingChanges.clear()
        userSelectedCategory = false
        _uiState.update {
            it.copy(
                values = null,
                schema = null,
                defaults = null,
                uncoveredPaths = emptyList(),
                path = null,
                yamlText = null,
                modifiedKeys = emptySet(),
                isSaving = false,
                yamlIsSaving = false,
                yamlIsLoading = false,
                errorMessage = null,
            )
        }
        // A scope switch must load the new context. The scoped caches mean this paints the
        // new scope's own entry instantly when one exists, then revalidates in the background.
        loadAll()
    }

    fun loadAll(forceRefresh: Boolean = false) {
        val requestScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
        val requestProfile = runCatching { AuthManager.activeProfileId.value }.getOrNull()
        val requestService = ApiClient.hermesApi
        val scopeKey = requestScope?.inMemoryKey("schema") ?: ""
        if (forceRefresh && scopeKey.isNotBlank()) {
            schemaCache.remove(scopeKey)
            defaultsCache.remove(scopeKey)
        }
        val cachedSchema = if (scopeKey.isNotBlank()) schemaCache.get(scopeKey) else null
        val cachedDefaults = if (scopeKey.isNotBlank()) defaultsCache.get(scopeKey) else null
        val hasCachedData = _uiState.value.values != null
        if (!hasCachedData) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        }

        loadJob?.cancel()
        val currentGeneration = ++loadGeneration

        fun isCurrent(): Boolean {
            if (currentGeneration != loadGeneration) return false
            val currentScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
            if (requestScope != null && currentScope != requestScope) return false
            val currentProfile = runCatching { AuthManager.activeProfileId.value }.getOrNull()
            if (requestProfile != currentProfile) return false
            if (requestService !== ApiClient.hermesApi) return false
            return true
        }

        loadJob =
            viewModelScope.launch {
                try {
                    coroutineScope {
                        val configDeferred =
                            async(ioDispatcher) {
                                safeApiRead { requestService.getConfig() }
                            }
                        val schemaDeferred =
                            if (cachedSchema == null) {
                                async(ioDispatcher) {
                                    safeApiRead { requestService.getConfigSchema() }
                                }
                            } else {
                                null
                            }
                        val defaultsDeferred =
                            if (cachedDefaults == null) {
                                async(ioDispatcher) {
                                    safeApiRead { requestService.getConfigDefaults() }
                                }
                            } else {
                                null
                            }

                        // Required config success publishes flattened values and isLoading = false IMMEDIATELY
                        val configResult = configDeferred.await()
                        if (!isCurrent()) return@coroutineScope

                        if (configResult is NetworkResult.Success) {
                            val values = flattenConfig(configResult.data)
                            _uiState.update { state ->
                                val effectiveSchema = state.schema ?: cachedSchema
                                val effectiveDefaults = state.defaults ?: cachedDefaults
                                val categories = effectiveSchema?.category_order ?: emptyList()
                                val validCategory =
                                    when {
                                        userSelectedCategory && state.activeCategory.isNotBlank() -> {
                                            state.activeCategory
                                        }

                                        state.activeCategory.isNotBlank() &&
                                            (categories.isEmpty() || state.activeCategory in categories) -> {
                                            state.activeCategory
                                        }

                                        categories.isNotEmpty() -> {
                                            categories.first()
                                        }

                                        else -> {
                                            state.activeCategory
                                        }
                                    }
                                val mergedValues =
                                    if (pendingChanges.isNotEmpty()) {
                                        values.toMutableMap().apply { putAll(pendingChanges) }
                                    } else {
                                        values
                                    }
                                state.copy(
                                    isLoading = false,
                                    values = mergedValues,
                                    schema = effectiveSchema,
                                    defaults = effectiveDefaults,
                                    uncoveredPaths =
                                        collectUncoveredPaths(
                                            mergedValues,
                                            effectiveSchema?.fields?.keys ?: emptySet(),
                                        ),
                                    activeCategory = validCategory,
                                    errorMessage = null,
                                )
                            }
                        } else {
                            if (!hasCachedData && _uiState.value.values == null) {
                                val errorMsg =
                                    (configResult as? NetworkResult.Failure)?.error?.message ?: "Unknown error"
                                _uiState.update {
                                    it.copy(isLoading = false, errorMessage = "Failed to load config: $errorMsg")
                                }
                            } else {
                                // Keep the last good values visible, but never present the refresh as a success.
                                val errorMsg =
                                    (configResult as? NetworkResult.Failure)?.error?.message ?: "Unknown error"
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        errorMessage = "Failed to refresh config: $errorMsg",
                                        toastMessage = "Failed to refresh config: $errorMsg",
                                    )
                                }
                            }
                            return@coroutineScope
                        }

                        // Optional metadata: await schema and defaults
                        val schemaResult = schemaDeferred?.await()
                        val defaultsResult = defaultsDeferred?.await()

                        if (!isCurrent()) return@coroutineScope

                        val newSchema = (schemaResult as? NetworkResult.Success)?.data
                        if (newSchema != null && scopeKey.isNotBlank()) {
                            schemaCache.put(scopeKey, newSchema)
                        }
                        val schema = newSchema ?: cachedSchema

                        val newDefaults = (defaultsResult as? NetworkResult.Success)?.data
                        if (newDefaults != null && scopeKey.isNotBlank()) {
                            defaultsCache.put(scopeKey, newDefaults)
                        }
                        val defaults = newDefaults ?: cachedDefaults

                        if (schema != null || defaults != null) {
                            _uiState.update { state ->
                                val effectiveSchema = schema ?: state.schema
                                val effectiveDefaults = defaults ?: state.defaults
                                val categories = effectiveSchema?.category_order ?: emptyList()
                                val validCategory =
                                    when {
                                        userSelectedCategory && state.activeCategory.isNotBlank() -> {
                                            state.activeCategory
                                        }

                                        state.activeCategory.isNotBlank() &&
                                            (categories.isEmpty() || state.activeCategory in categories) -> {
                                            state.activeCategory
                                        }

                                        categories.isNotEmpty() -> {
                                            categories.first()
                                        }

                                        else -> {
                                            state.activeCategory
                                        }
                                    }
                                val currentValues = state.values ?: emptyMap()
                                state.copy(
                                    schema = effectiveSchema,
                                    defaults = effectiveDefaults,
                                    uncoveredPaths =
                                        collectUncoveredPaths(
                                            currentValues,
                                            effectiveSchema?.fields?.keys ?: emptySet(),
                                        ),
                                    activeCategory = validCategory,
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isCurrent() && !hasCachedData && _uiState.value.values == null) {
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = "Failed to load config: ${e.message}")
                        }
                    } else if (isCurrent()) {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
            }
    }

    fun updateField(
        key: String,
        value: JsonElement,
    ) {
        pendingChanges[key] = value
        _uiState.update {
            it.copy(
                values = it.values?.toMutableMap()?.apply { this[key] = value },
                modifiedKeys = pendingChanges.keys.toSet(),
            )
        }
    }

    /** Reset ONE field to its default value (pending until Save). */
    fun resetField(key: String) {
        val state = _uiState.value
        val defaultVal = state.defaults?.get(key) ?: return
        pendingChanges[key] = defaultVal
        _uiState.update {
            it.copy(
                values = it.values?.toMutableMap()?.apply { this[key] = defaultVal },
                modifiedKeys = pendingChanges.keys.toSet(),
            )
        }
    }

    /** Clear a clearable field to blank (e.g. timezone → system default). */
    fun clearField(key: String) {
        val blank = JsonPrimitive("")
        pendingChanges[key] = blank
        _uiState.update {
            it.copy(
                values = it.values?.toMutableMap()?.apply { this[key] = blank },
                modifiedKeys = pendingChanges.keys.toSet(),
            )
        }
    }

    fun setActiveCategory(category: String) {
        userSelectedCategory = true
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
            val requestScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
            val requestProfile = runCatching { AuthManager.activeProfileId.value }.getOrNull()
            val requestService = ApiClient.hermesApi
            _uiState.update { it.copy(yamlMode = true, yamlIsLoading = true) }
            viewModelScope.launch {
                val result =
                    withContext(ioDispatcher) {
                        safeApiRead { requestService.getRawConfig() }
                    }
                val currentScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
                val currentProfile = runCatching { AuthManager.activeProfileId.value }.getOrNull()
                if (requestScope != null && currentScope != requestScope) return@launch
                if (requestProfile != currentProfile) return@launch
                if (requestService !== ApiClient.hermesApi) return@launch

                when (result) {
                    is NetworkResult.Success -> {
                        _uiState.update {
                            it.copy(
                                yamlIsLoading = false,
                                yamlText = result.data.yaml ?: "",
                                path = result.data.path ?: it.path,
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
        val requestScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
        val requestProfile = runCatching { AuthManager.activeProfileId.value }.getOrNull()
        val requestService = ApiClient.hermesApi
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val changeset = buildChangeset(pendingChanges)
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        requestService.updateConfig(
                            ConfigUpdateRequest(config = changeset),
                        )
                    }
                }
            val currentScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
            val currentProfile = runCatching { AuthManager.activeProfileId.value }.getOrNull()
            if (requestScope != null && currentScope != requestScope) return@launch
            if (requestProfile != currentProfile) return@launch
            if (requestService !== ApiClient.hermesApi) return@launch

            when (result) {
                is NetworkResult.Success -> {
                    pendingChanges.clear()
                    val configResult =
                        withContext(ioDispatcher) {
                            safeApiRead { requestService.getConfig() }
                        }
                    val currentScopeAfterGet = runCatching { AuthManager.currentDataScope() }.getOrNull()
                    val currentProfileAfterGet = runCatching { AuthManager.activeProfileId.value }.getOrNull()
                    if (requestScope != null && currentScopeAfterGet != requestScope) return@launch
                    if (requestProfile != currentProfileAfterGet) return@launch
                    if (requestService !== ApiClient.hermesApi) return@launch

                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            values =
                                (configResult as? NetworkResult.Success)?.data?.let(::flattenConfig)
                                    ?: it.values,
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
        val requestScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
        val requestProfile = runCatching { AuthManager.activeProfileId.value }.getOrNull()
        val requestService = ApiClient.hermesApi
        _uiState.update { it.copy(yamlIsSaving = true) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        requestService.updateRawConfig(
                            UpdateRawConfigRequest(yaml_text = yamlText),
                        )
                    }
                }
            val currentScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
            val currentProfile = runCatching { AuthManager.activeProfileId.value }.getOrNull()
            if (requestScope != null && currentScope != requestScope) return@launch
            if (requestProfile != currentProfile) return@launch
            if (requestService !== ApiClient.hermesApi) return@launch

            when (result) {
                is NetworkResult.Success -> {
                    val configResult =
                        withContext(ioDispatcher) {
                            safeApiRead { requestService.getConfig() }
                        }
                    val currentScopeAfterGet = runCatching { AuthManager.currentDataScope() }.getOrNull()
                    val currentProfileAfterGet = runCatching { AuthManager.activeProfileId.value }.getOrNull()
                    if (requestScope != null && currentScopeAfterGet != requestScope) return@launch
                    if (requestProfile != currentProfileAfterGet) return@launch
                    if (requestService !== ApiClient.hermesApi) return@launch

                    _uiState.update {
                        it.copy(
                            yamlIsSaving = false,
                            values =
                                (configResult as? NetworkResult.Success)?.data?.let(::flattenConfig)
                                    ?: it.values,
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
        val updatedValues = state.values?.toMutableMap()
        for ((key, _) in categoryFields) {
            val defaultVal = defaults[key]
            if (defaultVal != null) {
                pendingChanges[key] = defaultVal
                updatedValues?.set(key, defaultVal)
                count++
            }
        }
        _uiState.update {
            it.copy(
                values = updatedValues ?: it.values,
                modifiedKeys = pendingChanges.keys.toSet(),
            )
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

        fun toJsonObject(map: Map<String, Any>): Map<String, JsonElement> =
            map.mapValues { (_, v) ->
                if (v is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    JsonObject(toJsonObject(v as Map<String, Any>))
                } else {
                    v as JsonElement
                }
            }

        return toJsonObject(root)
    }
}
