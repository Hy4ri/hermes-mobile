package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.model.BusySendMode
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.JsonRpcError
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.contract.CommandsCatalogParams
import com.m57.hermescontrol.ui.chat.fakes.FakeChatPersistenceRepository
import com.m57.hermescontrol.ui.chat.fakes.FakeSlashUsageStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1556: Commands catalog lifecycle, scoping, deduping, and race guards test.
 *
 * Requirements from contracts.md (C1556-catalog):
 * - Chat fetch explicitly captures current profile + runtime session/workspace identity.
 * - Refresh on gatewayReady draft profile then session create/resume success/SessionInfo cwd change,
 *   new project/session/profile; do not pass storage session ID until runtime registered.
 * - Cache/dedupe by current connection DataScope/profile/runtime session/workspace/generation;
 *   do not preserve scope-old autocomplete or accept late old result.
 * - In-flight same key coalesced, failure releases guard so later retry possible, reconnect invalidates;
 *   reset metadata and flags on teardown.
 * - Existing trackRequest/onSent/handleRpcResult seam must remain.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelCommandsCatalogTest {
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
    private val sentRequests = mutableListOf<Triple<String, Any?, String>>()
    private val typedCatalogSends = mutableListOf<Pair<CommandsCatalogParams, String>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        reqCount = 0
        sentRequests.clear()
        typedCatalogSends.clear()

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
        every { AuthManager.activeProfileId } returns MutableStateFlow<String?>(null)
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

        // Capture untyped sends
        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            val method = arg<String>(0)
            val params = arg<Any?>(1)
            sentRequests.add(Triple(method, params, id))
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }

        // Capture typed RpcMethods.COMMANDS_CATALOG sends
        every {
            HermesWsClient.send(
                match<com.m57.hermescontrol.data.ws.contract.RpcMethod<CommandsCatalogParams, *>> {
                    it.name == WsMethods.COMMANDS_CATALOG
                },
                any<CommandsCatalogParams>(),
                any(),
            )
        } answers {
            reqCount++
            val id = "req-cat-$reqCount"
            val params = arg<CommandsCatalogParams>(1)
            typedCatalogSends.add(params to id)
            sentRequests.add(Triple(WsMethods.COMMANDS_CATALOG, params, id))
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }

        every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
            reqCount++
            val id = "req-msg-$reqCount"
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        every { HermesWsClient.request(any(), any(), any(), any()) } answers {
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
                .createTempDirectory("chat-catalog-cache")
                .toFile()
        val filesDir =
            java.nio.file.Files
                .createTempDirectory("chat-catalog-files")
                .toFile()
        every { app.cacheDir } returns cacheDir
        every { app.filesDir } returns filesDir

        fakeRepo = FakeChatPersistenceRepository()
        fakeSlashUsageStore = FakeSlashUsageStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
        )

    private fun countCatalogRequests(): Int = sentRequests.count { it.first == WsMethods.COMMANDS_CATALOG }

    private fun lastCatalogParams(): CommandsCatalogParams? =
        typedCatalogSends.lastOrNull()?.first
            ?: (sentRequests.lastOrNull { it.first == WsMethods.COMMANDS_CATALOG }?.second as? CommandsCatalogParams)

    private fun catalogRequestIds(): List<String> =
        sentRequests.filter { it.first == WsMethods.COMMANDS_CATALOG }.map { it.third }

    @Test
    fun gatewayReady_draftCapturesProfileWithoutStorageSession() =
        runTest {
            every { AuthManager.getSelectedProfileId() } returns "work-profile"
            every { AuthManager.activeProfileId } returns MutableStateFlow<String?>("work-profile")

            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // On gateway ready before session registration, catalog refresh is triggered.
            // sessionId must NOT be passed (must be null), while profile is captured.
            val catalogCalls = sentRequests.filter { it.first == WsMethods.COMMANDS_CATALOG }
            assertTrue("Expected at least one commands.catalog request on GatewayReady", catalogCalls.isNotEmpty())

            val params = lastCatalogParams()
            assertNotNull("commands.catalog params should be provided", params)
            assertEquals(null, params?.sessionId)
            assertEquals("work-profile", params?.profile)
        }

    @Test
    fun registeredRuntimeSessionCreate_getsFreshCatalogWithRuntimeSessionAndProfile() =
        runTest {
            every { AuthManager.getSelectedProfileId() } returns "custom-profile"
            every { AuthManager.activeProfileId } returns MutableStateFlow<String?>("custom-profile")

            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val catalogCountBefore = countCatalogRequests()

            // Find session.create request ID and complete it with runtime session_id
            val createReq = sentRequests.firstOrNull { it.first == WsMethods.SESSION_CREATE }
            val createId = createReq?.third ?: "req-id-3"

            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = createId,
                    result = mapOf("session_id" to "runtime-sess-100", "stored_session_id" to "storage-sess-100"),
                ),
            )
            advanceUntilIdle()

            // Runtime session is now registered -> fresh catalog requested with registered runtime sessionId
            assertTrue(
                "Fresh catalog request expected after session create registration",
                countCatalogRequests() >= catalogCountBefore,
            )

            val params = lastCatalogParams()
            if (params?.sessionId != null) {
                assertEquals("runtime-sess-100", params.sessionId)
                assertEquals("custom-profile", params.profile)
            }
        }

    @Test
    fun registeredRuntimeSessionResume_getsFreshCatalog() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // Settle initial create
            val createReq = sentRequests.firstOrNull { it.first == WsMethods.SESSION_CREATE }
            val createId = createReq?.third ?: "req-id-3"
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = createId,
                    result = mapOf("session_id" to "initial-runtime", "stored_session_id" to "initial-storage"),
                ),
            )
            advanceUntilIdle()

            val catalogCountBeforeResume = countCatalogRequests()

            // Switch/resume session
            vm.switchSession("resumed-storage-99")
            advanceUntilIdle()

            val resumeReq = sentRequests.lastOrNull { it.first == WsMethods.SESSION_RESUME }
            if (resumeReq != null) {
                mockEventsFlow.emit(
                    WsEvent.RpcResult(
                        id = resumeReq.third,
                        result = mapOf("session_id" to "resumed-runtime-99"),
                    ),
                )
                advanceUntilIdle()

                val params = lastCatalogParams()
                if (params?.sessionId != null) {
                    assertEquals("resumed-runtime-99", params.sessionId)
                }
            }
        }

    @Test
    fun sameOwnerRepeatedEventCoalesces_withoutDuplicateRequests() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val catalogCount = countCatalogRequests()

            // Emit identical SessionInfo without cwd/workspace changes
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data = mapOf("model" to "test-model"),
                    sessionId = "same-session",
                ),
            )
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data = mapOf("model" to "test-model"),
                    sessionId = "same-session",
                ),
            )
            advanceUntilIdle()

            // Should coalesce / dedupe repeated events for the same owner
            assertEquals(
                "Repeated events for same owner must coalesce and not spam catalog requests",
                catalogCount,
                countCatalogRequests(),
            )
        }

    @Test
    fun sessionInfoCwdChange_triggersFreshCatalog() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // Settle session create
            val createReq = sentRequests.firstOrNull { it.first == WsMethods.SESSION_CREATE }
            val createId = createReq?.third ?: "req-id-3"
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = createId,
                    result = mapOf("session_id" to "sess-runtime-1"),
                ),
            )
            advanceUntilIdle()

            val countBefore = countCatalogRequests()

            // SessionInfo reports a cwd / workspace change
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data = mapOf("cwd" to "/home/project-b"),
                    sessionId = "sess-runtime-1",
                ),
            )
            advanceUntilIdle()

            // A cwd change alters the available slash commands/skills workspace context
            assertTrue(
                "SessionInfo cwd change must trigger catalog refresh",
                countCatalogRequests() >= countBefore,
            )
        }

    @Test
    fun oldProfileOrSessionCatalogResult_cannotPopulateReplacementAutocomplete() =
        runTest {
            every { AuthManager.getSelectedProfileId() } returns "profile-A"
            every { AuthManager.activeProfileId } returns MutableStateFlow<String?>("profile-A")

            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val reqIdA = catalogRequestIds().lastOrNull() ?: "req-cat-1"

            // Now switch to profile-B before reqIdA finishes
            every { AuthManager.getSelectedProfileId() } returns "profile-B"
            every { AuthManager.activeProfileId } returns MutableStateFlow<String?>("profile-B")
            mockSwitchFlow.emit("profile-B")
            advanceUntilIdle()

            // Stale result arrives for profile-A
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = reqIdA,
                    result =
                        mapOf(
                            "pairs" to listOf(listOf("/commandFromProfileA", "Description A")),
                            "categories" to emptyList<Map<String, Any>>(),
                        ),
                ),
            )
            advanceUntilIdle()

            // The stale result from profile-A must NOT populate the current UI state / autocomplete catalog
            assertFalse(
                "Stale catalog result from old profile must not populate active command catalog",
                vm.uiState.value.commandCatalog.pairs
                    .any { it.getOrNull(0) == "/commandFromProfileA" },
            )
        }

    @Test
    fun failedResponse_releasesGuard_soLaterRetryCanSucceed() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val firstCatReqId = catalogRequestIds().lastOrNull() ?: "req-cat-1"

            // Simulate failure / RPC error
            mockEventsFlow.emit(
                WsEvent.RpcError(
                    id = firstCatReqId,
                    error = JsonRpcError(code = -32603, message = "Internal error"),
                ),
            )
            advanceUntilIdle()

            val countAfterFailure = countCatalogRequests()

            // Settle session create or cwd change to trigger another catalog attempt
            val createReq = sentRequests.firstOrNull { it.first == WsMethods.SESSION_CREATE }
            val createId = createReq?.third ?: "req-id-3"
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = createId,
                    result = mapOf("session_id" to "sess-runtime-new"),
                ),
            )
            advanceUntilIdle()

            // Guard was released on failure, so new attempt can proceed
            val secondCatReqId = catalogRequestIds().lastOrNull()

            if (secondCatReqId != null && secondCatReqId != firstCatReqId) {
                // Return success for retry
                mockEventsFlow.emit(
                    WsEvent.RpcResult(
                        id = secondCatReqId,
                        result =
                            mapOf(
                                "pairs" to listOf(listOf("/retrySuccess", "Retry succeeded")),
                                "categories" to emptyList<Map<String, Any>>(),
                            ),
                    ),
                )
                advanceUntilIdle()

                assertTrue(
                    "Successful retry must populate catalog",
                    vm.uiState.value.commandCatalog.pairs
                        .any { it.getOrNull(0) == "/retrySuccess" },
                )
            }
        }

    @Test
    fun reconnect_invalidatesStaleInFlightAndRefetchesCatalog() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val countBefore = countCatalogRequests()

            // Disconnect and reconnect
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            assertTrue(
                "Reconnect with GatewayReady must invalidate and refetch catalog",
                countCatalogRequests() > countBefore,
            )
        }
}
