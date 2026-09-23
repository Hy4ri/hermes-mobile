package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.ModelCapabilities
import com.m57.hermescontrol.data.model.ModelInfoResponse
import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatModelSwitchDelegateTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val uiState =
        MutableStateFlow(ChatUiState(currentSessionId = "sess-1", currentSessionModel = "anthropic/claude-3"))
    private var runtimeId: String? = "runtime-1"
    private val sentMethods = mutableListOf<String>()
    private val sentParams = mutableListOf<Map<String, Any>>()
    private val slashCommands = mutableListOf<String>()
    private val assistantMessages = mutableListOf<String>()
    private var contextRefetched = 0
    private var modelSwitchInitiated = 0
    private var pinnedList = mutableListOf(PinnedModel("anthropic", "claude-3"))
    private val modelRequests = mutableListOf<Boolean>()
    private var modelResponseGate: CompletableDeferred<Unit>? = null
    private var modelFailure: NetworkResult.Failure? = null

    private val initialDataScope =
        DataScope(
            connectionProfileId = "conn-1",
            baseUrl = "http://localhost:9119",
            activeProfileId = "default",
            inMemoryAuthGeneration = 1L,
        )

    private val dataScope = MutableStateFlow<DataScope?>(initialDataScope)

    private val fakeResponse =
        ModelOptionsResponse(
            providers =
                listOf(
                    ModelProvider(
                        slug = "openai",
                        name = "OpenAI",
                        models = listOf("gpt-4o"),
                        capabilities = mapOf("gpt-4o" to ModelCapabilities(fast = true, reasoning = true)),
                    ),
                ),
        )

    private val delegate =
        ChatModelSwitchDelegate(
            scope = testScope,
            ioDispatcher = testDispatcher,
            uiState = uiState,
            runtimeSessionId = { runtimeId },
            wsSend = { method, params, onSent ->
                sentMethods.add(method)
                sentParams.add(params)
                onSent?.invoke("req-${sentMethods.size}")
            },
            trackRequest = { _, _ -> },
            addAssistantMessage = { assistantMessages.add(it) },
            handleSlashCommand = { slashCommands.add(it) },
            fetchContextUsage = { contextRefetched++ },
            onModelSwitchInitiated = { modelSwitchInitiated++ },
            dataScopeFlow = dataScope,
            getModelOptionsCall = { refresh ->
                modelRequests.add(refresh)
                modelResponseGate?.await()
                modelFailure ?: NetworkResult.Success(fakeResponse)
            },
            getPinnedModels = { pinnedList },
            savePinnedModels = { pinnedList = it.toMutableList() },
        )

    @Test
    fun isModelPickerCommand_identifiesBareModelCommandOnly() {
        assertTrue(delegate.isModelPickerCommand("/model"))
        assertTrue(delegate.isModelPickerCommand("/MODEL "))
        assertTrue(delegate.isModelPickerCommand("  /model  "))
        assertFalse(delegate.isModelPickerCommand("/model openai/gpt-4o"))
        assertFalse(delegate.isModelPickerCommand("hello /model"))
    }

    @Test
    fun openModelPicker_andPreload_populatesProvidersAndPinned() =
        testScope.runTest {
            delegate.preloadModelOptions()
            advanceUntilIdle()

            delegate.openModelPicker()
            assertTrue(uiState.value.showModelPicker)
            assertEquals(1, uiState.value.modelPickerProviders.size)
            assertFalse(uiState.value.modelPickerLoading)
        }

    @Test
    fun openModelPicker_duringPreload_reusesRequestAndPublishesResult() =
        testScope.runTest {
            modelResponseGate = CompletableDeferred()
            delegate.preloadModelOptions()
            runCurrent()
            delegate.openModelPicker()
            runCurrent()

            val requestsWhileLoading = modelRequests.toList()
            modelResponseGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(false), requestsWhileLoading)
            assertEquals(fakeResponse.providers, uiState.value.modelPickerProviders)
            assertFalse(uiState.value.modelPickerLoading)
        }

    @Test
    fun coldOpen_usesServerCache_andReopenUsesMemoryCache() =
        testScope.runTest {
            delegate.openModelPicker()
            advanceUntilIdle()
            delegate.closeModelPicker()
            delegate.openModelPicker()
            assertFalse(uiState.value.modelPickerLoading)
            advanceUntilIdle()
            assertEquals(listOf(false), modelRequests)
            assertEquals(fakeResponse.providers, uiState.value.modelPickerProviders)
        }

    @Test
    fun scopeSwitch_clearsCachedModelsAndReloadsOpenPicker() =
        testScope.runTest {
            val scopeObserverJob = kotlinx.coroutines.Job()
            val observerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + scopeObserverJob)
            delegate.attachScopeObserver(observerScope)

            delegate.preloadModelOptions()
            advanceUntilIdle()
            delegate.openModelPicker()

            assertEquals(fakeResponse.providers, uiState.value.modelPickerProviders)
            assertFalse(uiState.value.modelPickerLoading)

            modelResponseGate = CompletableDeferred()
            dataScope.value = initialDataScope.copy(activeProfileId = "work")
            runCurrent()

            // Previous-scope rows disappear immediately, before the new
            // catalog request is allowed to complete.
            assertTrue(uiState.value.showModelPicker)
            assertTrue(uiState.value.modelPickerProviders.isEmpty())
            assertTrue(uiState.value.modelPickerLoading)
            assertEquals(listOf(false, false), modelRequests)

            modelResponseGate?.complete(Unit)
            advanceUntilIdle()

            assertEquals(fakeResponse.providers, uiState.value.modelPickerProviders)
            assertFalse(uiState.value.modelPickerLoading)

            scopeObserverJob.cancel()
        }

    @Test
    fun modelInfoCapabilities_mergeWithCatalogAndClearOnScopeSwitch() =
        testScope.runTest {
            val scopeObserverJob = kotlinx.coroutines.Job()
            val observerScope = kotlinx.coroutines.CoroutineScope(testDispatcher + scopeObserverJob)
            delegate.attachScopeObserver(observerScope)
            delegate.preloadModelOptions()
            advanceUntilIdle()
            uiState.update { it.copy(currentSessionModel = "openai/gpt-4o") }

            delegate.applyModelInfo(
                ModelInfoResponse(
                    provider = "openai",
                    model = "gpt-4o",
                    capabilities =
                        ModelCapabilities(
                            supports_tools = false,
                            supports_vision = true,
                            supports_reasoning = true,
                        ),
                ),
                initialDataScope,
            )

            assertEquals(true, uiState.value.currentModelCapabilities?.fast)
            assertEquals(false, uiState.value.currentModelCapabilities?.supports_tools)
            assertEquals(true, uiState.value.currentModelCapabilities?.supports_vision)

            dataScope.value = initialDataScope.copy(activeProfileId = "work")
            runCurrent()

            assertNull(uiState.value.currentModelCapabilities)
            scopeObserverJob.cancel()
        }

    @Test
    fun closeAndReopenDuringPreload_doesNotDuplicateOrReopenAfterDismiss() =
        testScope.runTest {
            modelResponseGate = CompletableDeferred()
            delegate.preloadModelOptions()
            runCurrent()
            delegate.openModelPicker()
            delegate.closeModelPicker()
            delegate.openModelPicker()
            delegate.closeModelPicker()
            runCurrent()
            modelResponseGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(false), modelRequests)
            assertFalse(uiState.value.showModelPicker)
            assertFalse(uiState.value.modelPickerLoading)
            assertEquals(fakeResponse.providers, uiState.value.modelPickerProviders)
        }

    @Test
    fun explicitRefresh_supersedesPreload() =
        testScope.runTest {
            modelResponseGate = CompletableDeferred()
            delegate.preloadModelOptions()
            runCurrent()
            delegate.refreshModelOptions()
            runCurrent()
            modelResponseGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(false, true), modelRequests)
            assertEquals(fakeResponse.providers, uiState.value.modelPickerProviders)
            assertFalse(uiState.value.modelPickerLoading)
        }

    @Test
    fun failedPreload_isSilentAndCanRetryOnOpen() =
        testScope.runTest {
            modelFailure = NetworkResult.Failure(NetworkError.Http(503, "Unavailable"))
            delegate.preloadModelOptions()
            advanceUntilIdle()
            assertNull(uiState.value.errorMessage)
            assertFalse(uiState.value.modelPickerLoading)

            modelFailure = null
            delegate.openModelPicker()
            advanceUntilIdle()
            assertEquals(listOf(false, false), modelRequests)
            assertEquals(fakeResponse.providers, uiState.value.modelPickerProviders)
        }

    @Test
    fun failedOpen_clearsLoadingAndReportsError() =
        testScope.runTest {
            modelFailure = NetworkResult.Failure(NetworkError.Http(503, "Unavailable"))
            delegate.openModelPicker()
            advanceUntilIdle()
            assertFalse(uiState.value.modelPickerLoading)
            assertEquals("Failed to load models: Unavailable", uiState.value.errorMessage)
        }

    @Test
    fun sendSlashModel_optimisticallyUpdatesModelAndDispatchesSlash() {
        delegate.sendSlashModel("openai", "gpt-4o")
        assertEquals("openai/gpt-4o", uiState.value.currentSessionModel)
        assertFalse(uiState.value.showModelPicker)
        assertEquals(listOf("/model gpt-4o --provider openai --session"), slashCommands)
        assertEquals(1, modelSwitchInitiated)
    }

    @Test
    fun sendSlashModel_withSameModel_doesNotInitiateSwitchOrBlankTokens() {
        uiState.value =
            uiState.value.copy(
                currentSessionModel = "openai/gpt-4o",
                fullContextTokens = 128_000L,
                showModelPicker = true,
            )
        val initialInitiated = modelSwitchInitiated
        delegate.sendSlashModel("openai", "gpt-4o")
        assertEquals("openai/gpt-4o", uiState.value.currentSessionModel)
        assertEquals(128_000L, uiState.value.fullContextTokens)
        assertEquals(initialInitiated, modelSwitchInitiated)
        assertEquals(emptyList<String>(), slashCommands)
        assertFalse(uiState.value.showModelPicker)
    }

    @Test
    fun handleModelSwitch_stripsLeadingSlashModel_andHandlesConfirmation() =
        testScope.runTest {
            delegate.handleModelSwitch("/MODEL gpt-4o --provider openai --session")
            advanceUntilIdle()

            assertEquals(listOf(WsMethods.CONFIG_SET), sentMethods)
            assertEquals("gpt-4o --provider openai --session", sentParams.first()["value"])

            delegate.handleConfigSetResult(
                id = "req-1",
                result =
                    mapOf(
                        "key" to "model",
                        "confirm_required" to true,
                        "confirm_message" to "High token cost ahead",
                    ),
            )
            assertEquals("High token cost ahead", uiState.value.modelSwitchConfirmMessage)

            delegate.confirmModelSwitchExpensive()
            advanceUntilIdle()
            assertEquals(2, sentMethods.size)
            assertEquals(true, sentParams[1]["confirm_expensive_model"])
        }

    @Test
    fun dismissModelSwitchConfirm_revertsToPreviousModel() =
        testScope.runTest {
            delegate.sendSlashModel("opencode-free", "muse-spark-1.3-contributor-free")
            advanceUntilIdle()
            // In real ChatViewModel, handleSlashCommand routes to handleModelSwitch:
            delegate.handleModelSwitch("/model muse-spark-1.3-contributor-free --provider opencode-free --session")
            advanceUntilIdle()
            delegate.handleConfigSetResult(
                id = "req-1",
                result = mapOf("key" to "model", "confirm_required" to true),
            )

            delegate.dismissModelSwitchConfirm()
            assertNull(uiState.value.modelSwitchConfirmMessage)
            assertEquals("anthropic/claude-3", uiState.value.currentSessionModel)
        }

    @Test
    fun handleConfigSetError_rollsBackToPreviousModel_onlyWhenMatchingCurrentSequence() =
        testScope.runTest {
            delegate.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()
            delegate.handleModelSwitch("/model gpt-4o --provider openai --session")
            advanceUntilIdle()
            assertEquals("openai/gpt-4o", uiState.value.currentSessionModel)

            // Current switch error rolls back
            delegate.handleConfigSetError("req-1", "Model unavailable")
            assertEquals("anthropic/claude-3", uiState.value.currentSessionModel)
            assertNull(uiState.value.fullContextTokens)
        }

    @Test
    fun toggleFastMode_sendsConfigSetFast_andUpdatesOnAck() =
        testScope.runTest {
            delegate.preloadModelOptions()
            advanceUntilIdle()

            // Switch to model with fast capability
            delegate.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()
            sentMethods.clear()
            sentParams.clear()

            assertTrue(uiState.value.currentModelCapabilities?.fast == true)
            assertFalse(uiState.value.fastMode)
            assertFalse(uiState.value.isFastModeChanging)

            delegate.toggleFastMode()
            advanceUntilIdle()
            assertTrue(uiState.value.isFastModeChanging)
            assertFalse(uiState.value.fastMode) // Still unconfirmed!
            assertEquals(listOf(WsMethods.CONFIG_SET), sentMethods)
            assertEquals("fast", sentParams.first()["key"])
            assertEquals("fast", sentParams.first()["value"])
            assertEquals("runtime-1", sentParams.first()["session_id"])

            // Backend acknowledges
            delegate.handleConfigSetResult("req-1", mapOf("key" to "fast", "value" to "fast"))
            assertTrue(uiState.value.fastMode)
            assertFalse(uiState.value.isFastModeChanging)

            // Toggle off
            delegate.toggleFastMode()
            advanceUntilIdle()
            assertTrue(uiState.value.isFastModeChanging)
            assertEquals("normal", sentParams.last()["value"])

            delegate.handleConfigSetResult("req-2", mapOf("key" to "fast", "value" to "normal"))
            assertFalse(uiState.value.fastMode)
            assertFalse(uiState.value.isFastModeChanging)
        }

    @Test
    fun toggleFastMode_whenRejectedByBackend_clearsChangingAndLatchesUnavailable() =
        testScope.runTest {
            delegate.preloadModelOptions()
            advanceUntilIdle()
            delegate.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()
            sentMethods.clear()
            sentParams.clear()
            assertTrue(uiState.value.currentModelCapabilities?.fast == true)

            delegate.toggleFastMode()
            advanceUntilIdle()
            assertTrue(uiState.value.isFastModeChanging)

            // Backend returns error indicating fast mode not available
            delegate.handleConfigSetError("req-1", mapOf("message" to "fast mode is not available for this model"))
            assertFalse(uiState.value.isFastModeChanging)
            assertFalse(uiState.value.fastMode)
            assertEquals(false, uiState.value.currentModelCapabilities?.fast)

            // Further toggle attempts are no-ops
            val sentCount = sentMethods.size
            delegate.toggleFastMode()
            advanceUntilIdle()
            assertEquals(sentCount, sentMethods.size)
        }

    @Test
    fun handleConfigSetResult_staleSequenceConfirmRequired_isIgnored() =
        testScope.runTest {
            delegate.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()
            delegate.handleModelSwitch("/model gpt-4o --provider openai --session")
            advanceUntilIdle()

            // A second switch arrives before first confirms
            delegate.sendSlashModel("anthropic", "claude-3-5-sonnet")
            advanceUntilIdle()
            delegate.handleModelSwitch("/model claude-3-5-sonnet --provider anthropic --session")
            advanceUntilIdle()

            // First switch response arrives with confirm_required
            delegate.handleConfigSetResult(
                id = "req-1",
                result = mapOf("key" to "model", "confirm_required" to true),
            )
            assertNull(uiState.value.modelSwitchConfirmMessage)
        }

    @Test
    fun handleConfigSetError_staleSequence_isIgnored() =
        testScope.runTest {
            delegate.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()
            delegate.handleModelSwitch("/model gpt-4o --provider openai --session")
            advanceUntilIdle()

            // Second switch arrives
            delegate.sendSlashModel("anthropic", "claude-3-5-sonnet")
            advanceUntilIdle()
            delegate.handleModelSwitch("/model claude-3-5-sonnet --provider anthropic --session")
            advanceUntilIdle()

            // First switch fails — should not roll back the second switch
            delegate.handleConfigSetError("req-1", "First switch failed")
            assertEquals("anthropic/claude-3-5-sonnet", uiState.value.currentSessionModel)
        }

    @Test
    fun setReasoningLevel_awaitsRequest_updatesOnAck_andPreventsConcurrentPick() =
        testScope.runTest {
            val gate = CompletableDeferred<Any?>()
            val reqMethods = mutableListOf<String>()
            val reqParams = mutableListOf<Map<String, Any>>()

            val customDelegate =
                ChatModelSwitchDelegate(
                    scope = testScope,
                    ioDispatcher = testDispatcher,
                    uiState = uiState,
                    runtimeSessionId = { runtimeId },
                    wsSend = { _, _, _ -> },
                    trackRequest = { _, _ -> },
                    addAssistantMessage = {},
                    handleSlashCommand = {},
                    fetchContextUsage = {},
                    dataScopeFlow = dataScope,
                    wsRequest = { method, params ->
                        reqMethods.add(method)
                        reqParams.add(params)
                        gate.await()
                    },
                )

            uiState.value = uiState.value.copy(reasoningLevel = "ultra", reasoningWireLevel = "max")

            customDelegate.setReasoningLevel("high")
            runCurrent()

            // Confirmed level remains unchanged until acknowledged. The stale
            // wire is cleared immediately so "High→Max" can never be painted.
            assertEquals("ultra", uiState.value.reasoningLevel)
            assertEquals("high", uiState.value.pendingReasoningLevel)
            assertNull(uiState.value.reasoningWireLevel)
            assertEquals(listOf(WsMethods.CONFIG_SET), reqMethods)
            assertEquals("session", reqParams.first()["scope"])
            assertEquals("high", reqParams.first()["value"])

            // Second pick while in-flight is rejected (serialized)
            customDelegate.setReasoningLevel("low")
            runCurrent()
            assertEquals(1, reqMethods.size)

            // Simulate session.info winning the race and publishing the fresh
            // authoritative wire before the RPC ACK resumes the delegate.
            uiState.value = uiState.value.copy(reasoningWireLevel = "high")

            // Production request() returns a raw JsonObject, not a Map.
            gate.complete(
                kotlinx.serialization.json.buildJsonObject {
                    put("key", kotlinx.serialization.json.JsonPrimitive("reasoning"))
                    put("value", kotlinx.serialization.json.JsonPrimitive("high"))
                },
            )
            advanceUntilIdle()

            assertEquals("high", uiState.value.reasoningLevel)
            assertNull(uiState.value.pendingReasoningLevel)
            // ACK must not erase a fresh wire that arrived first.
            assertEquals("high", uiState.value.reasoningWireLevel)
        }

    @Test
    fun setReasoningLevel_onFailure_clearsPendingAndSurfacesError() =
        testScope.runTest {
            val customDelegate =
                ChatModelSwitchDelegate(
                    scope = testScope,
                    ioDispatcher = testDispatcher,
                    uiState = uiState,
                    runtimeSessionId = { runtimeId },
                    wsSend = { _, _, _ -> },
                    trackRequest = { _, _ -> },
                    addAssistantMessage = {},
                    handleSlashCommand = {},
                    fetchContextUsage = {},
                    dataScopeFlow = dataScope,
                    wsRequest = { _, _ -> throw RuntimeException("4002 unknown reasoning value") },
                )

            customDelegate.setReasoningLevel("ultra")
            advanceUntilIdle()

            assertNull(uiState.value.reasoningLevel)
            assertNull(uiState.value.pendingReasoningLevel)
            assertTrue(uiState.value.errorMessage?.contains("4002") == true)
        }

    @Test
    fun setReasoningLevel_whenRuntimeSessionNullOrBlank_doesNotSend() =
        testScope.runTest {
            val reqMethods = mutableListOf<String>()
            runtimeId = null

            val customDelegate =
                ChatModelSwitchDelegate(
                    scope = testScope,
                    ioDispatcher = testDispatcher,
                    uiState = uiState,
                    runtimeSessionId = { runtimeId },
                    wsSend = { _, _, _ -> },
                    trackRequest = { _, _ -> },
                    addAssistantMessage = {},
                    handleSlashCommand = {},
                    fetchContextUsage = {},
                    dataScopeFlow = dataScope,
                    wsRequest = { method, _ ->
                        reqMethods.add(method)
                        null
                    },
                )

            customDelegate.setReasoningLevel("high")
            advanceUntilIdle()

            assertTrue(reqMethods.isEmpty())
            assertNull(uiState.value.pendingReasoningLevel)
            assertNull(uiState.value.reasoningLevel)
        }

    @Test
    fun setReasoningLevel_staleScopeOrReset_dropsLateAck() =
        testScope.runTest {
            val gate = CompletableDeferred<Any?>()
            val customDelegate =
                ChatModelSwitchDelegate(
                    scope = testScope,
                    ioDispatcher = testDispatcher,
                    uiState = uiState,
                    runtimeSessionId = { runtimeId },
                    wsSend = { _, _, _ -> },
                    trackRequest = { _, _ -> },
                    addAssistantMessage = {},
                    handleSlashCommand = {},
                    fetchContextUsage = {},
                    dataScopeFlow = dataScope,
                    wsRequest = { _, _ -> gate.await() },
                )

            customDelegate.setReasoningLevel("high")
            runCurrent()
            assertEquals("high", uiState.value.pendingReasoningLevel)

            // Reset called while in-flight
            customDelegate.reset()
            assertNull(uiState.value.pendingReasoningLevel)

            // Late RPC response arrives
            gate.complete(mapOf("key" to "reasoning", "value" to "high"))
            advanceUntilIdle()

            assertNull(uiState.value.reasoningLevel)
            assertNull(uiState.value.pendingReasoningLevel)
        }

    @Test
    fun rapidPicks_preservesOriginalConfirmedModelForRollback() =
        testScope.runTest {
            // Pick model A
            delegate.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()
            delegate.handleModelSwitch("/model gpt-4o --provider openai --session")
            advanceUntilIdle()

            // Pick model B before A confirms
            delegate.sendSlashModel("anthropic", "claude-3-5-sonnet")
            advanceUntilIdle()
            delegate.handleModelSwitch("/model claude-3-5-sonnet --provider anthropic --session")
            advanceUntilIdle()

            // Dismiss confirmation on B — should revert to original model before both switches
            delegate.handleConfigSetResult(
                id = "req-2",
                result = mapOf("key" to "model", "confirm_required" to true),
            )
            delegate.dismissModelSwitchConfirm()
            assertEquals("anthropic/claude-3", uiState.value.currentSessionModel)
        }

    @Test
    fun handleConfigSetResult_modelWarningWithoutConfirm_addsAssistantMessage() =
        testScope.runTest {
            delegate.handleModelSwitch("/model gpt-69-sol --provider openai-codex --session")
            advanceUntilIdle()

            val warningText =
                "Note: `gpt-69-sol` was not found in the OpenAI Codex model listing. " +
                    "Similar models: `gpt-5.6-sol`"
            delegate.handleConfigSetResult(
                id = "req-1",
                result =
                    mapOf(
                        "key" to "model",
                        "value" to "gpt-69-sol",
                        "confirm_required" to false,
                        "warning" to warningText,
                    ),
            )

            assertEquals(1, assistantMessages.size)
            assertEquals("⚠ $warningText", assistantMessages.first())
        }

    @Test
    fun handleConfigSetResult_modelWarningWithExistingWarningEmoji_doesNotDuplicateEmoji() =
        testScope.runTest {
            delegate.handleModelSwitch("/model gpt-69-sol --provider openai-codex --session")
            advanceUntilIdle()

            val warningText = "⚠ Note: Already prefixed"
            delegate.handleConfigSetResult(
                id = "req-1",
                result =
                    mapOf(
                        "key" to "model",
                        "value" to "gpt-69-sol",
                        "confirm_required" to false,
                        "warning" to warningText,
                    ),
            )

            assertEquals(listOf(warningText), assistantMessages)
        }

    @Test
    fun handleConfigSetResult_staleSequenceWarning_isIgnored() =
        testScope.runTest {
            // First switch
            delegate.handleModelSwitch("/model gpt-4o --provider openai --session")
            advanceUntilIdle()

            // Second switch supersedes first
            delegate.handleModelSwitch("/model claude-3-5-sonnet --provider anthropic --session")
            advanceUntilIdle()

            // First switch response arrives with warning
            delegate.handleConfigSetResult(
                id = "req-1",
                result =
                    mapOf(
                        "key" to "model",
                        "value" to "gpt-4o",
                        "confirm_required" to false,
                        "warning" to "Stale warning",
                    ),
            )

            assertTrue(assistantMessages.isEmpty())
        }

    @Test
    fun handleConfigSetResult_blankWarning_doesNotAddAssistantMessage() =
        testScope.runTest {
            delegate.handleModelSwitch("/model gpt-4o --provider openai --session")
            advanceUntilIdle()

            delegate.handleConfigSetResult(
                id = "req-1",
                result =
                    mapOf(
                        "key" to "model",
                        "value" to "gpt-4o",
                        "confirm_required" to false,
                        "warning" to "   ",
                    ),
            )

            assertTrue(assistantMessages.isEmpty())
        }
}
