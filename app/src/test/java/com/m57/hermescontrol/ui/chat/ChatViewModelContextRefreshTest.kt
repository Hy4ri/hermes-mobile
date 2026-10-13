package com.m57.hermescontrol.ui.chat

import android.app.Application
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.model.ModelInfoResponse
import com.m57.hermescontrol.data.model.SessionDetailResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.ui.chat.fakes.FakeChatPersistenceRepository
import com.m57.hermescontrol.ui.chat.fakes.FakeSlashUsageStore
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Independent #1558 live-meter RPC concurrency/coalescing and lazy historical-detail tests.
 *
 * Verifies contract C1558-context:
 * - Deferred both RPC context_breakdown and usage START before either is released (concurrent dispatch).
 * - Repeated triggers for same owner/session coalesce and avoid duplicate RPC calls.
 * - skipRestFallback = true performs zero REST calls (including no getModelInfo and no getSessionDetail).
 * - used = 0 is treated as legitimate occupancy when actual positive RPC context_max is returned (not conflated with missing).
 * - Mismatched model or missing actual RPC context_max uses scoped getModelInfo cache only when fallback is needed.
 * - Scope/model/session changes fence out stale responses.
 * - getSessionDetail is never invoked periodically by fetchContextUsage, but explicit fetchSessionDetailBreakdown()
 *   coalesces requests and updates contextBreakdown, isContextDetailLoading, and contextDetailError for the same owner.
 * - Storage vs runtime ID correctly routes live RPCs to the runtime session ID.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelContextRefreshTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockEventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    private val mockConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    private val mockDataScopeFlow = MutableStateFlow<DataScope?>(null)
    private val mockSwitchFlow = MutableSharedFlow<String>()

    private lateinit var app: Application
    private lateinit var fakeRepo: FakeChatPersistenceRepository
    private lateinit var fakeSlashUsageStore: FakeSlashUsageStore
    private lateinit var mockApi: HermesApiService

    private var reqCount = 0
    private var sessionCreateRequestId: String? = null

    // Track untyped / typed request calls
    private val sentRequests = mutableListOf<Triple<String, Any?, String>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        reqCount = 0
        sessionCreateRequestId = null
        sentRequests.clear()

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkObject(AuthManager)
        every { AuthManager.getPinnedModels() } returns emptyList()
        every { AuthManager.getToken() } returns "test-token"
        every { AuthManager.getBaseUrl() } returns "http://test.local/"
        every { AuthManager.getSelectedProfileId() } returns null
        every { AuthManager.activeProfileId } returns MutableStateFlow<String?>(null)
        every { AuthManager.currentDataScope() } returns null
        every { AuthManager.dataScopeFlow } returns mockDataScopeFlow
        every { AuthManager.isTypingEffectEnabled() } returns true
        every { AuthManager.getBusySendMode() } returns com.m57.hermescontrol.data.model.BusySendMode.CORRECT
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
            val method = arg<String>(0)
            val params = arg<Any?>(1)
            sentRequests.add(Triple(method, params, id))
            if (method == WsMethods.SESSION_CREATE) {
                sessionCreateRequestId = id
            }
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
            reqCount++
            val id = "req-req-$reqCount"
            val method = arg<String>(0)
            val params = arg<Any?>(1)
            sentRequests.add(Triple(method, params, id))
            CompletableDeferred<Any?>().apply { complete(null) }
        }

        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        mockkObject(HermesDatabase)

        app = mockk(relaxed = true)
        val cacheDir =
            java.nio.file.Files
                .createTempDirectory("chat-context-cache")
                .toFile()
        val filesDir =
            java.nio.file.Files
                .createTempDirectory("chat-context-files")
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

    private suspend fun TestScope.createViewModelWithActiveSession(
        runtimeId: String = "runtime-123",
        storageId: String = "stored-123",
        model: String = "hermes-3-llama-3.1-8b",
        provider: String = "nous",
    ): Pair<ChatViewModel, String> {
        val vm = createViewModel()
        advanceUntilIdle()

        mockConnectionStatus.value = ConnectionStatus.CONNECTED
        mockEventsFlow.emit(WsEvent.GatewayReady(null))
        advanceUntilIdle()

        val createId = requireNotNull(sessionCreateRequestId) { "session.create not sent" }
        mockEventsFlow.emit(
            WsEvent.RpcResult(
                createId,
                mapOf(
                    "session_id" to runtimeId,
                    "stored_session_id" to storageId,
                ),
            ),
        )
        advanceUntilIdle()

        // Set model info so currentSessionModel is known
        mockEventsFlow.emit(
            WsEvent.SessionInfo(
                mapOf(
                    "model" to model,
                    "provider" to provider,
                ),
            ),
        )
        advanceUntilIdle()

        // Setup traffic (session.info triggers model lookups) must not count toward per-test call assertions.
        clearMocks(mockApi, answers = false, recordedCalls = true, childMocks = false)
        clearMocks(HermesWsClient, answers = false, recordedCalls = true, childMocks = false)
        return Pair(vm, storageId)
    }

    @Test
    fun deferredBothRpcContextBreakdownAndUsage_startConcurrentlyBeforeEitherReleased() =
        runTest {
            val (vm, _) = createViewModelWithActiveSession()

            val breakdownDeferred = CompletableDeferred<Any?>()
            val usageDeferred = CompletableDeferred<Any?>()

            var breakdownStarted = false
            var usageStarted = false

            every {
                HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any(), any())
            } answers {
                breakdownStarted = true
                breakdownDeferred
            }

            every {
                HermesWsClient.request(WsMethods.SESSION_USAGE, any(), any(), any())
            } answers {
                usageStarted = true
                usageDeferred
            }

            vm.fetchContextUsage()
            runCurrent()

            // Both RPC calls must be in flight concurrently before either resolves
            assertTrue("SESSION_CONTEXT_BREAKDOWN should start before release", breakdownStarted)
            assertTrue("SESSION_USAGE should start before release", usageStarted)

            // Now release breakdown
            breakdownDeferred.complete(
                buildJsonObject {
                    put("context_max", 128_000L)
                    put("context_used", 4500L)
                    put("model", "nous/hermes-3-llama-3.1-8b")
                },
            )
            // Release usage
            usageDeferred.complete(
                buildJsonObject {
                    put("total", 4500L)
                },
            )
            advanceUntilIdle()

            assertEquals(128_000L, vm.uiState.value.fullContextTokens)
            assertEquals(4500L, vm.uiState.value.usedContextTokens)
        }

    @Test
    fun repeatedTriggers_forSameOwner_coalesceAndDoNotDuplicateInFlightRpc() =
        runTest {
            val (vm, _) = createViewModelWithActiveSession()

            val breakdownDeferred = CompletableDeferred<Any?>()
            val usageDeferred = CompletableDeferred<Any?>()

            var breakdownInvocations = 0
            var usageInvocations = 0

            every {
                HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any(), any())
            } answers {
                breakdownInvocations++
                breakdownDeferred
            }

            every {
                HermesWsClient.request(WsMethods.SESSION_USAGE, any(), any(), any())
            } answers {
                usageInvocations++
                usageDeferred
            }

            // Fire multiple times while previous request is still pending
            vm.fetchContextUsage()
            vm.fetchContextUsage()
            vm.fetchContextUsage()
            runCurrent()

            assertEquals("Breakdown RPC should only be triggered once while in flight", 1, breakdownInvocations)
            assertEquals("Usage RPC should only be triggered once while in flight", 1, usageInvocations)

            // Resolve deferreds
            breakdownDeferred.complete(
                buildJsonObject {
                    put("context_max", 64_000L)
                    put("context_used", 1200L)
                },
            )
            usageDeferred.complete(null)
            advanceUntilIdle()

            assertEquals(64_000L, vm.uiState.value.fullContextTokens)
            assertEquals(1200L, vm.uiState.value.usedContextTokens)

            // Once settled, a subsequent call can fire again
            val secondBreakdown =
                CompletableDeferred<Any?>(
                    buildJsonObject {
                        put("context_max", 64_000L)
                        put("context_used", 1500L)
                    },
                )
            every {
                HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any(), any())
            } answers {
                breakdownInvocations++
                secondBreakdown
            }

            vm.fetchContextUsage()
            advanceUntilIdle()

            assertEquals(2, breakdownInvocations)
            assertEquals(1500L, vm.uiState.value.usedContextTokens)
        }

    @Test
    fun skipRestFallbackTrue_makesZeroRestRequestsIncludingModelInfoAndSessionDetail() =
        runTest {
            val (vm, _) = createViewModelWithActiveSession()

            // Return empty/null from RPC so that a fallback might normally be tempted
            every {
                HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any(), any())
            } answers { CompletableDeferred<Any?>().apply { complete(null) } }
            every {
                HermesWsClient.request(WsMethods.SESSION_USAGE, any(), any(), any())
            } answers { CompletableDeferred<Any?>().apply { complete(null) } }

            vm.fetchContextUsage(skipRestFallback = true)
            advanceUntilIdle()

            // Absolutely ZERO REST requests must occur
            coVerify(exactly = 0) { mockApi.getModelInfo(any()) }
            coVerify(exactly = 0) { mockApi.getSessionDetail(any(), any()) }
            coVerify(exactly = 0) { mockApi.getSessionInfo(any(), any()) }
        }

    @Test
    fun usedZeroIsLegitimateOccupancy_withActualPositiveRpcMax_doesNotConflateWithMissing() =
        runTest {
            val (vm, _) = createViewModelWithActiveSession(model = "gpt-4o", provider = "openai")

            every {
                HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any(), any())
            } answers {
                CompletableDeferred<Any?>(
                    buildJsonObject {
                        put("context_max", 128_000L)
                        put("context_used", 0L)
                        put("model", "openai/gpt-4o")
                    },
                )
            }
            every {
                HermesWsClient.request(WsMethods.SESSION_USAGE, any(), any(), any())
            } answers { CompletableDeferred<Any?>().apply { complete(null) } }

            vm.fetchContextUsage()
            advanceUntilIdle()

            // 0L is legitimate occupancy, must NOT be turned into null or treated as missing
            assertEquals(0L, vm.uiState.value.usedContextTokens)
            assertEquals(128_000L, vm.uiState.value.fullContextTokens)

            // No REST fallback needed because RPC context_max was positive and matched
            coVerify(exactly = 0) { mockApi.getModelInfo(any()) }
        }

    @Test
    fun mismatchedModelOrNoActualMax_usesScopedModelInfoCacheOnlyWhenFallbackNeeded() =
        runTest {
            val (vm, _) = createViewModelWithActiveSession(model = "claude-3-opus", provider = "anthropic")

            coEvery { mockApi.getModelInfo(any()) } returns
                Response.success(
                    ModelInfoResponse(
                        model = "claude-3-opus",
                        provider = "anthropic",
                        effective_context_length = 200_000L,
                    ),
                )

            // RPC breakdown returns a MISMATCHED model ("other-model")
            every {
                HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any(), any())
            } returns
                CompletableDeferred<Any?>(
                    buildJsonObject {
                        put("context_max", 32_000L)
                        put("context_used", 500L)
                        put("model", "unrelated-model")
                    },
                )
            every {
                HermesWsClient.request(WsMethods.SESSION_USAGE, any(), any(), any())
            } answers { CompletableDeferred<Any?>().apply { complete(null) } }

            // With skipRestFallback = false (default), it falls back to getModelInfo
            vm.fetchContextUsage(skipRestFallback = false)
            advanceUntilIdle()

            coVerify(exactly = 1) { mockApi.getModelInfo(any()) }
            assertEquals(200_000L, vm.uiState.value.fullContextTokens)

            // Second call with same model & scope must reuse cached ModelInfo and NOT call REST again
            vm.fetchContextUsage(skipRestFallback = false)
            advanceUntilIdle()

            coVerify(exactly = 1) { mockApi.getModelInfo(any()) }
        }

    @Test
    fun scopeOrModelChanges_fenceOutStaleResponses() =
        runTest {
            val (vm, _) = createViewModelWithActiveSession(model = "model-initial", provider = "nous")

            val slowBreakdown = CompletableDeferred<Any?>()
            every {
                HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any(), any())
            } returns slowBreakdown
            every {
                HermesWsClient.request(WsMethods.SESSION_USAGE, any(), any(), any())
            } answers { CompletableDeferred<Any?>().apply { complete(null) } }

            vm.fetchContextUsage()
            runCurrent()

            // Change model before slowBreakdown completes
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    mapOf("model" to "model-switched", "provider" to "nous"),
                ),
            )
            advanceUntilIdle()

            // Complete the stale slow breakdown from the previous model
            slowBreakdown.complete(
                buildJsonObject {
                    put("context_max", 999_999L)
                    put("context_used", 88_888L)
                    put("model", "nous/model-initial")
                },
            )
            advanceUntilIdle()

            // The stale response must NOT overwrite the new model's meter
            assertEquals("nous/model-switched", vm.uiState.value.currentSessionModel)
            // fullContextTokens should not be the stale 999_999L
            assertTrue(
                "Stale meter response must be fenced on model switch",
                vm.uiState.value.fullContextTokens != 999_999L,
            )
        }

    @Test
    fun fetchContextUsage_neverInvokesPeriodicSessionDetail_butExplicitDetailCallDoes() =
        runTest {
            val (vm, storageId) = createViewModelWithActiveSession()

            coEvery { mockApi.getSessionDetail(any(), any()) } returns
                Response.success(
                    SessionDetailResponse(
                        session_id = storageId,
                        input_tokens = 10_000L,
                        output_tokens = 2_000L,
                        cache_read_tokens = 500L,
                        cache_write_tokens = 250L,
                        reasoning_tokens = 100L,
                        message_count = 12,
                    ),
                )

            every {
                HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any(), any())
            } returns
                CompletableDeferred<Any?>(
                    buildJsonObject {
                        put("context_max", 128_000L)
                        put("context_used", 3000L)
                    },
                )
            every {
                HermesWsClient.request(WsMethods.SESSION_USAGE, any(), any(), any())
            } answers { CompletableDeferred<Any?>().apply { complete(null) } }

            // Periodic or normal context usage fetch
            vm.fetchContextUsage()
            advanceUntilIdle()

            // Contract: fetchContextUsage MUST NOT call getSessionDetail
            coVerify(exactly = 0) { mockApi.getSessionDetail(any(), any()) }
            assertNull("contextBreakdown remains null before explicit detail fetch", vm.uiState.value.contextBreakdown)

            // Explicit call to fetchSessionDetailBreakdown()
            vm.fetchSessionDetailBreakdown()
            advanceUntilIdle()

            coVerify(exactly = 1) { mockApi.getSessionDetail(storageId, any()) }
            assertNotNull(
                "contextBreakdown should be populated after explicit fetch",
                vm.uiState.value.contextBreakdown,
            )
            assertEquals(
                10_000L,
                vm.uiState.value.contextBreakdown
                    ?.inputTokens,
            )
            assertEquals(
                2_000L,
                vm.uiState.value.contextBreakdown
                    ?.outputTokens,
            )
            assertEquals(
                500L,
                vm.uiState.value.contextBreakdown
                    ?.cacheReadTokens,
            )
            assertEquals(
                250L,
                vm.uiState.value.contextBreakdown
                    ?.cacheWriteTokens,
            )
            assertEquals(
                100L,
                vm.uiState.value.contextBreakdown
                    ?.reasoningTokens,
            )
            assertEquals(
                12,
                vm.uiState.value.contextBreakdown
                    ?.messageCount,
            )
            assertFalse(vm.uiState.value.isContextDetailLoading)
            assertNull(vm.uiState.value.contextDetailError)
        }

    @Test
    fun explicitDetailCall_coalescesInFlight_andSetsLoadingAndErrorStates() =
        runTest {
            val (vm, storageId) = createViewModelWithActiveSession()

            coEvery { mockApi.getSessionDetail(any(), any()) } coAnswers {
                kotlinx.coroutines.delay(100)
                Response.error(500, okhttp3.ResponseBody.create(null, "Server Error"))
            }

            // Trigger multiple times while in flight
            vm.fetchSessionDetailBreakdown()
            vm.fetchSessionDetailBreakdown()

            // Check loading flag while in flight
            runCurrent()
            assertTrue("isContextDetailLoading should be true while in flight", vm.uiState.value.isContextDetailLoading)

            advanceUntilIdle()

            // Coalesced: only 1 network call occurred
            coVerify(exactly = 1) { mockApi.getSessionDetail(storageId, any()) }
            assertFalse(
                "isContextDetailLoading should be false after completion",
                vm.uiState.value.isContextDetailLoading,
            )
            assertNotNull("contextDetailError should be set on failure", vm.uiState.value.contextDetailError)
        }

    @Test
    fun storageVsRuntimeId_contextRpcUsesRuntimeIdNotStorageId() =
        runTest {
            val runtimeId = "rt-alpha-99"
            val storageId = "hist-omega-42"
            val (vm, _) = createViewModelWithActiveSession(runtimeId = runtimeId, storageId = storageId)

            every {
                HermesWsClient.request(WsMethods.SESSION_CONTEXT_BREAKDOWN, any(), any(), any())
            } answers { CompletableDeferred<Any?>().apply { complete(null) } }
            every {
                HermesWsClient.request(WsMethods.SESSION_USAGE, any(), any(), any())
            } answers { CompletableDeferred<Any?>().apply { complete(null) } }

            vm.fetchContextUsage()
            advanceUntilIdle()

            // Verify live RPC was sent with runtimeId, NOT storageId
            verify {
                HermesWsClient.request(
                    WsMethods.SESSION_CONTEXT_BREAKDOWN,
                    match {
                        it.toString().contains("\"session_id\":\"${runtimeId}\"")
                    },
                    any(),
                    any(),
                )
            }
            verify(exactly = 0) {
                HermesWsClient.request(
                    WsMethods.SESSION_CONTEXT_BREAKDOWN,
                    match {
                        it.toString().contains("\"session_id\":\"${storageId}\"")
                    },
                    any(),
                    any(),
                )
            }
        }
}
