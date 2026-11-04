package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.ModelCapabilities
import com.m57.hermescontrol.data.model.ModelInfoResponse
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.model.mergedWithCanonical
import com.m57.hermescontrol.data.model.reasoningSupport
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.ModelCatalogStore
import com.m57.hermescontrol.data.ws.contract.ConfigGetParams
import com.m57.hermescontrol.data.ws.contract.ConfigSetParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.toAny
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks an in-flight `config.set key=model` RPC and the model it would replace
 * so confirmation dialog dismiss can roll back optimistically.
 */
private data class ActiveModelSwitch(
    val spec: String,
    val previousModel: String?,
    val sequence: Long,
    val sessionId: String,
    val dataScope: DataScope?,
)

private data class ActiveFastSwitch(
    val targetFast: Boolean,
)

/** Profile-wide config key for Daybreak (issue #420); persisted as `agent.daybreak`. */
private const val DAYBREAK_CONFIG_KEY = "daybreak"

/**
 * Parse a `config.get`/`config.set` daybreak payload. The backend answers a real JSON boolean;
 * string words are accepted defensively. Anything else is unknown (null), never a guess.
 */
private fun parseDaybreakConfigValue(raw: Any?): Boolean? {
    val map = raw as? Map<*, *> ?: return null
    return when (val value = map["value"]) {
        is Boolean -> {
            value
        }

        is String -> {
            when (value.trim().lowercase()) {
                "true", "1", "on", "yes" -> true
                "false", "0", "off", "no" -> false
                else -> null
            }
        }

        else -> {
            null
        }
    }
}

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
    private val wsSend: (params: ConfigSetParams, onSent: ((String) -> Unit)?) -> Unit,
    private val trackRequest: (id: String, method: String) -> Unit,
    private val addAssistantMessage: (text: String) -> Unit,
    private val handleSlashCommand: (command: String) -> Unit,
    private val fetchContextUsage: () -> Unit,
    private val onModelSwitchInitiated: () -> Unit = {},
    private val dataScopeFlow: StateFlow<DataScope?> = AuthManager.dataScopeFlow,
    private val getModelOptionsCall: suspend (
        refresh: Boolean,
    ) -> NetworkResult<com.m57.hermescontrol.data.model.ModelOptionsResponse> =
        { refresh -> ModelCatalogStore.shared.ensureLoaded(forceRefresh = refresh) },
    private val getPinnedModels: () -> List<PinnedModel> = { AuthManager.getPinnedModels() },
    private val savePinnedModels: (List<PinnedModel>) -> Unit = { AuthManager.savePinnedModels(it) },
    private val wsRequest: (suspend (params: ConfigSetParams) -> JsonElement)? = null,
    /**
     * Suspend `config.get` used for profile-wide reads (issue #420, key `daybreak`).
     * Null when the host does not wire it; reads then stay unknown and never mutate state.
     */
    private val wsGetRequest: (suspend (params: ConfigGetParams) -> JsonElement)? = null,
) {
    private val pendingModelSwitchRequests = ConcurrentHashMap<String, ActiveModelSwitch>()
    private val pendingFastSwitchRequests = ConcurrentHashMap<String, ActiveFastSwitch>()
    private val fastRejectedModels = ConcurrentHashMap.newKeySet<String>()
    private var activeModelSwitchConfirmation: ActiveModelSwitch? = null
    private var optimisticPreviousModel: String? = null
    private var lastConfirmedModel: String? = null
    private var unconfirmedTargetModel: String? = null
    private var modelSwitchSequence: Long = 0L
    private var reasoningOpSequence: Long = 0L
    private var reasoningJob: Job? = null
    private var cachedModelOptions: List<ModelProvider> = emptyList()
    private var cachedModelOptionsScope: DataScope? = dataScopeFlow.value
    private var modelOptionsJob: Job? = null
    private var modelOptionsJobScope: DataScope? = null
    private var modelInfoCapabilities: ModelCapabilities? = null
    private var modelInfoModel: String? = null
    private var modelInfoScope: DataScope? = null

    // ── Daybreak (issue #420) ──
    // Profile-wide `agent.daybreak`, bound to the current DataScope (connection + profile).
    // Mirrors desktop store/daybreak.ts: confirmed value, stale-read fencing, serialized writes.
    private var daybreakConfirmed: Boolean = false
    private var daybreakReadSeq: Long = 0L
    private var daybreakWriteSeq: Long = 0L
    private var daybreakPendingWrites: Int = 0
    private var daybreakGeneration: Long = 0L
    private val daybreakWriteMutex = Mutex()

    fun attachScopeObserver(externalScope: CoroutineScope) {
        externalScope.launch {
            dataScopeFlow.collect { newScope ->
                if (cachedModelOptionsScope == newScope) return@collect

                // A model catalog belongs to exactly one DataScope. Never let
                // models or capability flags bleed across profile/server/auth
                // boundaries while the new scope is reconnecting.
                cachedModelOptions = emptyList()
                cachedModelOptionsScope = newScope
                fastRejectedModels.clear()
                modelInfoCapabilities = null
                modelInfoModel = null
                modelInfoScope = null
                // Daybreak is per connection + profile: never carry the old scope's value.
                // Bumping the generation makes every old-scope write's finally-block a no-op,
                // so it cannot corrupt the new scope's pending count or confirmed value.
                daybreakConfirmed = false
                daybreakPendingWrites = 0
                daybreakReadSeq++
                daybreakGeneration++
                resetTransientOperations()

                if (modelOptionsJobScope != newScope) {
                    modelOptionsJob?.cancel()
                    modelOptionsJob = null
                    modelOptionsJobScope = null
                }

                val shouldReload = newScope != null && uiState.value.showModelPicker
                uiState.update {
                    it.copy(
                        modelPickerProviders = emptyList(),
                        modelPickerLoading = shouldReload,
                        currentModelCapabilities = null,
                        modelPickerCanUseDefault = false,
                        daybreakEnabled = false,
                        daybreakPending = false,
                    )
                }

                if (shouldReload) {
                    loadModelOptions(refresh = false)
                }
            }
        }
    }

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
        val currentScope = dataScopeFlow.value
        val hasCached =
            cachedModelOptionsScope == currentScope && cachedModelOptions.isNotEmpty()
        uiState.update {
            it.copy(
                showModelPicker = true,
                modelPickerProviders = if (hasCached) cachedModelOptions else emptyList(),
                modelPickerPinned = getPinnedModels(),
                modelPickerCanUseDefault = runtimeSessionId() != null,
                modelPickerLoading = !hasCached,
            )
        }
        if (!hasCached) {
            loadModelOptions(refresh = false)
        } else {
            // Refresh shared picker state on every open, not just first load.
            syncDaybreak()
        }
    }

    fun refreshModelOptions() {
        loadModelOptions(refresh = true)
    }

    private fun loadModelOptions(refresh: Boolean) {
        val requestScope = dataScopeFlow.value

        if (cachedModelOptionsScope != requestScope) {
            cachedModelOptions = emptyList()
            cachedModelOptionsScope = requestScope
            fastRejectedModels.clear()
            uiState.update {
                it.copy(
                    modelPickerProviders = emptyList(),
                    currentModelCapabilities = null,
                )
            }
        }

        // Reuse only work belonging to this exact DataScope. A reconnect into
        // another profile/server must not inherit the previous preload.
        if (modelOptionsJob?.isActive == true) {
            if (modelOptionsJobScope == requestScope && !refresh) return
            modelOptionsJob?.cancel()
        }

        uiState.update { it.copy(modelPickerLoading = true) }
        modelOptionsJobScope = requestScope
        modelOptionsJob =
            scope.launch {
                val result = withContext(ioDispatcher) { getModelOptionsCall(refresh) }

                // The scope may have changed while IO was in flight. Never
                // publish that old scope's result into the current picker.
                if (dataScopeFlow.value != requestScope) {
                    return@launch
                }

                when (result) {
                    is NetworkResult.Success -> {
                        cachedModelOptions = result.data.providers.orEmpty()
                        cachedModelOptionsScope = requestScope
                        uiState.update {
                            it.copy(
                                modelPickerProviders = cachedModelOptions,
                                modelPickerPinned = getPinnedModels(),
                                modelPickerLoading = false,
                            )
                        }
                        syncCurrentModelCapabilities()
                        syncDaybreak()
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
        if (uiState.value.isModelResetPending) resetTransientOperations()
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

    /**
     * Resets the session model override (/model reset) so the gateway drops
     * the session pin and reverts to config.yaml's configured default model.
     */
    fun resetSessionModel() {
        handleModelSwitch("/model reset")
    }

    private fun ownsRequest(pending: ActiveModelSwitch): Boolean =
        pending.sequence == modelSwitchSequence &&
            pending.sessionId == runtimeSessionId() &&
            pending.dataScope == dataScopeFlow.value

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
        val isReset = spec.equals("reset", ignoreCase = true)
        if (isReset && uiState.value.isModelResetPending && !confirmExpensive) return
        activeModelSwitchConfirmation = null
        uiState.update {
            it.copy(
                modelSwitchConfirmMessage = null,
                isModelResetPending = isReset,
                showModelPicker = false,
                modelPickerLoading = false,
            )
        }
        val previousModel = lastConfirmedModel ?: optimisticPreviousModel ?: uiState.value.currentSessionModel
        if (!isReset) {
            // Retain optimisticPreviousModel for consumeOptimisticPreviousModel() when SessionInfo arrives.
            if (optimisticPreviousModel == null && !spec.equals(uiState.value.currentSessionModel, ignoreCase = true)) {
                optimisticPreviousModel = uiState.value.currentSessionModel
                uiState.update { it.copy(fullContextTokens = null) }
                onModelSwitchInitiated()
            }
            unconfirmedTargetModel = spec
        }
        val switchSeq = ++modelSwitchSequence
        val requestScope = dataScopeFlow.value
        val params =
            ConfigSetParams(
                key = "model",
                value = spec,
                sessionId = sessionId,
                confirmExpensiveModel = true.takeIf { confirmExpensive },
            )
        val pending = ActiveModelSwitch(spec, previousModel, switchSeq, sessionId, requestScope)
        scope.launch(ioDispatcher) {
            if (!ownsRequest(pending)) return@launch
            wsSend(params) { id ->
                if (ownsRequest(pending)) {
                    trackRequest(id, RpcMethods.CONFIG_SET.name)
                    pendingModelSwitchRequests[id] = pending
                }
            }
        }
    }

    fun handleConfigSetResult(
        id: String,
        result: Any?,
    ) {
        val pending = pendingModelSwitchRequests[id]
        val map = result as? Map<*, *>
        val key = map?.get("key") as? String
        if (pending != null && (!ownsRequest(pending) || map == null || (key != null && key != "model"))) {
            handleConfigSetError(id, "Invalid or stale model response")
            return
        }
        pendingModelSwitchRequests.remove(id)
        val pendingFast = pendingFastSwitchRequests.remove(id)
        if (map == null) return
        if (key == "model" || (key == null && pending != null)) {
            if (pending == null || !ownsRequest(pending)) return
            val confirmRequired = map["confirm_required"] as? Boolean ?: false
            if (confirmRequired) {
                val confirmMessage =
                    (map["confirm_message"] as? String)
                        ?: (map["warning"] as? String)
                        ?: "This model requires confirmation to switch. Continue?"
                activeModelSwitchConfirmation = pending
                uiState.update { it.copy(modelSwitchConfirmMessage = confirmMessage) }
            } else {
                if (pending.spec.equals("reset", ignoreCase = true)) {
                    // Some older gateways echo the command; it is never a model label.
                    val resetValue =
                        (map["value"] as? String)?.takeIf {
                            it.isNotBlank() && !it.equals("reset", ignoreCase = true)
                        }
                    uiState.update {
                        it.copy(
                            isModelResetPending = false,
                            currentSessionModel = resetValue ?: it.currentSessionModel,
                            fullContextTokens = null,
                        )
                    }
                    syncCurrentModelCapabilities()
                    onModelSwitchInitiated()
                    fetchContextUsage()
                }
                val warning = (map["warning"] as? String)?.trim()
                if (!warning.isNullOrBlank()) {
                    addAssistantMessage(if (warning.startsWith("⚠")) warning else "⚠ $warning")
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
        val pending = activeModelSwitchConfirmation ?: return
        activeModelSwitchConfirmation = null
        if (!ownsRequest(pending)) return
        optimisticPreviousModel = null
        unconfirmedTargetModel = null
        val revertModel = lastConfirmedModel ?: pending.previousModel
        uiState.update {
            it.copy(
                modelSwitchConfirmMessage = null,
                isModelResetPending = false,
                currentSessionModel = revertModel ?: it.currentSessionModel,
                fullContextTokens = null,
            )
        }
        syncCurrentModelCapabilities()
        onModelSwitchInitiated()
        fetchContextUsage()
    }

    fun confirmModelSwitchExpensive() {
        val pending = activeModelSwitchConfirmation ?: return
        activeModelSwitchConfirmation = null
        if (!ownsRequest(pending)) return
        uiState.update { it.copy(modelSwitchConfirmMessage = null) }
        handleModelSwitch(pending.spec, confirmExpensive = true)
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
                ConfigSetParams(
                    key = "fast",
                    value = if (target) "fast" else "normal",
                    sessionId = sessionId,
                ),
            ) { id ->
                trackRequest(id, RpcMethods.CONFIG_SET.name)
                pendingFastSwitchRequests[id] = ActiveFastSwitch(target)
            }
        }
    }

    /** Catalog lists Daybreak for at least one model in the current scope. */
    private fun daybreakListed(): Boolean =
        cachedModelOptionsScope == dataScopeFlow.value &&
            cachedModelOptions.any { provider ->
                provider.capabilities?.values?.any { it.daybreak == true } == true
            }

    /**
     * Read the profile's `agent.daybreak` (issue #420). A read never overrides a write: it
     * applies only when no write started (or is still in flight) since the read began, and
     * only for the scope that started it. Unknown keys (older gateways) and transport errors
     * leave the confirmed value untouched — no fallback mutation.
     */
    fun syncDaybreak() {
        val getRequest = wsGetRequest ?: return
        if (!daybreakListed()) return
        val capturedScope = dataScopeFlow.value
        val readSeq = ++daybreakReadSeq
        val writesAtStart = daybreakWriteSeq
        scope.launch(ioDispatcher) {
            val value =
                try {
                    parseDaybreakConfigValue(getRequest(ConfigGetParams(key = DAYBREAK_CONFIG_KEY))?.toAny())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            if (value == null) return@launch
            if (readSeq != daybreakReadSeq || daybreakWriteSeq != writesAtStart || daybreakPendingWrites > 0) {
                return@launch
            }
            if (capturedScope != dataScopeFlow.value) return@launch
            daybreakConfirmed = value
            uiState.update { it.copy(daybreakEnabled = value) }
        }
    }

    /**
     * Optimistic Daybreak write (issue #420). Writes to the one profile key go out one at a
     * time under [daybreakWriteMutex], in click order; each success updates the confirmed
     * value even while a newer flip is queued. A refusal restores the confirmed value when
     * it is still the latest flip, and surfaces the error.
     */
    fun setDaybreak(on: Boolean) {
        val request = wsRequest ?: return
        if (!daybreakListed()) return
        val capturedScope = dataScopeFlow.value
        val writeSeq = ++daybreakWriteSeq
        val generation = daybreakGeneration
        daybreakPendingWrites++
        uiState.update { it.copy(daybreakEnabled = on, daybreakPending = true) }
        scope.launch(ioDispatcher) {
            try {
                daybreakWriteMutex.withLock {
                    // A write that waited on the mutex across a profile switch must not
                    // be decorated with the new profile: skip it entirely.
                    if (capturedScope != dataScopeFlow.value) return@withLock
                    val result =
                        request(
                            ConfigSetParams(
                                key = DAYBREAK_CONFIG_KEY,
                                value = on.toString(),
                            ),
                        )
                    val responseMap = result?.toAny() as? Map<*, *>
                    val saved = parseDaybreakConfigValue(responseMap)
                    if (saved == null || responseMap?.get("key") != DAYBREAK_CONFIG_KEY) {
                        throw IllegalStateException("Invalid daybreak config.set response")
                    }
                    if (capturedScope == dataScopeFlow.value) {
                        daybreakConfirmed = saved
                    }
                    if (writeSeq == daybreakWriteSeq && capturedScope == dataScopeFlow.value) {
                        uiState.update { it.copy(daybreakEnabled = saved) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (writeSeq == daybreakWriteSeq && capturedScope == dataScopeFlow.value) {
                    uiState.update {
                        it.copy(
                            daybreakEnabled = daybreakConfirmed,
                            errorMessage = "Could not change Daybreak: ${e.message ?: e.toString()}",
                        )
                    }
                }
            } finally {
                // Old-scope completions must not publish into the new scope's pending flag.
                if (generation == daybreakGeneration) {
                    daybreakPendingWrites--
                    uiState.update { it.copy(daybreakPending = daybreakPendingWrites > 0) }
                }
            }
        }
    }

    fun handleConfigSetError(
        id: String,
        error: Any?,
    ) {
        val pendingModel = pendingModelSwitchRequests.remove(id)
        if (pendingModel != null && ownsRequest(pendingModel)) {
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
                        isModelResetPending = false,
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
                        isModelResetPending = false,
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
        uiState.value.isModelResetPending ||
            optimisticPreviousModel != null ||
            unconfirmedTargetModel != null ||
            activeModelSwitchConfirmation != null

    fun onModelConfirmed(modelLabel: String) {
        lastConfirmedModel = modelLabel
        optimisticPreviousModel = null
        unconfirmedTargetModel = null
    }

    /**
     * Resets in-flight transient model, fast, and reasoning switch operations.
     * Increments sequence counters so late callbacks cannot regain ownership after disconnect.
     * Unlike [reset], does not discard cached model options, data scope, lastConfirmedModel, or model info capabilities.
     */
    fun resetTransientOperations() {
        pendingModelSwitchRequests.clear()
        pendingFastSwitchRequests.clear()
        activeModelSwitchConfirmation = null
        optimisticPreviousModel = null
        unconfirmedTargetModel = null
        modelSwitchSequence++
        reasoningOpSequence++
        reasoningJob?.cancel()
        reasoningJob = null
        uiState.update {
            it.copy(
                modelSwitchConfirmMessage = null,
                isFastModeChanging = false,
                isModelResetPending = false,
                pendingReasoningLevel = null,
                reasoningWireLevel = null,
            )
        }
    }

    fun reset() {
        resetTransientOperations()
        lastConfirmedModel = null
        modelInfoCapabilities = null
        modelInfoModel = null
        modelInfoScope = null
    }

    fun setReasoningLevel(
        level: String?,
        scopeName: String = "session",
        enforceCapabilities: Boolean = true,
    ) {
        if (level == null) return
        val currentPending = uiState.value.pendingReasoningLevel
        if (currentPending != null) return // One reasoning write at a time
        val sessionId = runtimeSessionId() ?: return
        if (sessionId.isBlank()) return
        if (scopeName != "session" && scopeName != "global") return

        // Capability validation
        if (enforceCapabilities) {
            val caps = uiState.value.currentModelCapabilities
            if (caps?.reasoningSupport == false) return
            if (level == "none" && caps?.can_disable_reasoning == false) return
        }

        val opSeq = ++reasoningOpSequence
        val capturedScope = dataScopeFlow.value
        // The old wire value belongs to the previous requested effort. Clear it
        // before starting the RPC so neither possible ordering can lie:
        //   ACK first -> wire stays unknown until session.info arrives.
        //   session.info first -> its fresh wire survives the later ACK.
        uiState.update {
            it.copy(
                pendingReasoningLevel = level,
                reasoningWireLevel = null,
            )
        }

        reasoningJob?.cancel()
        val job =
            scope.launch(ioDispatcher) {
                try {
                    val params =
                        ConfigSetParams(
                            key = "reasoning",
                            value = level,
                            sessionId = sessionId,
                            scope = scopeName,
                        )
                    val result =
                        if (wsRequest != null) {
                            wsRequest.invoke(params)
                        } else {
                            wsSend(params) { reqId ->
                                trackRequest(reqId, RpcMethods.CONFIG_SET.name)
                            }
                            null
                        }

                    val ackLevel =
                        if (wsRequest != null) {
                            val map = result?.toAny() as? Map<*, *>
                            val resKey = map?.get("key") as? String
                            val resVal = map?.get("value") as? String
                            if (resKey != "reasoning" || resVal.isNullOrEmpty()) {
                                throw IllegalStateException("Invalid reasoning config.set response")
                            }
                            resVal
                        } else {
                            level
                        }

                    uiState.update { state ->
                        if (opSeq == reasoningOpSequence && capturedScope == dataScopeFlow.value) {
                            state.copy(
                                reasoningLevel = ackLevel,
                            )
                        } else {
                            state
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    uiState.update { state ->
                        if (opSeq == reasoningOpSequence && capturedScope == dataScopeFlow.value) {
                            state.copy(
                                errorMessage = "Could not confirm reasoning change: ${e.message ?: e.toString()}",
                            )
                        } else {
                            state
                        }
                    }
                } finally {
                    uiState.update { state ->
                        if (opSeq == reasoningOpSequence &&
                            capturedScope == dataScopeFlow.value &&
                            state.pendingReasoningLevel == level
                        ) {
                            state.copy(pendingReasoningLevel = null)
                        } else {
                            state
                        }
                    }
                }
            }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                uiState.update { state ->
                    if (opSeq == reasoningOpSequence &&
                        capturedScope == dataScopeFlow.value &&
                        state.pendingReasoningLevel == level
                    ) {
                        state.copy(pendingReasoningLevel = null)
                    } else {
                        state
                    }
                }
            }
        }
        reasoningJob = job
    }

    fun getModelCapabilities(
        providerSlug: String,
        modelName: String,
    ): ModelCapabilities? {
        if (cachedModelOptionsScope != dataScopeFlow.value) return null
        val catalog =
            cachedModelOptions
                .find { it.slug == providerSlug }
                ?.capabilities
                ?.get(modelName)
        val infoMatches =
            modelInfoScope == dataScopeFlow.value &&
                (modelInfoModel == "$providerSlug/$modelName" || modelInfoModel?.substringAfter('/') == modelName)
        val overlay = if (infoMatches) modelInfoCapabilities else null
        return catalog.mergedWithCanonical(overlay)
    }

    fun applyModelInfo(
        info: ModelInfoResponse,
        scope: DataScope?,
    ) {
        modelInfoCapabilities = info.capabilities
        modelInfoModel = info.provider?.let { "$it/${info.model.orEmpty()}" }
        modelInfoScope = scope
        syncCurrentModelCapabilities()
    }

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
                    val baseCaps = getModelCapabilities(provider, model)
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
