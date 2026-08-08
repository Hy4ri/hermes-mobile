package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.GatewayFile
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.data.remote.GatewayFileResult
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.JsonRpcError
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.ui.chat.fakes.FakeChatPersistenceRepository
import io.mockk.*
import io.mockk.coEvery
import io.mockk.coVerify
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
    private val mockSwitchFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private lateinit var app: Application
    private lateinit var fakeRepo: FakeChatPersistenceRepository

    /** Counter used to generate unique WS request IDs. */
    private var reqCount = 0

    @Test
    fun mergeTranscriptWithLive_collapsesDuplicateIdsKeepingLatestMessage() {
        val stale = ChatMessage(id = "duplicate-id", role = MessageRole.ASSISTANT, content = "stale")
        val latest = stale.copy(content = "latest")

        val merged = mergeTranscriptWithLive(emptyList(), listOf(stale, latest))

        assertEquals(listOf(latest), merged)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        reqCount = 0

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkObject(AuthManager)
        every { AuthManager.getPinnedModels() } returns emptyList()
        mockkObject(HermesWsClient)
        // Catch-all for unstubbed request() calls: the real implementation
        // registers a pending call and launches a 120s timeout job on the
        // singleton's real-IO wsScope. The timer outlives the test class,
        // fires after unmockkAll and crashes an unrelated later test with
        // MockK's "can't find stub" (UncaughtExceptionsBeforeTest). Mirror
        // the real request()'s send() delegation (tests verify send calls)
        // but skip the timer. Must COMPLETE (Unit): a never-completing
        // deferred freezes the VM's event collector at the SESSION_CREATE
        // session.usage await and lets its late completion race per-test
        // capture stubs (slash-focus flake, CI 31137581335 on 4645a2b).
        every { HermesWsClient.request(any(), any(), any()) } answers {
            HermesWsClient.send(arg(0), arg(1)) {}
            CompletableDeferred<Any?>(Unit)
        }
        mockkObject(ApiClient)
        mockkObject(HermesDatabase)

        app = mockk(relaxed = true)
        fakeRepo = FakeChatPersistenceRepository()
        ActiveSessionHolder.set(null)

        mockConnectionStatus.value = ConnectionStatus.DISCONNECTED

        every { AuthManager.getToken() } returns "test-token"
        every { AuthManager.getBaseUrl() } returns "http://test.local/"
        every { AuthManager.getSelectedProfileId() } returns null
        mockkObject(ProfileSwitchCoordinator)
        every { ProfileSwitchCoordinator.switched } returns mockSwitchFlow
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
        // Both dispatchers injected — a real ioDispatcher would race the
        // test scheduler (repo writes hop it) and shuffle RPC ordering.
        ChatViewModel(app, startCleanup, fakeRepo, testDispatcher, testDispatcher)

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
    fun profileSwitch_wipesOpenSessionForFreshStart() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            assertEquals(sessionId, viewModel.uiState.value.currentSessionId)

            // Profile switch fires → chat wipes the stale conversation. The
            // re-dialed socket's gateway.ready then auto-creates a FRESH
            // session in the new profile (handleGatewayReady, desktop
            // requestFreshSession parity).
            mockSwitchFlow.emit("meow")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.currentSessionId)
            assertEquals("Hermes", viewModel.uiState.value.chatTitle)
        }

    @Test
    fun profileSwitch_endToEnd_recreatesFreshSessionInNewProfile() =
        runTest {
            val (viewModel, oldSessionId) = createViewModelWithSession()
            assertEquals(oldSessionId, viewModel.uiState.value.currentSessionId)

            // 1. Switch fires → stale conversation wiped.
            mockSwitchFlow.emit("meow")
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.currentSessionId)

            // 2. The coordinator re-dials the socket: disconnect first (status
            //    transition), then gateway.ready → handleGatewayReady:
            //    loadSessions (req-id-4), fetchCommandCatalog (req-id-5),
            //    then createNewSession (req-id-6) — desktop requestFreshSession.
            //    (The first ready cycle consumed req-ids 1-3.)
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED
            advanceUntilIdle()
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // 3. Fresh session created → a NEW session id, not the old one.
            //    createNewSession is the LAST WS send of the ready cycle, so
            //    its id is the current counter value (robust against extra
            //    sends from the reconnect path).
            mockEventsFlow.emit(
                WsEvent.RpcResult("req-id-$reqCount", mapOf("session_id" to "session-meow")),
            )
            advanceUntilIdle()

            assertEquals("session-meow", viewModel.uiState.value.currentSessionId)
            assertTrue(viewModel.uiState.value.currentSessionId != oldSessionId)
            // The fresh session create goes through send() → WsProfileParams
            // injects the active profile (the WS profile-scoping seam).
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
    fun testSessionBranch_publishesRuntimeSessionId() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("branch-request")
                "branch-request"
            }

            viewModel.sendMessage("/fork")
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    "branch-request",
                    mapOf(
                        "session_id" to "runtime-branch",
                        "stored_session_id" to "stored-branch",
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("runtime-branch", ActiveSessionHolder.activeSessionId.value)
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
                    mapOf("session_id" to "session-from-notification", "omit_messages" to true),
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
    fun testToolExecution_sealsInterimTextAndStripsCompletePrefix() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Calculating sum", sessionId))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.ToolStart("calculator", mapOf("input" to "2+2")))
            advanceUntilIdle()

            // Issue #842: the interim text is sealed as its own bubble at
            // tool.start (desktop parity), the stream tail clears, and the
            // sealed id is tracked for the complete-prefix strip.
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
            assertFalse(viewModel.uiState.value.messages[1].isStreaming)
            assertEquals(
                MessageRole.TOOL,
                viewModel.uiState.value.messages[2]
                    .role,
            )
            assertNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(
                listOf(viewModel.uiState.value.messages[1].id),
                viewModel.streamingState.value.sealedOrphanIds,
            )

            // Finalize: the complete payload repeats the sealed commentary as
            // a prefix — the final bubble strips it, so each line appears once.
            mockEventsFlow.emit(WsEvent.MessageComplete("Calculating sum = 4", sessionId))
            advanceUntilIdle()
            val assistant =
                viewModel.uiState.value.messages.filter { it.role == MessageRole.ASSISTANT }
            assertEquals(2, assistant.size)
            assertEquals("Calculating sum", assistant[0].content)
            assertEquals(" = 4", assistant[1].content)
            assertFalse(assistant[1].isStreaming)
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
            ActiveSessionHolder.set(sessionId, "stale-session")

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
            assertEquals(sessionId, ActiveSessionHolder.resolveStoredSessionId(sessionId))

            verify { HermesWsClient.sendMessage(sessionId, "Hello Hermes", any()) }
        }

    @Test
    fun testSendMessageRedirectWhenStreaming() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // First send starts streaming
            viewModel.sendMessage("Hello Hermes")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isAgentTyping)

            // Second send while streaming triggers redirect
            viewModel.sendMessage("Wait, correction")
            advanceUntilIdle()

            verify { HermesWsClient.sendRedirect(sessionId, "Wait, correction", any()) }
        }

    // ── Session switch ───────────────────────────────────────────────────────

    @Test
    fun testSwitchSession_opensSelectedHistorySessionInsteadOfLatestDescendant() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            coEvery { ApiClient.hermesApi.getLatestDescendant("session-456") } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.LatestDescendantResponse(
                        requested_session_id = "session-456",
                        session_id = "unrelated-descendant",
                        changed = true,
                    ),
                )

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertEquals("session-456", viewModel.uiState.value.currentSessionId)
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )

            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to "session-456", "omit_messages" to true),
                    any(),
                )
            }
        }

    @Test
    fun testSwitchSession_ignoresLateResumeResultFromPreviousSelection() =
        runTest {
            stubEmptySessionRests("session-a", "session-b")
            val (viewModel, _) = createViewModelWithSession()
            val resumeRequests = mutableMapOf<String, String>()
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                val sessionId = arg<Map<String, String>>(1).getValue("session_id")
                val requestId = "resume-$sessionId"
                resumeRequests[sessionId] = requestId
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }

            viewModel.switchSession("session-a")
            runCurrent()
            viewModel.switchSession("session-b")
            runCurrent()
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeRequests.getValue("session-b"),
                    mapOf("session_id" to "runtime-b", "resumed" to "session-b"),
                ),
            )
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeRequests.getValue("session-a"),
                    mapOf("session_id" to "runtime-a", "resumed" to "session-a"),
                ),
            )
            advanceUntilIdle()

            assertEquals("session-b", viewModel.uiState.value.currentSessionId)
            assertEquals("runtime-b", ActiveSessionHolder.activeSessionId.value)
        }

    @Test
    fun testReconnect_ignoresSupersededResumeForSameSession() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            val resumeRequests = mutableListOf<String>()
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                val requestId = "resume-${resumeRequests.size + 1}"
                resumeRequests += requestId
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }

            viewModel.switchSession("session-a")
            runCurrent()
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            runCurrent()
            mockEventsFlow.emit(
                WsEvent.RpcResult(resumeRequests[1], mapOf("session_id" to "runtime-new")),
            )
            mockEventsFlow.emit(
                WsEvent.RpcResult(resumeRequests[0], mapOf("session_id" to "runtime-old")),
            )
            advanceUntilIdle()

            assertEquals("session-a", viewModel.uiState.value.currentSessionId)
            assertEquals("runtime-new", ActiveSessionHolder.activeSessionId.value)
        }

    @Test
    fun testSwitchSession_ignoresLateResumeErrorBeforeReducerMutation() =
        runTest {
            val mockApi = ApiClient.hermesApi
            val messagesA =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
            val messagesB =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
            every { AuthManager.getBaseUrl() } returns "http://test.local/"
            coEvery { mockApi.getSessions(any(), any(), any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionListResponse(sessions = emptyList(), total = 0),
                )
            coEvery { mockApi.getSessionMessages("session-a", any(), any(), any()) } coAnswers { messagesA.await() }
            coEvery { mockApi.getSessionMessages("session-b", any(), any(), any()) } coAnswers { messagesB.await() }

            val (viewModel, _) = createViewModelWithSession()
            val resumeRequests = mutableMapOf<String, String>()
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                val sessionId = arg<Map<String, String>>(1).getValue("session_id")
                val requestId = "resume-$sessionId"
                resumeRequests[sessionId] = requestId
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }

            viewModel.switchSession("session-a")
            runCurrent()
            viewModel.switchSession("session-b")
            runCurrent()
            mockEventsFlow.emit(
                WsEvent.RpcError(
                    resumeRequests.getValue("session-a"),
                    JsonRpcError(code = -32000, message = "stale resume failure"),
                ),
            )
            runCurrent()

            assertEquals("session-b", viewModel.uiState.value.currentSessionId)
            assertTrue(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.errorMessage)
            assertNull(viewModel.uiState.value.resumeError)

            val empty =
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(messages = emptyList()),
                )
            messagesA.complete(empty)
            messagesB.complete(empty)
            advanceUntilIdle()
        }

    @Test
    fun testSwitchSession_ignoresLateRestHydrationFromPreviousGeneration() =
        runTest {
            val mockApi = ApiClient.hermesApi
            val messagesA =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
            val messagesB =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
            every { AuthManager.getBaseUrl() } returns "http://test.local/"
            coEvery { mockApi.getSessions(any(), any(), any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionListResponse(sessions = emptyList(), total = 0),
                )
            coEvery { mockApi.getSessionMessages("session-a", any(), any(), any()) } coAnswers { messagesA.await() }
            coEvery { mockApi.getSessionMessages("session-b", any(), any(), any()) } coAnswers { messagesB.await() }

            val (viewModel, _) = createViewModelWithSession()
            viewModel.switchSession("session-a")
            runCurrent()
            viewModel.switchSession("session-b")
            runCurrent()

            messagesB.complete(
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    role = "assistant",
                                    content = JsonPrimitive("message-b"),
                                ),
                            ),
                    ),
                ),
            )
            runCurrent()
            messagesA.complete(
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    role = "assistant",
                                    content = JsonPrimitive("stale-message-a"),
                                ),
                            ),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("session-b", viewModel.uiState.value.currentSessionId)
            assertEquals(listOf("message-b"), viewModel.uiState.value.messages.map { it.content })
        }

    @Test
    fun testSwitchSession_clearsSessionBoundUiState() =
        runTest {
            stubEmptySessionRests("session-b")
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.ClarifyRequest("Choose", listOf("A"), "clarify-1", sessionId),
            )
            mockEventsFlow.emit(WsEvent.SudoRequest("sudo-1", sessionId))
            mockEventsFlow.emit(WsEvent.SecretRequest("secret-1", sessionId))
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("model" to "model-a", "provider" to "provider-a", "reasoning_effort" to "high"),
                ),
            )
            mockEventsFlow.emit(
                WsEvent.SubagentEvent(
                    type = "subagent.start",
                    payload = mapOf("subagent_id" to "sub-1", "goal" to "work"),
                    sessionId = sessionId,
                ),
            )
            mockEventsFlow.emit(
                WsEvent.ToolStart(
                    name = "todo",
                    data = mapOf("todos" to listOf(mapOf("id" to "todo-1", "content" to "work"))),
                    sessionId = sessionId,
                ),
            )
            mockEventsFlow.emit(WsEvent.GatewayError("old error"))
            viewModel.openModelPicker()
            runCurrent()

            val oldState = viewModel.uiState.value
            assertNotNull(oldState.clarifyRequest)
            assertNotNull(oldState.sudoPrompt)
            assertNotNull(oldState.secretPrompt)
            assertNotNull(oldState.currentSessionModel)
            assertTrue(oldState.subagentIndicators.isNotEmpty())
            assertTrue(oldState.todos.isNotEmpty())
            assertTrue(oldState.showModelPicker)
            assertNotNull(oldState.errorMessage)

            viewModel.switchSession("session-b")
            runCurrent()
            val state = viewModel.uiState.value

            assertEquals("session-b", state.currentSessionId)
            assertTrue(state.messages.isEmpty())
            assertNull(state.clarifyRequest)
            assertNull(state.sudoPrompt)
            assertNull(state.secretPrompt)
            assertNull(state.currentSessionModel)
            assertNull(state.reasoningLevel)
            assertTrue(state.subagentIndicators.isEmpty())
            assertTrue(state.todos.isEmpty())
            assertFalse(state.showModelPicker)
            assertNull(state.errorMessage)
            assertNull(state.resumeError)
        }

    @Test
    fun testCreateNewSession_invalidatesResumeAndClearsSessionStateBeforeSend() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            var resumeRequestId = ""
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                resumeRequestId = "resume-a"
                arg<((String) -> Unit)?>(2)?.invoke(resumeRequestId)
                resumeRequestId
            }
            viewModel.switchSession("session-a")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.SudoRequest("sudo-1", "session-a"))
            mockEventsFlow.emit(WsEvent.GatewayError("old error"))
            runCurrent()

            viewModel.createNewSession()
            mockEventsFlow.emit(
                WsEvent.RpcError(
                    resumeRequestId,
                    JsonRpcError(code = -32000, message = "stale resume failure"),
                ),
            )
            runCurrent()

            val state = viewModel.uiState.value
            assertNull(state.currentSessionId)
            assertNull(state.sudoPrompt)
            assertNull(state.errorMessage)
            assertNull(state.resumeError)
            assertTrue(state.isLoading)
        }

    @Test
    fun testSessionBranchResult_clearsSessionBoundUiState() =
        runTest {
            stubEmptySessionRests("branch-1")
            val (viewModel, sessionId) = createViewModelWithSession()
            val captured = captureSends()
            mockEventsFlow.emit(WsEvent.SecretRequest("secret-1", sessionId))
            mockEventsFlow.emit(
                WsEvent.SubagentEvent(
                    type = "subagent.start",
                    payload = mapOf("subagent_id" to "sub-1", "goal" to "work"),
                    sessionId = sessionId,
                ),
            )
            mockEventsFlow.emit(
                WsEvent.ToolStart(
                    name = "todo",
                    data = mapOf("todos" to listOf(mapOf("id" to "todo-1", "content" to "work"))),
                    sessionId = sessionId,
                ),
            )
            mockEventsFlow.emit(WsEvent.SessionInfo(mapOf("model" to "model-a", "provider" to "provider-a")))
            runCurrent()

            viewModel.sendMessage("/fork")
            runCurrent()
            val branchRequest = captured.last { it.first == WsMethods.SESSION_BRANCH }.second
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    branchRequest,
                    mapOf("session_id" to "branch-1", "title" to "Branch"),
                ),
            )
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals("branch-1", state.currentSessionId)
            assertNull(state.secretPrompt)
            assertNull(state.currentSessionModel)
            assertTrue(state.subagentIndicators.isEmpty())
            assertTrue(state.todos.isEmpty())
            assertNull(state.errorMessage)
        }

    // ── Session resume recovery (desktop parity: warm cache + bounded retry) ──

    /** Override the send stub to capture (method → id) pairs. */
    private fun captureSends(): MutableList<Pair<String, String>> {
        val captured = mutableListOf<Pair<String, String>>()
        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            captured.add(arg<String>(0) to id)
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        return captured
    }

    private fun stubSession456Rests(success: Boolean) {
        val mockApi = ApiClient.hermesApi
        // mapServerMessages reads AuthManager.getBaseUrl() on the SUCCESS path
        // before mapping any messages. mockkObject is spy-semantics: unstubbed
        // calls fall through to the REAL AuthManager, whose serverStore is null
        // unless another test class happened to init it earlier in the JVM
        // (the order-dependent flakiness). Stub it for determinism — same
        // pattern as E2eIntegrationTest / ApiClientTest.
        if (success) {
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
                                    message_count = 0,
                                ),
                            ),
                        total = 1,
                    ),
                )
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages = emptyList(),
                    ),
                )
        } else {
            // Relaxed mock returns a non-success response → NetworkResult.Failure.
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any())
            } returns
                retrofit2.Response.error(
                    500,
                    okhttp3.ResponseBody.create(null, "{\"detail\":\"boom\"}"),
                )
        }
    }

    private fun stubEmptySessionRests(vararg sessionIds: String) {
        val mockApi = ApiClient.hermesApi
        every { AuthManager.getBaseUrl() } returns "http://test.local/"
        coEvery { mockApi.getSessions(any(), any(), any()) } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionListResponse(
                    sessions =
                        sessionIds.map {
                            com.m57.hermescontrol.data.model.SessionInfo(
                                id = it,
                                title = it,
                                message_count = 0,
                            )
                        },
                    total = sessionIds.size,
                ),
            )
        sessionIds.forEach { sessionId ->
            coEvery { mockApi.getSessionMessages(sessionId, any(), any(), any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(messages = emptyList()),
                )
        }
    }

    @Test
    fun testSwitchSession_paintsWarmCache_andKeepsItWhenResumeExhausted() =
        runTest {
            // Seed the Room cache for the target session.
            fakeRepo.dao.addMessageDirect(
                com.m57.hermescontrol.data.local.ChatMessageEntity(
                    id = "cached-1",
                    sessionId = "session-456",
                    role = "user",
                    content = "Cached hello",
                    timestamp = 1L,
                ),
            )
            // Server keeps failing → bounded retries exhaust → resumeError.
            stubSession456Rests(success = false)

            val (viewModel, _) = createViewModelWithSession()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            // Warm cache painted and survived the exhausted retry cycle — the
            // screen never went blank, and the user gets history + an error.
            assertEquals(1, viewModel.uiState.value.messages.size)
            assertEquals("Cached hello", viewModel.uiState.value.messages[0].content)
            assertFalse(viewModel.uiState.value.isLoading)
            assertNotNull("resumeError must be set after retries exhaust", viewModel.uiState.value.resumeError)
            assertFalse(viewModel.uiState.value.isResumeRetrying)
        }

    /**
     * Regression for the spurious "Error: JsonRpcError(code=4001, message=session
     * not found, data=null)" snackbar when opening a session from history: the
     * chat sync effect fires fetchContextUsage() immediately on session switch,
     * BEFORE session.resume has registered the session on the gateway. The
     * context/usage RPCs resolve against the gateway's LIVE runtime registry
     * (_sess_nowait) and would 4001 on the storage id — so they must be skipped
     * until the resume result confirms the runtime id (which is then used).
     */
    @Test
    fun testSwitchSession_contextRpcSkippedUntilResumeConfirmsRuntimeId() =
        runTest {
            stubSession456Rests(success = true)

            // Awaited live-registry RPCs (sendRpcAndAwait → HermesWsClient.request).
            // Completed deferred so the awaits resolve; capture the params. Installed
            // BEFORE createViewModelWithSession so the SESSION_CREATE-era
            // fetchContextUsage() call is stubbed too (otherwise its unstubbed
            // invocation is still recorded and pollutes the verify(exactly = 0)).
            val paramsSlot = slot<Map<String, Any>>()
            every {
                HermesWsClient.request(any(), capture(paramsSlot), any())
            } returns CompletableDeferred<Any?>(emptyMap<String, Any?>())

            val (viewModel, _) = createViewModelWithSession()

            // Capture the session.resume request id so its result can be delivered.
            var resumeRequestId: String? = null
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                reqCount++
                val id = "resume-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                resumeRequestId = id
                id
            }

            // Switch to session-456: runtimeSessionId resets to null until the
            // resume result lands — the storage id is not (yet) live on the
            // gateway. ChatScreen's sync effect fires fetchContextUsage()
            // immediately on session switch — simulate it while the resume
            // result is still in flight. The live-registry RPCs must NOT fire
            // with the stale storage id (the gateway would 4001).
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            viewModel.fetchContextUsage()
            advanceUntilIdle()

            verify(exactly = 0) {
                HermesWsClient.request(
                    WsMethods.SESSION_CONTEXT_BREAKDOWN,
                    match { it["session_id"] == "session-456" },
                    any(),
                )
            }
            verify(exactly = 0) {
                HermesWsClient.request(
                    WsMethods.SESSION_USAGE,
                    match { it["session_id"] == "session-456" },
                    any(),
                )
            }

            // Resume result lands → runtime id confirmed → the RPCs fire with it.
            val resumeId = checkNotNull(resumeRequestId)
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeId,
                    mapOf(
                        "session_id" to "runtime-456",
                        "resumed" to "session-456",
                        "info" to emptyMap<String, Any?>(),
                    ),
                ),
            )
            advanceUntilIdle()

            verify {
                HermesWsClient.request(
                    WsMethods.SESSION_CONTEXT_BREAKDOWN,
                    match { it["session_id"] == "runtime-456" },
                    any(),
                )
            }
            verify {
                HermesWsClient.request(
                    WsMethods.SESSION_USAGE,
                    match { it["session_id"] == "runtime-456" },
                    any(),
                )
            }
            assertEquals("runtime-456", paramsSlot.captured["session_id"])
        }

    @Test
    fun testSessionResume_boundedRetry_thenExplicitError() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            // Drive one RPC failure per cycle; each failure arms a backoff retry.
            // Exhaustion needs MAX+1 failures: attempts 0..MAX-1 arm retries,
            // the MAX-th failure hits the exhausted latch.
            for (cycle in 1..(ChatViewModel.MAX_RESUME_RETRIES + 1)) {
                val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
                mockEventsFlow.emit(
                    WsEvent.RpcError(
                        resumeId,
                        JsonRpcError(code = -32000, message = "resume rejected"),
                    ),
                )
                advanceUntilIdle()
            }

            val resumeSends = captured.count { it.first == WsMethods.SESSION_RESUME }
            assertEquals(
                "initial + MAX_RESUME_RETRIES retries",
                ChatViewModel.MAX_RESUME_RETRIES + 1,
                resumeSends,
            )
            assertNotNull(viewModel.uiState.value.resumeError)
            assertFalse(viewModel.uiState.value.isResumeRetrying)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun testSessionResume_rpcAndRestFailuresCountAsOneCycle() =
        runTest {
            // REST fails + one RpcError lands while the retry is already armed —
            // they must count as ONE failure, so the retry budget is not
            // double-burned (5 sends total, not 4).
            stubSession456Rests(success = false)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            runCurrent()
            // First cycle: REST already failed and armed the retry; now the WS
            // reject for the same resume arrives while that job is pending.
            val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(
                WsEvent.RpcError(
                    resumeId,
                    JsonRpcError(code = -32000, message = "resume rejected"),
                ),
            )
            runCurrent()
            advanceUntilIdle()

            val resumeSends = captured.count { it.first == WsMethods.SESSION_RESUME }
            assertEquals(
                "initial + MAX_RESUME_RETRIES retries — no double-burn",
                ChatViewModel.MAX_RESUME_RETRIES + 1,
                resumeSends,
            )
            assertNotNull(viewModel.uiState.value.resumeError)
        }

    @Test
    fun testReconnectOnUnconfirmedSession_skipsDoomedResume() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            // Simulate a WS reconnect while sitting on the freshly created
            // session (never prompted — the gateway persists the DB row
            // lazily on the first prompt). The old code re-resumed the
            // storage key and the gateway 4007'd "session not found"
            // permanently; the retry could never fix it because the row only
            // appears once a prompt lands.
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            verify(exactly = 0) {
                HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any())
            }
            assertNull(
                "reconnect on an unconfirmed session must not error",
                viewModel.uiState.value.resumeError,
            )

            // Positive control: once the session HAS server presence (REST
            // 200 confirmed the row), a reconnect resumes it as before.
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to "session-456", "omit_messages" to true),
                    any(),
                )
            }
        }

    @Test
    fun testSessionInfoModelSwap_blanksStaleMeterAndRefetches() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            // Establish the OLD model's label through the real event path.
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("model" to "deepseek-v4-flash", "provider" to "opencode-go"),
                ),
            )
            advanceUntilIdle()
            assertEquals("opencode-go/deepseek-v4-flash", viewModel.uiState.value.currentSessionModel)

            // Fetch the meter so it carries the OLD model's window (1M).
            var contextMax = 1_000_000L
            var breakdownCalls = 0
            every { HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any()) } answers {
                breakdownCalls++
                CompletableDeferred<Any?>(
                    mapOf("context_max" to contextMax, "context_used" to 42025L),
                )
            }
            viewModel.fetchContextUsage()
            advanceUntilIdle()
            assertEquals(1_000_000L, viewModel.uiState.value.fullContextTokens)

            // The swap: the live agent hasn't warmed up yet, so the RPC comes
            // back empty — the meter must NOT fall back to the profile-scoped
            // REST window (which describes the OLD model). It stays hidden.
            contextMax = 0L
            val callsBeforeSwap = breakdownCalls
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("model" to "tencent/hy3:free", "provider" to "nous"),
                ),
            )
            advanceUntilIdle()

            assertEquals("nous/tencent/hy3:free", viewModel.uiState.value.currentSessionModel)
            assertTrue("model swap must re-fire the meter fetch", breakdownCalls > callsBeforeSwap)
            assertNull(
                "cold RPC after a swap must keep the meter hidden, not show the old window",
                viewModel.uiState.value.fullContextTokens,
            )

            // The runtime warms up — the next fetch lands the NEW model's window.
            contextMax = 262_144L
            viewModel.fetchContextUsage()
            advanceUntilIdle()
            assertEquals(262_144L, viewModel.uiState.value.fullContextTokens)
        }

    @Test
    fun testSessionInfoSameModel_keepsMeterUntouched() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            // Establish label + meter through the real paths.
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("model" to "tencent/hy3:free", "provider" to "nous"),
                ),
            )
            advanceUntilIdle()
            var breakdownCalls = 0
            every { HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any()) } answers {
                breakdownCalls++
                CompletableDeferred<Any?>(
                    mapOf("context_max" to 262_144L),
                )
            }
            viewModel.fetchContextUsage()
            advanceUntilIdle()
            assertEquals(262_144L, viewModel.uiState.value.fullContextTokens)
            val callsBefore = breakdownCalls

            // Identical model again — no swap, no blank, no refetch.
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("model" to "tencent/hy3:free", "provider" to "nous"),
                ),
            )
            advanceUntilIdle()

            assertEquals(callsBefore, breakdownCalls)
            assertEquals(262_144L, viewModel.uiState.value.fullContextTokens)
            assertEquals("nous/tencent/hy3:free", viewModel.uiState.value.currentSessionModel)
        }

    @Test
    fun testResumeNotFound_recoversWithNewSession() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            val createsBefore = captured.count { it.first == WsMethods.SESSION_CREATE }

            // Gateway definitively has no row for this session (RPC 4007) —
            // e.g. the session was deleted or pruned server-side.
            mockEventsFlow.emit(
                WsEvent.RpcError(resumeId, JsonRpcError(code = 4007, message = "session not found")),
            )
            advanceUntilIdle()

            // No dead-end popup: the app recovered by creating a fresh session.
            assertNull("4007 must not dead-end on resumeError", viewModel.uiState.value.resumeError)
            assertEquals(createsBefore + 1, captured.count { it.first == WsMethods.SESSION_CREATE })

            // A second reject for the same resume (the paired REST 404 lands
            // just after the WS reject) must not double-create.
            mockEventsFlow.emit(
                WsEvent.RpcError(resumeId, JsonRpcError(code = 4007, message = "session not found")),
            )
            advanceUntilIdle()
            assertEquals(createsBefore + 1, captured.count { it.first == WsMethods.SESSION_CREATE })

            // Land the recovery create → the user lands on the new session
            // with an explanatory notice (queued until the create result so
            // the create's message wipe can't swallow it).
            val createId = captured.last { it.first == WsMethods.SESSION_CREATE }.second
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    createId,
                    mapOf(
                        "session_id" to "runtime-new",
                        "stored_session_id" to "session-new",
                    ),
                ),
            )
            advanceUntilIdle()
            assertEquals("session-new", viewModel.uiState.value.currentSessionId)
            assertTrue(
                "recovery notice must be visible",
                viewModel.uiState.value.messages.any { it.content.contains("no longer available") },
            )
        }

    @Test
    fun testBranchResult_keepsStorageIdAsCurrentSession() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            // mapServerMessages reads AuthManager.getBaseUrl() unconditionally
            // on the REST-success path (mockkObject spy fall-through → real
            // uninitialized AuthManager throws). Stub it — same pattern as
            // stubSession456Rests / E2eIntegrationTest.

            // Stub the transcript fetch explicitly (relaxed mocks return null
            // and muddy the retry path) and capture which session id it is
            // requested with.
            val fetchedSessions = mutableListOf<String>()
            coEvery {
                ApiClient.hermesApi.getSessionMessages(capture(fetchedSessions), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(messages = emptyList()),
                )

            // /fork sends session.branch keyed on the runtime id.
            viewModel.sendMessage("/fork")
            advanceUntilIdle()

            val branchId = captured.last { it.first == WsMethods.SESSION_BRANCH }.second
            val branchStorage = "branch-storage-1"
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    branchId,
                    mapOf(
                        "session_id" to "branch-runtime-1",
                        "stored_session_id" to branchStorage,
                        "title" to "Branch",
                        "message_count" to 0,
                        "messages" to emptyList<Any>(),
                    ),
                ),
            )
            advanceUntilIdle()

            // The DB key must stay in currentSessionId — storing the runtime
            // id here made every later resume 4007 and the transcript 404.
            assertEquals(branchStorage, viewModel.uiState.value.currentSessionId)
            // And the transcript must be fetched by the storage key, not the
            // runtime registry id (which the gateway 404s on).
            assertTrue(
                "transcript must be fetched by the storage key",
                fetchedSessions.contains(branchStorage),
            )
        }

    @Test
    fun testSessionResume_wsSuccessWaitsForRestRetry() =
        runTest {
            stubSession456Rests(success = true)
            val mockApi = ApiClient.hermesApi
            var restCalls = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any())
            } coAnswers {
                val call = restCalls++
                if (call < 3) {
                    retrofit2.Response.error(
                        500,
                        okhttp3.ResponseBody.create(null, "{\"detail\":\"boom\"}"),
                    )
                } else {
                    retrofit2.Response.success(
                        com.m57.hermescontrol.data.model.SessionMessagesResponse(messages = emptyList()),
                    )
                }
            }
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            withTimeout(2_000) { viewModel.uiState.first { it.isResumeRetrying } }

            val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(
                WsEvent.RpcResult(resumeId, mapOf("session_id" to "runtime-456")),
            )
            runCurrent()

            assertTrue("WS success must leave the REST retry armed", viewModel.uiState.value.isResumeRetrying)
            assertEquals(1, captured.count { it.first == WsMethods.SESSION_RESUME })

            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isResumeRetrying)
            assertNull(viewModel.uiState.value.resumeError)
            assertEquals(2, captured.count { it.first == WsMethods.SESSION_RESUME })
        }

    @Test
    fun testRetryResumeSession_clearsErrorAndResends() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            // Exhaust the retry budget (MAX+1 failures — see boundedRetry test).
            for (cycle in 1..(ChatViewModel.MAX_RESUME_RETRIES + 1)) {
                val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
                mockEventsFlow.emit(
                    WsEvent.RpcError(
                        resumeId,
                        JsonRpcError(code = -32000, message = "resume rejected"),
                    ),
                )
                advanceUntilIdle()
            }
            assertNotNull(viewModel.uiState.value.resumeError)

            // Manual retry clears the exhausted latch and dispatches a fresh resume.
            viewModel.retryResumeSession()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.resumeError)
            assertFalse(viewModel.uiState.value.isResumeRetrying)
            val resumeSends = captured.count { it.first == WsMethods.SESSION_RESUME }
            assertEquals(ChatViewModel.MAX_RESUME_RETRIES + 2, resumeSends)
        }

    @Test
    fun testGatewayReconnect_clearsResumeErrorAndRestartsCycle() =
        runTest {
            stubSession456Rests(success = false)
            val (viewModel, _) = createViewModelWithSession()

            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertNotNull("resumeError must be set after retries exhaust", viewModel.uiState.value.resumeError)

            // Reconnect is a fresh start: the error latch is cleared and the
            // current session is re-resumed on the new socket.
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            runCurrent()

            assertNull(viewModel.uiState.value.resumeError)
            assertFalse(viewModel.uiState.value.isResumeRetrying)

            // The re-resume's own failures start a NEW bounded cycle.
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.resumeError)
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

    // ── History pagination guard (issue #674 & #686) ───────────────────────────────

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
                mockApi.getSessionMessages("session-456", any(), any(), any())
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
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun testLoadOlderMessages_honorsServerReturnedOffset() =
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
                                    message_count = 100,
                                ),
                            ),
                        total = 1,
                    ),
                )
            var messagesCallCount = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any())
            } coAnswers {
                messagesCallCount += 1
                if (messagesCallCount == 1) {
                    retrofit2.Response.success(
                        com.m57.hermescontrol.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        role = "assistant",
                                        content = JsonPrimitive("Msg 50"),
                                    ),
                                ),
                            offset = 50,
                            total = 100,
                        ),
                    )
                } else {
                    retrofit2.Response.success(
                        com.m57.hermescontrol.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        role = "user",
                                        content = JsonPrimitive("Older Msg 20"),
                                    ),
                                ),
                            offset = 20,
                            total = 100,
                        ),
                    )
                }
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.hasOlderMessages)

            // Server returns effective offset 20 (different from requested offset 0)
            viewModel.loadOlderMessages()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.hasOlderMessages)
            val messages = viewModel.uiState.value.messages
            assertEquals(2, messages.size)
            assertEquals("rest-session-456-20", messages[0].id)
            assertEquals("Older Msg 20", messages[0].content)
        }

    @Test
    fun testLoadOlderMessages_oldServerFallback_stopsPaginationWhenOffsetDoesNotDecrease() =
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
                                    message_count = 100,
                                ),
                            ),
                        total = 1,
                    ),
                )
            var messagesCallCount = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any())
            } coAnswers {
                messagesCallCount += 1
                if (messagesCallCount == 1) {
                    retrofit2.Response.success(
                        com.m57.hermescontrol.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        role = "assistant",
                                        content = JsonPrimitive("Msg 50"),
                                    ),
                                ),
                            offset = 50,
                            total = 100,
                        ),
                    )
                } else {
                    // Older server ignores query params and returns offset = 50
                    // (same as oldOffset, offset did not decrease)
                    retrofit2.Response.success(
                        com.m57.hermescontrol.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        role = "assistant",
                                        content = JsonPrimitive("Msg 50"),
                                    ),
                                ),
                            offset = 50,
                            total = 100,
                        ),
                    )
                }
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            viewModel.loadOlderMessages()
            advanceUntilIdle()

            assertFalse(
                "hasOlderMessages must be false when returned offset does not decrease",
                viewModel.uiState.value.hasOlderMessages,
            )
        }

    @Test
    fun testLoadMessages_handlesJsonObjectToolResult() =
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
                                    message_count = 1,
                                ),
                            ),
                        total = 1,
                    ),
                )
            val jsonObjectContent =
                buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                }
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    role = "tool",
                                    content = jsonObjectContent,
                                ),
                            ),
                        offset = 0,
                        total = 1,
                    ),
                )

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            val messages = viewModel.uiState.value.messages
            assertEquals(1, messages.size)
            assertEquals("{\"status\":\"ok\"}", messages[0].content)
        }

    // ── Attachment open (issue #724) ─────────────────────────────────────

    @Test
    fun `openAttachment GATEWAY success fires ACTION_VIEW with FileProvider uri`() =
        runTest {
            val cacheDir =
                java.io.File(System.getProperty("java.io.tmpdir"), "hermes_open_test_${System.nanoTime()}")
            cacheDir.mkdirs()
            every { app.cacheDir } returns cacheDir

            mockkObject(GatewayFileClient)
            val bytes = "hello".toByteArray()
            coEvery {
                GatewayFileClient.fetch(any())
            } returns GatewayFileResult.Success(GatewayFile("note.txt", "text/plain", bytes))

            val intentSlot = slot<Intent>()
            // android.jar stubs Intent ctor/setters to throw "not mocked" in unit tests;
            // mock the Intent constructor so openWithView can build + deliver it,
            // and capture the constructed instance to assert on its setters.
            mockkConstructor(Intent::class)
            every { anyConstructed<Intent>().setDataAndType(any(), any()) } answers { self as Intent }
            every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
            mockkStatic(FileProvider::class)
            every {
                FileProvider.getUriForFile(any(), any(), any())
            } returns mockk(relaxed = true)
            every { app.getApplicationContext() } returns app
            every { app.applicationContext } returns app
            every { app.startActivity(capture(intentSlot)) } returns Unit

            val vm = createViewModel()
            val attachment =
                Attachment(
                    uri = "unused",
                    name = "note.txt",
                    mimeType = "text/plain",
                    gatewayUrl = "https://gw/api/files/download?path=%2Ftmp%2Fnote.txt&token=t",
                    source = AttachmentSource.GATEWAY,
                )

            vm.openAttachment(attachment)
            advanceUntilIdle()

            // fetch was invoked (proves we entered the IO launch)
            coVerify { GatewayFileClient.fetch(any()) }
            // ACTION_VIEW intent delivered, with the right type + grant flag.
            verify { app.startActivity(any()) }
            verify { intentSlot.captured.setDataAndType(any(), eq("text/plain")) }
            verify { intentSlot.captured.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            assertNull(vm.uiState.value.openError)
            cacheDir.deleteRecursively()
        }

    @Test
    fun `saveAttachment writes gateway file to selected document`() =
        runTest {
            mockkObject(GatewayFileClient)
            val bytes = "downloaded".toByteArray()
            coEvery { GatewayFileClient.fetch("/tmp/note.txt") } returns
                GatewayFileResult.Success(GatewayFile("note.txt", "text/plain", bytes))
            val resolver = mockk<android.content.ContentResolver>()
            val output = java.io.ByteArrayOutputStream()
            val destination = mockk<android.net.Uri>()
            every { app.contentResolver } returns resolver
            every { resolver.openOutputStream(destination, "wt") } returns output

            val vm = createViewModel()
            val attachment =
                Attachment(
                    uri = "unused",
                    name = "note.txt",
                    mimeType = "text/plain",
                    gatewayUrl = "https://gw/api/files/download?path=%2Ftmp%2Fnote.txt&token=t",
                    source = AttachmentSource.GATEWAY,
                )

            vm.saveAttachment(attachment, destination)
            advanceUntilIdle()

            assertArrayEquals(bytes, output.toByteArray())
            assertNull(vm.uiState.value.savingAttachmentPath)
            assertEquals("Saved note.txt", vm.uiState.value.openError)
        }

    @Test
    fun `saveAttachment never deletes the selected document when download fails`() =
        runTest {
            mockkObject(GatewayFileClient)
            val resolver = mockk<android.content.ContentResolver>(relaxed = true)
            val destination = mockk<android.net.Uri>()
            every { app.contentResolver } returns resolver
            coEvery {
                GatewayFileClient.fetch("/tmp/missing.pdf")
            } returns GatewayFileResult.NotFound

            val (viewModel, _) = createViewModelWithSession()
            viewModel.saveAttachment(
                attachment =
                    Attachment(
                        uri = "gateway:/tmp/missing.pdf",
                        name = "missing.pdf",
                        mimeType = "application/pdf",
                        size = 0,
                        source = AttachmentSource.GATEWAY,
                        gatewayUrl = "https://host/files/download?path=%2Ftmp%2Fmissing.pdf",
                    ),
                destination = destination,
            )
            advanceUntilIdle()

            verify(exactly = 0) { resolver.delete(any(), any(), any()) }
            assertNull(viewModel.uiState.value.savingAttachmentPath)
        }

    @Test
    fun `saveAttachment never deletes the selected document when writing fails`() =
        runTest {
            mockkObject(GatewayFileClient)
            val resolver = mockk<android.content.ContentResolver>(relaxed = true)
            val destination = mockk<android.net.Uri>()
            every { app.contentResolver } returns resolver
            every { resolver.openOutputStream(destination, "wt") } throws IllegalStateException("write failed")
            coEvery { GatewayFileClient.fetch("/tmp/report.pdf") } returns
                GatewayFileResult.Success(GatewayFile("report.pdf", "application/pdf", byteArrayOf(1)))

            val viewModel = createViewModel()
            viewModel.saveAttachment(
                Attachment(
                    uri = "gateway:/tmp/report.pdf",
                    name = "report.pdf",
                    mimeType = "application/pdf",
                    source = AttachmentSource.GATEWAY,
                ),
                destination,
            )
            advanceUntilIdle()

            verify(exactly = 0) { resolver.delete(any(), any(), any()) }
            assertNull(viewModel.uiState.value.savingAttachmentPath)
            assertTrue(viewModel.uiState.value.openError.orEmpty().startsWith("Could not save"))
        }

    @Test
    fun `saveAttachment ignores overlapping saves`() =
        runTest {
            mockkObject(GatewayFileClient)
            val firstResult = CompletableDeferred<GatewayFileResult>()
            val fetchedPaths = mutableListOf<String>()
            coEvery { GatewayFileClient.fetch(capture(fetchedPaths)) } coAnswers { firstResult.await() }
            val resolver = mockk<android.content.ContentResolver>(relaxed = true)
            every { app.contentResolver } returns resolver
            val viewModel = createViewModel()

            viewModel.saveAttachment(
                Attachment("gateway:/tmp/first.pdf", "first.pdf", "application/pdf", source = AttachmentSource.GATEWAY),
                mockk(),
            )
            runCurrent()
            viewModel.saveAttachment(
                Attachment(
                    "gateway:/tmp/second.pdf",
                    "second.pdf",
                    "application/pdf",
                    source = AttachmentSource.GATEWAY,
                ),
                mockk(),
            )

            assertEquals("/tmp/first.pdf", viewModel.uiState.value.savingAttachmentPath)
            assertEquals(listOf("/tmp/first.pdf"), fetchedPaths)

            firstResult.complete(GatewayFileResult.NotFound)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.savingAttachmentPath)
        }

    @Test
    fun `openAttachment GATEWAY not-found surfaces openError`() =
        runTest {
            mockkObject(GatewayFileClient)
            coEvery {
                GatewayFileClient.fetch(any())
            } returns GatewayFileResult.NotFound

            val vm = createViewModel()
            val attachment =
                Attachment(
                    uri = "unused",
                    name = "missing.pdf",
                    mimeType = "application/pdf",
                    gatewayUrl = "https://gw/api/files/download?path=%2Ftmp%2Fmissing.pdf&token=t",
                    source = AttachmentSource.GATEWAY,
                )

            vm.openAttachment(attachment)
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.openError)
            assertTrue(
                vm.uiState.value.openError!!
                    .contains("missing.pdf"),
            )
        }
}
