package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.ModelCapabilities
import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
    private var pinnedList = mutableListOf(PinnedModel("anthropic", "claude-3"))

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
            getModelOptionsCall = { NetworkResult.Success(fakeResponse) },
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
    fun sendSlashModel_optimisticallyUpdatesModelAndDispatchesSlash() {
        delegate.sendSlashModel("openai", "gpt-4o")
        assertEquals("openai/gpt-4o", uiState.value.currentSessionModel)
        assertFalse(uiState.value.showModelPicker)
        assertEquals(listOf("/model gpt-4o --provider openai --session"), slashCommands)
        assertEquals(1, contextRefetched)
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
}
