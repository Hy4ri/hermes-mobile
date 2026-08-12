package com.m57.hermescontrol.ui.chat

import android.app.Application
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.model.ActionResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Issue #862 — `/update` from chat: the backend handler is interactive +
 * session-exiting (confirmation modal + relaunch as `hermes update`), so it can
 * never produce the single response the slash worker waits for — sending it via
 * slash.exec/command.dispatch always dies with a 45s "slash worker timed out".
 * The command must be intercepted client-side: confirm dialog → REST
 * `POST /api/hermes/update` → the shared ActionProgressDialog tracks the log.
 *
 * NOTE: no mockkStatic(Dispatchers) — that bleed breaks later classes. The
 * update flow is REST + StateFlow only; setMain on the test dispatcher is safe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatUpdateCommandTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockEventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    private val mockConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    private lateinit var app: Application
    private lateinit var fakeRepo: FakeChatPersistenceRepository
    private lateinit var mockApi: HermesApiService
    private var reqCount = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        reqCount = 0

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkObject(AuthManager)
        mockkObject(HermesWsClient)
        mockkObject(ApiClient)
        mockkObject(HermesDatabase)

        app = mockk(relaxed = true)
        fakeRepo = FakeChatPersistenceRepository()
        mockApi = mockk(relaxed = true)

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

        // ChatViewModel's init subscribes to ProfileSwitchCoordinator.switched
        // on viewModelScope. Without mocking the singleton, every VM created
        // here parks a collector on the REAL flow — when a later test class
        // (e.g. ProfileSwitchCoordinatorTest) emits on it, the stale-Main
        // resumption crashes with DispatchException (CI flakes). Mirror
        // ChatViewModelTest: stub the flow with a never-emitting mock so the
        // collectors park harmlessly.
        mockkObject(ProfileSwitchCoordinator)
        every { ProfileSwitchCoordinator.switched } returns MutableSharedFlow<String>()
        every { ProfileSwitchCoordinator.connectionSwitched } returns MutableSharedFlow<String>()

        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        every { HermesWsClient.sendMessage(any(), any(), any(), any()) } answers {
            reqCount++
            val id = "req-msg-$reqCount"
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        // Catch-all: mirror send() but complete immediately (no 120s timer).
        every { HermesWsClient.request(any(), any(), any()) } answers {
            HermesWsClient.send(arg(0), arg(1)) {}
            CompletableDeferred<Any?>(Unit)
        }
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private suspend fun TestScope.createViewModelWithSession(): Pair<ChatViewModel, String> {
        // FakeSlashUsageStore: the real store inits a DataStore on real IO
        // threads — with a relaxed-mock app that NPEs on cacheDir and leaks
        // an uncaught exception into the NEXT runTest class (CI flakes:
        // UncaughtExceptionsBeforeTest).
        val vm = ChatViewModel(app, false, fakeRepo, FakeSlashUsageStore(), ioDispatcher = testDispatcher)
        advanceUntilIdle()
        mockConnectionStatus.value = ConnectionStatus.CONNECTED
        mockEventsFlow.emit(WsEvent.GatewayReady(null))
        advanceUntilIdle()
        // req-id-3 = session.create (after loadSessions + fetchCommandCatalog)
        mockEventsFlow.emit(WsEvent.RpcResult("req-id-3", mapOf("session_id" to "session-xyz")))
        advanceUntilIdle()
        return Pair(vm, "session-xyz")
    }

    @Test
    fun `update in chat opens the confirm dialog and fires no WS RPC`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            vm.sendMessage("/update")
            advanceUntilIdle()

            assertTrue("confirm dialog should open", vm.uiState.value.updateConfirmOpen)
            assertEquals("/update", vm.uiState.value.messages.last().content)

            // The whole point of the fix: NOTHING goes over the wire.
            verify(exactly = 0) {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            }
            verify(exactly = 0) { HermesWsClient.request(WsMethods.SLASH_EXEC, any(), any()) }
        }

    @Test
    fun `applyUpdate triggers the REST action and starts the progress popup`() =
        runTest {
            val vm = ChatViewModel(app, false, fakeRepo, FakeSlashUsageStore(), ioDispatcher = testDispatcher)
            coEvery { mockApi.updateHermes() } returns
                Response.success(ActionResponse(ok = true, name = "hermes-update"))
            // One running poll, then the action exits — runTest's teardown
            // advances virtual time, so the loop must terminate.
            coEvery { mockApi.getActionStatus("hermes-update") } returnsMany
                listOf(
                    Response.success(
                        com.m57.hermescontrol.data.model.ActionStatusResponse(
                            name = "hermes-update",
                            running = true,
                            lines = listOf("cloning repo…"),
                        ),
                    ),
                    Response.success(
                        com.m57.hermescontrol.data.model.ActionStatusResponse(
                            name = "hermes-update",
                            running = false,
                            exit_code = 0,
                            lines = listOf("cloning repo…", "done"),
                        ),
                    ),
                )

            vm.applyUpdate()
            runCurrent()

            val state = vm.actionProgress.state.value
            assertTrue("popup should be visible", state.visible)
            assertEquals(
                com.m57.hermescontrol.ui.common.ActionProgressPhase.RUNNING,
                state.phase,
            )
            assertEquals("hermes-update", state.actionName)
        }

    @Test
    fun `applyUpdate settles on success when the action exits cleanly`() =
        runTest {
            val vm = ChatViewModel(app, false, fakeRepo, FakeSlashUsageStore(), ioDispatcher = testDispatcher)
            coEvery { mockApi.updateHermes() } returns
                Response.success(ActionResponse(ok = true, name = "hermes-update"))
            coEvery { mockApi.getActionStatus("hermes-update") } returns
                Response.success(
                    com.m57.hermescontrol.data.model.ActionStatusResponse(
                        name = "hermes-update",
                        running = false,
                        exit_code = 0,
                        lines = listOf("done"),
                    ),
                )

            vm.applyUpdate()
            advanceTimeBy(1_500)
            runCurrent()

            val state = vm.actionProgress.state.value
            assertEquals(
                com.m57.hermescontrol.ui.common.ActionProgressPhase.SUCCEEDED,
                state.phase,
            )
            assertEquals(0, state.exitCode)
        }

    @Test
    fun `applyUpdate surfaces a rejected trigger in the popup`() =
        runTest {
            val vm = ChatViewModel(app, false, fakeRepo, FakeSlashUsageStore(), ioDispatcher = testDispatcher)
            coEvery { mockApi.updateHermes() } returns
                Response.error(404, "no update endpoint".toResponseBody())

            vm.applyUpdate()
            runCurrent()

            val state = vm.actionProgress.state.value
            assertEquals(
                com.m57.hermescontrol.ui.common.ActionProgressPhase.FAILED,
                state.phase,
            )
            assertNotNull(state.error)
            assertFalse(vm.uiState.value.updateConfirmOpen)
        }
}
