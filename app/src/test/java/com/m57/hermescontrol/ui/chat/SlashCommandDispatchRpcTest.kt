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
                HermesWsClient.request(capture(methodSlot), capture(paramsSlot), any())
            } answers {
                captured = true
                CompletableDeferred<Any?>(Unit)
            }

            // /help is NOT client-special-cased -> RpcDispatch
            vm.sendMessage("/help")
            advanceUntilIdle()

            assertTrue("expected a COMMAND_DISPATCH request", captured)
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
                HermesWsClient.request(capture(methodSlot), capture(paramsSlot), any())
            } answers {
                CompletableDeferred<Any?>(Unit)
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
    fun `non-registry-miss backend error is surfaced directly (no fallback)`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            every {
                HermesWsClient.request(any(), any(), any())
            } answers {
                val d = CompletableDeferred<Any?>()
                d.completeExceptionally(
                    // A real, actionable error (not the "not a command" 4018
                    // that triggers the slash.exec fallback).
                    HermesWsClient.HermesRpcException("session busy — /interrupt first"),
                )
                d
            }

            vm.sendMessage("/help")
            advanceUntilIdle()

            val last = vm.uiState.value.messages.lastOrNull()
            assertEquals("⚠️ /help: session busy — /interrupt first", last?.content)
        }

    @Test
    fun `registry-miss 4018 on command dispatch falls back to slash_exec and surfaces output`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            val methodSlot = slot<String>()
            val paramsSlot = slot<Map<String, Any>>()
            every {
                HermesWsClient.request(capture(methodSlot), capture(paramsSlot), any())
            } answers {
                val m = methodSlot.captured
                val d = CompletableDeferred<Any?>()
                if (m == WsMethods.COMMAND_DISPATCH) {
                    // Backend rejects /status with the registry-miss 4018 (issue #576).
                    d.completeExceptionally(
                        HermesWsClient.HermesRpcException(
                            "not a quick/plugin/bundle/skill command: status",
                        ),
                    )
                } else {
                    // slash.exec runs the full COMMAND_REGISTRY and returns output.
                    d.complete(mapOf("output" to "STATUS: gateway reachable"))
                }
                d
            }

            vm.sendMessage("/status")
            advanceUntilIdle()

            // The fallback must have hit slash.exec with the full command string.
            // (methodSlot/paramsSlot hold the LAST call = slash.exec.)
            assertEquals(WsMethods.SLASH_EXEC, methodSlot.captured)
            val params = paramsSlot.captured
            assertEquals("/status", params["command"])
            assertEquals(sessionId, params["session_id"])

            // And the slash.exec output must be surfaced to the user.
            val last = vm.uiState.value.messages.lastOrNull()
            assertEquals("STATUS: gateway reachable", last?.content)
        }
}
