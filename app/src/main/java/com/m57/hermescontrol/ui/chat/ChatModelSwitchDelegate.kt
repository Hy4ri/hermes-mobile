package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ModelCapabilities
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.ModelCatalogStore
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks an in-flight `config.set key=model` RPC and the model it would replace
 * so confirmation dialog dismiss can roll back optimistically.
 */
private data class ActiveModelSwitch(
    val spec: String,
    val previousModel: String?,
    val sequence: Long,
)

private data class ActiveFastSwitch(
    val targetFast: Boolean,
)

/**
 * Owns model picker loading/caching, in-session model switches, model capabilities,
 * pinned model toggling, and expensive model switch confirmation dialogs.
 *
 * Extracted behavior-preservingly from [ChatViewModel] (issue #589).
 */
class ChatModelSwitchDelegate(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val runtimeSessionId: () -> String?,
    private val wsSend: (method: String, params: Map<String, Any>, onSent: ((String) -> Unit)?) -> Unit,
    private val trackRequest: (id: String, method: String) -> Unit,
    private val addAssistantMessage: (text: String) -> Unit,
    private val handleSlashCommand: (command: String) -> Unit,
    private val fetchContextUsage: () -> Unit,
    private val onModelSwitchInitiated: () -> Unit = {},
    private val getModelOptionsCall: suspend (
        refresh: Boolean,
    ) -> NetworkResult<com.m57.hermescontrol.data.model.ModelOptionsResponse> =
        { refresh -> ModelCatalogStore.shared.ensureLoaded(forceRefresh = refresh) },
    private val getPinnedModels: () -> List<PinnedModel> = { AuthManager.getPinnedModels() },
    private val savePinnedModels: (List<PinnedModel>) -> Unit = { AuthManager.savePinnedModels(it) },
) {
    private val pendingModelSwitchRequests = ConcurrentHashMap<String, ActiveModelSwitch>()
    private val pendingFastSwitchRequests = ConcurrentHashMap<String, ActiveFastSwitch>()
    private val fastRejectedModels = ConcurrentHashMap.newKeySet<String>()
    private var activeModelSwitchConfirmation: ActiveModelSwitch? = null
    private var optimisticPreviousModel: String? = null
    private var lastConfirmedModel: String? = null
    private var unconfirmedTargetModel: String? = null
    private var modelSwitchSequence: Long = 0L
    private var cachedModelOptions: List<ModelProvider> = emptyList()
    private var modelOptionsJob: Job? = null

    fun isModelPickerCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (!trimmed.startsWith("/", ignoreCase = true)) return false
        val body = trimmed.removePrefix("/").trimStart()
        return body.equals("model", ignoreCase = true) ||
            (
                body.startsWith("model ", ignoreCase = true) &&
                    body.substringAfter("model").trim().isEmpty()
            )
    }

    fun preloadModelOptions() {
        loadModelOptions(refresh = false)
    }

    fun openModelPicker() {
        val hasCached = cachedModelOptions.isNotEmpty()
        uiState.update {
            it.copy(
                showModelPicker = true,
                modelPickerProviders = if (hasCached) cachedModelOptions else emptyList(),
                modelPickerPinned = getPinnedModels(),
                modelPickerLoading = !hasCached,
            )
        }
        if (!hasCached) {
            loadModelOptions(refresh = false)
        }
    }

    fun refreshModelOptions() {
        loadModelOptions(refresh = true)
    }

    private fun loadModelOptions(refresh: Boolean) {
        // Opening during preload must reuse that request, not force another catalog fetch.
        if (modelOptionsJob?.isActive == true) {
            if (!refresh) return
            modelOptionsJob?.cancel()
        }
        uiState.update { it.copy(modelPickerLoading = true) }
        modelOptionsJob =
            scope.launch {
                val result = withContext(ioDispatcher) { getModelOptionsCall(refresh) }
                when (result) {
                    is NetworkResult.Success -> {
                        cachedModelOptions = result.data.providers.orEmpty()
                        uiState.update {
                            it.copy(
                                modelPickerProviders = cachedModelOptions,
                                modelPickerPinned = getPinnedModels(),
                                modelPickerLoading = false,
                            )
                        }
                        syncCurrentModelCapabilities()
                    }

                    is NetworkResult.Failure -> {
                        uiState.update {
                            it.copy(
                                modelPickerLoading = false,
                                errorMessage =
                                    if (it.showModelPicker || refresh) {
                                        "Failed to load models: ${result.error.message}"
                                    } else {
                                        it.errorMessage
                                    },
                            )
                        }
                    }
                }
            }
    }

    fun closeModelPicker() {
        uiState.update { it.copy(showModelPicker = false, modelPickerLoading = false) }
    }

    fun togglePinModel(
        providerSlug: String,
        modelName: String,
    ) {
        val currentPinned = getPinnedModels().toMutableList()
        val target = PinnedModel(providerSlug, modelName)
        if (currentPinned.contains(target)) {
            currentPinned.remove(target)
        } else {
            currentPinned.add(target)
        }
        savePinnedModels(currentPinned)
        uiState.update { it.copy(modelPickerPinned = currentPinned) }
    }

    fun consumeOptimisticPreviousModel(): String? = optimisticPreviousModel.also { optimisticPreviousModel = null }

    fun sendSlashModel(
        provider: String,
        model: String,
    ) {
        val targetModelLabel = "$provider/$model"
        val isDifferent = !targetModelLabel.equals(uiState.value.currentSessionModel, ignoreCase = true)
        if (!isDifferent) {
            uiState.update {
                it.copy(
                    showModelPicker = false,
                    modelPickerLoading = false,
                )
            }
            return
        }
        if (optimisticPreviousModel == null) {
            optimisticPreviousModel = uiState.value.currentSessionModel
        }
        unconfirmedTargetModel = targetModelLabel
        uiState.update {
            it.copy(
                showModelPicker = false,
                modelPickerLoading = false,
                currentSessionModel = targetModelLabel,
                fullContextTokens = null,
            )
        }
        onModelSwitchInitiated()
        syncCurrentModelCapabilities()
        handleSlashCommand("/model $model --provider $provider --session")
    }

    fun handleModelSwitch(
        command: String,
        confirmExpensive: Boolean = false,
    ) {
        val sessionId = runtimeSessionId()
        if (sessionId == null) {
            addAssistantMessage("No active session. Use `/new` to create one.")
            return
        }
        val spec =
            if (command.startsWith("/model", ignoreCase = true)) {
                command.substring(6).trim()
            } else {
                command.trim()
            }
        val previousModel = lastConfirmedModel ?: optimisticPreviousModel ?: uiState.value.currentSessionModel
        // Retain optimisticPreviousModel for consumeOptimisticPreviousModel() when SessionInfo arrives.
        if (optimisticPreviousModel == null && !spec.equals(uiState.value.currentSessionModel, ignoreCase = true)) {
            optimisticPreviousModel = uiState.value.currentSessionModel
            uiState.update { it.copy(fullContextTokens = null) }
            onModelSwitchInitiated()
        }
        unconfirmedTargetModel = spec
        val switchSeq = ++modelSwitchSequence
        val params =
            mutableMapOf<String, Any>(
                "key" to "model",
                "value" to spec,
                "session_id" to sessionId,
            )
        if (confirmExpensive) {
            params["confirm_expensive_model"] = true
        }
        scope.launch(ioDispatcher) {
            wsSend(
                WsMethods.CONFIG_SET,
                params,
            ) { id ->
                trackRequest(id, WsMethods.CONFIG_SET)
                pendingModelSwitchRequests[id] = ActiveModelSwitch(spec, previousModel, switchSeq)
            }
        }
    }

    fun handleConfigSetResult(
        id: String,
        result: Any?,
    ) {
        val pending = pendingModelSwitchRequests.remove(id)
        val pendingFast = pendingFastSwitchRequests.remove(id)
        val map = result as? Map<*, *> ?: return
        val key = map["key"] as? String
        if (key == "model") {
            val confirmRequired = map["confirm_required"] as? Boolean ?: false
            if (confirmRequired) {
                if (pending != null && pending.sequence == modelSwitchSequence) {
                    val confirmMessage =
                        (map["confirm_message"] as? String)
                            ?: (map["warning"] as? String)
                            ?: "This model requires confirmation to switch. Continue?"
                    activeModelSwitchConfirmation = pending
                    uiState.update {
                        it.copy(modelSwitchConfirmMessage = confirmMessage)
                    }
                }
            }
        } else if (key == "fast") {
            val rawVal = map["value"] as? String
            val confirmedFast =
                if (rawVal != null) {
                    rawVal == "fast" || rawVal == "priority"
                } else {
                    pendingFast?.targetFast ?: uiState.value.fastMode
                }
            uiState.update {
                it.copy(
                    fastMode = confirmedFast,
                    isFastModeChanging = false,
                )
            }
        }
    }

    fun dismissModelSwitchConfirm() {
        val pending = activeModelSwitchConfirmation
        activeModelSwitchConfirmation = null
        optimisticPreviousModel = null
        unconfirmedTargetModel = null
        val revertModel = lastConfirmedModel ?: pending?.previousModel
        uiState.update {
            it.copy(
                modelSwitchConfirmMessage = null,
                currentSessionModel = revertModel ?: it.currentSessionModel,
                fullContextTokens = null,
            )
        }
        syncCurrentModelCapabilities()
        onModelSwitchInitiated()
        fetchContextUsage()
    }

    fun confirmModelSwitchExpensive() {
        val pending = activeModelSwitchConfirmation
        activeModelSwitchConfirmation = null
        uiState.update { it.copy(modelSwitchConfirmMessage = null) }
        if (pending != null) {
            handleModelSwitch(pending.spec, confirmExpensive = true)
        }
    }

    fun toggleFastMode() {
        val state = uiState.value
        val sessionId = runtimeSessionId() ?: return
        if (state.currentModelCapabilities?.fast != true) return
        if (state.isFastModeChanging) return

        val target = !state.fastMode
        uiState.update { it.copy(isFastModeChanging = true) }
        scope.launch(ioDispatcher) {
            wsSend(
                WsMethods.CONFIG_SET,
                mapOf(
                    "key" to "fast",
                    "value" to if (target) "fast" else "normal",
                    "session_id" to sessionId,
                ),
            ) { id ->
                trackRequest(id, WsMethods.CONFIG_SET)
                pendingFastSwitchRequests[id] = ActiveFastSwitch(target)
            }
        }
    }

    fun handleConfigSetError(
        id: String,
        error: Any?,
    ) {
        val pendingModel = pendingModelSwitchRequests.remove(id)
        if (pendingModel != null && pendingModel.sequence == modelSwitchSequence) {
            val revertModel = lastConfirmedModel ?: pendingModel.previousModel
            optimisticPreviousModel = null
            unconfirmedTargetModel = null
            activeModelSwitchConfirmation = null
            if (revertModel != null) {
                uiState.update {
                    it.copy(
                        currentSessionModel = revertModel,
                        fullContextTokens = null,
                        modelSwitchConfirmMessage = null,
                    )
                }
                syncCurrentModelCapabilities()
                onModelSwitchInitiated()
                fetchContextUsage()
            } else {
                uiState.update {
                    it.copy(
                        fullContextTokens = null,
                        modelSwitchConfirmMessage = null,
                    )
                }
                onModelSwitchInitiated()
            }
        }
        val pendingFast = pendingFastSwitchRequests.remove(id)
        if (pendingFast != null) {
            val errorMsg =
                when (error) {
                    is Map<*, *> -> error["message"] as? String ?: error.toString()
                    else -> error.toString()
                }.lowercase()
            val currentModel = uiState.value.currentSessionModel
            if (currentModel != null &&
                (errorMsg.contains("fast mode is not available") || errorMsg.contains("unknown fast mode"))
            ) {
                fastRejectedModels.add(currentModel)
                syncCurrentModelCapabilities()
            }
            uiState.update { it.copy(isFastModeChanging = false) }
        }
    }

    fun isSwitchPending(): Boolean =
        optimisticPreviousModel != null ||
            unconfirmedTargetModel != null ||
            activeModelSwitchConfirmation != null

    fun onModelConfirmed(modelLabel: String) {
        lastConfirmedModel = modelLabel
        optimisticPreviousModel = null
        unconfirmedTargetModel = null
    }

    fun reset() {
        pendingModelSwitchRequests.clear()
        pendingFastSwitchRequests.clear()
        activeModelSwitchConfirmation = null
        optimisticPreviousModel = null
        lastConfirmedModel = null
        unconfirmedTargetModel = null
    }

    fun setReasoningLevel(level: String?) {
        uiState.update { it.copy(reasoningLevel = level) }
        val sessionId = runtimeSessionId() ?: return
        if (level == null) return
        scope.launch(ioDispatcher) {
            wsSend(
                WsMethods.CONFIG_SET,
                mapOf(
                    "key" to "reasoning",
                    "value" to level,
                    "session_id" to sessionId,
                ),
            ) { id -> trackRequest(id, WsMethods.CONFIG_SET) }
        }
    }

    fun getModelCapabilities(
        providerSlug: String,
        modelName: String,
    ): ModelCapabilities? = cachedModelOptions.find { it.slug == providerSlug }?.capabilities?.get(modelName)

    fun getCurrentModelCapabilities(): ModelCapabilities? {
        val label = uiState.value.currentSessionModel ?: return null
        val idx = label.indexOf('/')
        if (idx <= 0) return null
        val provider = label.substring(0, idx)
        val model = label.substring(idx + 1)
        return getModelCapabilities(provider, model)
    }

    fun syncCurrentModelCapabilities() {
        val label = uiState.value.currentSessionModel
        val caps =
            if (label == null) {
                null
            } else {
                val idx = label.indexOf('/')
                if (idx <= 0) {
                    null
                } else {
                    val provider = label.substring(0, idx)
                    val model = label.substring(idx + 1)
                    val baseCaps = cachedModelOptions.find { it.slug == provider }?.capabilities?.get(model)
                    if (fastRejectedModels.contains(label) && baseCaps?.fast == true) {
                        baseCaps.copy(fast = false)
                    } else {
                        baseCaps
                    }
                }
            }
        uiState.update { state ->
            if (state.currentModelCapabilities != caps) state.copy(currentModelCapabilities = caps) else state
        }
    }
}
