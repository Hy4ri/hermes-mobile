package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ModelCapabilities
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks an in-flight `config.set key=model` RPC and the model it would replace
 * so confirmation dialog dismiss can roll back optimistically.
 */
data class ActiveModelSwitch(
    val spec: String,
    val previousModel: String?,
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
    private val getModelOptionsCall: suspend (
        refresh: Boolean,
    ) -> NetworkResult<com.m57.hermescontrol.data.model.ModelOptionsResponse> =
        { refresh -> safeApiCall { ApiClient.hermesApi.getModelOptions(refresh = refresh) } },
    private val getPinnedModels: () -> List<PinnedModel> = { AuthManager.getPinnedModels() },
    private val savePinnedModels: (List<PinnedModel>) -> Unit = { AuthManager.savePinnedModels(it) },
) {
    val pendingModelSwitchRequests = ConcurrentHashMap<String, ActiveModelSwitch>()
    var activeModelSwitchConfirmation: ActiveModelSwitch? = null
    var optimisticPreviousModel: String? = null
    var cachedModelOptions: List<ModelProvider> = emptyList()

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
        scope.launch(ioDispatcher) {
            val result = getModelOptionsCall(false)
            if (result is NetworkResult.Success) {
                cachedModelOptions = result.data.providers.orEmpty()
                uiState.update { it.copy(modelPickerPinned = getPinnedModels()) }
                syncCurrentModelCapabilities()
            }
        }
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
            refreshModelOptions()
        }
    }

    fun refreshModelOptions() {
        uiState.update { it.copy(modelPickerLoading = true) }
        scope.launch(ioDispatcher) {
            val result = getModelOptionsCall(true)
            when (result) {
                is NetworkResult.Success -> {
                    cachedModelOptions = result.data.providers.orEmpty()
                    uiState.update {
                        it.copy(
                            modelPickerProviders = cachedModelOptions,
                            modelPickerLoading = false,
                        )
                    }
                    syncCurrentModelCapabilities()
                }

                is NetworkResult.Failure -> {
                    uiState.update {
                        it.copy(
                            modelPickerLoading = false,
                            errorMessage = "Failed to load models: ${result.error.message}",
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

    fun sendSlashModel(
        provider: String,
        model: String,
    ) {
        optimisticPreviousModel = uiState.value.currentSessionModel
        uiState.update {
            it.copy(
                showModelPicker = false,
                modelPickerLoading = false,
                currentSessionModel = "$provider/$model",
            )
        }
        syncCurrentModelCapabilities()
        fetchContextUsage()
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
        val previousModel = optimisticPreviousModel ?: uiState.value.currentSessionModel
        optimisticPreviousModel = null
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
                pendingModelSwitchRequests[id] = ActiveModelSwitch(spec, previousModel)
            }
        }
    }

    fun handleConfigSetResult(
        id: String,
        result: Any?,
    ) {
        val pending = pendingModelSwitchRequests.remove(id)
        val map = result as? Map<*, *> ?: return
        val key = map["key"] as? String
        if (key == "model") {
            val confirmRequired = map["confirm_required"] as? Boolean ?: false
            if (confirmRequired) {
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
    }

    fun dismissModelSwitchConfirm() {
        val pending = activeModelSwitchConfirmation
        activeModelSwitchConfirmation = null
        uiState.update {
            it.copy(
                modelSwitchConfirmMessage = null,
                currentSessionModel = pending?.previousModel ?: it.currentSessionModel,
            )
        }
        syncCurrentModelCapabilities()
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
                    cachedModelOptions.find { it.slug == provider }?.capabilities?.get(model)
                }
            }
        uiState.update { state ->
            if (state.currentModelCapabilities != caps) state.copy(currentModelCapabilities = caps) else state
        }
    }
}
