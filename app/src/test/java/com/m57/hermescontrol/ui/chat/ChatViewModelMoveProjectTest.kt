package com.m57.hermescontrol.ui.chat

import android.app.Application
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.model.ProjectFolder
import com.m57.hermescontrol.data.model.ProjectInfo
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

/** #1581: moving the open chat into a project via `session.workspace.move`. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelMoveProjectTest {
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

    private val project =
        ProjectInfo(id = "p1", name = "Alpha", folders = listOf(ProjectFolder(path = "/work/alpha")))

    private fun moveRequests() = sentRequests.filter { it.first == WsMethods.SESSION_WORKSPACE_MOVE }

    @Test
    fun move_sendsStoredIdAsSessionKeyAndFirstFolderAsCwd_thenUpdatesProject() =
        runTest {
            val (vm, storageId) = createViewModelWithActiveSession()
            val deferred = CompletableDeferred<Any?>()
            every { HermesWsClient.request(WsMethods.SESSION_WORKSPACE_MOVE, any(), any(), any()) } answers {
                sentRequests.add(Triple(arg(0), arg<Any?>(1), "move"))
                deferred
            }

            vm.moveSessionToProject(project)
            runCurrent()

            assertTrue(vm.uiState.value.isMovingProject)
            val params = moveRequests().single().second as Map<*, *>
            assertEquals(storageId, (params["session_key"] as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals("/work/alpha", (params["cwd"] as kotlinx.serialization.json.JsonPrimitive).content)

            deferred.complete(buildJsonObject { put("cwd", "/work/alpha") })
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isMovingProject)
            assertEquals("p1", vm.uiState.value.sessionProjectId)
            assertNull(vm.uiState.value.errorMessage)
        }

    @Test
    fun move_backendError_keepsProjectAndSurfacesMessage() =
        runTest {
            val (vm, _) = createViewModelWithActiveSession()
            every { app.getString(any(), any()) } answers { "failed: ${arg<Array<Any?>>(1).first()}" }
            every { HermesWsClient.request(WsMethods.SESSION_WORKSPACE_MOVE, any(), any(), any()) } answers {
                CompletableDeferred<Any?>().apply {
                    completeExceptionally(
                        HermesWsClient.HermesRpcException("working directory does not exist: /work/alpha", 4017),
                    )
                }
            }

            vm.moveSessionToProject(project)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isMovingProject)
            assertNull(vm.uiState.value.sessionProjectId)
            assertEquals("failed: working directory does not exist: /work/alpha", vm.uiState.value.errorMessage)
        }

    @Test
    fun move_whileTurnRunning_isRefusedWithoutRpc() =
        runTest {
            val (vm, _) = createViewModelWithActiveSession()
            every { app.getString(any()) } returns "busy"
            mockEventsFlow.emit(WsEvent.SessionInfo(mapOf("running" to true)))
            advanceUntilIdle()

            vm.moveSessionToProject(project)
            advanceUntilIdle()

            assertTrue(moveRequests().isEmpty())
            assertEquals("busy", vm.uiState.value.errorMessage)
            assertFalse(vm.uiState.value.isMovingProject)
        }

    @Test
    fun move_toCurrentProject_isNoOp() =
        runTest {
            val (vm, _) = createViewModelWithActiveSession()
            mockEventsFlow.emit(
                WsEvent.SessionInfo(mapOf("cwd" to "/work/alpha", "project" to mapOf("id" to "p1"))),
            )
            advanceUntilIdle()
            assertEquals("p1", vm.uiState.value.sessionProjectId)

            vm.moveSessionToProject(project)
            advanceUntilIdle()

            assertTrue(moveRequests().isEmpty())
        }

    @Test
    fun sessionInfoWithoutProject_clearsSessionProject() =
        runTest {
            val (vm, _) = createViewModelWithActiveSession()
            mockEventsFlow.emit(
                WsEvent.SessionInfo(mapOf("cwd" to "/work/alpha", "project" to mapOf("id" to "p1"))),
            )
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.SessionInfo(mapOf("cwd" to "/home/user", "project" to null)))
            advanceUntilIdle()

            assertNull(vm.uiState.value.sessionProjectId)
        }
}
