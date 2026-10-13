package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.model.BusySendMode
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.ui.chat.fakes.FakeChatPersistenceRepository
import com.m57.hermescontrol.ui.chat.fakes.FakeSlashUsageStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression and integration tests for C1562 / C1565 / C1564:
 * - ChangeEvents coalesced sync for SESSIONS and PROJECTS (250ms debounce)
 * - Legacy SessionUpdated event backward compatibility
 * - Window-owned server-request 4404 decline when declines_not_shown capability is advertised
 * - No reply for window-owned server-requests when declines_not_shown capability is missing
 * - Genuinely unknown server-request methods respond with -32601
 * - Handled interactive prompts (clarify/approval) are not declined with 4404
 * - Transport ownership: late status disconnect observer does not wipe/cancel replacement generation
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatGatewaySyncTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockEventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    private val mockConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    private val mockSwitchFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val mockDataScopeFlow = MutableStateFlow<DataScope?>(null)

    private lateinit var app: Application
    private lateinit var fakeRepo: FakeChatPersistenceRepository
    private lateinit var fakeSlashUsageStore: FakeSlashUsageStore
    private lateinit var mockApi: HermesApiService

    private var reqCount = 0
    private val sentRequests = mutableListOf<Pair<String, Map<String, Any>?>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        reqCount = 0
        sentRequests.clear()

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkObject(AuthManager)
        every { AuthManager.getPinnedModels() } returns emptyList()
        every { AuthManager.getToken() } returns "test-token"
        every { AuthManager.getBaseUrl() } returns "http://test.local/"
        every { AuthManager.getSelectedProfileId() } returns null
        every { AuthManager.currentDataScope() } returns null
        every { AuthManager.dataScopeFlow } returns mockDataScopeFlow
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

        mockkObject(ProfileSwitchCoordinator)
        every { ProfileSwitchCoordinator.switched } returns mockSwitchFlow
        every { ProfileSwitchCoordinator.connectionSwitched } returns MutableSharedFlow<String>()

        mockkObject(HermesWsClient)
        every { HermesWsClient.events } returns mockEventsFlow
        every { HermesWsClient.connectionStatus } returns mockConnectionStatus
        every { HermesWsClient.connect() } answers {
            mockConnectionStatus.value = ConnectionStatus.CONNECTING
        }
        every { HermesWsClient.disconnect() } returns Unit
        every { HermesWsClient.rejectAllPending() } returns Unit

        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            sentRequests += arg<String>(0) to (arg<Any?>(1) as? Map<String, Any>?)
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
            reqCount++
            val id = "req-msg-$reqCount"
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        every { HermesWsClient.request(any(), any(), any()) } answers {
            HermesWsClient.send(arg(0), arg(1)) {}
            CompletableDeferred<Any?>(Unit)
        }
        every { HermesWsClient.respondToServerRequest(any(), any()) } returns true
        every { HermesWsClient.respondToServerRequestError(any(), any(), any()) } returns true

        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        mockkObject(HermesDatabase)

        app = mockk(relaxed = true)
        val cacheDir =
            java.nio.file.Files
                .createTempDirectory("chat-sync-cache")
                .toFile()
        val filesDir =
            java.nio.file.Files
                .createTempDirectory("chat-sync-files")
                .toFile()
        every { app.cacheDir } returns cacheDir
        every { app.filesDir } returns filesDir

        fakeRepo = FakeChatPersistenceRepository()
        fakeSlashUsageStore = FakeSlashUsageStore()
        ActiveSessionHolder.set(null)
    }

    private val createdViewModels = mutableListOf<ChatViewModel>()

    @After
    fun tearDown() {
        // Cancel viewModelScope so no job outlives the test and touches Dispatchers.Main after resetMain().
        createdViewModels.forEach { vm ->
            androidx.lifecycle.ViewModel::class.java.declaredMethods
                .firstOrNull { it.name.startsWith("clear") && it.parameterCount == 0 }
                ?.apply { isAccessible = true }
                ?.invoke(vm)
        }
        createdViewModels.clear()
        // Cancelled withContext(Main) continuations resume on real worker threads after the test body ends;
        // resetMain() would make that throw on the worker, so park Main on an inert test dispatcher instead.
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        unmockkAll()
    }

    private fun createViewModel(): ChatViewModel =
        ChatViewModel(
            application = app,
            startCleanup = false,
            repo = fakeRepo,
            slashUsageStore = fakeSlashUsageStore,
            ioDispatcher = testDispatcher,
            sendStore = ChatSendStore(),
        ).also { createdViewModels += it }

    private suspend fun TestScope.initConnectedViewModel(
        declinesNotShown: Boolean = false,
    ): Pair<ChatViewModel, String> {
        // The flag rides on the client.capabilities RESULT, which HermesWsClient captures per connection.
        every { HermesWsClient.declinesNotShown } returns declinesNotShown
        val vm = createViewModel()
        advanceUntilIdle()

        mockConnectionStatus.value = ConnectionStatus.CONNECTED
        mockEventsFlow.emit(WsEvent.GatewayReady(null))
        advanceUntilIdle()

        // Settle initial session creation (req-id-3 by default sequence)
        val createRequestId =
            sentRequests.firstOrNull { it.first == WsMethods.SESSION_CREATE }?.let {
                // Find its request id from sequence index
                val idx = sentRequests.indexOf(it) + 1
                "req-id-$idx"
            } ?: "req-id-3"

        mockEventsFlow.emit(
            WsEvent.RpcResult(
                createRequestId,
                mapOf("session_id" to "session-sync-123"),
            ),
        )
        advanceUntilIdle()
        return Pair(vm, "session-sync-123")
    }

    // ── #1562 Event-driven sync & coalescing ────────────────────────────────────

    @Test
    fun sessionsChangeEvent_coalescesWithin250msAndTriggersSingleLoadSessions() =
        runTest {
            val (vm, _) = initConnectedViewModel()
            val initialSessionListCalls = sentRequests.count { it.first == WsMethods.SESSION_LIST }

            // Emit multiple ChangeEvent(ChangeEvents.SESSIONS) rapidly
            mockEventsFlow.emit(WsEvent.ChangeEvent(type = ChangeEvents.SESSIONS, data = null))
            mockEventsFlow.emit(WsEvent.ChangeEvent(type = "sessions.changed", data = mapOf("reason" to "new")))
            advanceTimeBy(100L)

            // Should not have fired another SESSION_LIST yet due to 250ms coalescing debounce
            assertEquals(
                initialSessionListCalls,
                sentRequests.count { it.first == WsMethods.SESSION_LIST },
            )

            // Emit one more right before the window closes
            mockEventsFlow.emit(WsEvent.ChangeEvent(type = ChangeEvents.SESSIONS, data = null))
            advanceTimeBy(260L)
            advanceUntilIdle()

            // Exactly one additional SESSION_LIST should have been sent
            assertEquals(
                initialSessionListCalls + 1,
                sentRequests.count { it.first == WsMethods.SESSION_LIST },
            )
        }

    @Test
    fun projectsChangeEvent_coalescesWithin250msAndTriggersLoadStartableProjects() =
        runTest {
            val (vm, _) = initConnectedViewModel()
            val initialProjectsCalls = sentRequests.count { it.first == WsMethods.PROJECTS_LIST }

            // Emit multiple ChangeEvent(ChangeEvents.PROJECTS)
            mockEventsFlow.emit(WsEvent.ChangeEvent(type = ChangeEvents.PROJECTS, data = null))
            mockEventsFlow.emit(WsEvent.ChangeEvent(type = "projects.changed", data = mapOf("id" to "p1")))
            advanceTimeBy(120L)

            assertEquals(
                initialProjectsCalls,
                sentRequests.count { it.first == WsMethods.PROJECTS_LIST },
            )

            mockEventsFlow.emit(WsEvent.ChangeEvent(type = ChangeEvents.PROJECTS, data = null))
            advanceTimeBy(260L)
            advanceUntilIdle()

            // Coalesced into one PROJECTS_LIST call
            assertEquals(
                initialProjectsCalls + 1,
                sentRequests.count { it.first == WsMethods.PROJECTS_LIST },
            )
        }

    @Test
    fun legacySessionUpdatedEvent_triggersSessionRefreshForCompatibility() =
        runTest {
            val (vm, _) = initConnectedViewModel()
            val initialSessionListCalls = sentRequests.count { it.first == WsMethods.SESSION_LIST }

            mockEventsFlow.emit(
                WsEvent.SessionUpdated(
                    data = mapOf("session_id" to "session-sync-123"),
                ),
            )
            advanceTimeBy(300L)
            advanceUntilIdle()

            assertEquals(
                initialSessionListCalls + 1,
                sentRequests.count { it.first == WsMethods.SESSION_LIST },
            )
        }

    @Test
    fun unrelatedChangeEvents_doNotTriggerSessionOrProjectRefreshes() =
        runTest {
            val (vm, _) = initConnectedViewModel()
            val initialSessionListCalls = sentRequests.count { it.first == WsMethods.SESSION_LIST }
            val initialProjectsCalls = sentRequests.count { it.first == WsMethods.PROJECTS_LIST }

            mockEventsFlow.emit(WsEvent.ChangeEvent(type = ChangeEvents.CRON, data = null))
            mockEventsFlow.emit(WsEvent.ChangeEvent(type = "pairing.changed", data = null))
            mockEventsFlow.emit(WsEvent.ChangeEvent(type = "unknown.event", data = null))
            advanceTimeBy(500L)
            advanceUntilIdle()

            assertEquals(
                initialSessionListCalls,
                sentRequests.count { it.first == WsMethods.SESSION_LIST },
            )
            assertEquals(
                initialProjectsCalls,
                sentRequests.count { it.first == WsMethods.PROJECTS_LIST },
            )
        }

    @Test
    fun profileSwitch_fencesLateScopedResponsesFromOverwritingNewState() =
        runTest {
            val (vm, _) = initConnectedViewModel()

            // Trigger a session change event to launch a reload
            mockEventsFlow.emit(WsEvent.ChangeEvent(type = ChangeEvents.SESSIONS, data = null))
            advanceTimeBy(260L)
            val reloadReqId =
                sentRequests.last { it.first == WsMethods.SESSION_LIST }.let {
                    val idx = sentRequests.indexOf(it) + 1
                    "req-id-$idx"
                }

            // Profile switch happens while reload response is in-flight
            mockSwitchFlow.emit("profile-2")
            advanceUntilIdle()

            // Now late old-generation response arrives
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = reloadReqId,
                    result =
                        mapOf(
                            "sessions" to
                                listOf(
                                    mapOf("session_id" to "stale-old-session", "title" to "Old Scope"),
                                ),
                        ),
                ),
            )
            advanceUntilIdle()

            // Stale list result must not pollute the new scope's current session or state
            assertFalse(
                vm.uiState.value.sessions
                    .any { it.id == "stale-old-session" },
            )
        }

    // ── #1565 Window method declines (4404) & unknown methods (-32601) ─────────

    @Test
    fun windowMethods_withDeclinesNotShownCapability_respond4404() =
        runTest {
            // When backend advertises declines_not_shown=true in gateway.ready
            val (vm, _) =
                initConnectedViewModel(
                    declinesNotShown = true,
                )

            val windowMethods =
                listOf(
                    "preview.read",
                    "preview.act",
                    "terminal.read",
                    "window.read",
                    "tour",
                )

            windowMethods.forEachIndexed { index, method ->
                val serverReqId = "srq-win-$index"
                mockEventsFlow.emit(
                    WsEvent.ServerRequest(
                        id = serverReqId,
                        method = method,
                        params = mapOf("session_id" to "session-sync-123"),
                    ),
                )
                advanceUntilIdle()

                // Must respond with 4404 decline so Desktop owner can answer
                verify(exactly = 1) {
                    HermesWsClient.respondToServerRequestError(
                        eq(serverReqId),
                        eq(4404),
                        match { it.contains("not shown", ignoreCase = true) },
                    )
                }
            }
        }

    @Test
    fun windowMethods_withoutDeclinesNotShownCapability_doNotAnswer() =
        runTest {
            // Older backend that settles on first error: declines_not_shown absent / false
            val (vm, _) =
                initConnectedViewModel(
                    declinesNotShown = false,
                )

            val windowMethods =
                listOf(
                    "preview.read",
                    "preview.act",
                    "terminal.read",
                    "window.read",
                    "tour",
                )

            windowMethods.forEachIndexed { index, method ->
                val serverReqId = "srq-bystander-$index"
                mockEventsFlow.emit(
                    WsEvent.ServerRequest(
                        id = serverReqId,
                        method = method,
                        params = mapOf("session_id" to "session-sync-123"),
                    ),
                )
                advanceUntilIdle()

                // Mobile must NOT send any error or answer, allowing the real desktop owner to answer
                verify(exactly = 0) {
                    HermesWsClient.respondToServerRequest(eq(serverReqId), any())
                }
                verify(exactly = 0) {
                    HermesWsClient.respondToServerRequestError(eq(serverReqId), any(), any())
                }
            }
        }

    @Test
    fun genuinelyUnknownServerRequestMethods_alwaysRespondWith32601() =
        runTest {
            val (vm, _) =
                initConnectedViewModel(
                    declinesNotShown = true,
                )

            val unknownReqId = "srq-genuinely-unknown"
            mockEventsFlow.emit(
                WsEvent.ServerRequest(
                    id = unknownReqId,
                    method = "some.nonexistent.window_or_server_method",
                    params = mapOf("session_id" to "session-sync-123"),
                ),
            )
            advanceUntilIdle()

            // Genuinely unknown method must receive -32601 error
            verify(exactly = 1) {
                HermesWsClient.respondToServerRequestError(
                    eq(unknownReqId),
                    eq(-32601),
                    any(),
                )
            }
        }

    @Test
    fun handledInteractivePrompts_clarifyAndApproval_areNotDeclined() =
        runTest {
            val (vm, _) =
                initConnectedViewModel(
                    declinesNotShown = true,
                )

            // Clarify request
            val clarifyReqId = "srq-clarify-handled"
            mockEventsFlow.emit(
                WsEvent.ServerRequest(
                    id = clarifyReqId,
                    method = "clarify",
                    params =
                        mapOf(
                            "session_id" to "session-sync-123",
                            "question" to "Choose target?",
                            "choices" to listOf("A", "B"),
                        ),
                ),
            )
            advanceUntilIdle()

            // Approval request
            val approvalReqId = "srq-approval-handled"
            mockEventsFlow.emit(
                WsEvent.ServerRequest(
                    id = approvalReqId,
                    method = "approval",
                    params =
                        mapOf(
                            "session_id" to "session-sync-123",
                            "command" to "git status",
                        ),
                ),
            )
            advanceUntilIdle()

            // Neither should be declined with 4404 or -32601
            verify(exactly = 0) {
                HermesWsClient.respondToServerRequestError(eq(clarifyReqId), eq(4404), any())
            }
            verify(exactly = 0) {
                HermesWsClient.respondToServerRequestError(eq(clarifyReqId), eq(-32601), any())
            }
            verify(exactly = 0) {
                HermesWsClient.respondToServerRequestError(eq(approvalReqId), eq(4404), any())
            }
            verify(exactly = 0) {
                HermesWsClient.respondToServerRequestError(eq(approvalReqId), eq(-32601), any())
            }
        }

    @Test
    fun capabilityState_resetsOnConnectionTeardownOrScopeChange() =
        runTest {
            val (vm, _) =
                initConnectedViewModel(
                    declinesNotShown = true,
                )

            // Disconnect socket: capabilities must reset
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED
            advanceUntilIdle()
            // RpcChannel.clearCapabilityRequests() drops the flag on teardown; the mocked client mirrors that.
            every { HermesWsClient.declinesNotShown } returns false

            // Reconnect without the capability flag
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val serverReqId = "srq-win-after-reset"
            mockEventsFlow.emit(
                WsEvent.ServerRequest(
                    id = serverReqId,
                    method = "preview.read",
                    params = mapOf("session_id" to "session-sync-123"),
                ),
            )
            advanceUntilIdle()

            // Reset capability means no 4404 sent now
            verify(exactly = 0) {
                HermesWsClient.respondToServerRequestError(eq(serverReqId), eq(4404), any())
            }
        }

    // ── #1564 Observer-free transport ownership ─────────────────────────────────

    @Test
    fun socketStatusDropObserver_doesNotRejectReplacementGenerationCalls() =
        runTest {
            val (vm, _) = initConnectedViewModel()

            // When socket drops and enters RECONNECTING / DISCONNECTED,
            // ChatViewModel's observer must NOT invoke wsClient.rejectAllPending()
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            advanceUntilIdle()

            // Verify rejectAllPending is not triggered by ChatViewModel status collector
            verify(exactly = 0) { HermesWsClient.rejectAllPending() }
        }
}
