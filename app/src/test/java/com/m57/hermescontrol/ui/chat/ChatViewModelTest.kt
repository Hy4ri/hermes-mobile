package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.model.BusySendMode
import com.m57.hermescontrol.data.model.AudioTranscriptionResponse
import com.m57.hermescontrol.data.model.PaginationInfo
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.model.SessionMessagesAroundPagination
import com.m57.hermescontrol.data.model.SessionMessagesAroundResponse
import com.m57.hermescontrol.data.model.SessionMessagesResponse
import com.m57.hermescontrol.data.model.SessionTimelineEntry
import com.m57.hermescontrol.data.model.SessionTimelinePagination
import com.m57.hermescontrol.data.model.SessionTimelineResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.GatewayFile
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.data.remote.GatewayFileResult
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.ConnectionOperationParser
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.JsonRpcError
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.notification.TurnCorrelationTracker
import com.m57.hermescontrol.ui.chat.fakes.FakeChatPersistenceRepository
import com.m57.hermescontrol.ui.chat.fakes.FakeSlashUsageStore
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
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockEventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    private val mockConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    private val mockSwitchFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private lateinit var app: Application
    private lateinit var fakeRepo: FakeChatPersistenceRepository
    private lateinit var fakeSlashUsageStore: FakeSlashUsageStore
    private lateinit var attachmentCacheDir: java.io.File
    private lateinit var attachmentFilesDir: java.io.File

    /** Counter used to generate unique WS request IDs. */
    private var reqCount = 0
    private val sentRequestMethods = mutableListOf<Pair<String, String>>()

    @Test
    fun mergeTranscriptWithLive_collapsesDuplicateIdsKeepingLatestMessage() {
        val stale = ChatMessage(id = "duplicate-id", role = MessageRole.ASSISTANT, content = "stale")
        val latest = stale.copy(content = "latest")

        val merged = mergeTranscriptWithLive(emptyList(), listOf(stale, latest))

        assertEquals(listOf(latest), merged)
    }

    @Test
    fun mergeTranscriptWithLive_matchingUserContentCollapsesLocalAndRestCopies() {
        // /queue bubbles show the stripped queued text, so the optimistic
        // local copy and the later REST echo share content — the sync merge
        // must collapse them into one row instead of rendering a duplicate
        // below its answer (PR #892 follow-up).
        val local =
            ChatMessage(
                id = "ws-local-1",
                role = MessageRole.USER,
                content = "do the thing",
                timestamp = 100L,
            )
        val rest =
            ChatMessage(
                id = "rest-sess-5",
                role = MessageRole.USER,
                content = "do the thing",
                timestamp = 100L,
            )

        val merged = mergeTranscriptWithLive(listOf(rest), listOf(local))

        assertEquals(1, merged.size)
        // The richer local copy wins when both sides carry the same content.
        assertEquals("ws-local-1", merged.single().id)
    }

    @Test
    fun mergeTranscriptWithLive_collapsesGatewayAttachmentContextCopy() {
        val local =
            ChatMessage(
                id = "ws-local-attachment",
                role = MessageRole.USER,
                content = "What is the secret word in the attached file?",
                timestamp = 100L,
            )
        val rest =
            ChatMessage(
                id = "rest-sess-attachment",
                role = MessageRole.USER,
                content =
                    """
                    @file:files/agent-vault/hermes/attachments/note.txt

                    What is the secret word in the attached file?

                    --- Attached Context ---

                    📄 @file:files/agent-vault/hermes/attachments/note.txt (8 tokens)
                    ```
                    THE_SECRET_WORD_IS_MANGO_8421
                    ```
                    """.trimIndent(),
                timestamp = 100L,
            )

        val merged = mergeTranscriptWithLive(listOf(rest), listOf(local))

        assertEquals(1, merged.size)
        assertEquals("ws-local-attachment", merged.single().id)
    }

    @Test
    fun mapServerMessages_restOnlyAttachmentHidesGatewayContext() {
        val ref = "@file:files/agent-vault/hermes/attachments/note.txt"
        val persisted =
            """
            $ref

            What is the secret word in the attached file?

            --- Attached Context ---

            📄 $ref (8 tokens)
            ```
            THE_SECRET_WORD_IS_MANGO_8421
            ```
            """.trimIndent()

        val mapped =
            mapServerMessages(
                sessionId = "session-1",
                messages =
                    listOf(
                        SessionMessage(
                            id = 42,
                            role = "user",
                            content = JsonPrimitive(persisted),
                            timestamp = JsonPrimitive("100"),
                        ),
                    ),
                offset = 0,
                latestPaging = true,
                liveMessages = emptyList(),
            )

        assertEquals(1, mapped.size)
        assertEquals(
            """
            $ref

            What is the secret word in the attached file?
            """.trimIndent(),
            mapped.single().content,
        )
        assertFalse(mapped.single().content.contains("--- Attached Context ---"))
        assertFalse(mapped.single().content.contains("THE_SECRET_WORD_IS_MANGO_8421"))
    }

    @Test
    fun mapServerMessages_prefersCompactionDisplayContent() {
        val mapped =
            mapServerMessages(
                sessionId = "session-1",
                messages =
                    listOf(
                        SessionMessage(
                            id = 42,
                            role = "user",
                            content = JsonPrimitive("[model-facing compaction summary]"),
                            display_content = JsonPrimitive("The original user prompt"),
                        ),
                    ),
                offset = 0,
                latestPaging = true,
                liveMessages = emptyList(),
            )

        assertEquals("The original user prompt", mapped.single().content)
    }

    @Test
    fun stripAttachmentRefLines_preservesUserAuthoredAttachedContextHeading() {
        val authored =
            """
            Explain this heading:
            --- Attached Context ---
            this is ordinary user-authored text
            """.trimIndent()

        assertEquals(authored, stripAttachmentRefLines(authored))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        reqCount = 0
        sentRequestMethods.clear()

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
        attachmentCacheDir =
            java.nio.file.Files
                .createTempDirectory("chat-attachments-test")
                .toFile()
        attachmentFilesDir =
            java.nio.file.Files
                .createTempDirectory("chat-send-files-test")
                .toFile()
        every { app.cacheDir } returns attachmentCacheDir
        every { app.filesDir } returns attachmentFilesDir
        fakeRepo = FakeChatPersistenceRepository()
        fakeSlashUsageStore = FakeSlashUsageStore()
        ActiveSessionHolder.set(null)

        mockConnectionStatus.value = ConnectionStatus.DISCONNECTED

        every { AuthManager.getToken() } returns "test-token"
        every { AuthManager.getBaseUrl() } returns "http://test.local/"
        every { AuthManager.getSelectedProfileId() } returns null
        mockkObject(ProfileSwitchCoordinator)
        every { ProfileSwitchCoordinator.switched } returns mockSwitchFlow
        every { ProfileSwitchCoordinator.connectionSwitched } returns MutableSharedFlow<String>()
        every { AuthManager.isTypingEffectEnabled() } returns true
        every { AuthManager.getBusySendMode() } returns BusySendMode.CORRECT
        every { AuthManager.getTypingEffectDelayMs() } returns 30
        every { AuthManager.isMessageStatsEnabled() } returns false
        every { AuthManager.isUserMessageTokensEnabled() } returns true
        every { AuthManager.isAssistantMessageTokensEnabled() } returns true
        every { AuthManager.isTokensPerSecondEnabled() } returns true
        every { AuthManager.isModelProviderShown() } returns false
        every { AuthManager.isAutoReconnect() } returns false
        every { AuthManager.isRestoreLastSession() } returns false
        every { AuthManager.getLastOpenedSessionId() } returns null
        every { AuthManager.setLastOpenedSessionId(any()) } returns Unit
        every { AuthManager.clearLastOpenedSessionId() } returns Unit
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
            sentRequestMethods += arg<String>(0) to id
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
            reqCount++
            val id = "req-msg-$reqCount"
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        every { HermesWsClient.respondToServerRequest(any(), any()) } returns true
        every { HermesWsClient.respondToServerRequestError(any(), any(), any()) } returns true

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
        attachmentCacheDir.deleteRecursively()
        attachmentFilesDir.deleteRecursively()
        unmockkAll()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Create a ViewModel with the fake repo injected directly. */
    private fun createViewModel(
        startCleanup: Boolean = false,
        historyDispatcher: kotlinx.coroutines.CoroutineDispatcher = testDispatcher,
        sendStore: ChatSendStore = ChatSendStore(),
    ): ChatViewModel =
        // All injected dispatchers share the test scheduler so RPC ordering is deterministic.
        ChatViewModel(
            app,
            startCleanup,
            fakeRepo,
            fakeSlashUsageStore,
            testDispatcher,
            testDispatcher,
            historyDispatcher,
            sendStore,
        )

    /**
     * Create ViewModel, simulate GatewayReady, feed SESSION_CREATE result,
     * and return a Pair(viewModel, sessionId).
     *
     * Request ID sequence: GatewayReady triggers loadSessions (req-id-1),
     * fetchCommandCatalog (req-id-2), then createNewSession (req-id-3).
     */
    private suspend fun TestScope.createViewModelWithSession(
        startCleanup: Boolean = false,
        historyDispatcher: kotlinx.coroutines.CoroutineDispatcher = testDispatcher,
        sendStore: ChatSendStore = ChatSendStore(),
    ): Pair<ChatViewModel, String> {
        val viewModel = createViewModel(startCleanup, historyDispatcher, sendStore)
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

    @Test
    fun connectionOperation_liveEventRoutesAndSessionResetClearsIt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val snapshot = connectionOperationSnapshot(sessionId = sessionId)

            mockEventsFlow.emit(WsEvent.ConnectionRequest(snapshot))
            advanceUntilIdle()
            assertEquals(
                "op-1218",
                viewModel.connectionOperationState.value.operation
                    ?.opId,
            )

            viewModel.createNewSession()
            advanceUntilIdle()
            assertNull(viewModel.connectionOperationState.value.operation)

            mockEventsFlow.emit(WsEvent.ConnectionUpdate(snapshot.copy(seq = 2L)))
            advanceUntilIdle()
            assertNull(viewModel.connectionOperationState.value.operation)
        }

    @Test
    fun connectionOperation_sessionResumeRestoresPendingSnapshot() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.switchSession("stored-session")
            advanceUntilIdle()
            val resumeRequestId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second

            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeRequestId,
                    mapOf(
                        "session_id" to "runtime-session",
                        "pending_connection" to connectionOperationPayload(),
                    ),
                ),
            )
            advanceUntilIdle()

            val operation = viewModel.connectionOperationState.value.operation
            assertEquals("runtime-session", operation?.sessionId)
            assertEquals("op-1218", operation?.opId)
        }

    @Test
    fun sessionResume_restoresRetainedFailureAsNonDurablePartial() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.switchSession("stored-session")
            advanceUntilIdle()
            val resumeRequestId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second

            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeRequestId,
                    mapOf(
                        "session_id" to "runtime-session",
                        "resumed" to "stored-session",
                        "inflight" to
                            mapOf(
                                "assistant" to "Retained partial answer",
                                "user" to "Private user prompt",
                                "streaming" to false,
                                "status" to "error",
                                "error" to "Provider failed",
                                "recoverable" to true,
                                "error_surface" to mapOf("provider" to "example", "code" to "rate_limit"),
                            ),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("stored-session", viewModel.uiState.value.currentSessionId)
            assertEquals(
                "Retained partial answer",
                viewModel.uiState.value.messages
                    .last()
                    .content,
            )
            assertEquals(
                "code: rate_limit\nprovider: example\nProvider failed",
                viewModel.uiState.value.replyFailure
                    ?.details,
            )
            assertFalse(
                viewModel.uiState.value.replyFailure!!
                    .details
                    .contains("Private user prompt"),
            )
            assertTrue(
                fakeRepo.dao
                    .getMessagesForSession("stored-session")
                    .none { it.content == "Retained partial answer" },
            )
        }

    @Test
    fun staleSessionResumeFailureCannotClearHealthyCurrentStream() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.switchSession("stored-old")
            advanceUntilIdle()
            val staleResumeId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second

            viewModel.switchSession("stored-current")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.MessageStart("stored-current"))
            mockEventsFlow.emit(WsEvent.MessageToken("Healthy current stream", "stored-current"))
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    staleResumeId,
                    mapOf(
                        "session_id" to "runtime-old",
                        "inflight" to
                            mapOf(
                                "assistant" to "Old failed partial",
                                "status" to "error",
                                "error" to "Old provider failure",
                            ),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("stored-current", viewModel.uiState.value.currentSessionId)
            assertNull(viewModel.uiState.value.replyFailure)
            assertEquals(
                "Healthy current stream",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .none { it.content == "Old failed partial" },
            )
        }

    private fun connectionOperationSnapshot(
        sessionId: String,
        seq: Long = 1L,
    ) = checkNotNull(ConnectionOperationParser.parse(connectionOperationPayload(seq), sessionId))

    private fun connectionOperationPayload(seq: Long = 1L): Map<String, Any?> =
        mapOf(
            "op_id" to "op-1218",
            "seq" to seq,
            "deadline_at" to 2_000_000_000.0,
            "timeout_seconds" to 300.0,
            "tool_call_id" to "tool-1218",
            "targets" to
                listOf(
                    mapOf(
                        "name" to "github",
                        "kind" to "connector",
                        "action" to "authorize",
                        "state" to "pending",
                    ),
                ),
        )

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
    fun testSlashCommand_new_inBotChat_compactsInsteadOfCreatingSession() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            // Set session title to "Bot Chat" via SESSION_LIST result
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = "req-id-1",
                    result =
                        mapOf(
                            "sessions" to
                                listOf(
                                    mapOf(
                                        "id" to sessionId,
                                        "title" to "Bot Chat",
                                        "message_count" to 5.0,
                                    ),
                                ),
                        ),
                ),
            )
            advanceUntilIdle()

            // Reset recording on HermesWsClient to only inspect calls after /new
            clearMocks(HermesWsClient, answers = false, recordedCalls = true)

            viewModel.sendMessage("/new")
            advanceUntilIdle()

            // In Bot Chat, /new should NOT create a fresh session, but instead dispatch /compact
            verify(exactly = 0) {
                HermesWsClient.send(
                    WsMethods.SESSION_CREATE,
                    any(),
                    any(),
                )
            }
            assertTrue(
                viewModel.uiState.value.messages.any {
                    it.content.contains("compacting instead")
                },
            )
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
    fun testSlashCommand_stop_andInterruptedNoticeStayInPlaceAcrossSync() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // 1. Initial messages in UI state
            val m0 = ChatMessage(id = "rest-$sessionId-0", role = MessageRole.USER, content = "start task")
            val m1 = ChatMessage(id = "rest-$sessionId-1", role = MessageRole.ASSISTANT, content = "running...")
            val stateField =
                ChatViewModel::class.java
                    .getDeclaredField("_uiState")
                    .apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val state = stateField.get(viewModel) as MutableStateFlow<ChatUiState>
            state.value = viewModel.uiState.value.copy(messages = listOf(m0, m1))

            every {
                HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any())
            } answers {
                val onSent = thirdArg<((String) -> Unit)?>()
                onSent?.invoke("interrupt-req-1")
                "interrupt-req-1"
            }

            // 2. User sends /stop
            viewModel.sendMessage("/stop")
            advanceUntilIdle()

            // 3. WS returns interrupt success -> adds "Session interrupted"
            mockEventsFlow.emit(WsEvent.RpcResult("interrupt-req-1", mapOf("status" to "interrupted")))
            advanceUntilIdle()

            // 4. Server transcript sync arrives with newer turns
            val serverRows =
                listOf(
                    com.m57.hermescontrol.data.model.SessionMessage(
                        id = 0,
                        role = "user",
                        content = kotlinx.serialization.json.JsonPrimitive("start task"),
                        timestamp = kotlinx.serialization.json.JsonPrimitive(1),
                    ),
                    com.m57.hermescontrol.data.model.SessionMessage(
                        id = 1,
                        role = "assistant",
                        content = kotlinx.serialization.json.JsonPrimitive("running..."),
                        timestamp = kotlinx.serialization.json.JsonPrimitive(2),
                    ),
                    com.m57.hermescontrol.data.model.SessionMessage(
                        id = 2,
                        role = "user",
                        content = kotlinx.serialization.json.JsonPrimitive("next prompt"),
                        timestamp = kotlinx.serialization.json.JsonPrimitive(3),
                    ),
                    com.m57.hermescontrol.data.model.SessionMessage(
                        id = 3,
                        role = "assistant",
                        content = kotlinx.serialization.json.JsonPrimitive("next reply"),
                        timestamp = kotlinx.serialization.json.JsonPrimitive(4),
                    ),
                )

            // Simulate sync/merge with serverRows
            val current = viewModel.uiState.value.messages
            val merged =
                com.m57.hermescontrol.ui.chat.mergeTranscriptWithLive(
                    com.m57.hermescontrol.ui.chat
                        .mapServerMessages(sessionId, serverRows, 0, true, current),
                    current,
                    chronological = true,
                    preserveLiveIds = true,
                )

            val contents = merged.map { it.content }
            assertEquals(
                listOf("start task", "running...", "/stop", "Session interrupted", "next prompt", "next reply"),
                contents,
            )
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
    fun testSlashCommand_btw_dispatchesPromptBtw_andSetsBtwState() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            every {
                HermesWsClient.request(WsMethods.PROMPT_BTW, any(), any())
            } returns
                CompletableDeferred(
                    mapOf("task_id" to "btw_123456"),
                )

            viewModel.sendMessage("/btw which file was that in?")
            advanceUntilIdle()

            val btw = viewModel.uiState.value.btwState
            assertNotNull(btw)
            assertEquals("which file was that in?", btw?.question)
            assertEquals("btw_123456", btw?.taskId)
            assertTrue(btw?.isLoading == true)
            verify {
                HermesWsClient.request(
                    WsMethods.PROMPT_BTW,
                    mapOf("session_id" to sessionId, "text" to "which file was that in?"),
                    any(),
                )
            }
            // Ensure transcript messages were NOT polluted with the side question
            assertTrue(
                viewModel.uiState.value.messages
                    .none { it.content.contains("which file was that in?") },
            )
        }

    // ── Slash usage ranking (issue #865) ────────────────────────────────────

    @Test
    fun slashUsage_dispatchIncrementsCountPerCommand() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            every {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            } returns
                CompletableDeferred(
                    mapOf("type" to "exec", "output" to "ok"),
                )

            viewModel.sendMessage("/help")
            advanceUntilIdle()
            viewModel.sendMessage("/help")
            advanceUntilIdle()
            viewModel.sendMessage("/new")
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.slashUsageCounts["/help"])
            assertEquals(1, viewModel.uiState.value.slashUsageCounts["/new"])
        }

    @Test
    fun slashUsage_blockedCommand_notCounted() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/clear")
            advanceUntilIdle()

            // A blocklisted command can never dispatch — it must not climb
            // the autocomplete ranking either.
            assertTrue(
                viewModel.uiState.value.slashUsageCounts
                    .isEmpty(),
            )
            verify(exactly = 0) {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            }
        }

    // ── /resume · /history open the history tab (issue #864) ────────────────

    @Test
    fun slashCommand_resume_requestsHistoryNavigationWithoutGateway() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/resume")
            advanceUntilIdle()

            assertTrue(
                "openHistoryRequested must be set for the screen to navigate",
                viewModel.uiState.value.openHistoryRequested,
            )
            // Client-side only: no gateway round-trip for /resume.
            verify(exactly = 0) {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            }
            verify(exactly = 0) {
                HermesWsClient.request(WsMethods.SLASH_EXEC, any(), any())
            }
        }

    @Test
    fun slashCommand_history_requestsHistoryNavigation() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/history")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.openHistoryRequested)
            verify(exactly = 0) {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            }
        }

    @Test
    fun consumeOpenHistoryRequest_clearsFlag() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/resume")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.openHistoryRequested)

            viewModel.consumeOpenHistoryRequest()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.openHistoryRequested)
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

    @Test
    fun testModelSwitch_confirmRequired_triggersConfirmationDialogAndReSends() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            var lastReqId = ""
            val capturedParams = mutableListOf<Map<String, Any>>()
            every { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) } answers {
                val params = arg<Map<String, Any>>(1)
                capturedParams.add(params)
                val id = "req-confirm-${capturedParams.size}"
                lastReqId = id
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            // User sends model switch command that triggers privacy/expensive guard
            viewModel.sendMessage("/model muse-spark-1.3-contributor-free --provider opencode-free --session")
            advanceUntilIdle()

            assertEquals(1, capturedParams.size)
            assertNull(capturedParams[0]["confirm_expensive_model"])

            // Backend returns confirm_required = true
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = lastReqId,
                    result =
                        mapOf(
                            "key" to "model",
                            "value" to "muse-spark-1.3-contributor-free",
                            "confirm_required" to true,
                            "confirm_message" to "Meta training warning",
                        ),
                ),
            )
            advanceUntilIdle()

            // UI state now shows confirmation dialog message
            assertEquals("Meta training warning", viewModel.uiState.value.modelSwitchConfirmMessage)

            // User confirms
            viewModel.confirmModelSwitchExpensive()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.modelSwitchConfirmMessage)
            assertEquals(2, capturedParams.size)
            assertEquals(true, capturedParams[1]["confirm_expensive_model"])
            assertEquals(
                "muse-spark-1.3-contributor-free --provider opencode-free --session",
                capturedParams[1]["value"],
            )
            assertEquals(sessionId, capturedParams[1]["session_id"])
        }

    @Test
    fun testModelSwitch_dismissConfirmation_revertsModel() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            var lastReqId = ""
            every { HermesWsClient.send(WsMethods.CONFIG_SET, any(), any()) } answers {
                val id = "req-cfg-dismiss"
                lastReqId = id
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            // Set an initial model
            viewModel.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()
            assertEquals("openai/gpt-4o", viewModel.uiState.value.currentSessionModel)

            // Switch to expensive model
            viewModel.sendSlashModel("opencode-free", "muse-spark-1.3-contributor-free")
            advanceUntilIdle()
            assertEquals("opencode-free/muse-spark-1.3-contributor-free", viewModel.uiState.value.currentSessionModel)

            // Backend requires confirmation
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = lastReqId,
                    result =
                        mapOf(
                            "key" to "model",
                            "value" to "muse-spark-1.3-contributor-free",
                            "confirm_required" to true,
                            "confirm_message" to "Meta training warning",
                        ),
                ),
            )
            advanceUntilIdle()
            assertEquals("Meta training warning", viewModel.uiState.value.modelSwitchConfirmMessage)

            // User dismisses/cancels
            viewModel.dismissModelSwitchConfirm()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.modelSwitchConfirmMessage)
            // Reverted back to previous model
            assertEquals("openai/gpt-4o", viewModel.uiState.value.currentSessionModel)
        }

    @Test
    fun testUndoCommand_dispatchesAndPrefillsComposer() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            every {
                HermesWsClient.request(
                    WsMethods.COMMAND_DISPATCH,
                    mapOf("name" to "undo", "arg" to "2", "session_id" to sessionId),
                    any(),
                )
            } returns
                CompletableDeferred(
                    mapOf(
                        "type" to "prefill",
                        "message" to "undone user message",
                        "notice" to "↶ Undid 2 turns",
                    ),
                )

            viewModel.sendMessage("/undo 2")
            advanceUntilIdle()

            // Prefill text is staged in UI state
            assertEquals("undone user message", viewModel.uiState.value.pendingPrefillText)
            // Notice is displayed as system message with rewind target feedback
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content.contains("↶ Undid 2 turns") && it.content.contains("undone user message") },
            )
            // Verify slash usage was recorded
            assertEquals(1, viewModel.uiState.value.slashUsageCounts["/undo"])

            // Consume prefill text
            viewModel.consumePendingPrefill()
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.pendingPrefillText)
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
    fun terminalReplyFailureIsScopedDismissibleAndNeverResends() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Partial", sessionId))
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.MessageComplete("Other error", "other-session", rawPayload = mapOf("status" to "error")),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isAgentTyping)
            assertNull(viewModel.uiState.value.replyFailure)
            assertEquals(
                "Partial",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
            mockEventsFlow.emit(
                WsEvent.MessageComplete("Provider error", sessionId, rawPayload = mapOf("status" to "error")),
            )
            advanceUntilIdle()
            val failure = viewModel.uiState.value.replyFailure!!
            assertFalse(viewModel.uiState.value.isAgentTyping)
            assertFalse(viewModel.uiState.value.isThinking)
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content == "Partial" },
            )
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Provider error" },
            )
            viewModel.dismissReplyFailure("stale-card")
            advanceUntilIdle()
            assertEquals(failure, viewModel.uiState.value.replyFailure)
            viewModel.dismissReplyFailure(failure.id)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.replyFailure)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun unscopedFailureCannotCrossSessionSwitch() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val failure = WsEvent.MessageComplete("Error", null, rawPayload = mapOf("status" to "error"))
            mockEventsFlow.emit(failure)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.replyFailure)
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.switchSession("another-session")
            advanceUntilIdle()
            // A delayed start without identity cannot authorize a failed turn on this chat.
            mockEventsFlow.emit(WsEvent.MessageStart(null))
            advanceUntilIdle()
            mockEventsFlow.emit(failure)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.replyFailure)
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Error" },
            )
        }

    @Test
    fun unscopedFailureAfterForeignStartCannotUsePreviousSessionPin() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Current partial", sessionId))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.MessageStart("foreign-session"))
            mockEventsFlow.emit(
                WsEvent.MessageComplete("Foreign diagnostic", null, rawPayload = mapOf("status" to "error")),
            )
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.replyFailure)
            assertEquals(
                "Current partial",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Foreign diagnostic" },
            )
        }

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
            assertFalse(
                viewModel.uiState.value.messages[1]
                    .isStreaming,
            )
            assertEquals(
                MessageRole.TOOL,
                viewModel.uiState.value.messages[2]
                    .role,
            )
            assertNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(
                listOf(
                    viewModel.uiState.value.messages[1]
                        .id,
                ),
                viewModel.streamingState.value.sealedOrphanIds,
            )

            // Finalize: the complete payload repeats the sealed commentary as
            // a prefix — the final bubble strips it, so each line appears once.
            mockEventsFlow.emit(WsEvent.MessageComplete("Calculating sum = 4", sessionId))
            advanceUntilIdle()
            val assistant =
                viewModel.uiState.value.messages
                    .filter { it.role == MessageRole.ASSISTANT }
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
    fun mainTurnBusyTracksRuntimeEventsNotClarificationTyping() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            assertFalse(viewModel.uiState.value.isMainTurnBusy)

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isMainTurnBusy)

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isMainTurnBusy)

            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = "Continue?",
                    options = listOf("Yes"),
                    clarifyId = "clarify-busy-state",
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()
            viewModel.respondToClarify("Yes")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isAgentTyping)
            assertFalse(viewModel.uiState.value.isMainTurnBusy)
        }

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
    fun testClarifyRequestWithQuestionIdAndRespond() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = "Pick one:",
                    options = listOf("Option 1"),
                    clarifyId = "clarify-batch-1",
                    sessionId = sessionId,
                    questionId = "q0",
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "Pick one:",
                viewModel.uiState.value.clarifyRequest
                    ?.text,
            )
            assertEquals(
                "q0",
                viewModel.uiState.value.clarifyRequest
                    ?.questionId,
            )

            viewModel.respondToClarify("Option 1")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clarifyRequest)
            verify {
                HermesWsClient.send(
                    method = WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "response" to "Option 1",
                            "answer" to "Option 1",
                            "clarify_id" to "clarify-batch-1",
                            "request_id" to "clarify-batch-1",
                            "question_id" to "q0",
                        ),
                    onSent = any(),
                )
            }
        }

    @Test
    fun testClarifyBatch_respondsToAllQuestions() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = "1. Pick A\n\n2. Pick B",
                    options = listOf("A1", "A2"),
                    clarifyId = "clarify-batch-multi",
                    sessionId = sessionId,
                    questionId = "q0",
                    questions =
                        listOf(
                            WsEvent.ClarifyQuestion(
                                qid = "q0",
                                question = "Pick A",
                                choices = listOf("A1", "A2"),
                                multiSelect = false,
                            ),
                            WsEvent.ClarifyQuestion(
                                qid = "q1",
                                question = "Pick B",
                                choices = listOf("B1", "B2"),
                                multiSelect = true,
                            ),
                        ),
                ),
            )
            advanceUntilIdle()

            val clarify = viewModel.uiState.value.clarifyRequest
            assertNotNull(clarify)
            assertEquals(2, clarify?.resolvedQuestions?.size)

            viewModel.respondToClarifyBatch(
                mapOf(
                    "q0" to "A1",
                    "q1" to "B1, B2",
                ),
            )
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clarifyRequest)
            verify {
                HermesWsClient.send(
                    method = WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "response" to "A1",
                            "answer" to "A1",
                            "question_id" to "q0",
                            "clarify_id" to "clarify-batch-multi",
                            "request_id" to "clarify-batch-multi",
                        ),
                    onSent = any(),
                )
            }
            verify {
                HermesWsClient.send(
                    method = WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "response" to "B1, B2",
                            "answer" to "B1, B2",
                            "question_id" to "q1",
                            "clarify_id" to "clarify-batch-multi",
                            "request_id" to "clarify-batch-multi",
                        ),
                    onSent = any(),
                )
            }
        }

    @Test
    fun testServerRequestClarifyReplay_restoresLockedAnswersAndMergesRemainingAnswer() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ServerRequest(
                    id = "srq-clarify-replay",
                    method = "clarify",
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "questions" to
                                listOf(
                                    mapOf("qid" to "q0", "question" to "First?", "choices" to listOf("yes")),
                                    mapOf("qid" to "q1", "question" to "Second?", "choices" to emptyList<String>()),
                                ),
                            "answers" to mapOf("q0" to "yes", "invalid" to 42),
                        ),
                    replayed = true,
                ),
            )
            advanceUntilIdle()

            val clarify = viewModel.uiState.value.clarifyRequest
            assertEquals("srq-clarify-replay", clarify?.serverRequestId)
            assertEquals(mapOf("q0" to "yes"), clarify?.lockedAnswers)
            assertEquals(2, clarify?.resolvedQuestions?.size)

            viewModel.respondToClarifyBatch(mapOf("q0" to "", "q1" to "new answer"))
            advanceUntilIdle()

            verify {
                HermesWsClient.respondToServerRequest(
                    "srq-clarify-replay",
                    withArg { result ->
                        val answers = result.jsonObject["answers"]?.jsonObject
                        assertEquals("yes", answers?.get("q0")?.jsonPrimitive?.content)
                        assertEquals("new answer", answers?.get("q1")?.jsonPrimitive?.content)
                        assertFalse(answers?.containsKey("invalid") == true)
                    },
                )
            }
        }

    @Test
    fun serverRequestCancel_clearsOnlyMatchingClarifyPrompt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.ServerRequest(
                    id = "srq-clarify-cancel",
                    method = "clarify",
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "question" to "Continue?",
                        ),
                ),
            )
            advanceUntilIdle()
            assertEquals(
                "srq-clarify-cancel",
                viewModel.uiState.value.clarifyRequest
                    ?.serverRequestId,
            )

            mockEventsFlow.emit(
                WsEvent.ServerRequestCancelled(
                    id = "srq-other",
                    method = "clarify",
                    reason = "timeout",
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()
            assertEquals(
                "srq-clarify-cancel",
                viewModel.uiState.value.clarifyRequest
                    ?.serverRequestId,
            )

            mockEventsFlow.emit(
                WsEvent.ServerRequestCancelled(
                    id = "srq-clarify-cancel",
                    method = "clarify",
                    reason = "timeout",
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.clarifyRequest)
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

    @Test
    fun testClarifyDismissWithQuestionIdInformsAgent() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    "Please choose:",
                    listOf("Yes", "No"),
                    "clarify-789",
                    sessionId,
                    questionId = "q0",
                ),
            )
            advanceUntilIdle()
            assertEquals(
                "clarify-789",
                viewModel.uiState.value.clarifyRequest
                    ?.clarifyId,
            )
            assertEquals(
                "q0",
                viewModel.uiState.value.clarifyRequest
                    ?.questionId,
            )

            viewModel.dismissClarify()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clarifyRequest)
            verify {
                HermesWsClient.send(
                    method = WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "response" to "The user cancelled — no answer provided.",
                            "answer" to "The user cancelled — no answer provided.",
                            "clarify_id" to "clarify-789",
                            "request_id" to "clarify-789",
                            "question_id" to "q0",
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
    fun addAttachments_keepsEverySelectedImageInOrder() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            val selected =
                listOf(
                    Attachment("content://image/1", "one.jpg", "image/jpeg", 100L),
                    Attachment("content://image/2", "two.png", "image/png", 200L),
                    Attachment("content://image/3", "three.webp", "image/webp", 300L),
                )

            viewModel.addAttachments(selected)
            advanceUntilIdle()

            assertEquals(selected, viewModel.uiState.value.pendingAttachments)
        }

    @Test
    fun sendMessage_mixedAttachmentsWithOversizedFileAreRejectedBeforeReading() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val contentResolver = mockk<ContentResolver>()
            every { app.contentResolver } returns contentResolver

            viewModel.addAttachment(
                uri = "content://image/photo",
                name = "photo.jpg",
                mimeType = "image/jpeg",
                size = 1024,
            )
            viewModel.addAttachment(
                uri = "content://oversized/archive",
                name = "archive.zip",
                mimeType = "application/zip",
                size = MAX_CHAT_ATTACHMENT_BYTES + 1,
            )

            assertFalse(viewModel.sendMessage("Inspect this archive"))
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)
            assertNull(viewModel.uiState.value.composerTextToRestore)
            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.contains("too large") == true,
            )
            verify(exactly = 0) { contentResolver.openInputStream(any()) }
        }

    @Test
    fun sendMessage_unknownSizeOversizedAttachmentRestoresComposerWithoutGhostMessage() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val uriString = "content://unknown/large-file"
            val mockUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val oversizedStream =
                object : java.io.InputStream() {
                    private var remaining = MAX_CHAT_ATTACHMENT_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        remaining -= count
                        return count
                    }
                }
            mockkStatic(Uri::class)
            every { Uri.parse(uriString) } returns mockUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(mockUri) } returns oversizedStream

            viewModel.addAttachment(
                uri = uriString,
                name = "unknown-size.bin",
                mimeType = "application/octet-stream",
                size = 0,
            )

            viewModel.sendMessage("Inspect this file")
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.pendingAttachments.size)
            assertEquals("Inspect this file", viewModel.uiState.value.composerTextToRestore)
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Inspect this file" },
            )
            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.contains("too large") == true,
            )
        }

    @Test
    fun sendMessage_oversizedDuringSnapshotLeavesNoPersistedGhostMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val persistedBeforeSend = fakeRepo.dao.count()
            val uriString = "content://unknown/growing-file"
            val mockUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val oversizedStream =
                object : java.io.InputStream() {
                    private var remaining = MAX_CHAT_ATTACHMENT_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        remaining -= count
                        return count
                    }
                }
            mockkStatic(Uri::class)
            every { Uri.parse(uriString) } returns mockUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(mockUri) } returns oversizedStream

            viewModel.addAttachment(
                uri = uriString,
                name = "growing-file.bin",
                mimeType = "application/octet-stream",
                size = 0,
            )

            viewModel.sendMessage("Inspect changing file")
            advanceUntilIdle()

            assertFalse(viewModel.streamingState.value.turnUsageBaselineCaptured)
            assertEquals(persistedBeforeSend, fakeRepo.dao.count())
            assertFalse(
                fakeRepo.dao
                    .getMessagesForSession("session-123")
                    .any { it.content == "Inspect changing file" },
            )
            assertEquals(1, viewModel.uiState.value.pendingAttachments.size)
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Inspect changing file" },
            )

            mockEventsFlow.emit(
                WsEvent.SessionUsage(
                    data = mapOf("usage" to mapOf("output" to 1300L)),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()
            viewModel.removeAttachment(0)
            viewModel.sendMessage("Retry without attachment")
            advanceUntilIdle()

            assertEquals(
                1300L,
                viewModel.streamingState.value.turnUsageBaseline
                    ?.outputTokens,
            )
            assertTrue(viewModel.streamingState.value.turnUsageBaselineCaptured)
        }

    @Test
    fun sendMessage_persistenceFailureRestoresComposerAndDoesNotDispatch() =
        runTest {
            fakeRepo = spyk(fakeRepo)
            coEvery {
                fakeRepo.persistMessage(match { it.content == "keep this prompt" }, any())
            } throws IllegalStateException("disk full")
            val (viewModel, _) = createViewModelWithSession()

            viewModel.sendMessage("keep this prompt")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.messages.any { it.content == "keep this prompt" })
            assertEquals("keep this prompt", state.composerTextToRestore)
            assertTrue(state.errorMessage?.contains("save") == true)
            assertFalse(state.isAgentTyping)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun sendMessage_rawJsonFileAttachmentRetainsReference() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val uriString = "content://test/note"
            val uri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse(uriString) } returns uri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-note-copy"
            every { Uri.parse("file://private-note-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(uri) } answers { "hello".byteInputStream() }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) } returns
                CompletableDeferred<Any?>(
                    buildJsonObject {
                        put("attached", true)
                        put("ref_text", "@file:attachments/note.txt")
                    },
                )
            viewModel.addAttachment(uriString, "note.txt", "text/plain", 5)

            viewModel.sendMessage("Inspect file")
            advanceUntilIdle()

            verify {
                HermesWsClient.sendMessage(any(), "@file:attachments/note.txt\n\nInspect file", any(), any())
            }
        }

    @Test
    fun sendMessage_laterGrowingAttachmentDoesNotPartiallyUploadEarlierAttachments() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val imageUriString = "content://image/valid"
            val growingUriString = "content://unknown/growing-file"
            val imageUri = mockk<Uri>()
            val growingUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val oversizedStream =
                object : java.io.InputStream() {
                    private var remaining = MAX_CHAT_ATTACHMENT_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        remaining -= count
                        return count
                    }
                }
            mockkStatic(Uri::class)
            every { Uri.parse(imageUriString) } returns imageUri
            every { Uri.parse(growingUriString) } returns growingUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(imageUri) } returns
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            every { contentResolver.openInputStream(growingUri) } returns oversizedStream

            viewModel.addAttachments(
                listOf(
                    Attachment(imageUriString, "photo.jpg", "image/jpeg", 4),
                    Attachment(growingUriString, "growing.bin", "application/octet-stream", 0),
                ),
            )

            viewModel.sendMessage("Inspect both")
            advanceUntilIdle()

            verify(exactly = 0) {
                HermesWsClient.request(
                    match { it == WsMethods.IMAGE_ATTACH_BYTES || it == WsMethods.FILE_ATTACH },
                    any(),
                    any(),
                )
            }
            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)
        }

    @Test
    fun sendMessage_oversizeResultAfterSessionSwitchDoesNotMutateNewSession() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val uriString = "content://unknown/session-switch-large-file"
            val mockUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val oversizedStream =
                object : java.io.InputStream() {
                    private var remaining = MAX_CHAT_ATTACHMENT_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
                        remaining -= count
                        return count
                    }
                }

            mockkStatic(Uri::class)
            every { Uri.parse(uriString) } returns mockUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(mockUri) } answers {
                viewModel.switchSession("session-456")
                oversizedStream
            }

            viewModel.addAttachment(
                uri = uriString,
                name = "large.bin",
                mimeType = "application/octet-stream",
                size = 0,
            )

            viewModel.sendMessage("Inspect in the old session")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("session-456", state.currentSessionId)
            assertTrue(state.pendingAttachments.isEmpty())
            assertNull(state.composerTextToRestore)
            assertNull(state.errorMessage)
        }

    @Test
    fun sendMessage_promptErrorAfterSessionSwitchDoesNotMutateNewSession() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, oldSessionId) = createViewModelWithSession()
            val uriString = "content://file/session-switch-valid-file"
            val mockUri = mockk<Uri>()
            val contentResolver = mockk<ContentResolver>()
            val promptRequestId = "old-session-prompt"

            mockkStatic(Uri::class)
            every { Uri.parse(uriString) } returns mockUri
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(mockUri) } answers {
                viewModel.switchSession("session-456")
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            }
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(promptRequestId)
                promptRequestId
            }

            viewModel.addAttachment(
                uri = uriString,
                name = "valid.bin",
                mimeType = "application/octet-stream",
                size = 4,
            )

            viewModel.sendMessage("Send in the old session")
            advanceUntilIdle()

            verify(exactly = 0) {
                HermesWsClient.sendMessage(
                    oldSessionId,
                    match { it.contains("Send in the old session") },
                    any(),
                    any(),
                )
            }
            assertFalse(viewModel.streamingState.value.turnUsageBaselineCaptured)

            val state = viewModel.uiState.value
            assertEquals("session-456", state.currentSessionId)
            assertNull(state.errorMessage)

            val resumeRequestId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeRequestId,
                    mapOf("session_id" to "session-456"),
                ),
            )
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.SessionUsage(
                    data = mapOf("usage" to mapOf("output" to 9000L)),
                    sessionId = "session-456",
                ),
            )
            advanceUntilIdle()
            viewModel.sendMessage("Send in the new session")
            advanceUntilIdle()

            assertEquals(
                9000L,
                viewModel.streamingState.value.turnUsageBaseline
                    ?.outputTokens,
            )
            assertTrue(viewModel.streamingState.value.turnUsageBaselineCaptured)
            verify {
                HermesWsClient.sendMessage(
                    "session-456",
                    match { it.contains("Send in the new session") },
                    any(),
                    any(),
                )
            }
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
            assertTrue(viewModel.uiState.value.isSending)
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
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isAgentTyping)

            // Second send while streaming triggers redirect
            viewModel.sendMessage("Wait, correction")
            advanceUntilIdle()

            verify { HermesWsClient.sendRedirect(sessionId, "Wait, correction", any()) }
        }

    @Test
    fun rejectedRedirectRemovesGhostAndQueuesRecoverableText() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            var redirectId = ""
            every { HermesWsClient.sendRedirect(sessionId, any(), any()) } answers {
                redirectId = "rejected-redirect"
                arg<((String) -> Unit)?>(2)?.invoke(redirectId)
                redirectId
            }

            viewModel.sendMessage("First turn")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("Correction")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(redirectId, mapOf("status" to "rejected")))
            advanceUntilIdle()

            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Correction" },
            )
            assertFalse(fakeRepo.loadMessages(sessionId).any { it.content == "Correction" })
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .any { it.text == "Correction" },
            )
            assertTrue(viewModel.uiState.value.isAgentTyping)
        }

    @Test
    fun rejectedRedirectFallsBackToQueueAndRestHistoryRecoversOneUserRow() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            var redirectId = ""
            var queuedId = ""
            var historyHasCorrection = false
            every { HermesWsClient.sendRedirect(sessionId, any(), any()) } answers {
                redirectId = "redirect-for-history"
                arg<((String) -> Unit)?>(2)?.invoke(redirectId)
                redirectId
            }
            every { HermesWsClient.sendMessage(sessionId, "Correction", any(), true) } answers {
                queuedId = "queued-for-history"
                arg<((String) -> Unit)?>(2)?.invoke(queuedId)
                queuedId
            }
            coEvery {
                ApiClient.hermesApi.getSessionMessages(sessionId, any(), any(), any(), any())
            } answers {
                retrofit2.Response.success(
                    SessionMessagesResponse(
                        messages =
                            if (historyHasCorrection) {
                                listOf(
                                    SessionMessage(
                                        id = 12,
                                        role = "user",
                                        content = JsonPrimitive("Correction"),
                                        timestamp = JsonPrimitive(System.currentTimeMillis()),
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                        pagination = PaginationInfo(order = "latest"),
                    ),
                )
            }

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("Correction")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(redirectId, mapOf("status" to "rejected")))
            advanceUntilIdle()
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Correction" },
            )

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            assertEquals("queued-for-history", queuedId)
            mockEventsFlow.emit(WsEvent.RpcResult(queuedId, mapOf("status" to "queued")))
            advanceUntilIdle()
            assertEquals(
                PendingSendState.ACCEPTED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )

            historyHasCorrection = true
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageComplete("Answered", sessionId))
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .isEmpty(),
            )
            assertEquals(
                1,
                viewModel.uiState.value.messages
                    .count { it.content == "Correction" },
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content == "Correction" && it.canonicalRestId != null },
            )
        }

    @Test
    fun busyQueueWaitsForCompletionAndUsesQueuedSubmit() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()

            assertTrue(viewModel.sendMessage("Next turn", BusySendMode.QUEUE))
            advanceUntilIdle()
            assertEquals(
                PendingSendState.QUEUED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Next turn", any(), any()) }

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Next turn", any(), true) }
        }

    @Test
    fun queueTapAfterTurnCompletionSubmitsNormally() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()

            assertTrue(viewModel.sendMessage("Late queue tap", BusySendMode.QUEUE))
            advanceUntilIdle()

            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Late queue tap", any(), false) }
            assertFalse(
                viewModel.uiState.value.pendingSends
                    .any { it.text == "Late queue tap" && it.state == PendingSendState.QUEUED },
            )
        }

    @Test
    fun queueDrainKeepsOwnershipWhileFirstReceiptPersistenceIsSuspended() =
        runTest {
            fakeRepo = spyk(fakeRepo)
            val releasePersistence = CompletableDeferred<Unit>()
            var holdPersistence = false
            var preparationCount = 0
            coEvery { fakeRepo.persistMessage(match { it.content == "FIFO first" }, any()) } coAnswers {
                if (holdPersistence) {
                    preparationCount++
                    releasePersistence.await()
                }
                Unit
            }
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("FIFO first", BusySendMode.QUEUE)
            viewModel.sendMessage("FIFO second", BusySendMode.QUEUE)
            advanceUntilIdle()

            holdPersistence = true
            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            assertEquals(1, preparationCount)
            // A duplicate completion must not start another drain during pre-enqueue IO.
            mockEventsFlow.emit(WsEvent.MessageComplete("Done again", sessionId))
            advanceUntilIdle()
            val preparationsBeforeRelease = preparationCount
            releasePersistence.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, preparationsBeforeRelease)
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "FIFO first", any(), true) }
            verify(exactly = 0) { HermesWsClient.sendMessage(sessionId, "FIFO second", any(), any()) }
        }

    @Test
    fun slashQueueSharesDurableLocalQueueWhileBusy() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("/queue after tools")
            advanceUntilIdle()
            assertEquals(
                PendingSendState.QUEUED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "after tools", any(), any()) }

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "after tools", any(), true) }
        }

    @Test
    fun busyAttachmentIsSnapshottedAndQueuedWithoutRedirect() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://queued/photo") } returns sourceUri
            every { Uri.parse("file://queued-copy") } returns snapshotUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://queued-copy"
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(1, 2, 3).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            io.mockk.mockkConstructor(android.util.Base64OutputStream::class)
            every {
                anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any())
            } returns Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.IMAGE_ATTACH_BYTES, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("attached" to true))

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.addAttachment("content://queued/photo", "photo.png", "image/png", 3)
            viewModel.sendMessage("See photo")
            assertTrue("The receipt must not exist before the private snapshot completes", store.all().isEmpty())
            advanceUntilIdle()
            assertEquals(
                PendingSendState.QUEUED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            assertTrue(snapshotFile.exists())
            assertEquals(
                "file://queued-copy",
                store
                    .all()
                    .single()
                    .attachments
                    .single()
                    .uri,
            )
            verify(exactly = 0) { HermesWsClient.sendRedirect(any(), any(), any()) }

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "See photo", any(), true) }
        }

    @Test
    fun manualAttachmentRetryCannotOverlapActiveQueuePreparation() =
        runTest {
            fakeRepo = spyk(fakeRepo)
            val releasePersistence = CompletableDeferred<Unit>()
            var holdPersistence = false
            var preparations = 0
            coEvery { fakeRepo.persistMessage(match { it.content == "Queued attachment" }, any()) } coAnswers {
                if (holdPersistence) {
                    preparations++
                    releasePersistence.await()
                }
                Unit
            }
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshot: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://queue/overlap") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshot = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://queue-overlap"
            every { Uri.parse("file://queue-overlap") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(1, 2, 3).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers { snapshot.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("ref_text" to "@file:overlap.txt"))
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.addAttachment("content://queue/overlap", "overlap.txt", "text/plain", 3)
            viewModel.sendMessage("Queued attachment", BusySendMode.QUEUE)
            advanceUntilIdle()
            val receipt = store.all().single()
            holdPersistence = true
            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            assertEquals(1, preparations)

            viewModel.sendQueuedNow(receipt.id)
            viewModel.sendQueuedNow(receipt.id)
            advanceUntilIdle()
            val preparationsBeforeRelease = preparations
            releasePersistence.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, preparationsBeforeRelease)
            assertEquals(1, store.all().size)
            assertEquals(PendingSendState.SENDING, store.all().single().state)
            verify(exactly = 1) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }
            verify(exactly = 1) {
                HermesWsClient.sendMessage(sessionId, "@file:overlap.txt\n\nQueued attachment", any(), true)
            }
            verify(exactly = 0) {
                HermesWsClient.sendMessage(sessionId, "@file:overlap.txt\n\nQueued attachment", any(), false)
            }
        }

    @Test
    fun idleAttachmentReceiptIsCommittedOnlyWithPrivateSnapshotUri() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://idle/photo") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-idle-copy"
            every { Uri.parse("file://private-idle-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(1, 2, 3).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.IMAGE_ATTACH_BYTES, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("attached" to true))

            viewModel.addAttachment("content://idle/photo", "photo.png", "image/png", 3)
            assertTrue(viewModel.sendMessage("Idle attachment"))
            assertTrue(store.all().isEmpty())
            advanceUntilIdle()

            assertTrue(snapshotFile.exists())
            assertEquals(
                "file://private-idle-copy",
                store
                    .all()
                    .single()
                    .attachments
                    .single()
                    .uri,
            )
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Idle attachment", any(), false) }
        }

    @Test
    fun interruptAttachmentReceiptIsPrivateBeforeInterruptRequest() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            mockkStatic(Uri::class)
            every { Uri.parse("content://interrupt/note") } returns sourceUri
            every { Uri.fromFile(any()) } returns snapshotUri
            every { snapshotUri.toString() } returns "file://private-interrupt-copy"
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(4, 5, 6).inputStream() }

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.addAttachment("content://interrupt/note", "note.txt", "text/plain", 3)
            assertTrue(viewModel.sendMessage("Replace with file", BusySendMode.INTERRUPT))
            assertTrue(store.all().isEmpty())
            advanceUntilIdle()

            assertEquals(
                "file://private-interrupt-copy",
                store
                    .all()
                    .single()
                    .attachments
                    .single()
                    .uri,
            )
            verify(exactly = 1) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Replace with file", any(), any()) }
        }

    @Test
    fun slowAttachmentPrecommitKeepsLaterTextBehindItsReceipt() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://slow/first") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-first-copy"
            every { Uri.parse("file://private-first-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers {
                assertTrue(viewModel.sendMessage("Later text"))
                byteArrayOf(1, 2, 3).inputStream()
            }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.IMAGE_ATTACH_BYTES, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("attached" to true))

            viewModel.addAttachment("content://slow/first", "first.png", "image/png", 3)
            assertTrue(viewModel.sendMessage("First attachment"))
            advanceUntilIdle()

            assertEquals(listOf("First attachment", "Later text"), store.all().map { it.text })
            assertTrue(store.all().zipWithNext().all { (a, b) -> a.createdAt < b.createdAt })
            assertEquals(PendingSendState.SENDING, store.all()[0].state)
            assertEquals(PendingSendState.QUEUED, store.all()[1].state)
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "First attachment", any(), false) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Later text", any(), any()) }
        }

    @Test
    fun busyAttachmentSnapshotFailureRestoresDraftWithoutReceiptOrGhost() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            mockkStatic(Uri::class)
            every { Uri.parse("content://queued/missing") } returns sourceUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } returns null

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.addAttachment("content://queued/missing", "missing.txt", "text/plain", 4)

            assertTrue(viewModel.sendMessage("Keep my draft", BusySendMode.QUEUE))
            advanceUntilIdle()

            assertTrue(store.all().isEmpty())
            assertEquals("Keep my draft", viewModel.uiState.value.composerTextToRestore)
            assertEquals(
                "content://queued/missing",
                viewModel.uiState.value.pendingAttachments
                    .single()
                    .uri,
            )
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Keep my draft" },
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Keep my draft", any(), any()) }
        }

    @Test
    fun busyAttachmentSnapshotFinishingAfterSessionSwitchDoesNotCommitOrSubmit() =
        runTest {
            stubSession456Rests(success = true)
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var stagedFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://queued/session-switch") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                stagedFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://stale-copy"
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers {
                viewModel.switchSession("session-456")
                byteArrayOf(1, 2, 3).inputStream()
            }

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.addAttachment(
                "content://queued/session-switch",
                "switch.txt",
                "text/plain",
                3,
            )
            assertTrue(viewModel.sendMessage("Old session queued", BusySendMode.QUEUE))
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Old session queued" },
            )

            advanceUntilIdle()

            assertEquals("session-456", viewModel.uiState.value.currentSessionId)
            assertTrue(store.all().isEmpty())
            assertFalse(stagedFile.exists())
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Old session queued", any(), any()) }
        }

    @Test
    fun idleIgnoresBusyModeAndSubmitsNormally() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            assertTrue(viewModel.sendMessage("Idle", BusySendMode.GUIDE))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Idle", any(), false) }
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_STEER, any(), any()) }
        }

    @Test
    fun subagentActivityAloneDoesNotTurnAnIdleMainSessionBusy() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.SubagentEvent(
                    type = "subagent.progress",
                    payload = mapOf("subagent_id" to "child-1", "status" to "running"),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()
            viewModel.sendMessage("Main prompt", BusySendMode.GUIDE)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Main prompt", any(), false) }
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_STEER, any(), any()) }
        }

    @Test
    fun toolStartMarksMainTurnBusyEvenWithoutMessageStart() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.ToolStart("shell", emptyMap(), sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("Guide", BusySendMode.GUIDE)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.send(WsMethods.SESSION_STEER, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(sessionId, "Guide", any(), any()) }
        }

    @Test
    fun refreshSettingsPicksUpChangedSavedBusyMode() =
        runTest {
            var savedMode = BusySendMode.CORRECT
            every { AuthManager.getBusySendMode() } answers { savedMode }
            val viewModel = createViewModel()
            advanceUntilIdle()
            assertEquals(BusySendMode.CORRECT, viewModel.uiState.value.busySendMode)

            savedMode = BusySendMode.GUIDE
            viewModel.refreshSettings()
            advanceUntilIdle()
            assertEquals(BusySendMode.GUIDE, viewModel.uiState.value.busySendMode)
        }

    @Test
    fun rapidDuplicateTapCreatesOneReceiptAndOneRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            assertTrue(viewModel.sendMessage("One tap"))
            assertFalse(viewModel.sendMessage("One tap"))
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.pendingSends.size)
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "One tap", any(), any()) }
        }

    @Test
    fun stalePromptAckAfterSessionSwitchKeepsOldReceiptAndCurrentSessionClear() =
        runTest {
            stubSession456Rests(success = true)
            val sendStore = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = sendStore)
            val requestId = "old-prompt"
            every { HermesWsClient.sendMessage(sessionId, "Old session", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }
            viewModel.sendMessage("Old session")
            advanceUntilIdle()
            viewModel.switchSession("session-456")
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.RpcResult(requestId, mapOf("status" to "streaming")))
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .isEmpty(),
            )
            assertEquals(PendingSendState.UNKNOWN, sendStore.all().single().state)
        }

    @Test
    fun busyGuideUsesSteerAndUnsupportedFallsBackToQueueWithoutInterrupt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()

            viewModel.sendMessage("Keep the tests", BusySendMode.GUIDE)
            advanceUntilIdle()
            val id = sentRequestMethods.last { it.first == WsMethods.SESSION_STEER }.second
            mockEventsFlow.emit(WsEvent.RpcError(id, JsonRpcError(4010, "steer unsupported")))
            advanceUntilIdle()

            assertEquals(
                PendingSendState.QUEUED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    @Test
    fun interruptWithUnknownDeliveryQueuesWithoutInterrupting() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            every { HermesWsClient.sendMessage(sessionId, "Uncertain", any(), false) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("uncertain-submit")
                "uncertain-submit"
            }

            viewModel.sendMessage("Uncertain")
            advanceUntilIdle()
            viewModel.expireOutgoingRequest("uncertain-submit")
            advanceUntilIdle()

            viewModel.sendMessage("Replacement", BusySendMode.INTERRUPT)
            advanceUntilIdle()

            val replacement =
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Replacement" }
            assertEquals(BusySendMode.QUEUE, replacement.mode)
            assertEquals(PendingSendState.QUEUED, replacement.state)
            assertEquals(
                PendingSendState.UNKNOWN,
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Uncertain" }
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Replacement", any(), any()) }
        }

    @Test
    fun interruptWithAcceptedDeliveryQueuesWithoutInterrupting() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            every { HermesWsClient.sendMessage(sessionId, "Accepted", any(), false) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("accepted-submit")
                "accepted-submit"
            }

            viewModel.sendMessage("Accepted")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult("accepted-submit", mapOf("status" to "streaming")))
            advanceUntilIdle()

            viewModel.sendMessage("Replacement", BusySendMode.INTERRUPT)
            advanceUntilIdle()

            val replacement =
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Replacement" }
            assertEquals(BusySendMode.QUEUE, replacement.mode)
            assertEquals(PendingSendState.QUEUED, replacement.state)
            assertEquals(
                PendingSendState.ACCEPTED,
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Accepted" }
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Replacement", any(), any()) }
        }

    @Test
    fun interruptAfterCompletedTurnSubmitsNormally() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()

            viewModel.sendMessage("Fresh turn", BusySendMode.INTERRUPT)
            advanceUntilIdle()

            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Fresh turn", any(), false) }
        }

    @Test
    fun hardInterruptWaitsForAckBeforeSubmitting() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()

            viewModel.sendMessage("Urgent", BusySendMode.INTERRUPT)
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Urgent", any(), any()) }
            val id = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second
            mockEventsFlow.emit(WsEvent.RpcResult(id, mapOf("status" to "interrupted")))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Urgent", any(), true) }
        }

    @Test
    fun notInterruptedQueuesKnownUnsentReplacementAndSubmitsExactlyOnce() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()

            viewModel.sendMessage("Replacement", BusySendMode.INTERRUPT)
            advanceUntilIdle()
            val interruptId = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second

            mockEventsFlow.emit(WsEvent.RpcResult(interruptId, mapOf("status" to "not_interrupted")))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(interruptId, mapOf("status" to "not_interrupted")))
            advanceUntilIdle()

            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Replacement", any(), true) }
            assertEquals(
                PendingSendState.SENDING,
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Replacement" }
                    .state,
            )
        }

    @Test
    fun lateNotInterruptedDoesNotClearOrRaceANewerSameSessionTurn() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("Replacement", BusySendMode.INTERRUPT)
            advanceUntilIdle()
            val interruptId = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("new turn", sessionId))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(interruptId, mapOf("status" to "not_interrupted")))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isMainTurnBusy)
            assertEquals(
                "new turn",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
            assertEquals(
                PendingSendState.QUEUED,
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Replacement" }
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Replacement", any(), any()) }

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Replacement", any(), true) }
        }

    @Test
    fun lateInterruptedDoesNotClearOrDispatchAcrossANewerSameSessionTurn() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("Replacement", BusySendMode.INTERRUPT)
            advanceUntilIdle()
            val interruptId = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("new turn", sessionId))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(interruptId, mapOf("status" to "interrupted")))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isMainTurnBusy)
            assertEquals(
                "new turn",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
            assertEquals(
                PendingSendState.QUEUED,
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Replacement" }
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Replacement", any(), any()) }

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Replacement", any(), true) }
        }

    @Test
    fun attachmentInterruptUsesTurnCapturedAtClickAndQueuesIfCopyOutlivesIt() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://interrupt/slow") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-slow-copy"
            every { Uri.parse("file://private-slow-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers {
                mockEventsFlow.tryEmit(WsEvent.MessageStart(sessionId))
                byteArrayOf(1, 2, 3).inputStream()
            }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("ref_text" to "@file:slow.txt"))

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.addAttachment("content://interrupt/slow", "slow.txt", "text/plain", 3)
            assertTrue(viewModel.sendMessage("Captured turn", BusySendMode.INTERRUPT))
            advanceUntilIdle()

            val receipt = store.all().single()
            assertEquals(PendingSendState.QUEUED, receipt.state)
            assertEquals(BusySendMode.QUEUE, receipt.mode)
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            verify(
                exactly = 1,
            ) { HermesWsClient.sendMessage(sessionId, "@file:slow.txt\n\nCaptured turn", any(), true) }
        }

    @Test
    fun stopParksLocalQueueAndSendNowUnparksIt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("Later", BusySendMode.QUEUE)
            advanceUntilIdle()
            val queuedId =
                viewModel.uiState.value.pendingSends
                    .single()
                    .id

            viewModel.interruptSession()
            advanceUntilIdle()
            assertEquals(
                PendingSendState.PARKED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            val stopId = sentRequestMethods.last { it.first == WsMethods.SESSION_INTERRUPT }.second
            mockEventsFlow.emit(WsEvent.RpcResult(stopId, mapOf("status" to "interrupted")))
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Later", any(), any()) }

            viewModel.sendQueuedNow(queuedId)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Later", any(), true) }
        }

    @Test
    fun sendNowWaitsForEarlierAcceptedTurnToReachRestHistory() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            every { HermesWsClient.sendMessage(sessionId, "First", any(), true) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("first-queued-submit")
                "first-queued-submit"
            }
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("First", BusySendMode.QUEUE)
            viewModel.sendMessage("Second", BusySendMode.QUEUE)
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult("first-queued-submit", mapOf("status" to "queued")))
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            val second =
                viewModel.uiState.value.pendingSends
                    .single { it.text == "Second" }
            viewModel.sendQueuedNow(second.id)
            advanceUntilIdle()

            assertEquals(
                PendingSendState.QUEUED,
                viewModel.uiState.value.pendingSends
                    .single { it.id == second.id }
                    .state,
            )
            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    @Test
    fun manualRetryRecommitsAttachmentOnlyAfterPrivateStaging() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://retry/note") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-retry-copy"
            every { Uri.parse("file://private-retry-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(7, 8, 9).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("ref_text" to "@file:note.txt"))
            store.put(
                PendingSend(
                    id = "legacy-retry",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Retry attachment",
                    attachments = listOf(Attachment("content://retry/note", "note.txt", "text/plain", 3)),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.REJECTED,
                ),
            )
            viewModel.refreshSettings()
            advanceUntilIdle()

            viewModel.sendQueuedNow("legacy-retry")
            viewModel.sendQueuedNow("legacy-retry")
            assertEquals(
                "content://retry/note",
                store
                    .all()
                    .single()
                    .attachments
                    .single()
                    .uri,
            )
            assertTrue(store.all().single().requiresAttachmentRecovery)
            advanceUntilIdle()

            assertEquals(
                "file://private-retry-copy",
                store
                    .all()
                    .single()
                    .attachments
                    .single()
                    .uri,
            )
            assertFalse(store.all().single().requiresAttachmentRecovery)
            verify(exactly = 1) {
                HermesWsClient.sendMessage(sessionId, "@file:note.txt\n\nRetry attachment", any(), false)
            }
        }

    @Test
    fun manualRetryPreparationOversizePreservesOriginalReceipt() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            mockkStatic(Uri::class)
            every { Uri.parse("content://retry/growing") } returns sourceUri
            every { Uri.fromFile(any()) } returns snapshotUri
            every { snapshotUri.toString() } returns "file://retry-growing-copy"
            every { Uri.parse("file://retry-growing-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(1).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers {
                object : java.io.InputStream() {
                    private var remaining = MAX_CHAT_ATTACHMENT_BYTES + 1

                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        remaining -= count
                        return count
                    }
                }
            }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            store.put(
                PendingSend(
                    id = "retry-growing",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Keep recovery receipt",
                    attachments =
                        listOf(
                            Attachment("content://retry/growing", "growing.bin", "application/octet-stream", 1),
                        ),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.REJECTED,
                ),
            )
            val original = store.all().single()
            viewModel.refreshSettings()
            advanceUntilIdle()
            viewModel.sendQueuedNow(original.id)
            advanceUntilIdle()

            assertEquals(listOf(original), store.all())
            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.contains("too large") == true,
            )
            verify(exactly = 0) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Keep recovery receipt", any(), any()) }
        }

    @Test
    fun manualRetryOwnerChangePreservesExistingPrivateSnapshot() =
        runTest {
            var baseUrl = "http://test.local/"
            every { AuthManager.getBaseUrl() } answers { baseUrl }
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    baseUrl,
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val snapshot = java.io.File(attachmentFilesDir, "chat-send/retry-existing/0")
            snapshot.parentFile.mkdirs()
            snapshot.writeBytes(byteArrayOf(1, 2, 3))
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            mockkStatic(Uri::class)
            every { Uri.parse("file://retry-existing") } returns snapshotUri
            every { Uri.fromFile(any()) } returns snapshotUri
            every { snapshotUri.toString() } returns "file://retry-existing"
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(snapshotUri) } answers {
                baseUrl = "http://other-profile.local/"
                snapshot.inputStream()
            }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            val original =
                PendingSend(
                    id = "retry-existing",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Keep existing snapshot",
                    attachments = listOf(Attachment("file://retry-existing", "note.txt", "text/plain", 3)),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.UNKNOWN,
                )
            store.put(original)
            viewModel.sendQueuedNow(original.id)
            advanceUntilIdle()

            assertEquals(listOf(original), store.all())
            assertTrue("Rollback must retain the original receipt's private bytes", snapshot.exists())
            verify(exactly = 0) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun manualRetryUnreadablePreparationPreservesUnknownReceipt() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val snapshot = java.io.File(attachmentFilesDir, "chat-send/retry-unreadable/0")
            snapshot.parentFile.mkdirs()
            snapshot.writeBytes(byteArrayOf(1))
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            mockkStatic(Uri::class)
            every { Uri.parse("file://retry-unreadable") } returns snapshotUri
            every { Uri.fromFile(any()) } returns snapshotUri
            every { snapshotUri.toString() } returns "file://retry-unreadable"
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(snapshotUri) } returns null
            val original =
                PendingSend(
                    id = "retry-unreadable",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Keep unknown receipt",
                    attachments = listOf(Attachment("file://retry-unreadable", "note.txt", "text/plain", 1)),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.UNKNOWN,
                )
            store.put(original)
            viewModel.sendQueuedNow(original.id)
            advanceUntilIdle()

            assertEquals(listOf(original), store.all())
            verify(exactly = 0) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun legacyContentUriReceiptIsQuarantinedUntilExplicitRecovery() =
        runTest {
            val store = ChatSendStore()
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            store.put(
                PendingSend(
                    id = "legacy-auto-drain",
                    scope = scope,
                    sessionId = "session-123",
                    text = "Needs attachment recovery",
                    attachments = listOf(Attachment("content://legacy/lost", "lost.txt", "text/plain", 4)),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.QUEUED,
                ),
            )
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)

            val receipt =
                viewModel.uiState.value.pendingSends
                    .single()
            assertEquals(PendingSendState.REJECTED, receipt.state)
            assertTrue(receipt.requiresAttachmentRecovery)
            assertEquals("Needs attachment recovery", receipt.text)
            assertEquals("lost.txt", receipt.attachments.single().name)

            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Needs attachment recovery", any(), any()) }
        }

    @Test
    fun manualAttachmentRetryKeepsOriginalReceiptAcrossSessionChange() =
        runTest {
            stubSession456Rests(success = true)
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val sourceUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            mockkStatic(Uri::class)
            every { Uri.parse("content://retry/session") } returns sourceUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers {
                viewModel.switchSession("session-456")
                byteArrayOf(1, 2, 3).inputStream()
            }
            store.put(
                PendingSend(
                    id = "retry-session",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Keep old session receipt",
                    attachments = listOf(Attachment("content://retry/session", "session.txt", "text/plain", 3)),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.UNKNOWN,
                ),
            )
            viewModel.refreshSettings()

            viewModel.sendQueuedNow("retry-session")
            advanceUntilIdle()

            val preserved = store.all().single { it.id == "retry-session" }
            assertEquals("content://retry/session", preserved.attachments.single().uri)
            assertEquals(PendingSendState.REJECTED, preserved.state)
            assertTrue(preserved.requiresAttachmentRecovery)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Keep old session receipt", any(), any()) }
        }

    @Test
    fun manualAttachmentRetryKeepsOriginalReceiptAcrossProfileScopeChange() =
        runTest {
            var baseUrl = "http://test.local/"
            every { AuthManager.getBaseUrl() } answers { baseUrl }
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    baseUrl,
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val sourceUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            mockkStatic(Uri::class)
            every { Uri.parse("content://retry/profile") } returns sourceUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers {
                baseUrl = "http://other-profile.local/"
                byteArrayOf(1, 2, 3).inputStream()
            }
            store.put(
                PendingSend(
                    id = "retry-profile",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Keep old profile receipt",
                    attachments = listOf(Attachment("content://retry/profile", "profile.txt", "text/plain", 3)),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.UNKNOWN,
                ),
            )
            viewModel.refreshSettings()

            viewModel.sendQueuedNow("retry-profile")
            advanceUntilIdle()

            val preserved = store.all().single { it.id == "retry-profile" }
            assertEquals(scope, preserved.scope)
            assertEquals("content://retry/profile", preserved.attachments.single().uri)
            assertEquals(PendingSendState.REJECTED, preserved.state)
            assertTrue(preserved.requiresAttachmentRecovery)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Keep old profile receipt", any(), any()) }
        }

    // ── Send ownership linearization regressions ────────────────────────────

    @Test
    fun manualAttachmentRetryContextChangeAfterValidationPreservesOriginalReceiptAndDoesNotDispatch() =
        runTest {
            var baseUrl = "http://test.local/"
            var changeContextOnNextStoreEdit = false
            every { AuthManager.getBaseUrl() } answers { baseUrl }
            val prefs = mockk<SharedPreferences>()
            val editor = mockk<SharedPreferences.Editor>()
            every { prefs.getString("rows", null) } returns null
            every { prefs.edit() } answers {
                if (changeContextOnNextStoreEdit) {
                    changeContextOnNextStoreEdit = false
                    baseUrl = "http://other-profile.local/"
                }
                editor
            }
            every { editor.putString("rows", any()) } returns editor
            every { editor.commit() } returns true
            val store = ChatSendStore(prefs)
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    baseUrl,
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://retry/post-validation") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-post-validation-copy"
            every { Uri.parse("file://private-post-validation-copy") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(1, 2, 3).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("ref_text" to "@file:post-validation.txt"))
            store.put(
                PendingSend(
                    id = "retry-post-validation",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Keep post-validation receipt",
                    attachments =
                        listOf(
                            Attachment(
                                "content://retry/post-validation",
                                "post-validation.txt",
                                "text/plain",
                                3,
                            ),
                        ),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.REJECTED,
                ),
            )
            viewModel.refreshSettings()
            advanceUntilIdle()

            // processPendingSendReservation has already validated the old owner when it
            // opens the store transaction that would replace the recovery receipt.
            changeContextOnNextStoreEdit = true
            viewModel.sendQueuedNow("retry-post-validation")
            advanceUntilIdle()

            val preserved = store.all().single { it.id == "retry-post-validation" }
            assertEquals("http://other-profile.local/", baseUrl)
            assertEquals(scope, preserved.scope)
            assertEquals("content://retry/post-validation", preserved.attachments.single().uri)
            assertEquals(PendingSendState.REJECTED, preserved.state)
            assertTrue(preserved.requiresAttachmentRecovery)
            verify(exactly = 0) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }
            verify(exactly = 0) {
                HermesWsClient.sendMessage(
                    any(),
                    "@file:post-validation.txt\n\nKeep post-validation receipt",
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun queuedClaimContextChangeBeforeFinalValidationRollsBackToQueuedWithoutDispatch() =
        runTest {
            var baseUrl = "http://test.local/"
            var armClaimCommit = false
            var changeContextOnNextScopeRead = false
            every { AuthManager.getBaseUrl() } answers {
                if (changeContextOnNextScopeRead) {
                    changeContextOnNextScopeRead = false
                    baseUrl = "http://other-profile.local/"
                }
                baseUrl
            }
            val prefs = mockk<SharedPreferences>()
            val editor = mockk<SharedPreferences.Editor>()
            every { prefs.getString("rows", null) } returns null
            every { prefs.edit() } returns editor
            every { editor.putString("rows", any()) } returns editor
            every { editor.commit() } answers {
                if (armClaimCommit) {
                    armClaimCommit = false
                    changeContextOnNextScopeRead = true
                }
                true
            }
            val store = ChatSendStore(prefs)
            val scope =
                listOf(
                    baseUrl,
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            store.put(
                PendingSend(
                    id = "queue-post-claim-context-change",
                    scope = scope,
                    sessionId = "session-123",
                    text = "Claimed then stale",
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.QUEUED,
                ),
            )

            // The claim's durable commit arms the first scope read in publishPendingSends,
            // after update() has installed SENDING but before drainPendingQueue's final check.
            armClaimCommit = true
            val (_, sessionId) = createViewModelWithSession(sendStore = store)
            mockEventsFlow.emit(WsEvent.MessageComplete("Done", sessionId))
            advanceUntilIdle()

            val recoverable = store.all().single { it.id == "queue-post-claim-context-change" }
            assertEquals("http://other-profile.local/", baseUrl)
            assertEquals(PendingSendState.QUEUED, recoverable.state)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "Claimed then stale", any(), any()) }
        }

    @Test
    fun manualAttachmentRetryContextChangeBeforeAttachmentRpcPreservesOriginalReceipt() =
        runTest {
            var baseUrl = "http://test.local/"
            every { AuthManager.getBaseUrl() } answers { baseUrl }
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    baseUrl,
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            val sourceUri = mockk<Uri>()
            val snapshotUri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            lateinit var snapshotFile: java.io.File
            mockkStatic(Uri::class)
            every { Uri.parse("content://retry/pre-attachment-rpc") } returns sourceUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-pre-attachment-rpc"
            every { Uri.parse("file://private-pre-attachment-rpc") } returns snapshotUri
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(sourceUri) } answers { byteArrayOf(1, 2, 3).inputStream() }
            every { resolver.openInputStream(snapshotUri) } answers {
                baseUrl = "http://other-profile.local/"
                snapshotFile.inputStream()
            }
            mockkConstructor(android.util.Base64OutputStream::class)
            every { anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any()) } returns
                Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            every { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("ref_text" to "@file:pre-attachment-rpc.txt"))
            store.put(
                PendingSend(
                    id = "retry-pre-attachment-rpc",
                    scope = scope,
                    sessionId = sessionId,
                    text = "Keep pre-attachment receipt",
                    attachments =
                        listOf(
                            Attachment(
                                "content://retry/pre-attachment-rpc",
                                "pre-attachment-rpc.txt",
                                "text/plain",
                                3,
                            ),
                        ),
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.REJECTED,
                ),
            )
            viewModel.refreshSettings()
            advanceUntilIdle()

            viewModel.sendQueuedNow("retry-pre-attachment-rpc")
            advanceUntilIdle()

            val preserved = store.all().single { it.id == "retry-pre-attachment-rpc" }
            assertEquals(scope, preserved.scope)
            assertEquals("content://retry/pre-attachment-rpc", preserved.attachments.single().uri)
            assertEquals(PendingSendState.REJECTED, preserved.state)
            assertTrue(preserved.requiresAttachmentRecovery)
            verify(exactly = 0) { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun acceptedReceiptCanBeRetriedOnlyByExplicitSendAgain() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            every { HermesWsClient.sendMessage(sessionId, "Possibly buffered", any(), false) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("buffered-submit")
                "buffered-submit"
            }
            viewModel.sendMessage("Possibly buffered")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult("buffered-submit", mapOf("status" to "streaming")))
            advanceUntilIdle()
            val receipt =
                viewModel.uiState.value.pendingSends
                    .single()
            assertEquals(PendingSendState.ACCEPTED, receipt.state)
            verify(exactly = 0) { HermesWsClient.sendMessage(sessionId, "Possibly buffered", any(), true) }

            viewModel.sendQueuedNow(receipt.id)
            advanceUntilIdle()
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Possibly buffered", any(), true) }
        }

    @Test
    fun disconnectQuarantinesAcceptedReceiptWithoutAutomaticResend() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            every { HermesWsClient.sendMessage(sessionId, "Accepted before disconnect", any(), false) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("accepted-before-disconnect")
                "accepted-before-disconnect"
            }
            viewModel.sendMessage("Accepted before disconnect")
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.RpcResult("accepted-before-disconnect", mapOf("status" to "streaming")),
            )
            advanceUntilIdle()
            assertEquals(
                PendingSendState.ACCEPTED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )

            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED
            advanceUntilIdle()

            assertEquals(
                PendingSendState.UNKNOWN,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            verify(exactly = 1) {
                HermesWsClient.sendMessage(sessionId, "Accepted before disconnect", any(), false)
            }
            verify(exactly = 0) {
                HermesWsClient.sendMessage(sessionId, "Accepted before disconnect", any(), true)
            }
        }

    @Test
    fun sendNowPromotesTheSelectedQueuedMessageWhileIdle() =
        runTest {
            val store = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            store.put(PendingSend("first", scope, sessionId, "First queued", mode = BusySendMode.QUEUE))
            store.put(PendingSend("second", scope, sessionId, "Second queued", mode = BusySendMode.QUEUE))
            viewModel.refreshSettings()
            advanceUntilIdle()

            viewModel.sendQueuedNow("second")
            advanceUntilIdle()

            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Second queued", any(), true) }
            verify(exactly = 0) { HermesWsClient.sendMessage(sessionId, "First queued", any(), any()) }
            assertEquals(PendingSendState.QUEUED, store.all().single { it.id == "first" }.state)
        }

    @Test
    fun pendingQueueIsScopedByConnectionProfileAndStoredSession() =
        runTest {
            var connectionProfile = "first"
            every { AuthManager.getSelectedProfileId() } answers { connectionProfile }
            stubSession456Rests(success = true)
            val sendStore = ChatSendStore()
            val (viewModel, sessionId) = createViewModelWithSession(sendStore = sendStore)
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()
            viewModel.sendMessage("Only here", BusySendMode.QUEUE)
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.pendingSends.size)

            connectionProfile = "second"
            viewModel.refreshSettings()
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .isEmpty(),
            )
            connectionProfile = "first"
            viewModel.refreshSettings()
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.pendingSends.size)

            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .isEmpty(),
            )
        }

    @Test
    fun restoredQueueDrainsAfterResumeHydrationWhenAckArrivesFirst() =
        runTest {
            stubSession456Rests(success = true)
            val response =
                CompletableDeferred<retrofit2.Response<SessionMessagesResponse>>()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers { response.await() }
            val store = ChatSendStore()
            val (viewModel, _) = createViewModelWithSession(sendStore = store)
            val captured = captureSends()
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            store.put(PendingSend("restored", scope, "session-456", "After relaunch", mode = BusySendMode.QUEUE))

            viewModel.switchSession("session-456")
            runCurrent()
            val resumeId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(resumeId, mapOf("session_id" to "runtime-456", "running" to false)))
            runCurrent()
            assertFalse(viewModel.uiState.value.isSessionReady)
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), "After relaunch", any(), any()) }

            response.complete(retrofit2.Response.success(SessionMessagesResponse(messages = emptyList())))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
            verify(exactly = 1) { HermesWsClient.sendMessage("runtime-456", "After relaunch", any(), true) }
        }

    @Test
    fun sessionSwitchCancelsObsoleteDrainAndDispatchesRestoredQueue() =
        runTest {
            stubSession456Rests(success = true)
            val store = ChatSendStore()
            val (viewModel, _) = createViewModelWithSession(sendStore = store)
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            // Model an active session-A staging job without real threads or blocking I/O.
            // Reset must release ownership before session B becomes ready to drain.
            val obsoleteDrain = Job()
            ChatViewModel::class.java
                .getDeclaredField("queueDrainJob")
                .apply { isAccessible = true }
                .set(viewModel, obsoleteDrain)
            every { HermesWsClient.sendMessage("runtime-456", "Session B queued", any(), true) } answers {
                arg<((String) -> Unit)?>(2)?.invoke("session-b-submit")
                "session-b-submit"
            }
            store.put(
                PendingSend(
                    "session-b-restored",
                    scope,
                    "session-456",
                    "Session B queued",
                    mode = BusySendMode.QUEUE,
                ),
            )
            viewModel.switchSession("session-456")
            runCurrent()
            val resumeId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(
                WsEvent.RpcResult(resumeId, mapOf("session_id" to "runtime-456", "running" to false)),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
            assertTrue("Session switch must cancel the obsolete drain", obsoleteDrain.isCancelled)
            verify(exactly = 1) {
                HermesWsClient.sendMessage("runtime-456", "Session B queued", any(), true)
            }
            verify(exactly = 0) {
                HermesWsClient.sendMessage(any(), "Session A queued", any(), any())
            }
        }

    @Test
    fun timeoutKeepsRecoverableReceiptAndLateAckDoesNotResend() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val requestId = "timeout-request"
            every { HermesWsClient.sendMessage(sessionId, "Maybe", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }
            viewModel.sendMessage("Maybe")
            advanceUntilIdle()
            viewModel.expireOutgoingRequest(requestId)
            advanceUntilIdle()
            assertEquals(
                PendingSendState.UNKNOWN,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Maybe" },
            )

            mockEventsFlow.emit(WsEvent.RpcResult(requestId, mapOf("status" to "streaming")))
            advanceUntilIdle()
            assertEquals(
                PendingSendState.ACCEPTED,
                viewModel.uiState.value.pendingSends
                    .single()
                    .state,
            )
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Maybe", any(), any()) }
        }

    @Test
    fun restCompletionBeforePromptAckSettlesReceiptAndKeepsCompletionIdentity() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val promptId = "late-prompt-ack"
            every { HermesWsClient.sendMessage(sessionId, "Fast", any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(promptId)
                promptId
            }
            coEvery {
                ApiClient.hermesApi.getSessionMessages(sessionId, any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    SessionMessagesResponse(
                        messages =
                            listOf(
                                SessionMessage(
                                    id = 4,
                                    role = "user",
                                    content = JsonPrimitive("Fast"),
                                    timestamp = JsonPrimitive(System.currentTimeMillis()),
                                ),
                            ),
                        pagination = PaginationInfo(order = "latest"),
                    ),
                )

            viewModel.sendMessage("Fast")
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageComplete("Answer", sessionId, completionId = "completion-fast"))
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .isEmpty(),
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.completionId == "completion-fast" },
            )

            mockEventsFlow.emit(WsEvent.RpcResult(promptId, mapOf("status" to "streaming")))
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .isEmpty(),
            )
            verify(exactly = 1) { HermesWsClient.sendMessage(sessionId, "Fast", any(), any()) }
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
                    com.m57.hermescontrol.data.model
                        .SessionListResponse(sessions = emptyList(), total = 0),
                )
            coEvery {
                mockApi.getSessionMessages(
                    "session-a",
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } coAnswers { messagesA.await() }
            coEvery {
                mockApi.getSessionMessages(
                    "session-b",
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } coAnswers { messagesB.await() }

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
                    com.m57.hermescontrol.data.model
                        .SessionMessagesResponse(messages = emptyList()),
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
                    com.m57.hermescontrol.data.model
                        .SessionListResponse(sessions = emptyList(), total = 0),
                )
            coEvery {
                mockApi.getSessionMessages(
                    "session-a",
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } coAnswers { messagesA.await() }
            coEvery {
                mockApi.getSessionMessages(
                    "session-b",
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } coAnswers { messagesB.await() }

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
            assertEquals(
                listOf("message-b"),
                viewModel.uiState.value.messages
                    .map { it.content },
            )
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
                    mapOf(
                        "model" to "model-a",
                        "provider" to "provider-a",
                        "reasoning_effort" to "high",
                        "terminal_backend" to "docker",
                    ),
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
            assertEquals("docker", oldState.terminalBackend)
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
            assertNull(state.terminalBackend)
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
            assertNull(state.pendingReasoningLevel)
            assertNull(state.reasoningWireLevel)
            assertTrue(state.isLoading)
        }

    @Test
    fun testSessionInfo_reconcilesRequestedAndWireReasoningEffort() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data =
                        mapOf(
                            "model" to "gpt-5",
                            "provider" to "openai",
                            "reasoning_effort" to "ultra",
                            "reasoning_effort_wire" to "max",
                        ),
                    sessionId = sessionId,
                ),
            )
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals("ultra", state.reasoningLevel)
            assertEquals("max", state.reasoningWireLevel)

            // Other session's event is ignored
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data =
                        mapOf(
                            "model" to "gpt-5",
                            "provider" to "openai",
                            "reasoning_effort" to "low",
                        ),
                    sessionId = "other-session-id",
                ),
            )
            runCurrent()

            assertEquals("ultra", viewModel.uiState.value.reasoningLevel)
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
    @Test
    fun testSendReadiness_restBeforeResume_preservesDraftUntilAck() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            assertTrue(viewModel.uiState.value.isSessionReady)
            val captured = captureSends()
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val before = viewModel.uiState.value
            assertFalse(before.isSessionReady)
            assertFalse(viewModel.sendMessage("keep this draft"))
            assertEquals(before.messages, viewModel.uiState.value.messages)
            assertEquals(before.pendingAttachments, viewModel.uiState.value.pendingAttachments)
            assertFalse(viewModel.uiState.value.isAgentTyping)
            val id = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(id, mapOf("session_id" to "runtime-456")))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
            assertTrue(viewModel.sendMessage("keep this draft"))
            advanceUntilIdle()
            assertEquals(
                1,
                viewModel.uiState.value.messages
                    .count { it.content == "keep this draft" },
            )
        }

    @Test
    fun testSendReadiness_resumeBeforeRest_waitsForHydration() =
        runTest {
            stubSession456Rests(success = true)
            val response =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers { response.await() }
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()
            viewModel.switchSession("session-456")
            runCurrent()
            val id = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(id, mapOf("session_id" to "runtime-456")))
            runCurrent()
            assertFalse(viewModel.uiState.value.isSessionReady)
            assertFalse(viewModel.sendMessage("/queue draft"))
            response.complete(
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model
                        .SessionMessagesResponse(messages = emptyList()),
                ),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
        }

    @Test
    fun testSendReadiness_reconnectRejectsOldAckAndRequiresFreshHydration() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val oldId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            runCurrent()
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.RpcResult(oldId, mapOf("session_id" to "stale-runtime")))
            runCurrent()
            assertFalse(viewModel.uiState.value.isSessionReady)
            assertFalse(viewModel.sendMessage("draft"))
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSessionReady)
            val freshId = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(freshId, mapOf("session_id" to "fresh-runtime")))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
            viewModel.retryResumeSession()
            runCurrent()
            assertFalse(viewModel.uiState.value.isSessionReady)
        }

    @Test
    fun testSendReadiness_malformedAckNeverEnablesSend() =
        runTest {
            stubSession456Rests(success = true)
            val (viewModel, _) = createViewModelWithSession()
            val captured = captureSends()
            viewModel.switchSession("session-456")
            runCurrent()
            val id = captured.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(id, mapOf("session_id" to "")))
            runCurrent()
            assertFalse(viewModel.uiState.value.isSessionReady)
            assertFalse(viewModel.sendMessage("draft"))
            assertTrue(viewModel.uiState.value.isResumeRetrying)
        }

    @Test
    fun testSendReadiness_abandonedCreateCannotAcceptDraft() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            runCurrent()
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            runCurrent()
            assertFalse(viewModel.sendMessage("retain me"))
            assertFalse(viewModel.uiState.value.isAgentTyping)
        }

    @Test
    fun testSendReadiness_pendingCreateAppliesBackpressure() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            assertTrue(viewModel.sendMessage("first"))
            assertFalse(viewModel.sendMessage("second"))
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "second" },
            )
        }

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
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages = emptyList(),
                    ),
                )
        } else {
            // Relaxed mock returns a non-success response → NetworkResult.Failure.
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
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
            coEvery { mockApi.getSessionMessages(sessionId, any(), any(), any(), any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model
                        .SessionMessagesResponse(messages = emptyList()),
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
            assertEquals(
                "Cached hello",
                viewModel.uiState.value.messages[0]
                    .content,
            )
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
    fun testPickerModelSwitch_blanksMeterAndResolvesNewContextWindow_issue1103() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            // Profile default model is 900k
            coEvery { ApiClient.hermesApi.getModelInfo() } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.ModelInfoResponse(
                        model = "gpt-5.6-luna-900k",
                        provider = "openai-codex",
                        effective_context_length = 900_000L,
                    ),
                )

            // Initial session.info for default model
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("model" to "gpt-5.6-luna-900k", "provider" to "openai-codex"),
                ),
            )
            advanceUntilIdle()
            assertEquals("openai-codex/gpt-5.6-luna-900k", viewModel.uiState.value.currentSessionModel)

            var breakdownResult: Any =
                mapOf("context_max" to 900_000L, "context_used" to 10_000L)
            every { HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any()) } answers {
                CompletableDeferred<Any?>(breakdownResult)
            }
            viewModel.fetchContextUsage()
            advanceUntilIdle()
            assertEquals(900_000L, viewModel.uiState.value.fullContextTokens)

            // User picks Solar 200k via model picker (sendSlashModel)
            viewModel.sendSlashModel("nous", "upstage/solar-pro4:free")
            advanceUntilIdle()
            assertEquals("nous/upstage/solar-pro4:free", viewModel.uiState.value.currentSessionModel)
            assertNull("Picker model switch must immediately blank meter", viewModel.uiState.value.fullContextTokens)

            // Backend replies with session.info confirming Solar
            // Breakdown RPC returns JsonObject (from wire) with 524288 context_max
            breakdownResult =
                kotlinx.serialization.json.buildJsonObject {
                    put("context_max", kotlinx.serialization.json.JsonPrimitive(524288))
                    put("context_used", kotlinx.serialization.json.JsonPrimitive(20000))
                }
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("model" to "upstage/solar-pro4:free", "provider" to "nous"),
                ),
            )
            advanceUntilIdle()

            // Verified: meter resolves to Solar's 524288 window, NOT profile's 900k
            assertEquals(524288L, viewModel.uiState.value.fullContextTokens)

            // Periodic background sync with skipRestFallback=false must NOT overwrite with profile's 900k
            viewModel.fetchContextUsage(skipRestFallback = false)
            advanceUntilIdle()
            assertEquals(524288L, viewModel.uiState.value.fullContextTokens)
        }

    @Test
    fun testIsMatchingRpcModel_namespacedAndProviderMatching() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()

            // Current session model has provider prefix, rpc model retains internal namespace
            assertTrue(
                viewModel.isMatchingRpcModel(
                    "nous/upstage/solar-pro4:free",
                    "upstage/solar-pro4:free",
                ),
            )
            // Exact match
            assertTrue(
                viewModel.isMatchingRpcModel(
                    "upstage/solar-pro4:free",
                    "upstage/solar-pro4:free",
                ),
            )
            // Provider prefix with non-namespaced model
            assertTrue(
                viewModel.isMatchingRpcModel(
                    "openai/gpt-4o",
                    "gpt-4o",
                ),
            )
            // Never blindly strip model's own namespace: rpcModel missing internal namespace must not match
            assertFalse(
                viewModel.isMatchingRpcModel(
                    "nous/upstage/solar-pro4:free",
                    "solar-pro4:free",
                ),
            )
            // Mismatch
            assertFalse(
                viewModel.isMatchingRpcModel(
                    "nous/upstage/solar-pro4:free",
                    "claude-3",
                ),
            )
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
    fun testSessionInfo_hydratesFastModeState() =
        runTest {
            stubEmptySessionRests("session-a")
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.fastMode)

            // Fast = true in session.info
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("fast" to true, "service_tier" to "priority"),
                ),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.fastMode)
            assertFalse(viewModel.uiState.value.isFastModeChanging)

            // Fast = false / normal in session.info
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("fast" to false, "service_tier" to "normal"),
                ),
            )
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.fastMode)

            // Partial session.info without fast/service_tier does NOT clear state
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("fast" to true),
                ),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.fastMode)

            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("terminal_backend" to "tmux"),
                ),
            )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.fastMode) // preserved!
        }

    @Test
    fun testSessionResume_hydratesFastModeState() =
        runTest {
            stubEmptySessionRests("session-b")
            val (viewModel, _) = createViewModelWithSession()
            var resumeReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                val reqId = "resume-req-1"
                resumeReqId = reqId
                arg<((String) -> Unit)?>(2)?.invoke(reqId)
                reqId
            }

            viewModel.switchSession("session-b")
            runCurrent()

            // Simulate SESSION_RESUME response with fast=true in info
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = resumeReqId,
                    result =
                        mapOf(
                            "session_id" to "sess-runtime-1",
                            "resumed" to "session-b",
                            "info" to
                                mapOf(
                                    "model" to "gpt-4o",
                                    "provider" to "openai",
                                    "fast" to true,
                                    "service_tier" to "priority",
                                ),
                        ),
                ),
            )
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.fastMode)
            assertFalse(viewModel.uiState.value.isFastModeChanging)
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
                viewModel.uiState.value.messages
                    .any { it.content.contains("no longer available") },
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
                ApiClient.hermesApi.getSessionMessages(capture(fetchedSessions), any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model
                        .SessionMessagesResponse(messages = emptyList()),
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
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                val call = restCalls++
                if (call < 3) {
                    retrofit2.Response.error(
                        500,
                        okhttp3.ResponseBody.create(null, "{\"detail\":\"boom\"}"),
                    )
                } else {
                    retrofit2.Response.success(
                        com.m57.hermescontrol.data.model
                            .SessionMessagesResponse(messages = emptyList()),
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

    @Test
    fun testApprovalRequest_storesRequestIdAndChoices() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm -rf /tmp/x",
                    description = "dangerous command",
                    patternKeys = listOf("shell:rm"),
                    sessionId = null,
                    requestId = "req-1",
                    choices = listOf("once", "session", "always", "deny"),
                    allowPermanent = true,
                    smartDenied = false,
                ),
            )
            advanceUntilIdle()

            val msg =
                viewModel.uiState.value.messages
                    .first { it.content.contains("Approval Required") }
            assertEquals("req-1", msg.approvalInfo?.requestId)
            assertEquals(
                listOf("once", "session", "always", "deny"),
                msg.approvalInfo?.choices,
            )
            assertEquals(true, msg.approvalInfo?.allowPermanent)
        }

    @Test
    fun testRespondToApproval_mapsApproveToOnceAndSendsRequestId() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm",
                    description = "Dangerous",
                    patternKeys = null,
                    sessionId = null,
                    requestId = "req-42",
                    choices = listOf("once", "deny"),
                ),
            )
            advanceUntilIdle()

            viewModel.respondToApproval("approve")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.APPROVAL_RESPOND,
                    withArg { params ->
                        assertEquals(sessionId, params["session_id"])
                        assertEquals("once", params["choice"])
                        assertEquals("req-42", params["request_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testRespondToApproval_sessionChoice() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm",
                    description = "Dangerous",
                    patternKeys = null,
                    sessionId = null,
                    requestId = "req-7",
                    choices = listOf("once", "session", "always", "deny"),
                ),
            )
            advanceUntilIdle()

            viewModel.respondToApproval("session")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.APPROVAL_RESPOND,
                    withArg { params ->
                        assertEquals("session", params["choice"])
                        assertEquals("req-7", params["request_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testRespondToApproval_omitsRequestIdWhenAbsent() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "ls",
                    description = "Safe",
                    patternKeys = null,
                    sessionId = null,
                ),
            )
            advanceUntilIdle()

            viewModel.respondToApproval("deny")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.APPROVAL_RESPOND,
                    withArg { params ->
                        assertEquals("deny", params["choice"])
                        assertFalse(params.containsKey("request_id"))
                    },
                    any(),
                )
            }
        }

    @Test
    fun testRespondToApproval_triggersPendingReplay() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm",
                    description = "Dangerous",
                    patternKeys = null,
                    sessionId = null,
                    requestId = "req-9",
                    choices = listOf("once", "deny"),
                ),
            )
            advanceUntilIdle()

            viewModel.respondToApproval("once")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.APPROVAL_PENDING,
                    withArg { params ->
                        assertEquals(sessionId, params["session_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testApprovalFlow_prefersRuntimeSessionIdOverStorageId() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    "req-id-3",
                    mapOf(
                        "session_id" to "runtime-sid-999",
                        "stored_session_id" to "storage-uuid-111",
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("storage-uuid-111", viewModel.uiState.value.currentSessionId)

            mockEventsFlow.emit(
                WsEvent.ApprovalRequest(
                    command = "rm -rf /data",
                    description = "Dangerous operation",
                    patternKeys = null,
                    sessionId = null,
                    requestId = "req-runtime-test",
                    choices = listOf("once", "deny"),
                ),
            )
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.APPROVAL_RECEIVED,
                    withArg { params ->
                        assertEquals("runtime-sid-999", params["session_id"])
                        assertEquals("req-runtime-test", params["request_id"])
                    },
                    any(),
                )
            }

            viewModel.respondToApproval("once")
            advanceUntilIdle()

            verify {
                HermesWsClient.send(
                    WsMethods.APPROVAL_RESPOND,
                    withArg { params ->
                        assertEquals("runtime-sid-999", params["session_id"])
                        assertEquals("once", params["choice"])
                        assertEquals("req-runtime-test", params["request_id"])
                    },
                    any(),
                )
                HermesWsClient.send(
                    WsMethods.APPROVAL_PENDING,
                    withArg { params ->
                        assertEquals("runtime-sid-999", params["session_id"])
                    },
                    any(),
                )
            }
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

    @Test
    fun testSudoExpire_clearsMatchingPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sudoPrompt)

            mockEventsFlow.emit(WsEvent.SudoExpire(requestId = "sudo-1", sessionId = null))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun testSudoExpire_ignoresNonMatchingRequestId() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.SudoRequest(requestId = "sudo-2", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sudoPrompt)

            mockEventsFlow.emit(WsEvent.SudoExpire(requestId = "sudo-stale", sessionId = null))
            advanceUntilIdle()

            assertNotNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun testSecretExpire_clearsMatchingPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.SecretRequest(requestId = "secret-1", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.secretPrompt)

            mockEventsFlow.emit(WsEvent.SecretExpire(requestId = "secret-1", sessionId = null))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.secretPrompt)
        }

    @Test
    fun testSecretRequest_setsEnvVarAndPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SecretRequest(
                    requestId = "secret-1",
                    sessionId = null,
                    envVar = "GITHUB_TOKEN",
                    prompt = "Enter your GitHub token",
                ),
            )
            advanceUntilIdle()

            val prompt = viewModel.uiState.value.secretPrompt
            assertNotNull(prompt)
            assertEquals("GITHUB_TOKEN", prompt?.envVar)
            assertEquals("Enter your GitHub token", prompt?.prompt)
        }

    @Test
    fun testDismissSudo_sendsEmptyPassword() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.SudoRequest(requestId = "sudo-1", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sudoPrompt)

            viewModel.dismissSudo()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sudoPrompt)
            verify {
                HermesWsClient.send(
                    WsMethods.SUDO_RESPOND,
                    withArg { params ->
                        assertEquals(sessionId, params["session_id"])
                        assertEquals("", params["password"])
                        assertEquals("sudo-1", params["request_id"])
                    },
                    any(),
                )
            }
        }

    @Test
    fun testDismissSecret_sendsEmptyValue() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.SecretRequest(requestId = "secret-1", sessionId = null))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.secretPrompt)

            viewModel.dismissSecret()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.secretPrompt)
            verify {
                HermesWsClient.send(
                    WsMethods.SECRET_RESPOND,
                    withArg { params ->
                        assertEquals(sessionId, params["session_id"])
                        assertEquals("", params["value"])
                        assertEquals("secret-1", params["request_id"])
                    },
                    any(),
                )
            }
        }

    // ── Settings ─────────────────────────────────────────────────────────────

    @Test
    fun testRefreshSettings_updatesUiState() =
        runTest {
            // Given the default setup, init{} already calls refreshSettings() once,
            // so the initial state reflects the setUp defaults.
            val viewModel = createViewModel()
            advanceUntilIdle()
            with(viewModel.uiState.value) {
                assertTrue(typingEffectEnabled)
                assertEquals(30, typingEffectDelayMs)
                assertFalse(messageStatsEnabled)
                assertTrue(showUserMessageTokens)
                assertTrue(showAssistantMessageTokens)
                assertTrue(showTokensPerSecond)
                assertFalse(showModelProvider)
            }

            // When settings change after construction and refreshSettings() is re-invoked,
            // the UI state must reflect the NEW values — this proves refreshSettings()
            // re-reads AuthManager live (the real regression scenario).
            every { AuthManager.isTypingEffectEnabled() } returns false
            every { AuthManager.getTypingEffectDelayMs() } returns 50
            every { AuthManager.isMessageStatsEnabled() } returns true
            every { AuthManager.isUserMessageTokensEnabled() } returns false
            every { AuthManager.isAssistantMessageTokensEnabled() } returns false
            every { AuthManager.isTokensPerSecondEnabled() } returns false
            every { AuthManager.isModelProviderShown() } returns true
            viewModel.refreshSettings()
            advanceUntilIdle()

            // Then
            val state = viewModel.uiState.value
            assertFalse(state.typingEffectEnabled)
            assertEquals(50, state.typingEffectDelayMs)
            assertTrue(state.messageStatsEnabled)
            assertFalse(state.showUserMessageTokens)
            assertFalse(state.showAssistantMessageTokens)
            assertFalse(state.showTokensPerSecond)
            assertTrue(state.showModelProvider)
        }

    @Test
    fun testSendMessage_capturesCurrentUsageBeforeTurnAndSessionSwitchClearsIt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.SessionUsage(
                    data = mapOf("usage" to mapOf("output" to 1000L)),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()

            viewModel.sendMessage("prompt")
            advanceUntilIdle()

            assertEquals(
                1000L,
                viewModel.streamingState.value.turnUsageBaseline
                    ?.outputTokens,
            )
            assertTrue(viewModel.streamingState.value.turnUsageBaselineCaptured)
            viewModel.switchSession("session-other")

            assertNull(viewModel.streamingState.value.turnUsageBaseline)
            assertFalse(viewModel.streamingState.value.turnUsageBaselineCaptured)
        }

    @Test
    fun testToggleSearch() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertFalse(viewModel.searchState.isActive)

            viewModel.toggleSearch()
            advanceUntilIdle()

            assertTrue(viewModel.searchState.isActive)

            viewModel.setSearchQuery("test")
            advanceUntilIdle()

            viewModel.toggleSearch()
            advanceUntilIdle()

            assertFalse(viewModel.searchState.isActive)
            assertEquals("", viewModel.searchState.query)
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

            // Private staging failed before a durable receipt or outbound RPC existed.
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.content == "Here is an image" },
            )
            assertTrue(
                viewModel.uiState.value.pendingSends
                    .isEmpty(),
            )
            assertEquals("Here is an image", viewModel.uiState.value.composerTextToRestore)
            assertEquals(
                "content://dummy",
                viewModel.uiState.value.pendingAttachments
                    .single()
                    .uri,
            )
            verify(exactly = 0) { Log.e(any(), match { it.contains("Permission denied") }, any()) }
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
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
            val snapshotUri = mockk<Uri>()
            lateinit var snapshotFile: java.io.File
            every { Uri.parse("content://dummy") } returns mockUri
            every { Uri.fromFile(any()) } answers {
                snapshotFile = firstArg()
                snapshotUri
            }
            every { snapshotUri.toString() } returns "file://private-image-copy"
            every { Uri.parse("file://private-image-copy") } returns snapshotUri
            val contentResolver = mockk<ContentResolver>()
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(mockUri) } returns
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            every { contentResolver.openInputStream(snapshotUri) } answers { snapshotFile.inputStream() }

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
            assertTrue("encoded attachment cache must be cleaned", attachmentCacheDir.listFiles().isNullOrEmpty())
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
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
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
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                messagesCallCount += 1
                // Call 1 = the order=latest probe (discarded when the legacy
                // backend echoes no pagination — issue #859).
                if (messagesCallCount == 2) {
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
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
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
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
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

    // ── Newest-anchored paging (issue #859) ───────────────────────────────

    @Test
    fun testInitialLoad_latestOrder_pagesFromNewest() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            val captured = mutableListOf<Triple<Int, Int, String?>>()
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                captured.add(Triple(arg<Int>(2), arg<Int>(1), arg<String?>(3)))
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            (1..150).map { i ->
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = i,
                                    role = "assistant",
                                    content = JsonPrimitive("Msg $i"),
                                )
                            },
                        pagination =
                            com.m57.hermescontrol.data.model.PaginationInfo(
                                limit = 150,
                                offset = 0,
                                order = "latest",
                                returned = 150,
                            ),
                    ),
                )
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            // One request: the newest page at offset 0 with order=latest —
            // no count-based anchor, no sessions-list fetch.
            assertEquals(1, captured.size)
            assertEquals(Triple(0, 150, "latest"), captured[0])
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            assertEquals(150, viewModel.uiState.value.messages.size)
            // Stable keys come from the server row id, not the from-end position.
            assertEquals(
                "rest-session-456-150",
                viewModel.uiState.value.messages
                    .last()
                    .id,
            )
        }

    @Test
    fun testInitialLoad_latestOrder_shortPage_hasNoOlder() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 1,
                                    role = "assistant",
                                    content = JsonPrimitive("Only Msg"),
                                ),
                            ),
                        pagination =
                            com.m57.hermescontrol.data.model.PaginationInfo(
                                limit = 150,
                                offset = 0,
                                order = "latest",
                                returned = 1,
                            ),
                    ),
                )

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.hasOlderMessages)
            assertEquals(1, viewModel.uiState.value.messages.size)
        }

    @Test
    fun testLoadOlderMessages_latestOrder_increasesOffset() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            val captured = mutableListOf<Triple<Int, Int, String?>>()
            var page = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                captured.add(Triple(arg<Int>(2), arg<Int>(1), arg<String?>(3)))
                page += 1
                val (offset, returned) =
                    when (page) {
                        1 -> 0 to 150
                        2 -> 150 to 150
                        else -> 300 to 50
                    }
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            (1..returned).map { i ->
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = offset + i,
                                    role = "assistant",
                                    content = JsonPrimitive("Page $page Msg $i"),
                                )
                            },
                        pagination =
                            com.m57.hermescontrol.data.model.PaginationInfo(
                                limit = 150,
                                offset = offset,
                                order = "latest",
                                returned = returned,
                            ),
                    ),
                )
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasOlderMessages)

            // Older pages INCREASE the from-end offset, always full-size.
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(Triple(150, 150, "latest"), captured[1])
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            assertEquals(300, viewModel.uiState.value.messages.size)

            // Short final page: the oldest boundary — pagination stops.
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(Triple(300, 150, "latest"), captured[2])
            assertFalse(viewModel.uiState.value.hasOlderMessages)
            assertEquals(350, viewModel.uiState.value.messages.size)
        }

    @Test
    fun testSync_latestOrder_grownTranscript_keepsNewestMessage() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            val mockApi = ApiClient.hermesApi
            var page = 0
            coEvery {
                mockApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                page += 1
                // Hydration serves rows 1..150; the transcript then grows by 10
                // and the sync refetches the newest page (rows 11..160).
                val (from, to) = if (page == 1) 1 to 150 else 11 to 160
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            (from..to).map { i ->
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = i,
                                    role = "assistant",
                                    content = JsonPrimitive("Msg $i"),
                                )
                            },
                        pagination =
                            com.m57.hermescontrol.data.model.PaginationInfo(
                                limit = 150,
                                offset = 0,
                                order = "latest",
                                returned = 150,
                            ),
                    ),
                )
            }

            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertEquals(150, viewModel.uiState.value.messages.size)

            viewModel.syncCurrentSession()
            advanceUntilIdle()

            // 160 rows: the 10 new ones appended, the existing 150 kept —
            // no duplicates, no dropped newest copy (stable row-id keys).
            assertEquals(160, viewModel.uiState.value.messages.size)
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.id == "rest-session-456-160" },
            )
        }

    @Test
    fun paging_staleCacheBeforeRestDoesNotBecomeTheLatestTail() =
        runTest {
            val cachedIds = seedPagingCache(3)
            val response = CompletableDeferred<retrofit2.Response<SessionMessagesResponse>>()
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers { response.await() }

            viewModel.switchSession("session-456")
            runCurrent()
            assertEquals(
                cachedIds,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            response.complete(pagingResponse(100..102))
            advanceUntilIdle()

            val expected = cachedIds + (100..102).map { "rest-session-456-$it" }
            assertEquals(
                expected,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            viewModel.syncCurrentSession()
            advanceUntilIdle()
            assertEquals(
                expected,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertTrue(fakeRepo.dao.idsForSession("session-456").containsAll(cachedIds))
        }

    @Test
    fun paging_staleCacheAfterRestDoesNotBecomeTheLatestTail() =
        runTest {
            val cachedIds = seedPagingCache(3)
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..102)

            viewModel.switchSession("session-456")
            runCurrent()
            cacheRead.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                cachedIds + (100..102).map { "rest-session-456-$it" },
                viewModel.uiState.value.messages
                    .map { it.id },
            )
        }

    @Test
    fun paging_coldRestartPendingLocalRemainsAfterCanonicalWindow() =
        runTest {
            val pending =
                ChatMessage(
                    id = "pending-local",
                    role = MessageRole.USER,
                    content = "not delivered yet",
                    messageProvenance = MessageProvenance.LOCAL_PENDING,
                )
            fakeRepo.persistMessage(pending, "session-456")
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..102)

            viewModel.switchSession("session-456")
            runCurrent()
            cacheRead.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                (100..102).map { "rest-session-456-$it" } + pending.id,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
        }

    @Test
    fun paging_restoredLegacyUserBlockBeforeRestDoesNotFollowNewestReply() =
        runTest { verifyRestoredLegacyUserPlacement(cacheFirst = true) }

    @Test
    fun paging_restoredLegacyUserBlockAfterRestDoesNotFollowNewestReply() =
        runTest { verifyRestoredLegacyUserPlacement(cacheFirst = false) }

    private suspend fun TestScope.verifyRestoredLegacyUserPlacement(cacheFirst: Boolean) {
        val restored =
            (1..3).map { index ->
                ChatMessage(
                    id = "restored-legacy-$index",
                    role = MessageRole.USER,
                    content = "Earlier prompt $index",
                    // Placement must not infer delivery or chronology from wall-clock age.
                    timestamp = Long.MAX_VALUE - index,
                )
            }
        val pending =
            ChatMessage(
                id = "real-pending",
                role = MessageRole.USER,
                content = "Unsent current prompt",
                timestamp = 1L,
                messageProvenance = MessageProvenance.LOCAL_PENDING,
            )
        (restored + pending).forEach { fakeRepo.persistMessage(it, "session-456") }
        val cacheRead = CompletableDeferred<Unit>()
        if (!cacheFirst) fakeRepo.dao.beforeRead = { cacheRead.await() }
        val response = CompletableDeferred<retrofit2.Response<SessionMessagesResponse>>()
        val (viewModel, _) = createViewModelWithSession()
        val api = ApiClient.hermesApi
        coEvery {
            api.getSessionMessages("session-456", any(), any(), any(), any())
        } coAnswers { response.await() }

        viewModel.switchSession("session-456")
        runCurrent()
        response.complete(pagingResponse(100..102))
        runCurrent()
        cacheRead.complete(Unit)
        advanceUntilIdle()

        val expected = restored.map { it.id } + (100..102).map { "rest-session-456-$it" } + pending.id
        assertEquals(
            "Restored legacy prompts must not form a stale block after the newest reply",
            expected,
            viewModel.uiState.value.messages
                .map { it.id },
        )
        restored.forEach { original ->
            val retained =
                viewModel.uiState.value.messages
                    .single { it.id == original.id }
            assertEquals(original.content, retained.content)
            assertEquals(original.timestamp, retained.timestamp)
            assertEquals(MessageProvenance.UNKNOWN, retained.messageProvenance)
            assertNull("Moving a row must not invent delivery confirmation", retained.canonicalRestId)
            assertFalse("Restored placement is not proven historical delivery", retained.isHistoricalCache)
        }
        assertTrue(fakeRepo.dao.idsForSession("session-456").containsAll(restored.map { it.id } + pending.id))
        assertEquals(0, fakeRepo.dao.fullSessionReads)
        coVerify(exactly = 1) {
            api.getSessionMessages("session-456", any(), any(), any(), any())
        }
        verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }

        viewModel.syncCurrentSession()
        advanceUntilIdle()
        assertEquals(
            expected,
            viewModel.uiState.value.messages
                .map { it.id },
        )
        verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
    }

    @Test
    fun paging_queuePromptsPersistPendingProvenanceAfterRestart() = runTest { verifyQueuedPromptRestoration("/queue") }

    @Test
    fun paging_queueAliasPromptsPersistPendingProvenanceAfterRestart() = runTest { verifyQueuedPromptRestoration("/q") }

    private suspend fun TestScope.verifyQueuedPromptRestoration(command: String) {
        val (viewModel, sessionId) = createViewModelWithSession()
        assertTrue(viewModel.sendMessage("$command pending queued prompt"))
        advanceUntilIdle()
        val queued =
            viewModel.uiState.value.messages
                .single { it.role == MessageRole.USER }
        assertEquals("pending queued prompt", queued.content)
        assertEquals(
            "Queued prompts must retain durable pending provenance",
            MessageProvenance.LOCAL_PENDING.name,
            fakeRepo.dao.getMessage(queued.id)?.messageProvenance,
        )

        val api = ApiClient.hermesApi
        coEvery {
            api.getSessionMessages(sessionId, any(), any(), any(), any())
        } returns pagingResponse(100..102)
        val recreated = createViewModel()
        advanceUntilIdle()
        recreated.switchSession(sessionId)
        advanceUntilIdle()

        val restored = recreated.uiState.value.messages
        assertEquals(queued.id, restored.last().id)
        assertTrue(restored.indexOfFirst { it.id == "rest-$sessionId-102" } in 0 until restored.lastIndex)
        assertEquals(MessageProvenance.LOCAL_PENDING, restored.last().messageProvenance)
        assertNull(restored.last().canonicalRestId)
        assertFalse(restored.last().isHistoricalCache)
        verify(exactly = 1) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
    }

    @Test
    fun paging_coldRestartReceiptBackedUnknownUserRemainsLiveTail() =
        runTest {
            val receiptBacked =
                ChatMessage(
                    id = "receipt-backed-unknown-user",
                    role = MessageRole.USER,
                    content = "possibly accepted before restart",
                    messageProvenance = MessageProvenance.UNKNOWN,
                )
            val rejected =
                ChatMessage(
                    id = "receipt-backed-rejected-user",
                    role = MessageRole.USER,
                    content = "rejected before restart",
                    messageProvenance = MessageProvenance.UNKNOWN,
                )
            fakeRepo.persistMessage(receiptBacked, "session-456")
            fakeRepo.persistMessage(rejected, "session-456")
            val store = ChatSendStore()
            val scope =
                listOf(
                    AuthManager.getBaseUrl(),
                    AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                    AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                ).joinToString("\u001f")
            store.put(
                PendingSend(
                    id = receiptBacked.id,
                    scope = scope,
                    sessionId = "session-456",
                    text = receiptBacked.content,
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.UNKNOWN,
                ),
            )
            store.put(
                PendingSend(
                    id = rejected.id,
                    scope = scope,
                    sessionId = "session-456",
                    text = rejected.content,
                    mode = BusySendMode.QUEUE,
                    state = PendingSendState.REJECTED,
                ),
            )
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession(sendStore = store)
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..102)

            viewModel.switchSession("session-456")
            runCurrent()
            cacheRead.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf(rejected.id) + (100..102).map { "rest-session-456-$it" } + receiptBacked.id,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            val rejectedRetained =
                viewModel.uiState.value.messages
                    .single { it.id == rejected.id }
            assertTrue(rejectedRetained.isRestoredUnconfirmed)
            assertEquals(MessageProvenance.UNKNOWN, rejectedRetained.messageProvenance)
            val retained =
                viewModel.uiState.value.messages
                    .single { it.id == receiptBacked.id }
            assertFalse(retained.isHistoricalCache)
            assertFalse(retained.isRestoredUnconfirmed)
            assertEquals(MessageProvenance.UNKNOWN, retained.messageProvenance)
            assertNull(retained.canonicalRestId)
            assertEquals(PendingSendState.UNKNOWN, store.all().single { it.id == receiptBacked.id }.state)
            assertEquals(PendingSendState.REJECTED, store.all().single { it.id == rejected.id }.state)
            verify(exactly = 0) {
                HermesWsClient.sendMessage(any(), receiptBacked.content, any(), any())
            }
        }

    @Test
    fun paging_coldRestartLegacyUnknownUserRemainsConservativelyUnconfirmed() =
        runTest {
            val ambiguous =
                ChatMessage(
                    id = "legacy-unknown-user",
                    role = MessageRole.USER,
                    content = "possibly unsent before migration",
                )
            fakeRepo.persistMessage(ambiguous, "session-456")
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..102)
            viewModel.switchSession("session-456")
            runCurrent()
            cacheRead.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                listOf(ambiguous.id) + (100..102).map { "rest-session-456-$it" },
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            val retained =
                viewModel.uiState.value.messages
                    .single { it.id == ambiguous.id }
            assertFalse(retained.isHistoricalCache)
            assertTrue(retained.isRestoredUnconfirmed)
            assertEquals(MessageProvenance.UNKNOWN, retained.messageProvenance)
            assertNull(retained.canonicalRestId)
            assertTrue(fakeRepo.dao.idsForSession("session-456").contains(ambiguous.id))
        }

    @Test
    fun paging_coldRestartPermanentLocalKeepsLocalOrdering() =
        runTest {
            val localCommand =
                ChatMessage(
                    id = "local-command",
                    role = MessageRole.USER,
                    content = "/help",
                )
            fakeRepo.persistMessage(localCommand, "session-456")
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..102)

            viewModel.switchSession("session-456")
            runCurrent()
            cacheRead.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                (100..102).map { "rest-session-456-$it" } + localCommand.id,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
        }

    @Test
    fun paging_staleCacheDoesNotDemoteOptimisticIdentity() =
        runTest {
            val cachedIds = seedPagingCache(3)
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                ApiClient.hermesApi.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(100..102)

            viewModel.switchSession("session-456")
            runCurrent()
            val attachment = Attachment("content://test/file", "file.txt", "text/plain")
            val optimistic =
                ChatMessage(
                    id = cachedIds[1],
                    role = MessageRole.USER,
                    content = "Cached message 2",
                    attachments = listOf(attachment),
                )

            @Suppress("UNCHECKED_CAST")
            val state =
                ChatViewModel::class.java
                    .getDeclaredField("_uiState")
                    .apply { isAccessible = true }
                    .get(viewModel) as MutableStateFlow<Any>
            state.value = viewModel.uiState.value.copy(messages = viewModel.uiState.value.messages + optimistic)
            cacheRead.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf(cachedIds[0], cachedIds[2]) + (100..102).map { "rest-session-456-$it" } + cachedIds[1],
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            val retained =
                viewModel.uiState.value.messages
                    .last()
            assertFalse(retained.isHistoricalCache)
            assertEquals(listOf(attachment), retained.attachments)
        }

    // Chat paging: deterministic regressions, with no real network or wall-clock timing.
    private fun seedPagingCache(
        count: Int,
        sameTimestamp: Boolean = false,
    ): List<String> =
        (1..count).map { index ->
            val id = "cached-${index.toString().padStart(5, '0')}"
            fakeRepo.dao.addMessageDirect(
                com.m57.hermescontrol.data.local.ChatMessageEntity(
                    id = id,
                    sessionId = "session-456",
                    role = "user",
                    content = "Cached message $index",
                    timestamp = if (sameTimestamp) 1L else index.toLong(),
                ),
            )
            id
        }

    private fun pagingResponse(
        rows: IntRange,
        offset: Int = 0,
    ): retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse> =
        retrofit2.Response.success(
            com.m57.hermescontrol.data.model.SessionMessagesResponse(
                messages =
                    rows.map { index ->
                        com.m57.hermescontrol.data.model.SessionMessage(
                            id = index,
                            role = "assistant",
                            content = JsonPrimitive("Server message $index"),
                            timestamp = JsonPrimitive(index),
                        )
                    },
                pagination =
                    com.m57.hermescontrol.data.model.PaginationInfo(
                        limit = 150,
                        offset = offset,
                        order = "latest",
                        returned = rows.count(),
                    ),
            ),
        )

    @Test
    fun paging_syncNewestRestEcho_preservesLiveIdAndCompletionInOneBubble() =
        runTest {
            val api = ApiClient.hermesApi
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(IntRange.EMPTY)
            viewModel.switchSession("session-456")
            advanceUntilIdle()

            // A completed live reply has no confirmed REST identity until its echo arrives.
            val live =
                ChatMessage(
                    id = "live-reply",
                    role = MessageRole.ASSISTANT,
                    content = "Server message 42",
                    timestamp = 42_000L,
                    completionId = "comp",
                    restId = null,
                )

            @Suppress("UNCHECKED_CAST")
            val state =
                ChatViewModel::class.java
                    .getDeclaredField("_uiState")
                    .apply { isAccessible = true }
                    .get(viewModel) as MutableStateFlow<Any>
            state.value = viewModel.uiState.value.copy(messages = listOf(live))
            advanceUntilIdle()
            assertNull(
                viewModel.uiState.value.messages
                    .single()
                    .restId,
            )
            assertEquals(
                "comp",
                viewModel.uiState.value.messages
                    .single()
                    .completionId,
            )

            coEvery {
                api.getSessionMessages("session-456", 150, 0, "latest", any())
            } returns pagingResponse(42..42)

            viewModel.syncCurrentSession()
            advanceUntilIdle()

            coVerify(exactly = 2) {
                api.getSessionMessages("session-456", 150, 0, "latest", any())
            }
            val messages = viewModel.uiState.value.messages
            assertEquals("The newest REST echo must confirm the live reply without adding a bubble", 1, messages.size)
            val message = messages.single()
            assertEquals("Server message 42", message.content)
            assertEquals("live-reply", message.id)
            assertEquals("rest-session-456-42", message.restId)
            assertEquals("comp", message.completionId)
        }

    @Test
    fun paging_largeCache_initialReadIsBoundedAtDao() =
        runTest {
            val expectedIds = seedPagingCache(10_000)
            stubSession456Rests(success = false)
            val (viewModel, _) = createViewModelWithSession()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertEquals(
                expectedIds.takeLast(150),
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertEquals("Loading all rows then taking a suffix is not paging", 0, fakeRepo.dao.fullSessionReads)
            assertEquals(expectedIds.toSet(), fakeRepo.dao.idsForSession("session-456"))
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun paging_offlinePagesRetainEveryRowWithEqualTimestamps() =
        runTest {
            val expectedIds = seedPagingCache(350, sameTimestamp = true)
            stubSession456Rests(success = false)
            val (viewModel, _) = createViewModelWithSession()
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertEquals(
                expectedIds.takeLast(150),
                viewModel.uiState.value.messages
                    .map { it.id },
            )

            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(
                expectedIds.takeLast(300),
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            assertFalse(viewModel.uiState.value.isLoadingOlder)

            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(
                expectedIds,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertFalse(viewModel.uiState.value.hasOlderMessages)
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertEquals(expectedIds.toSet(), fakeRepo.dao.idsForSession("session-456"))
            assertEquals(0, fakeRepo.dao.fullSessionReads)
        }

    @Test
    fun paging_cacheEchoEnrichesExistingUuidBeforeEarlierRepeatedOccurrencesArrive() =
        runTest {
            val roles = listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL)
            for (index in 1..303) {
                val occurrence =
                    when (index) {
                        in 1..3 -> index - 1
                        in 151..153 -> index - 151
                        in 301..303 -> index - 301
                        else -> null
                    }
                val role = occurrence?.let { roles[it] } ?: MessageRole.SYSTEM
                val id =
                    when (index) {
                        in 1..3 -> "rest-session-456-${10 + requireNotNull(occurrence)}"
                        in 151..153 -> "rest-session-456-${20 + requireNotNull(occurrence)}"
                        in 301..303 -> "uuid-${requireNotNull(occurrence)}"
                        else -> "filler-$index"
                    }
                fakeRepo.dao.addMessageDirect(
                    com.m57.hermescontrol.data.local.ChatMessageEntity(
                        id = id,
                        sessionId = "session-456",
                        role = role.name,
                        content =
                            when (role) {
                                MessageRole.TOOL -> """{"output":"ok"}"""
                                MessageRole.SYSTEM -> "filler $index"
                                else -> "continue"
                            },
                        timestamp = index.toLong(),
                        toolName = if (role == MessageRole.TOOL && index > 300) "terminal" else null,
                    ),
                )
            }
            val storedIds = fakeRepo.dao.idsForSession("session-456")
            stubSession456Rests(success = false)
            val (viewModel, _) = createViewModelWithSession()
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertEquals(150, viewModel.uiState.value.messages.size)

            viewModel.loadOlderMessages()
            advanceUntilIdle()
            for (index in roles.indices) {
                val retained =
                    viewModel.uiState.value.messages
                        .single { it.id == "uuid-$index" }
                assertEquals("rest-session-456-${20 + index}", retained.restId)
            }
            assertEquals(297, viewModel.uiState.value.messages.size)

            viewModel.loadOlderMessages()
            advanceUntilIdle()
            val messages = viewModel.uiState.value.messages
            assertEquals(300, messages.size)
            for (index in roles.indices) {
                assertEquals(2, messages.count { it.role == roles[index] })
                assertTrue(messages.any { it.id == "rest-session-456-${10 + index}" })
                assertTrue(messages.any { it.id == "uuid-$index" })
            }
            assertEquals("terminal", messages.single { it.id == "uuid-2" }.toolName)
            assertEquals(storedIds, fakeRepo.dao.idsForSession("session-456"))
            assertFalse(viewModel.uiState.value.hasOlderMessages)
        }

    @Test
    fun paging_reusedToolPersistsCanonicalKeyWithoutReplacingLiveKey() =
        runTest {
            fakeRepo.dao.addMessageDirect(
                com.m57.hermescontrol.data.local.ChatMessageEntity(
                    id = "uuid-tool",
                    sessionId = "session-456",
                    role = "TOOL",
                    content = """{"output":"ok"}""",
                    timestamp = 1L,
                    toolName = "terminal",
                ),
            )
            val api = ApiClient.hermesApi
            val response = pagingResponse(42..42).body()!!
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    response.copy(
                        messages =
                            response.messages.map {
                                it.copy(
                                    role = "tool",
                                    content = JsonPrimitive("""{"output":"ok"}"""),
                                )
                            },
                    ),
                )
            val (viewModel, _) = createViewModelWithSession()
            viewModel.switchSession("session-456")
            advanceUntilIdle()

            val message =
                viewModel.uiState.value.messages
                    .single()
            assertEquals("uuid-tool", message.id)
            assertEquals("rest-session-456-42", message.restId)
            assertEquals("terminal", message.toolName)
            assertEquals(setOf("uuid-tool", "rest-session-456-42"), fakeRepo.dao.idsForSession("session-456"))
        }

    @Test
    fun paging_emptyServerPageDoesNotHideOlderOfflineHistory() =
        runTest {
            val api = ApiClient.hermesApi
            val expectedIds = seedPagingCache(350)
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(IntRange.EMPTY)

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertEquals(150, viewModel.uiState.value.messages.size)
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(300, viewModel.uiState.value.messages.size)
            assertEquals(expectedIds.toSet(), fakeRepo.dao.idsForSession("session-456"))
        }

    @Test
    fun paging_cacheFirst_serverHydrationDoesNotInflateInitialWindow() =
        runTest {
            val api = ApiClient.hermesApi
            seedPagingCache(10_000)
            val response =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers { response.await() }

            viewModel.switchSession("session-456")
            runCurrent()
            val cacheSize = viewModel.uiState.value.messages.size
            response.complete(pagingResponse(1..150))
            advanceUntilIdle()

            assertEquals(150, cacheSize)
            assertEquals(300, viewModel.uiState.value.messages.size)
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            assertEquals(0, fakeRepo.dao.fullSessionReads)
        }

    @Test
    fun paging_serverFirst_cacheCursorStillAllowsOfflineHistory() =
        runTest {
            val api = ApiClient.hermesApi
            seedPagingCache(350)
            val cacheRead = CompletableDeferred<Unit>()
            fakeRepo.dao.beforeRead = { cacheRead.await() }
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(IntRange.EMPTY)

            viewModel.switchSession("session-456")
            runCurrent()
            cacheRead.complete(Unit)
            advanceUntilIdle()

            assertEquals(150, viewModel.uiState.value.messages.size)
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(300, viewModel.uiState.value.messages.size)
            assertFalse(viewModel.uiState.value.isLoadingOlder)
        }

    @Test
    fun paging_pendingOlderPageIsSuppressedAndFailureAllowsRetry() =
        runTest {
            val api = ApiClient.hermesApi
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(151..300)
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val pending =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } coAnswers { pending.await() }

            viewModel.loadOlderMessages()
            runCurrent()
            repeat(5) { viewModel.loadOlderMessages() }
            runCurrent()
            coVerify(exactly = 1) {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            }
            assertTrue(viewModel.uiState.value.isLoadingOlder)
            pending.complete(retrofit2.Response.error(500, okhttp3.ResponseBody.create(null, "test failure")))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertTrue(viewModel.uiState.value.hasOlderMessages)

            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } returns pagingResponse(1..150, offset = 150)
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(300, viewModel.uiState.value.messages.size)
            assertFalse(viewModel.uiState.value.isLoadingOlder)
        }

    @Test
    fun paging_oldSessionResponseCannotReplaceNewSession() =
        runTest {
            val api = ApiClient.hermesApi
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(151..300)
            coEvery {
                api.getSessionMessages("session-other", any(), any(), any(), any())
            } returns pagingResponse(900..900)
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val pending =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } coAnswers { pending.await() }

            viewModel.loadOlderMessages()
            runCurrent()
            viewModel.switchSession("session-other")
            runCurrent()
            pending.complete(pagingResponse(1..150, offset = 150))
            advanceUntilIdle()

            assertEquals("session-other", viewModel.uiState.value.currentSessionId)
            assertEquals(
                listOf("rest-session-other-900"),
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertFalse(viewModel.uiState.value.isLoadingOlder)
        }

    /** Holds CPU continuations without sleeping or starting a real thread. */
    private class QueuedHistoryDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        val pending: Boolean get() = queue.isNotEmpty()

        override fun dispatch(
            context: kotlin.coroutines.CoroutineContext,
            block: Runnable,
        ) {
            queue.addLast(block)
        }

        fun runNext() = queue.removeFirst().run()
    }

    private fun TestScope.drainHistory(dispatcher: QueuedHistoryDispatcher) {
        repeat(100) {
            runCurrent()
            if (!dispatcher.pending) return
            dispatcher.runNext()
        }
        error("History did not settle after 100 CPU continuations")
    }

    @Test
    fun paging_liveMessageArrivingBeforeComputedSnapshotCommitSurvives() =
        runTest {
            val api = ApiClient.hermesApi
            val cpu = QueuedHistoryDispatcher()
            val (viewModel, _) = createViewModelWithSession(historyDispatcher = cpu)
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(1..150)
            viewModel.switchSession("session-456")
            runCurrent()
            // Cache query runs first; hydration has already captured an empty snapshot.
            cpu.runNext()
            runCurrent()
            mockEventsFlow.emit(WsEvent.MessageComplete("Live during merge", "session-456"))
            cpu.runNext()
            // The queued WS event runs before the computed result can commit on Main.
            runCurrent()
            drainHistory(cpu)

            val messages = viewModel.uiState.value.messages
            assertEquals(151, messages.size)
            assertEquals(1, messages.count { it.content == "Live during merge" })
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun paging_sessionSwitchDiscardsQueuedCpuResult() =
        runTest {
            val api = ApiClient.hermesApi
            val cpu = QueuedHistoryDispatcher()
            val (viewModel, _) = createViewModelWithSession(historyDispatcher = cpu)
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(1..150)
            coEvery {
                api.getSessionMessages("session-other", any(), any(), any(), any())
            } returns pagingResponse(900..900)
            viewModel.switchSession("session-456")
            runCurrent()
            cpu.runNext()
            runCurrent()
            cpu.runNext()
            viewModel.switchSession("session-other")
            drainHistory(cpu)

            assertEquals("session-other", viewModel.uiState.value.currentSessionId)
            assertEquals(
                listOf("rest-session-other-900"),
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun paging_reconnectInvalidatesPendingOlderRequestAndItsCursor() =
        runTest {
            val api = ApiClient.hermesApi
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(301..450)
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val oldResumeId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(oldResumeId, mapOf("session_id" to "runtime-456")))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
            val pending =
                CompletableDeferred<retrofit2.Response<com.m57.hermescontrol.data.model.SessionMessagesResponse>>()
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } coAnswers { pending.await() }
            viewModel.loadOlderMessages()
            runCurrent()

            coEvery {
                api.getSessionMessages("session-456", 150, 0, "latest", any())
            } returns pagingResponse(501..650)
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            runCurrent()
            assertFalse(viewModel.uiState.value.isSessionReady)
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertFalse(viewModel.sendMessage("preserved draft"))
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            runCurrent()
            pending.complete(pagingResponse(1..150, offset = 150))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.id == "rest-session-456-1" },
            )

            mockEventsFlow.emit(WsEvent.RpcResult(oldResumeId, mapOf("session_id" to "stale-runtime")))
            runCurrent()
            assertFalse(viewModel.uiState.value.isSessionReady)
            val freshResumeId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(freshResumeId, mapOf("session_id" to "fresh-runtime")))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } returns pagingResponse(351..500, offset = 150)
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.id == "rest-session-456-500" },
            )
            coVerify(exactly = 0) {
                api.getSessionMessages("session-456", 150, 300, "latest", any())
            }
        }

    @Test
    fun paging_offlineCacheRemainsReadableAndFreshResumeRestoresSendReadiness() =
        runTest {
            val ids = seedPagingCache(350)
            stubSession456Rests(success = false)
            val (viewModel, _) = createViewModelWithSession()
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSessionReady)
            assertFalse(viewModel.sendMessage("draft"))
            mockConnectionStatus.value = ConnectionStatus.NO_NETWORK
            runCurrent()
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(
                ids.takeLast(300),
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            val api = ApiClient.hermesApi
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(IntRange.EMPTY)
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSessionReady)
            val resumeId = sentRequestMethods.last { it.first == WsMethods.SESSION_RESUME }.second
            mockEventsFlow.emit(WsEvent.RpcResult(resumeId, mapOf("session_id" to "runtime-456")))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isSessionReady)
            assertTrue(viewModel.sendMessage("draft"))
            advanceUntilIdle()
            assertEquals(
                1,
                viewModel.uiState.value.messages
                    .count { it.content == "draft" },
            )
        }

    @Test
    fun paging_cacheAndServerCursorsAdvanceIndependently() =
        runTest {
            val api = ApiClient.hermesApi
            val cachedIds = seedPagingCache(350)
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(151..300)
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            repeat(2) {
                viewModel.loadOlderMessages()
                advanceUntilIdle()
            }
            coVerify(exactly = 1) {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            }
            assertEquals(
                cachedIds.toSet() + (151..300).map { "rest-session-456-$it" },
                viewModel.uiState.value.messages
                    .map { it.id }
                    .toSet(),
            )
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } returns pagingResponse(1..150, offset = 150)
            viewModel.loadOlderMessages()
            advanceUntilIdle()

            // The remaining cache page contains only the latest REST rows already on screen.
            // This same action must reach the independent server cursor, not stop on those echoes.
            coVerify(exactly = 1) {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            }
            val expectedIds = cachedIds.toSet() + (1..300).map { "rest-session-456-$it" }
            assertEquals(650, viewModel.uiState.value.messages.size)
            assertEquals(
                expectedIds,
                viewModel.uiState.value.messages
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(expectedIds, fakeRepo.dao.idsForSession("session-456"))
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertEquals(0, fakeRepo.dao.fullSessionReads)
            assertTrue(fakeRepo.dao.pageLimits.all { it == 151 })
        }

    @Test
    fun paging_latestGrowthBetweenPagesAndSyncNeverSkipsOlderRows() =
        runTest {
            val api = ApiClient.hermesApi
            val (viewModel, _) = createViewModelWithSession()
            var total = 450
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } coAnswers {
                val offset = arg<Int>(2)
                val end = (total - offset).coerceAtLeast(0)
                val start = (end - 150).coerceAtLeast(0) + 1
                pagingResponse(start..end, offset)
            }
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            total = 460
            viewModel.syncCurrentSession()
            advanceUntilIdle()
            repeat(2) {
                viewModel.loadOlderMessages()
                advanceUntilIdle()
            }

            assertEquals(
                (1..460).map { "rest-session-456-$it" }.toSet(),
                viewModel.uiState.value.messages
                    .map { it.id }
                    .toSet(),
            )
            assertFalse(viewModel.uiState.value.hasOlderMessages)
        }

    @Test
    fun paging_rawHiddenRowsStillAdvanceServerCursor() =
        runTest {
            val api = ApiClient.hermesApi
            val (viewModel, _) = createViewModelWithSession()
            val response = pagingResponse(1..150).body()!!
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns
                retrofit2.Response.success(
                    response.copy(messages = response.messages.map { it.copy(content = JsonPrimitive("")) }),
                )
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } returns pagingResponse(IntRange.EMPTY, offset = 150)
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.hasOlderMessages)
            coVerify(exactly = 1) {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            }
        }

    @Test
    fun paging_cacheReadExceptionSettlesAndAllowsRetry() =
        runTest {
            val ids = seedPagingCache(350)
            stubSession456Rests(success = false)
            fakeRepo.dao.beforeRead = { error("Synthetic Room read failure") }
            val (viewModel, _) = createViewModelWithSession()
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            fakeRepo.dao.beforeRead = {}
            viewModel.loadOlderMessages()
            advanceUntilIdle()

            assertEquals(
                ids.takeLast(150),
                viewModel.uiState.value.messages
                    .map { it.id },
            )
            assertFalse(viewModel.uiState.value.isLoadingOlder)
        }

    @Test
    fun paging_mapperExceptionSettlesAndRetriesSameServerOffset() =
        runTest {
            val api = ApiClient.hermesApi
            val (viewModel, _) = createViewModelWithSession()
            coEvery {
                api.getSessionMessages("session-456", any(), any(), any(), any())
            } returns pagingResponse(151..300)
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            val invalid = pagingResponse(1..150, 150).body()!!
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } returns retrofit2.Response.success(invalid.copy(messages = invalid.messages.map { it.copy(id = null) }))
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertTrue(viewModel.uiState.value.hasOlderMessages)
            coEvery {
                api.getSessionMessages("session-456", 150, 150, "latest", any())
            } returns pagingResponse(1..150, 150)
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(300, viewModel.uiState.value.messages.size)
        }

    private suspend fun TestScope.assertOverlappingHistoryPageKeepsOrder(equalTimestamps: Boolean) {
        val api = ApiClient.hermesApi
        val (viewModel, _) = createViewModelWithSession()
        (1..350).forEach { index ->
            fakeRepo.dao.addMessageDirect(
                com.m57.hermescontrol.data.local.ChatMessageEntity(
                    id = "rest-session-456-$index",
                    sessionId = "session-456",
                    role = "ASSISTANT",
                    content = "Server message $index",
                    timestamp = if (equalTimestamps) 1_000L else index * 1_000L,
                ),
            )
        }
        coEvery { api.getSessionMessages("session-456", any(), any(), any(), any()) } coAnswers {
            val offset = arg<Int>(2)
            val end = 350 - offset
            val response = pagingResponse((end - 149).coerceAtLeast(1)..end, offset).body()!!
            retrofit2.Response.success(
                if (equalTimestamps) {
                    response.copy(messages = response.messages.map { it.copy(timestamp = JsonPrimitive(1)) })
                } else {
                    response
                },
            )
        }
        viewModel.switchSession("session-456")
        advanceUntilIdle()
        repeat(2) {
            viewModel.loadOlderMessages()
            advanceUntilIdle()
        }
        val before =
            viewModel.uiState.value.messages
                .map { it.id }
        assertEquals((1..350).map { "rest-session-456-$it" }.toSet(), before.toSet())
        assertEquals(350, before.size)
        coVerify(exactly = 1) { api.getSessionMessages("session-456", any(), any(), any(), any()) }
        // Local history is exhausted. Both subsequent server pages overlap it completely.
        repeat(2) {
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertEquals(
                before,
                viewModel.uiState.value.messages
                    .map { it.id },
            )
        }
        assertFalse(viewModel.uiState.value.hasOlderMessages)
        coVerify(exactly = 1) { api.getSessionMessages("session-456", 150, 150, "latest", any()) }
        coVerify(exactly = 1) { api.getSessionMessages("session-456", 150, 300, "latest", any()) }
    }

    @Test
    fun paging_cacheExhaustedOverlappingServerPageKeepsExistingOrder() =
        runTest { assertOverlappingHistoryPageKeepsOrder(equalTimestamps = false) }

    @Test
    fun paging_cacheExhaustedOverlapWithEqualTimestampsKeepsExistingOrder() =
        runTest { assertOverlappingHistoryPageKeepsOrder(equalTimestamps = true) }

    // ── Attachment open (issue #724) ─────────────────────────────────────

    @Test
    fun `openAttachment GATEWAY success fires ACTION_VIEW with FileProvider uri`() =
        runTest {
            val cacheDir =
                java.io.File(System.getProperty("java.io.tmpdir"), "hermes_open_test_${System.nanoTime()}")
            cacheDir.mkdirs()
            every { app.cacheDir } returns cacheDir

            mockkObject(GatewayFileClient)
            val file = java.io.File(cacheDir, "note.txt").apply { writeBytes("hello".toByteArray()) }
            coEvery {
                GatewayFileClient.fetch(any(), any())
            } returns GatewayFileResult.Success(GatewayFile("note.txt", "text/plain", file))

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
            coVerify { GatewayFileClient.fetch(any(), any()) }
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
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_save_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val file =
                java.io
                    .File(System.getProperty("java.io.tmpdir"), "note_${System.nanoTime()}.txt")
                    .apply { writeText("downloaded") }
            coEvery { GatewayFileClient.fetch("/tmp/note.txt", any()) } returns
                GatewayFileResult.Success(GatewayFile("note.txt", "text/plain", file))
            // copyChunked is mocked with the object — restore real copy so the
            // saved document actually receives the bytes.
            coEvery { GatewayFileClient.copyChunked(any(), any()) } coAnswers {
                firstArg<java.io.InputStream>().copyTo(secondArg<java.io.OutputStream>())
            }
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

            assertArrayEquals("downloaded".toByteArray(), output.toByteArray())
            assertNull(vm.uiState.value.savingAttachmentPath)
            assertEquals("Saved note.txt", vm.uiState.value.openError)
        }

    @Test
    fun `saveAttachment never deletes the selected document when download fails`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_save_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val resolver = mockk<android.content.ContentResolver>(relaxed = true)
            val destination = mockk<android.net.Uri>()
            every { app.contentResolver } returns resolver
            coEvery {
                GatewayFileClient.fetch("/tmp/missing.pdf", any())
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
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_save_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val resolver = mockk<android.content.ContentResolver>(relaxed = true)
            val destination = mockk<android.net.Uri>()
            every { app.contentResolver } returns resolver
            every { resolver.openOutputStream(destination, "wt") } throws IllegalStateException("write failed")
            coEvery { GatewayFileClient.fetch("/tmp/report.pdf", any()) } returns
                GatewayFileResult.Success(
                    GatewayFile(
                        "report.pdf",
                        "application/pdf",
                        java.io
                            .File(System.getProperty("java.io.tmpdir"), "report_${System.nanoTime()}.pdf")
                            .apply { writeBytes(byteArrayOf(1)) },
                    ),
                )

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
            assertTrue(
                viewModel.uiState.value.openError
                    .orEmpty()
                    .startsWith("Could not save"),
            )
        }

    @Test
    fun `saveAttachment ignores overlapping saves`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_save_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val firstResult = CompletableDeferred<GatewayFileResult>()
            val fetchedPaths = mutableListOf<String>()
            coEvery { GatewayFileClient.fetch(capture(fetchedPaths), any()) } coAnswers { firstResult.await() }
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
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_open_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            coEvery {
                GatewayFileClient.fetch(any(), any())
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

    @Test
    fun `openAttachment shows opening state while fetch is in flight and clears it after`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_open_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val firstResult = CompletableDeferred<GatewayFileResult>()
            val fetchedPaths = mutableListOf<String>()
            coEvery { GatewayFileClient.fetch(capture(fetchedPaths), any()) } coAnswers { firstResult.await() }

            val vm = createViewModel()
            val attachment =
                Attachment(
                    uri = "gateway:/tmp/big.pdf",
                    name = "big.pdf",
                    mimeType = "application/pdf",
                    source = AttachmentSource.GATEWAY,
                )

            vm.openAttachment(attachment)
            runCurrent()

            // Indicator visible while the download is in flight…
            assertEquals(listOf("/tmp/big.pdf"), fetchedPaths)
            assertEquals("/tmp/big.pdf", vm.uiState.value.openingAttachmentPath)

            firstResult.complete(GatewayFileResult.NotFound)
            advanceUntilIdle()

            // …and cleared in all outcomes.
            assertNull(vm.uiState.value.openingAttachmentPath)
            assertTrue(
                vm.uiState.value.openError
                    .orEmpty()
                    .contains("big.pdf"),
            )
        }

    @Test
    fun `sendMessage while session create is in flight queues prompt and dispatches on SESSION_CREATE`() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val sentPrompts = mutableListOf<String>()
            every { HermesWsClient.sendMessage(any(), capture(sentPrompts), any(), any()) } returns "req-send-1"

            vm.sendMessage("My super important long prompt")
            advanceUntilIdle()

            // Optimistic UI state has the user message immediately
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "My super important long prompt" },
            )
            assertTrue(vm.uiState.value.isSending)
            // But wsClient.sendMessage not dispatched yet because session_id was null
            assertTrue(sentPrompts.isEmpty())

            // Now emit SESSION_CREATE result for the active session request
            val createReqId = "req-id-$reqCount"
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    createReqId,
                    mapOf("session_id" to "session-969", "stored_session_id" to "session-storage-969"),
                ),
            )
            advanceUntilIdle()

            // Prompt should be dispatched automatically!
            assertEquals(listOf("My super important long prompt"), sentPrompts)
            assertEquals("session-storage-969", vm.uiState.value.currentSessionId)
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "My super important long prompt" },
            )
        }

    @Test
    fun `reconnect with unpersisted session re-creates session on new socket and dispatches queued prompt`() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            advanceUntilIdle()

            // Session has no server presence yet (fresh session, no prompt sent).
            // Simulate reconnect:
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // On reconnect, an unpersisted session triggers createNewSession to mint a valid session on the new socket
            verify(atLeast = 2) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }

            val sentPrompts = mutableListOf<String>()
            every { HermesWsClient.sendMessage(any(), capture(sentPrompts), any(), any()) } returns "req-send-2"

            val createReqId = "req-id-$reqCount"
            vm.sendMessage("Prompt typed during reconnect")
            advanceUntilIdle()

            // Optimistic message in UI
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "Prompt typed during reconnect" },
            )

            // Complete the new SESSION_CREATE RPC
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    createReqId,
                    mapOf("session_id" to "session-reconnected", "stored_session_id" to "storage-reconnected"),
                ),
            )
            advanceUntilIdle()

            assertTrue(sentPrompts.contains("Prompt typed during reconnect"))
        }

    @Test
    fun `session creation failure clears agent typing and surfaces error for queued prompt`() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            vm.sendMessage("Prompt before failure")
            advanceUntilIdle()

            assertTrue(vm.uiState.value.isSending)

            val createReqId = "req-id-$reqCount"
            // Emit RPC error for SESSION_CREATE
            mockEventsFlow.emit(WsEvent.RpcError(createReqId, JsonRpcError(code = 500, message = "Backend exploded")))
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isAgentTyping)
            assertNotNull(vm.uiState.value.errorMessage)
            assertTrue(
                vm.uiState.value.errorMessage!!
                    .contains("Backend exploded"),
            )
        }

    // ── Subagent steering & cancellation (issue #1030) ───────────────────

    @Test
    fun testSteerSubagent_sendsRedirectAndUpdatesStatus() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val indicator =
                SubagentIndicator(
                    type = "subagent.start",
                    subagentId = "sub-123",
                    goal = "Search database",
                    status = "running",
                )
            mockEventsFlow.emit(
                WsEvent.SubagentEvent(
                    type = "subagent.start",
                    payload = mapOf("subagent_id" to "sub-123", "goal" to "Search database"),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()

            viewModel.steerSubagent(indicator, "Use index scan instead")
            advanceUntilIdle()

            val updated =
                viewModel.uiState.value.subagentIndicators
                    .first { it.subagentId == "sub-123" }
            assertEquals("steered", updated.status)
            assertTrue(updated.logs.any { it.text.contains("Use index scan instead") })
            io.mockk.verify { HermesWsClient.sendRedirect(sessionId, "/steer sub-123 Use index scan instead", any()) }
        }

    @Test
    fun testStopSubagent_sendsRedirectAndMarksCancelled() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val indicator =
                SubagentIndicator(
                    type = "subagent.start",
                    subagentId = "sub-123",
                    goal = "Run deep scan",
                    status = "running",
                )
            mockEventsFlow.emit(
                WsEvent.SubagentEvent(
                    type = "subagent.start",
                    payload = mapOf("subagent_id" to "sub-123", "goal" to "Run deep scan"),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()

            viewModel.stopSubagent(indicator)
            advanceUntilIdle()

            val updated =
                viewModel.uiState.value.subagentIndicators
                    .first { it.subagentId == "sub-123" }
            assertEquals("cancelled", updated.status)
            assertTrue(updated.logs.any { it.text.contains("Stopped subagent") })
            io.mockk.verify { HermesWsClient.sendRedirect(sessionId, "/stop sub-123", any()) }
        }

    @Test
    fun testHandleGatewayReady_withRestoreLastSessionEnabled_resumesStoredSession() =
        runTest {
            every { AuthManager.isRestoreLastSession() } returns true
            every { AuthManager.getLastOpenedSessionId() } returns "session-stored-999"

            val resumeParamsSlot = slot<Map<String, Any>>()
            every {
                HermesWsClient.send(WsMethods.SESSION_RESUME, capture(resumeParamsSlot), any())
            } answers {
                "req-resume-test"
            }

            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            io.mockk.verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    any(),
                    any(),
                )
            }
            assertEquals("session-stored-999", resumeParamsSlot.captured["session_id"])
        }

    @Test
    fun testHandleGatewayReady_withRestoreLastSessionDisabled_createsNewSession() =
        runTest {
            every { AuthManager.isRestoreLastSession() } returns false
            every { AuthManager.getLastOpenedSessionId() } returns "session-stored-999"

            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            io.mockk.verify {
                HermesWsClient.send(
                    WsMethods.SESSION_CREATE,
                    any(),
                    any(),
                )
            }
            io.mockk.verify(exactly = 0) {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun testSwitchSession_updatesLastOpenedSessionId() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            advanceUntilIdle()

            viewModel.switchSession("session-other-456")
            advanceUntilIdle()

            io.mockk.verify { AuthManager.setLastOpenedSessionId("session-other-456") }
        }

    // ── Turn-boundary capture ordering (reply-notification correlation) ───────

    /**
     * Turn boundaries are keyed by the active profile, so these tests need one.
     * Scoped per test rather than added to setUp: a non-blank profile is also
     * appended to `session.resume` params, which the older resume/switch tests
     * assert exactly.
     */
    private fun stubActiveProfile() {
        every { AuthManager.activeProfileId } returns MutableStateFlow<String?>("default")
    }

    @Test
    fun timelinePagesUseBackendCursorAndStopOnFinalPage() =
        runTest {
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            coEvery {
                api.getSessionTimeline(sessionId, "default", 500, 0)
            } returns
                retrofit2.Response.success(
                    SessionTimelineResponse(
                        entries = listOf(SessionTimelineEntry(row_id = 10, preview = "first")),
                        pagination =
                            SessionTimelinePagination(
                                limit = 500,
                                after_row_id = 0,
                                returned = 1,
                                total = 2,
                                has_more = true,
                                next_cursor = 123,
                            ),
                    ),
                )
            coEvery {
                api.getSessionTimeline(sessionId, "default", 500, 123)
            } returns
                retrofit2.Response.success(
                    SessionTimelineResponse(
                        entries = listOf(SessionTimelineEntry(row_id = 200, preview = "second")),
                        pagination =
                            SessionTimelinePagination(
                                limit = 500,
                                after_row_id = 123,
                                returned = 1,
                                total = 2,
                                has_more = false,
                                next_cursor = null,
                            ),
                    ),
                )

            viewModel.openTimeline()
            advanceUntilIdle()

            assertEquals(
                listOf(10),
                viewModel.timelineState.value.entries
                    .map { it.row_id },
            )
            assertEquals(123, viewModel.timelineState.value.nextCursor)
            assertTrue(viewModel.timelineState.value.hasMore)

            viewModel.loadMoreTimeline()
            advanceUntilIdle()

            assertEquals(
                listOf(10, 200),
                viewModel.timelineState.value.entries
                    .map { it.row_id },
            )
            assertFalse(viewModel.timelineState.value.hasMore)
            assertNull(viewModel.timelineState.value.nextCursor)
            coVerify(exactly = 1) { api.getSessionTimeline(sessionId, "default", 500, 123) }
        }

    @Test
    fun timelineJumpUsesBoundedHistoryWindowWithoutReplacingLiveTail() =
        runTest {
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            val persistedBeforeJump = fakeRepo.dao.count()
            val liveMessages = viewModel.uiState.value.messages
            coEvery {
                api.getSessionMessagesAround(sessionId, 42, "default", 120)
            } returns
                retrofit2.Response.success(
                    SessionMessagesAroundResponse(
                        messages =
                            listOf(
                                SessionMessage(id = 42, role = "user", content = JsonPrimitive("target")),
                                SessionMessage(id = 43, role = "assistant", content = JsonPrimitive("answer")),
                            ),
                        pagination =
                            SessionMessagesAroundPagination(
                                row_id = 42,
                                limit = 120,
                                returned = 2,
                                order = "oldest",
                                offset = 7,
                                total = 100,
                                has_older = true,
                                has_newer = true,
                            ),
                    ),
                )

            viewModel.jumpToTimelineEntry(42)
            advanceUntilIdle()

            val timeline = viewModel.timelineState.value
            assertTrue(timeline.isHistorical)
            assertEquals(42, timeline.historyAnchorRowId)
            assertTrue(timeline.historyHasOlder)
            assertTrue(timeline.historyHasNewer)
            assertEquals(
                listOf("rest-$sessionId-42", "rest-$sessionId-43"),
                timeline.historyMessages!!.map { it.id },
            )
            assertEquals(listOf("target", "answer"), timeline.historyMessages.map { it.content })
            assertEquals(liveMessages, viewModel.uiState.value.messages)
            // Direct-address history is a replaceable display window, not a
            // contiguous live-cache page. Persisting it would create fake gaps.
            assertEquals(persistedBeforeJump, fakeRepo.dao.count())

            viewModel.returnToLatestMessages()
            assertFalse(viewModel.timelineState.value.isHistorical)
            assertNull(viewModel.timelineState.value.historyMessages)
            assertFalse(viewModel.timelineState.value.historyHasOlder)
            assertFalse(viewModel.timelineState.value.historyHasNewer)
        }

    @Test
    fun missingTimelineRowKeepsCurrentHistoryWindow() =
        runTest {
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            coEvery {
                api.getSessionMessagesAround(sessionId, 42, "default", 120)
            } returns
                retrofit2.Response.success(
                    SessionMessagesAroundResponse(
                        messages = listOf(SessionMessage(id = 42, role = "user", content = JsonPrimitive("target"))),
                        pagination = SessionMessagesAroundPagination(row_id = 42),
                    ),
                )
            coEvery {
                api.getSessionMessagesAround(sessionId, 999, "default", 120)
            } returns
                retrofit2.Response.error(
                    404,
                    """{"detail":"Prompt not found"}""".toResponseBody(),
                )

            viewModel.jumpToTimelineEntry(42)
            advanceUntilIdle()
            val originalWindow = viewModel.timelineState.value.historyMessages

            viewModel.jumpToTimelineEntry(999)
            advanceUntilIdle()

            assertEquals(42, viewModel.timelineState.value.historyAnchorRowId)
            assertEquals(originalWindow, viewModel.timelineState.value.historyMessages)
            assertNotNull(viewModel.timelineState.value.windowErrorMessage)
        }

    @Test
    fun retryTimelineClearsWindowErrorBeforeRefreshing() =
        runTest {
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            coEvery {
                api.getSessionTimeline(sessionId, "default", 500, 0)
            } returns
                retrofit2.Response.success(
                    SessionTimelineResponse(
                        entries = listOf(SessionTimelineEntry(row_id = 42, preview = "target")),
                        pagination = SessionTimelinePagination(has_more = false),
                    ),
                )
            coEvery {
                api.getSessionMessagesAround(sessionId, 42, "default", 120)
            } returns
                retrofit2.Response.error(
                    404,
                    """{"detail":"Prompt not found"}""".toResponseBody(),
                )

            viewModel.openTimeline()
            advanceUntilIdle()
            viewModel.jumpToTimelineEntry(42)
            advanceUntilIdle()

            assertNotNull(viewModel.timelineState.value.windowErrorMessage)

            viewModel.retryTimeline()

            assertNull(viewModel.timelineState.value.windowErrorMessage)
            advanceUntilIdle()
            assertNull(viewModel.timelineState.value.windowErrorMessage)
            assertEquals(
                listOf(42),
                viewModel.timelineState.value.entries
                    .map { it.row_id },
            )
            coVerify(exactly = 2) { api.getSessionTimeline(sessionId, "default", 500, 0) }
        }

    @Test
    fun profileSwitchRejectsLateTimelineJumpResponse() =
        runTest {
            val profileFlow = MutableStateFlow<String?>("default")
            every { AuthManager.activeProfileId } returns profileFlow
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            val response = CompletableDeferred<retrofit2.Response<SessionMessagesAroundResponse>>()
            coEvery {
                api.getSessionMessagesAround(sessionId, 42, "default", 120)
            } coAnswers {
                response.await()
            }

            viewModel.jumpToTimelineEntry(42)
            runCurrent()
            profileFlow.value = "other"
            mockSwitchFlow.emit("other")
            runCurrent()
            response.complete(
                retrofit2.Response.success(
                    SessionMessagesAroundResponse(
                        messages = listOf(SessionMessage(id = 42, role = "user", content = JsonPrimitive("stale"))),
                        pagination = SessionMessagesAroundPagination(row_id = 42),
                    ),
                ),
            )
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.currentSessionId)
            assertFalse(viewModel.timelineState.value.isOpen)
            assertNull(viewModel.timelineState.value.historyMessages)
            assertNull(viewModel.timelineState.value.historyAnchorRowId)
        }

    @Test
    fun sessionSwitchRejectsLateTimelineJumpResponse() =
        runTest {
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            val response = CompletableDeferred<retrofit2.Response<SessionMessagesAroundResponse>>()
            coEvery {
                api.getSessionMessagesAround(sessionId, 42, "default", 120)
            } coAnswers {
                response.await()
            }

            viewModel.jumpToTimelineEntry(42)
            runCurrent()
            viewModel.switchSession("session-other")
            runCurrent()
            response.complete(
                retrofit2.Response.success(
                    SessionMessagesAroundResponse(
                        messages = listOf(SessionMessage(id = 42, role = "user", content = JsonPrimitive("stale"))),
                        pagination = SessionMessagesAroundPagination(row_id = 42),
                    ),
                ),
            )
            runCurrent()

            assertEquals("session-other", viewModel.uiState.value.currentSessionId)
            assertFalse(viewModel.timelineState.value.isOpen)
            assertNull(viewModel.timelineState.value.historyMessages)
            assertNull(viewModel.timelineState.value.historyAnchorRowId)
        }

    /**
     * The REST high-watermark must be read BEFORE prompt.submit leaves the
     * device. Capturing it afterwards lets a fast turn persist its assistant row
     * first, and a lower bound that already contains the reply can never exclude
     * a historical duplicate.
     */
    @Test
    fun sendMessage_readsTurnBoundaryBeforeSubmittingPrompt() =
        runTest {
            TurnCorrelationTracker.resetForTest()
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val events = mutableListOf<String>()
            val api = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            coEvery { api.getSessionMessages(any(), any(), any(), any(), any(), any()) } answers {
                // Only the boundary probe asks for a single newest row.
                if (arg<Int?>(1) == 1 && arg<String?>(3) == "latest") events += "boundary"
                retrofit2.Response.success(
                    SessionMessagesResponse(
                        messages = listOf(SessionMessage(id = 41, role = "user", content = JsonPrimitive("hi"))),
                        pagination = PaginationInfo(limit = 1, offset = 0, order = "latest", returned = 1),
                    ),
                )
            }
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                events += "submit"
                reqCount++
                val id = "req-msg-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("hello there")
            advanceUntilIdle()

            assertEquals(listOf("boundary", "submit"), events)
            assertEquals(41, TurnCorrelationTracker.boundaryFor("default", sessionId)?.beforeMessageId)
        }

    @Test
    fun sendMessage_stillSubmitsWhenBoundaryReadFails() =
        runTest {
            TurnCorrelationTracker.resetForTest()
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            coEvery { api.getSessionMessages(any(), any(), any(), any(), any(), any()) } returns
                retrofit2.Response.error(500, "boom".toResponseBody())
            var submits = 0
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                submits++
                reqCount++
                val id = "req-msg-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("hello there")
            advanceUntilIdle()

            assertEquals("REST health must never block sending chat", 1, submits)
            assertNull(TurnCorrelationTracker.boundaryFor("default", sessionId))
        }

    /**
     * A gateway that ignores `order=latest` answers a `limit=1` probe with the
     * OLDEST row; trusting that number would arm a boundary that is far too low
     * and let historical duplicates win. Without the pagination proof the turn is
     * left uncorrelatable, and the prompt still goes out.
     */
    @Test
    fun sendMessage_leavesTurnUncorrelatableWhenGatewayCannotConfirmOrder() =
        runTest {
            TurnCorrelationTracker.resetForTest()
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            coEvery { api.getSessionMessages(any(), any(), any(), any(), any(), any()) } answers {
                retrofit2.Response.success(
                    SessionMessagesResponse(
                        messages = listOf(SessionMessage(id = 1, role = "user", content = JsonPrimitive("oldest"))),
                    ),
                )
            }
            var submits = 0
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                submits++
                reqCount++
                val id = "req-msg-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("hello there")
            advanceUntilIdle()

            assertEquals(1, submits)
            assertNull(
                "Unconfirmed row order must not produce a boundary",
                TurnCorrelationTracker.boundaryFor("default", sessionId),
            )
        }

    /**
     * `AuthManager.activeProfileId` is null until a server profile is explicitly
     * selected, and the rest of AuthManager treats that as
     * [AuthManager.DEFAULT_PROFILE_ID]. A raw `.orEmpty()` made the whole feature
     * a no-op on a normal install: no boundary armed, so no reply notification
     * could ever be REST-auto-dismissed.
     */
    @Test
    fun sendMessage_nullActiveProfileArmsBoundaryUnderTheDefaultScope() =
        runTest {
            TurnCorrelationTracker.resetForTest()
            every { AuthManager.activeProfileId } returns MutableStateFlow<String?>(null)
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            coEvery { api.getSessionMessages(any(), any(), any(), any(), any(), any()) } answers {
                retrofit2.Response.success(
                    SessionMessagesResponse(
                        messages = listOf(SessionMessage(id = 41, role = "user", content = JsonPrimitive("hi"))),
                        pagination = PaginationInfo(limit = 1, offset = 0, order = "latest", returned = 1),
                    ),
                )
            }
            var submits = 0
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                submits++
                reqCount++
                val id = "req-msg-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("hello there")
            advanceUntilIdle()

            assertEquals(1, submits)
            assertEquals(
                "A default-profile install must still be correlatable",
                41,
                TurnCorrelationTracker.boundaryFor(AuthManager.DEFAULT_PROFILE_ID, sessionId)?.beforeMessageId,
            )
        }

    /**
     * `pagination.order` is the only proof the gateway honoured `order=latest`.
     * A gateway that reports `oldest` answered the `limit=1` probe with the
     * OLDEST row, and that as a lower bound would sit below the whole transcript
     * — exactly what lets a historical duplicate win.
     */
    @Test
    fun sendMessage_leavesTurnUncorrelatableWhenGatewayReportsOldestOrder() =
        runTest {
            TurnCorrelationTracker.resetForTest()
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            coEvery { api.getSessionMessages(any(), any(), any(), any(), any(), any()) } answers {
                retrofit2.Response.success(
                    SessionMessagesResponse(
                        messages = listOf(SessionMessage(id = 1, role = "user", content = JsonPrimitive("oldest"))),
                        pagination = PaginationInfo(limit = 1, offset = 0, order = "oldest", returned = 1),
                    ),
                )
            }
            var submits = 0
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                submits++
                reqCount++
                val id = "req-msg-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("hello there")
            advanceUntilIdle()

            assertEquals(1, submits)
            assertNull(
                "An unproven row order must not produce a boundary",
                TurnCorrelationTracker.boundaryFor("default", sessionId),
            )
        }

    /**
     * A queued prompt runs behind a turn that is already in flight, so no clean
     * lower bound exists for it — it must not borrow the running turn's boundary.
     */
    @Test
    fun queuedPromptNeverArmsATurnBoundary() =
        runTest {
            TurnCorrelationTracker.resetForTest()
            stubActiveProfile()
            val (viewModel, sessionId) = createViewModelWithSession()
            val api = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                reqCount++
                val id = "req-msg-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("/queue do the thing")
            advanceUntilIdle()

            io.mockk.coVerify(exactly = 0) {
                api.getSessionMessages(any(), any(), any(), any(), any(), any())
            }
            assertNull(TurnCorrelationTracker.boundaryFor("default", sessionId))
        }

    @Test
    fun sendVoiceNote_transcribesViaServerAndSubmitsTranscript() =
        runTest {
            TurnCorrelationTracker.resetForTest()
            stubActiveProfile()
            val api = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { ApiClient.transcriptionService(any()) } returns api
            coEvery { api.transcribeAudio(any()) } returns
                Response.success(
                    AudioTranscriptionResponse(ok = true, transcript = "hello from voice"),
                )
            val (viewModel, _) = createViewModelWithSession()

            val submittedTexts = mutableListOf<String>()
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                reqCount++
                val id = "req-msg-$reqCount"
                submittedTexts += arg<String>(1)
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            val voiceFile =
                java.io.File(attachmentCacheDir, "voice_note.m4a").apply {
                    writeBytes(byteArrayOf(1, 2, 3))
                }
            viewModel.sendVoiceNote(voiceFile)
            advanceUntilIdle()

            assertEquals("hello from voice", submittedTexts.lastOrNull())
            assertFalse(voiceFile.exists())
            assertNull(viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.isTranscribingVoiceNote)
        }

    @Test
    fun sendVoiceNote_reportsTranscriptionFailureAndDeletesClip() =
        runTest {
            val api = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { ApiClient.transcriptionService(any()) } returns api
            coEvery { api.transcribeAudio(any()) } returns
                Response.error(500, "transcribe failed".toResponseBody("text/plain".toMediaType()))
            val (viewModel, _) = createViewModelWithSession()

            val voiceFile =
                java.io.File(attachmentCacheDir, "voice_fail.m4a").apply {
                    writeBytes(byteArrayOf(9))
                }
            viewModel.sendVoiceNote(voiceFile)
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.contains("Voice note transcription failed") == true,
            )
            assertFalse(voiceFile.exists())
            assertFalse(viewModel.uiState.value.isTranscribingVoiceNote)
        }

    @Test
    fun sendVoiceNote_emptyTranscriptShowsNoSpeechAndDoesNotSubmit() =
        runTest {
            val api = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { ApiClient.transcriptionService(any()) } returns api
            coEvery { api.transcribeAudio(any()) } returns
                Response.success(AudioTranscriptionResponse(ok = true, transcript = "   "))
            val (viewModel, _) = createViewModelWithSession()

            val voiceFile =
                java.io.File(attachmentCacheDir, "voice_silent.m4a").apply {
                    writeBytes(byteArrayOf(0))
                }
            viewModel.sendVoiceNote(voiceFile)
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.contains("No speech detected") == true,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun sendVoiceNote_whileDisconnectedDropsClipLocallyWithoutUploading() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            val voiceFile =
                java.io.File(attachmentCacheDir, "voice_offline.m4a").apply {
                    writeBytes(byteArrayOf(1))
                }
            viewModel.sendVoiceNote(voiceFile)
            advanceUntilIdle()

            assertFalse(voiceFile.exists())
            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.contains("not connected") == true,
            )
        }

    @Test
    fun sendVoiceNote_whenSessionChangesMidTranscription_keepsTheTranscriptInTheComposer() =
        runTest {
            stubActiveProfile()
            val api = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { ApiClient.transcriptionService(any()) } returns api
            val transcription = CompletableDeferred<Response<AudioTranscriptionResponse>>()
            coEvery { api.transcribeAudio(any()) } coAnswers { transcription.await() }
            val (viewModel, _) = createViewModelWithSession()

            val voiceFile =
                java.io.File(attachmentCacheDir, "voice_session_race.m4a").apply {
                    writeBytes(byteArrayOf(7))
                }
            viewModel.sendVoiceNote(voiceFile)
            advanceUntilIdle()

            // The user moves to a new session while STT is still running.
            viewModel.createNewSession()
            advanceUntilIdle()

            transcription.complete(
                Response.success(AudioTranscriptionResponse(ok = true, transcript = "late transcript")),
            )
            advanceUntilIdle()

            // The recording belonged to the old session: the transcript must
            // survive in the composer instead of landing in a session it was
            // never recorded for, or vanishing (review, PR #1250).
            assertEquals("late transcript", viewModel.uiState.value.composerTextToRestore)
            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.contains("transcript kept") == true,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
            assertFalse(viewModel.uiState.value.isTranscribingVoiceNote)
            assertFalse(voiceFile.exists())
        }

    @Test
    fun sendVoiceNote_whenTheSendIsRejected_keepsTheTranscriptInTheComposer() =
        runTest {
            stubActiveProfile()
            val api = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { ApiClient.transcriptionService(any()) } returns api
            coEvery { api.transcribeAudio(any()) } returns
                Response.success(AudioTranscriptionResponse(ok = true, transcript = "keep me"))
            val (viewModel, _) = createViewModelWithSession()

            val voiceFile =
                java.io.File(attachmentCacheDir, "voice_send_race.m4a").apply {
                    writeBytes(byteArrayOf(8))
                }
            viewModel.sendVoiceNote(voiceFile)
            // The connection drops while the transcript is being produced.
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED
            advanceUntilIdle()

            assertEquals("keep me", viewModel.uiState.value.composerTextToRestore)
            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.contains("transcript kept") == true,
            )
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun sendVoiceNote_whileATranscriptionIsInFlight_dropsTheSecondClip() =
        runTest {
            stubActiveProfile()
            val api = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            every { ApiClient.transcriptionService(any()) } returns api
            val transcription = CompletableDeferred<Response<AudioTranscriptionResponse>>()
            coEvery { api.transcribeAudio(any()) } coAnswers { transcription.await() }
            val (viewModel, _) = createViewModelWithSession()

            val submittedTexts = mutableListOf<String>()
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                reqCount++
                val id = "req-msg-$reqCount"
                submittedTexts += arg<String>(1)
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            val firstFile =
                java.io.File(attachmentCacheDir, "voice_first.m4a").apply { writeBytes(byteArrayOf(1)) }
            val secondFile =
                java.io.File(attachmentCacheDir, "voice_second.m4a").apply { writeBytes(byteArrayOf(2)) }
            viewModel.sendVoiceNote(firstFile)
            advanceUntilIdle()

            viewModel.sendVoiceNote(secondFile)
            advanceUntilIdle()

            // Single-flight: the second clip is dropped without a second upload.
            assertFalse(secondFile.exists())
            coVerify(exactly = 1) { api.transcribeAudio(any()) }

            transcription.complete(
                Response.success(AudioTranscriptionResponse(ok = true, transcript = "only once")),
            )
            advanceUntilIdle()

            assertEquals(listOf("only once"), submittedTexts)
            assertFalse(viewModel.uiState.value.isTranscribingVoiceNote)
        }

    @Test
    fun canInterrupt_staysFalseWhileTypingWithNoRuntimeSession_thenTracksTheGeneration() =
        runTest {
            stubActiveProfile()
            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val sentPrompts = mutableListOf<String>()
            every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
                reqCount++
                val id = "req-msg-$reqCount"
                sentPrompts += arg<String>(1)
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            // Queued while session create is in flight: typing shows, but the
            // session has no runtime to interrupt yet, so Stop must stay
            // hidden and interruptSession() must not be reachable from it
            // (review, PR #1250).
            vm.sendMessage("held under session preparation")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isAgentTyping)
            assertFalse(vm.uiState.value.canInterrupt)

            // Once the runtime session exists and the prompt is dispatched,
            // the running generation is interruptible.
            val createReqId = "req-id-$reqCount"
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    createReqId,
                    mapOf("session_id" to "session-969", "stored_session_id" to "session-storage-969"),
                ),
            )
            advanceUntilIdle()

            assertEquals(listOf("held under session preparation"), sentPrompts)
            assertTrue(vm.uiState.value.canInterrupt)
        }
}
