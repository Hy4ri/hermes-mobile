package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.JsonRpcError
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.ui.chat.fakes.FakeChatPersistenceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockEventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    private val mockConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    private lateinit var app: Application
    private lateinit var fakeRepo: FakeChatPersistenceRepository

    /** Counter used to generate unique WS request IDs. */
    private var reqCount = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val testMainDispatcher = Dispatchers.Main
        reqCount = 0

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher
        every { Dispatchers.Main } returns testMainDispatcher

        mockkObject(AuthManager)
        every { AuthManager.getPinnedModels() } returns emptyList()
        mockkObject(HermesWsClient)
        mockkObject(ApiClient)
        mockkObject(HermesDatabase)

        app = mockk(relaxed = true)
        fakeRepo = FakeChatPersistenceRepository()

        mockConnectionStatus.value = ConnectionStatus.DISCONNECTED

        every { AuthManager.getToken() } returns "test-token"
        every { AuthManager.isTypingEffectEnabled() } returns true
        every { AuthManager.getTypingEffectDelayMs() } returns 30
        every { AuthManager.isAutoReconnect() } returns false
        every { HermesWsClient.events } returns mockEventsFlow
        every { HermesWsClient.connectionStatus } returns mockConnectionStatus
        every { HermesWsClient.connect() } answers {
            mockConnectionStatus.value = ConnectionStatus.CONNECTING
        }
        every { HermesWsClient.disconnect() } returns Unit

        // Default send stub: generates unique IDs and invokes onSent callback
        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        every { HermesWsClient.sendMessage(any(), any(), any()) } answers {
            reqCount++
            val id = "req-msg-$reqCount"
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }

        // Stub model-options so preloadModelOptions() (fired at GatewayReady) is safe.
        val mockApi = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        coEvery {
            mockApi.getModelOptions(any(), any())
        } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.ModelOptionsResponse(
                    providers =
                        listOf(
                            com.m57.hermescontrol.data.model.ModelProvider(
                                slug = "openai",
                                name = "OpenAI",
                                models = listOf("gpt-4o", "gpt-4o-mini"),
                            ),
                            com.m57.hermescontrol.data.model.ModelProvider(
                                slug = "anthropic",
                                name = "Anthropic",
                                models = listOf("claude-3-5-sonnet"),
                            ),
                        ),
                ),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Create a ViewModel with the fake repo injected directly. */
    private fun createViewModel(startCleanup: Boolean = false): ChatViewModel =
        ChatViewModel(app, startCleanup, fakeRepo, testDispatcher)

    /**
     * Create ViewModel, simulate GatewayReady, feed SESSION_CREATE result,
     * and return a Pair(viewModel, sessionId).
     *
     * Request ID sequence: GatewayReady triggers loadSessions (req-id-1),
     * fetchCommandCatalog (req-id-2), then createNewSession (req-id-3).
     */
    private suspend fun TestScope.createViewModelWithSession(
        startCleanup: Boolean = false,
    ): Pair<ChatViewModel, String> {
        val viewModel = createViewModel(startCleanup)
        advanceUntilIdle()

        mockConnectionStatus.value = ConnectionStatus.CONNECTED
        mockEventsFlow.emit(WsEvent.GatewayReady(null))
        advanceUntilIdle()

        // Emit SESSION_CREATE result (req-id-3 — after loadSessions and fetchCommandCatalog)
        mockEventsFlow.emit(WsEvent.RpcResult("req-id-3", mapOf("session_id" to "session-123")))
        advanceUntilIdle()

        // Sanity check: confirm the session was actually set
        val session = viewModel.uiState.value.currentSessionId
        checkNotNull(session) {
            "createViewModelWithSession: session was not set — " +
                "req-id-3 did not match SESSION_CREATE. " +
                "If the req sequence changed, update the RpcResult id here."
        }

        return Pair(viewModel, "session-123")
    }

    // ── Slash command tests ──────────────────────────────────────────────────

    @Test
    fun testSlashCommand_help_addsHelpMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            every {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            } returns
                CompletableDeferred(
                    mapOf("type" to "exec", "output" to "**Available Commands:**\n\u2022 `/status`\n\u2022 `/new`"),
                )

            viewModel.sendMessage("/help")
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content.contains("Available Commands") },
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content.contains("/status") },
            )
        }

    @Test
    fun testSlashCommand_new_createsNewSession() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/new")
            advanceUntilIdle()

            verify(atLeast = 1) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun testSlashCommand_fork_sendsSessionBranch() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            val captured = mutableListOf<Pair<String, Map<String, Any>>>()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                val id = "req-${captured.size + 1}"
                captured.add(arg<String>(0) to (arg<Map<String, Any>>(1)))
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            // /fork with an optional branch title.
            viewModel.sendMessage("/fork my-fork")
            advanceUntilIdle()

            val branchSent = captured.firstOrNull { it.first == WsMethods.SESSION_BRANCH }
            assertNotNull("session.branch should be dispatched for /fork", branchSent)
            assertEquals(sessionId, branchSent!!.second["session_id"])
            assertEquals("my-fork", branchSent.second["name"])
        }

    @Test
    fun testSlashCommand_fork_withoutName_omitsNameParam() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            val captured = mutableListOf<Pair<String, Map<String, Any>>>()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                val id = "req-${captured.size + 1}"
                captured.add(arg<String>(0) to (arg<Map<String, Any>>(1)))
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("/fork")
            advanceUntilIdle()

            val branchSent = captured.firstOrNull { it.first == WsMethods.SESSION_BRANCH }
            assertNotNull("session.branch should be dispatched for /fork", branchSent)
            assertEquals(sessionId, branchSent!!.second["session_id"])
            assertFalse("name param should be omitted when no title given", branchSent.second.containsKey("name"))
        }

    @Test
    fun testSlashCommand_stop_sendsInterrupt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/stop")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    @Test
    fun testSlashCommand_interrupt_sendsInterrupt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/interrupt")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    @Test
    fun testSlashCommand_unknown_showsErrorMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            var dispatchReqId = "dispatch-unk"

            every { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(dispatchReqId)
                dispatchReqId
            }

            viewModel.sendMessage("/nonexistent")
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.RpcError(
                    dispatchReqId,
                    JsonRpcError(code = -32601, message = "Unknown command: nonexistent"),
                ),
            )
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.contains("Unknown command") == true,
            )
        }

    @Test
    fun testSlashCommandStatusRoutesToSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/status")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) }
        }

    @Test
    fun testSlashCommandSessionsRoutesToSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/sessions")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) }
        }

    @Test
    fun testSlashCommandStatsRoutesToSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/stats")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) }
        }

    @Test
    fun testBareModelCommand_opensPickerInsteadOfDispatch() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            // A bare "/model" must NOT dispatch a slash command; it opens the picker.
            viewModel.sendMessage("/model")
            advanceUntilIdle()

            assertTrue(
                "picker should be shown when bare /model is typed",
                viewModel.uiState.value.showModelPicker,
            )
            assertTrue(
                "picker should have preloaded providers (cached at GatewayReady)",
                viewModel.uiState.value.modelPickerProviders
                    .isNotEmpty(),
            )
            verify(exactly = 0) { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) }
        }

    @Test
    fun testModelPickerSelection_hotSwapsCurrentSessionViaSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/model")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.showModelPicker)

            // Selecting a model must send the bare spec "gpt-4o --provider openai
            // --session" via the `config.set` RPC with key="model" (the gateway
            // routes key=="model" to _apply_model_switch; the /model prefix is
            // stripped before send because config.set does not parse slash
            // commands). NOT command.dispatch (4018s on /model), NOT prompt.submit
            // (LLM would treat it as text). Capture the config.set params.
            val modelCalls = mutableListOf<Triple<String, String, String>>()
            every { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) } answers {
                val params = arg<Map<String, Any>>(1)
                modelCalls.add(
                    Triple(
                        params["key"] as String,
                        params["value"] as String,
                        params["session_id"] as String,
                    ),
                )
                "req-cfg-${modelCalls.size}"
            }

            viewModel.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()

            assertFalse("picker closes after selection", viewModel.uiState.value.showModelPicker)
            assertEquals(
                "openai/gpt-4o",
                viewModel.uiState.value.currentSessionModel,
            )
            verify { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) }
            val call = modelCalls.firstOrNull { it.first == "model" }
            assertNotNull("selection must route through config.set key=model", call)
            assertEquals("gpt-4o --provider openai --session", call!!.second)
            assertEquals(sessionId, call.third)
        }

    @Test
    fun testTypedModelCommandWithArg_dispatchesDirectly() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // A fully-typed "/model <model> --provider <slug> --session" bypasses the
            // picker and dispatches straight to the backend as a normal prompt.
            viewModel.sendMessage("/model gpt-4o --provider openai --session")
            advanceUntilIdle()

            assertFalse(
                "typed /model with arg should not open the picker",
                viewModel.uiState.value.showModelPicker,
            )
            // A fully-typed /model goes to the backend via the `config.set` RPC
            // (key="model"), which the gateway routes to _apply_model_switch. NOT
            // command.dispatch (4018s on /model) and NOT prompt.submit (LLM would
            // treat it as text).
            verify { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) }
        }

    @Test
    fun testTypedModelCommand_caseInsensitive_doesNotForwardSlashPrefix() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // A fully-typed /MODEL (uppercase) must still route through
            // config.set key=model with the BARE spec — the leading "/MODEL"
            // slash prefix must be stripped, or parse_model_flags on the
            // backend won't recognize it and the hot-swap silently fails.
            val modelCalls = mutableListOf<Triple<String, String, String>>()
            every { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) } answers {
                val params = arg<Map<String, Any>>(1)
                modelCalls.add(
                    Triple(
                        params["key"] as String,
                        params["value"] as String,
                        params["session_id"] as String,
                    ),
                )
                "req-cfg-ci-${modelCalls.size}"
            }

            viewModel.sendMessage("/MODEL gpt-4o --provider openai --session")
            advanceUntilIdle()

            val call = modelCalls.firstOrNull { it.first == "model" }
            assertNotNull("uppercase /MODEL must route through config.set key=model", call)
            assertEquals(
                "slash prefix must be stripped before send",
                "gpt-4o --provider openai --session",
                call!!.second,
            )
            assertFalse(
                "value must not carry the literal /MODEL prefix",
                call.second.startsWith("/"),
            )
            assertEquals(sessionId, call.third)
        }

    // ── Connection / init tests ──────────────────────────────────────────────

    @Test
    fun testInitialStateAndConnection() =
        runTest {
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED

            createViewModel()
            advanceUntilIdle()

            verify { HermesWsClient.connect() }
        }

    @Test
    fun testAlreadyConnectedOnLaunch_createsSession() =
        runTest {
            mockConnectionStatus.value = ConnectionStatus.CONNECTED

            createViewModel()
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) }
            verify { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun testGatewayReady_createsSessionIfNoneExists() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) }
            verify { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
            assertTrue(viewModel.uiState.value.isConnected)
        }

    @Test
    fun testGatewayReady_withInitialSessionId_switchesToIt() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.initialSessionId = "session-from-notification"

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            assertEquals("session-from-notification", viewModel.uiState.value.currentSessionId)
            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to "session-from-notification"),
                    any(),
                )
            }
            // Should NOT create a new session
            verify(inverse = true) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    // ── RPC result tests ─────────────────────────────────────────────────────

    @Test
    fun testSessionCreateRpcResult() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // GatewayReady sends SESSION_LIST (req-id-1), COMMANDS_CATALOG (req-id-2),
            // then SESSION_CREATE (req-id-3)
            mockEventsFlow.emit(WsEvent.RpcResult("req-id-3", mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            assertEquals("session-123", viewModel.uiState.value.currentSessionId)
            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(1, viewModel.uiState.value.messages.size)
            assertEquals(
                "Session created",
                viewModel.uiState.value.messages[0]
                    .content,
            )
        }

    @Test
    fun testSessionListRpcResult() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // GatewayReady sends SESSION_LIST (req-id-1), COMMANDS_CATALOG (req-id-2),
            // then SESSION_CREATE (req-id-3). Emit the SESSION_LIST result.
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    "req-id-1",
                    mapOf(
                        "sessions" to
                            listOf(
                                mapOf(
                                    "id" to "session-123",
                                    "title" to "My Session Title",
                                    "message_count" to 12.0,
                                ),
                            ),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.sessions.size)
            assertEquals(
                "session-123",
                viewModel.uiState.value.sessions[0]
                    .id,
            )
            assertEquals(
                "My Session Title",
                viewModel.uiState.value.sessions[0]
                    .title,
            )
            assertEquals(
                12,
                viewModel.uiState.value.sessions[0]
                    .messageCount,
            )
        }

    // ── Streaming tests ──────────────────────────────────────────────────────

    @Test
    fun testMessageStreamingFlow() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // 1 — Start: reducer creates streamingMessage and sets isAgentTyping on uiState
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isAgentTyping)
            assertNotNull(viewModel.streamingState.value.streamingMessage)

            // 2 — Thinking
            mockEventsFlow.emit(WsEvent.ThinkingDelta("Thinking...", sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isThinking)
            assertEquals("Thinking...", viewModel.streamingState.value.thinkingText)

            // 3 — Deeper thinking
            mockEventsFlow.emit(WsEvent.ThinkingDelta(" deeper", sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isThinking)
            assertEquals("Thinking... deeper", viewModel.streamingState.value.thinkingText)

            // 4 — First token (flushed by isTestEnvironment)
            mockEventsFlow.emit(WsEvent.MessageToken("Hello", sessionId))
            advanceUntilIdle()
            assertFalse(viewModel.streamingState.value.isThinking)
            assertEquals(
                "Hello",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )

            // 5 — Second token
            mockEventsFlow.emit(WsEvent.MessageToken(" world", sessionId))
            advanceUntilIdle()
            assertEquals(
                "Hello world",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )

            // 6 — Complete: reducer finalizes message + resets streamingState
            mockEventsFlow.emit(WsEvent.MessageComplete("Hello world!", sessionId))
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isAgentTyping)
            assertNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "Hello world!",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertFalse(
                viewModel.uiState.value.messages[1]
                    .isStreaming,
            )
        }

    @Test
    fun testReasoningStreamingFlow() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // 1 — Reasoning available: block becomes visible (no token yet)
            mockEventsFlow.emit(WsEvent.ReasoningAvailable(sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isReasoning)

            // 2 — Reasoning delta
            mockEventsFlow.emit(WsEvent.ReasoningDelta("Let me think", sessionId))
            advanceUntilIdle()
            assertEquals("Let me think", viewModel.streamingState.value.reasoningText)

            // 3 — Deeper reasoning
            mockEventsFlow.emit(WsEvent.ReasoningDelta(" step by step", sessionId))
            advanceUntilIdle()
            assertEquals("Let me think step by step", viewModel.streamingState.value.reasoningText)

            // 4 — Thinking still independent
            mockEventsFlow.emit(WsEvent.ThinkingDelta("thinking", sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isThinking)
            assertEquals("thinking", viewModel.streamingState.value.thinkingText)
            // reasoning untouched
            assertEquals("Let me think step by step", viewModel.streamingState.value.reasoningText)

            // 5 — Complete: reducer finalizes message, attaching reasoning
            mockEventsFlow.emit(WsEvent.MessageComplete("The answer is 42", sessionId))
            advanceUntilIdle()

            assertNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "The answer is 42",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            // reasoning carried onto the finalized UI message
            assertEquals(
                "Let me think step by step",
                viewModel.uiState.value.messages[1]
                    .reasoningText,
            )
            // reasoning persisted to the entity (survives reload)
            val persisted =
                fakeRepo.dao.getMessagesForSession(sessionId).first { it.role == "ASSISTANT" }
            assertEquals("Let me think step by step", persisted.reasoningText)
        }

    @Test
    fun testToolExecution_finalizesPreviousStreamingMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Calculating sum", sessionId))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.ToolStart("calculator", mapOf("input" to "2+2")))
            advanceUntilIdle()

            // messages[0] = "Session created" system message
            assertEquals(
                "Calculating sum",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.ASSISTANT,
                viewModel.uiState.value.messages[1]
                    .role,
            )
            assertEquals(
                MessageRole.TOOL,
                viewModel.uiState.value.messages[2]
                    .role,
            )
            assertNull(viewModel.streamingState.value.streamingMessage)
        }

    @Test
    fun testMessageStart_finalizesPreviousStreamingMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("First part", sessionId))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Second part", sessionId))
            advanceUntilIdle()

            // messages[0] = "Session created" system message
            assertEquals(
                "First part",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertFalse(
                viewModel.uiState.value.messages[1]
                    .isStreaming,
            )
            assertNotNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(
                "Second part",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
        }

    @Test
    fun testToolExecution_serializesDataAsJson() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ToolStart(
                    name = "calculator",
                    data = mapOf("input" to "2+2", "nested" to mapOf("key" to "value")),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                MessageRole.TOOL,
                viewModel.uiState.value.messages[1]
                    .role,
            )
            assertEquals(
                ToolStatus.RUNNING,
                viewModel.uiState.value.messages[1]
                    .toolStatus,
            )

            mockEventsFlow.emit(
                WsEvent.ToolComplete("calculator", mapOf("result" to "4", "exit_code" to 0)),
            )
            advanceUntilIdle()

            assertEquals(
                ToolStatus.COMPLETED,
                viewModel.uiState.value.messages[1]
                    .toolStatus,
            )
        }

    // ── Clarify tests ────────────────────────────────────────────────────────

    @Test
    fun testClarifyRequestAndRespond() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(WsEvent.ClarifyRequest("Please choose:", listOf("Yes", "No"), "clarify-123"))
            advanceUntilIdle()

            assertEquals(
                "Please choose:",
                viewModel.uiState.value.clarifyRequest
                    ?.text,
            )
            assertEquals(
                listOf("Yes", "No"),
                viewModel.uiState.value.clarifyRequest
                    ?.options,
            )
            assertEquals(
                "clarify-123",
                viewModel.uiState.value.clarifyRequest
                    ?.clarifyId,
            )

            viewModel.respondToClarify("Yes")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clarifyRequest)
            assertEquals(2, viewModel.uiState.value.messages.size)

            verify {
                HermesWsClient.send(
                    method = WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "response" to "Yes",
                            "answer" to "Yes",
                            "clarify_id" to "clarify-123",
                            "request_id" to "clarify-123",
                        ),
                    onSent = any(),
                )
            }
        }

    @Test
    fun testClarifyRequestCustomResponse() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(WsEvent.ClarifyRequest("Please explain:", emptyList(), "clarify-456"))
            advanceUntilIdle()

            assertEquals(
                "Please explain:",
                viewModel.uiState.value.clarifyRequest
                    ?.text,
            )
            assertTrue(
                viewModel.uiState.value.clarifyRequest
                    ?.options
                    ?.isEmpty() == true,
            )

            viewModel.respondToClarify("This is my custom response text")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clarifyRequest)
            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "This is my custom response text",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.USER,
                viewModel.uiState.value.messages[1]
                    .role,
            )

            verify {
                HermesWsClient.send(
                    WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "response" to "This is my custom response text",
                            "answer" to "This is my custom response text",
                            "clarify_id" to "clarify-456",
                            "request_id" to "clarify-456",
                        ),
                    onSent = any(),
                )
            }
        }

    @Test
    fun testClarifyDismissInformsAgent() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    "Please choose:",
                    listOf("Yes", "No"),
                    "clarify-789",
                ),
            )
            advanceUntilIdle()
            assertEquals(
                "clarify-789",
                viewModel.uiState.value.clarifyRequest
                    ?.clarifyId,
            )

            viewModel.dismissClarify()
            advanceUntilIdle()

            // Dialog dismissed locally
            assertNull(viewModel.uiState.value.clarifyRequest)
            // Baseline has 1 "Connected" system message; dismiss adds exactly
            // ONE system note and must NOT fake a user bubble.
            val messages = viewModel.uiState.value.messages
            assertEquals(2, messages.size)
            assertEquals(MessageRole.SYSTEM, messages[0].role) // pre-existing "Connected"
            assertEquals(MessageRole.SYSTEM, messages[1].role) // dismiss trace
            assertTrue(messages[1].content.contains("dismissed", ignoreCase = true))

            verify {
                HermesWsClient.send(
                    WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "response" to "The user cancelled — no answer provided.",
                            "answer" to "The user cancelled — no answer provided.",
                            "clarify_id" to "clarify-789",
                            "request_id" to "clarify-789",
                        ),
                    onSent = any(),
                )
            }
        }

    // ── Attachments ──────────────────────────────────────────────────────────

    /** Add [count] dummy attachments so a test starts with a populated list. */
    private fun TestScope.addDummyAttachments(
        viewModel: ChatViewModel,
        count: Int,
    ) {
        repeat(count) { i ->
            viewModel.addAttachment("uri$i", "file$i.txt", "text/plain", (i + 1) * 100L)
        }
        advanceUntilIdle()
    }

    @Test
    fun testAddAttachment() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment(
                uri = "content://dummy/1",
                name = "dummy.txt",
                mimeType = "text/plain",
                size = 1024L,
            )
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("content://dummy/1", pending[0].uri)
            assertEquals("dummy.txt", pending[0].name)
        }

    @Test
    fun testRemoveAttachment_validIndex() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment("uri1", "file1.txt", "text/plain", 100)
            viewModel.addAttachment("uri2", "file2.txt", "text/plain", 200)
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)

            viewModel.removeAttachment(0)
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("uri2", pending[0].uri)
        }

    @Test
    fun testRemoveAttachment_invalidIndex() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment("uri1", "file1.txt", "text/plain", 100)
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.pendingAttachments.size)

            // Out of bounds index should not crash or change list
            viewModel.removeAttachment(5)
            viewModel.removeAttachment(-1)
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("uri1", pending[0].uri)
        }

    @Test
    fun testRemoveAttachment_mixedValidAndInvalidSequence() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            addDummyAttachments(viewModel, 3) // [uri0, uri1, uri2]

            // 1. Remove a valid index (the middle item) → list shrinks correctly.
            viewModel.removeAttachment(1)
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)
            assertEquals(
                "uri0",
                viewModel.uiState.value.pendingAttachments[0]
                    .uri,
            )
            assertEquals(
                "uri2",
                viewModel.uiState.value.pendingAttachments[1]
                    .uri,
            )

            // 2. Fire invalid removals (out of bounds + negative) — must be no-ops.
            viewModel.removeAttachment(99)
            viewModel.removeAttachment(-1)
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)

            // 3. Another valid removal on the shifted list → still consistent.
            viewModel.removeAttachment(1)
            advanceUntilIdle()
            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("uri0", pending[0].uri)
        }

    @Test
    fun testClearAttachments() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment("uri1", "file1.txt", "text/plain", 100)
            viewModel.addAttachment("uri2", "file2.txt", "text/plain", 200)
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)

            viewModel.clearAttachments()
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertTrue(pending.isEmpty())
        }

    // ── Send message ─────────────────────────────────────────────────────────

    @Test
    fun testSendMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("Hello Hermes")
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "Hello Hermes",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.USER,
                viewModel.uiState.value.messages[1]
                    .role,
            )
            assertTrue(viewModel.uiState.value.isAgentTyping)

            verify { HermesWsClient.sendMessage(sessionId, "Hello Hermes", any()) }
        }

    // ── Session switch ───────────────────────────────────────────────────────

    @Test
    fun testSwitchSession() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertEquals("session-456", viewModel.uiState.value.currentSessionId)
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )

            verify { HermesWsClient.send(WsMethods.SESSION_RESUME, mapOf("session_id" to "session-456"), any()) }
        }

    @Test
    fun testInterruptSession_withSessionId_sendsRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.interruptSession()
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, mapOf("session_id" to sessionId), any()) }
        }

    @Test
    fun testInterruptSession_withoutSessionId_doesNotSendRpc() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.interruptSession()
            advanceUntilIdle()

            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    // ── Error handling ───────────────────────────────────────────────────────

    @Test
    fun testRpcErrorHandling() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.RpcError(
                    "req-id-1",
                    JsonRpcError(code = -32603, message = "Internal error during creation"),
                ),
            )
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.errorMessage!!
                    .contains("Internal error during creation"),
            )
        }

    // ── Session mismatch ─────────────────────────────────────────────────────

    @Test
    fun testSessionMismatchEventsAreIgnored() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ToolStart(name = "calculator", data = mapOf("input" to "2+2"), sessionId = "session-other"),
            )
            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = "Choose:",
                    options = listOf("Yes"),
                    clarifyId = "clarify-1",
                    sessionId = "session-other",
                ),
            )
            mockEventsFlow.emit(WsEvent.MessageStart("session-other"))
            mockEventsFlow.emit(WsEvent.MessageToken("Hello", "session-other"))
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.messages.size)
            assertEquals(
                "Session created",
                viewModel.uiState.value.messages[0]
                    .content,
            )
            assertNull(viewModel.streamingState.value.streamingMessage)
            assertNull(viewModel.uiState.value.clarifyRequest)
        }

    // ── Reconnect ────────────────────────────────────────────────────────────

    @Test
    fun testReconnectDoesNotDuplicateEventCollection() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.reconnect()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Hello", sessionId))
            advanceUntilIdle()

            assertEquals(
                "Hello",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
        }

    // ── MessageComplete without streaming ────────────────────────────────────

    @Test
    fun testMessageCompleteWithoutStreaming_upsertsAssistantMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(WsEvent.MessageComplete("Fully complete message", sessionId))
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "Fully complete message",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.ASSISTANT,
                viewModel.uiState.value.messages[1]
                    .role,
            )
        }

    // ── Approval flow ────────────────────────────────────────────────────────

    @Test
    fun testApprovalRequest_addsSystemMessage() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm -rf /data",
                    description = "The agent wants to execute: rm -rf /data",
                    patternKeys = listOf("shell:rm"),
                    sessionId = null,
                ),
            )
            advanceUntilIdle()

            val msg =
                viewModel.uiState.value.messages
                    .first { it.content.contains("Approval Required") }
            assertNotNull(msg.approvalInfo)
            assertEquals("rm -rf /data", msg.approvalInfo?.command)
        }

    @Test
    fun testRespondToApproval_sendsRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm",
                    description = "Dangerous command",
                    patternKeys = null,
                    sessionId = null,
                ),
            )
            advanceUntilIdle()

            viewModel.respondToApproval("approve")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.APPROVAL_RESPOND, any(), any()) }
        }

    @Test
    fun testRespondToApproval_clearsButtons() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm",
                    description = "Dangerous",
                    patternKeys = null,
                    sessionId = null,
                ),
            )
            advanceUntilIdle()

            val approvalMsg =
                viewModel.uiState.value.messages
                    .firstOrNull { it.approvalInfo != null }
            assertNotNull(approvalMsg)

            viewModel.respondToApproval("approve")
            advanceUntilIdle()

            val msgAfter =
                viewModel.uiState.value.messages
                    .firstOrNull { it.id == approvalMsg!!.id }
            assertNotNull(msgAfter)
            assertNull(msgAfter!!.approvalInfo)
        }

    // ── Sudo / secret prompt flow (issue #524) ───────────────────────────

    @Test
    fun testSudoRequest_setsPromptState() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null),
            )
            advanceUntilIdle()

            val prompt = viewModel.uiState.value.sudoPrompt
            assertNotNull(prompt)
            assertEquals("sudo-1", prompt?.requestId)
        }

    @Test
    fun testRespondToSudo_sendsRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null),
            )
            advanceUntilIdle()

            viewModel.respondToSudo("hunter2")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.SUDO_RESPOND,
                    withArg { params ->
                        assertEquals(sessionId, params["session_id"])
                        assertEquals("hunter2", params["password"])
                        assertEquals("sudo-1", params["request_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testRespondToSudo_clearsPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null),
            )
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sudoPrompt)

            viewModel.respondToSudo("hunter2")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun testSecretRequest_setsPromptState() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SecretRequest(requestId = "secret-1", sessionId = null),
            )
            advanceUntilIdle()

            val prompt = viewModel.uiState.value.secretPrompt
            assertNotNull(prompt)
            assertEquals("secret-1", prompt?.requestId)
        }

    @Test
    fun testRespondToSecret_sendsRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SecretRequest(requestId = "secret-1", sessionId = null),
            )
            advanceUntilIdle()

            viewModel.respondToSecret("super-secret-token")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.SECRET_RESPOND,
                    withArg { params ->
                        assertEquals(sessionId, params["session_id"])
                        assertEquals("super-secret-token", params["value"])
                        assertEquals("secret-1", params["request_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testRespondToSecret_clearsPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SecretRequest(requestId = "secret-1", sessionId = null),
            )
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.secretPrompt)

            viewModel.respondToSecret("super-secret-token")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.secretPrompt)
        }

    // ── Settings ─────────────────────────────────────────────────────────────

    @Test
    fun testRefreshSettings_updatesUiState() =
        runTest {
            // Given the default setup, init{} already calls refreshSettings() once,
            // so the initial state reflects the setUp defaults (typingEffectEnabled=true,
            // typingEffectDelayMs=30).
            val viewModel = createViewModel()
            advanceUntilIdle()
            with(viewModel.uiState.value) {
                assertTrue(typingEffectEnabled)
                assertEquals(30, typingEffectDelayMs)
            }

            // When settings change after construction and refreshSettings() is re-invoked,
            // the UI state must reflect the NEW values — this proves refreshSettings()
            // re-reads AuthManager live (the real regression scenario).
            every { AuthManager.isTypingEffectEnabled() } returns false
            every { AuthManager.getTypingEffectDelayMs() } returns 50
            viewModel.refreshSettings()
            advanceUntilIdle()

            // Then
            val state = viewModel.uiState.value
            assertFalse(state.typingEffectEnabled)
            assertEquals(50, state.typingEffectDelayMs)
        }

    @Test
    fun testToggleSearch() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isSearchActive)

            viewModel.toggleSearch()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isSearchActive)

            viewModel.setSearchQuery("test")
            advanceUntilIdle()

            viewModel.toggleSearch()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isSearchActive)
            assertEquals("", viewModel.uiState.value.searchQuery)
        }

    @Test
    fun testSendMessage_readContentUriThrowsException_handlesGracefully() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // Android framework Uri.parse throws "not mocked" in plain unit tests,
            // so stub it (no Robolectric here). Then make the resolver throw on read.
            mockkStatic(Uri::class)
            val mockUri = mockk<Uri>()
            every { Uri.parse("content://dummy") } returns mockUri

            val contentResolver = mockk<ContentResolver>()
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(any()) } throws
                SecurityException("Permission denied")

            viewModel.addAttachment("content://dummy", "test.png", "image/png", 1000)
            advanceUntilIdle()

            viewModel.sendMessage("Here is an image")
            advanceUntilIdle()

            // Error path is logged so we can diagnose the failed read.
            verify { Log.e(any(), match { it.contains("Permission denied") }, any()) }

            // Graceful handling: the message is still sent even though the
            // attachment bytes couldn't be read (no crash, no lost message).
            val sent =
                viewModel.uiState.value.messages
                    .firstOrNull { it.id == sessionId } != null ||
                    viewModel.uiState.value.messages
                        .any { it.content == "Here is an image" }
            assertTrue("Message should still be sent despite attachment read failure", sent)
        }

    /**
     * Regression for the "session not found" (code 4001) error when sending an
     * image: the mobile image-attach path must pass `session_id` to
     * `image.attach_bytes` (the gateway resolves the session from it; desktop
     * does the same). Without it the backend 4001s and the image is dropped.
     * Also asserts the image attach is AWAITED (staged before prompt.submit),
     * not fire-and-forget.
     */
    @Test
    fun testSendMessage_imageAttachment_sendsSessionId() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // sendRpcAndAwait calls HermesWsClient.request(method, params) — the
            // 2-arg form (Kotlin synthesizes request(String, Map)). Stub that
            // exact signature, capture params, and return a completed deferred
            // so the await resolves without hanging.
            val paramsSlot = slot<Map<String, Any>>()
            every {
                HermesWsClient.request(any(), capture(paramsSlot), any())
            } returns CompletableDeferred<Any?>(mapOf("attached" to true))

            // Mock Android's Base64OutputStream constructor to avoid "Stub!" exception in JVM tests
            io.mockk.mockkConstructor(android.util.Base64OutputStream::class)
            every {
                anyConstructed<android.util.Base64OutputStream>().write(
                    any<ByteArray>(),
                    any(),
                    any(),
                )
            } returns Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit

            // The image bytes read via ContentResolver must succeed.
            mockkStatic(Uri::class)
            val mockUri = mockk<Uri>()
            every { Uri.parse("content://dummy") } returns mockUri
            val contentResolver = mockk<ContentResolver>()
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(any()) } returns
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))

            viewModel.addAttachment("content://dummy", "test.png", "image/png", 1000)
            advanceUntilIdle()
            assertTrue(
                "pendingAttachments must contain the image before send",
                viewModel.uiState.value.pendingAttachments
                    .isNotEmpty(),
            )
            assertTrue(
                "attached png must have isImage=true",
                viewModel.uiState.value.pendingAttachments
                    .first()
                    .isImage,
            )

            viewModel.sendMessage("Here is an image")
            advanceUntilIdle()

            // Verify the image attach RPC was issued with session_id.
            verify { HermesWsClient.request(WsMethods.IMAGE_ATTACH_BYTES, any()) }
            assertEquals(
                "session_id must be forwarded to image.attach_bytes",
                sessionId,
                paramsSlot.captured["session_id"],
            )
        }

    // ── Pending request timeout + rejectAllPending (issue #526) ───────────

    /**
     * On disconnect (RECONNECTING) the ViewModel must run rejectAllPending
     * without throwing and stay usable — mirroring desktop
     * JsonRpcGatewayClient.rejectAllPending invoked on socket close. This is
     * what prevents callers awaiting a CompletableDeferred from hanging
     * across a socket drop.
     */
    @Test
    fun testDisconnect_rejectsPendingWithoutError() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm",
                    description = "Dangerous",
                    patternKeys = null,
                    sessionId = null,
                ),
            )
            advanceUntilIdle()
            viewModel.respondToApproval("approve")
            advanceUntilIdle()

            // Simulate socket drop → reconnecting (triggers rejectAllPending).
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            advanceUntilIdle()

            // No exception propagated; VM remains usable.
            assertNull(viewModel.uiState.value.errorMessage)
        }

    /**
     * viewModel.reconnect() calls rejectAllPending() before wsClient.disconnect(),
     * so any in-flight awaited RPC is failed fast instead of hanging until its
     * own timeout.
     */
    @Test
    fun testReconnect_rejectsPendingWithoutError() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm",
                    description = "Dangerous",
                    patternKeys = null,
                    sessionId = null,
                ),
            )
            advanceUntilIdle()
            viewModel.respondToApproval("approve")
            advanceUntilIdle()

            // User-initiated reconnect must not throw / hang.
            viewModel.reconnect()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.errorMessage)
            verify { HermesWsClient.disconnect() }
        }

    // ── History pagination guard (issue #674) ───────────────────────────────

    /**
     * When the initial REST page returns empty messages, hasOlderMessages
     * must be false — otherwise the UI shows a load-more button that fetches
     * the same empty page again, creating an infinite loop.
     */
    @Test
    fun testEmptyInitialRestPage_disablesOlderPagination() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            coEvery {
                mockApi.getSessions(any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionListResponse(
                        sessions =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionInfo(
                                    id = "session-456",
                                    title = "Test",
                                    message_count = 200,
                                ),
                            ),
                        total = 1,
                    ),
                )
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages = emptyList(),
                    ),
                )

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertFalse(
                "hasOlderMessages must be false when initial page is empty",
                viewModel.uiState.value.hasOlderMessages,
            )
            assertTrue(viewModel.uiState.value.messages.isEmpty())
            assertFalse(viewModel.uiState.value.isLoading)
        }
}
