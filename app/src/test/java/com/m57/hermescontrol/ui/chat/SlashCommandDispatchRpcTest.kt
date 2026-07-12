package com.m57.hermescontrol.ui.chat

import android.app.Application
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.ui.chat.fakes.FakeChatPersistenceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #549 — Layer 1+2 linkage: a non-hardcoded slash command must be
 * forwarded to the backend via [WsMethods.COMMAND_DISPATCH] with the EXACT
 * param shape the gateway expects: { name, arg, session_id }.
 *
 * Verified against the live backend (hermes-agent tui_gateway/server.py
 * @method("command.dispatch")): the gateway reads `params["name"]` and
 * `params["arg"]`. A `{"command": ...}` shape (used by an earlier probe)
 * resolves to an empty name and returns error 4018. So the mobile MUST send
 * name/arg, not command. This test locks that contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlashCommandDispatchRpcTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockEventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    private val mockConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    private lateinit var app: Application
    private lateinit var fakeRepo: FakeChatPersistenceRepository
    private var reqCount = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val testMainDispatcher = Dispatchers.Main
        reqCount = 0

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher
        every { Dispatchers.Main } returns testMainDispatcher

        mockkObject(AuthManager)
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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private suspend fun TestScope.createViewModelWithSession(): Pair<ChatViewModel, String> {
        val vm = ChatViewModel(app, false, fakeRepo)
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
    fun `non-hardcoded slash command forwards name arg session_id to COMMAND_DISPATCH`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            val methodSlot = slot<String>()
            val paramsSlot = slot<Map<String, Any>>()
            var captured = false
            every {
                HermesWsClient.send(capture(methodSlot), capture(paramsSlot), any())
            } answers {
                captured = true
                reqCount++
                val id = "req-dispatch-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            // /help is NOT client-special-cased -> RpcDispatch
            vm.sendMessage("/help")
            advanceUntilIdle()

            assertTrue("expected a COMMAND_DISPATCH send", captured)
            assertEquals(WsMethods.COMMAND_DISPATCH, methodSlot.captured)
            val params = paramsSlot.captured
            assertEquals("help", params["name"])
            assertEquals("", params["arg"])
            assertEquals(sessionId, params["session_id"])
        }

    @Test
    fun `slash command with args forwards arg separately`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            val methodSlot = slot<String>()
            val paramsSlot = slot<Map<String, Any>>()
            every {
                HermesWsClient.send(capture(methodSlot), capture(paramsSlot), any())
            } answers {
                reqCount++
                val id = "req-dispatch-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            vm.sendMessage("/queue do the thing")
            advanceUntilIdle()

            assertEquals(WsMethods.COMMAND_DISPATCH, methodSlot.captured)
            val params = paramsSlot.captured
            assertEquals("queue", params["name"])
            assertEquals("do the thing", params["arg"])
            assertEquals(sessionId, params["session_id"])
        }

    @Test
    fun `client-special-cased commands do NOT hit COMMAND_DISPATCH`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            val dispatched = mutableListOf<String>()
            every {
                HermesWsClient.send(any(), any(), any())
            } answers {
                // track method via the captured value isn't possible here; re-stub below
                reqCount++
                val id = "req-id-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }
            // Narrow stub to record COMMAND_DISPATCH calls
            val seen = mutableListOf<String>()
            every {
                HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any())
            } answers {
                seen.add("dispatch")
                reqCount++
                val id = "req-id-$reqCount"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            vm.sendMessage("/stop")
            advanceUntilIdle()
            vm.sendMessage("/new")
            advanceUntilIdle()

            // /stop -> Interrupt, /new -> NewSession: neither should RpcDispatch.
            assertTrue(
                "client-handled commands must not be forwarded to the backend",
                seen.isEmpty(),
            )
        }
}
