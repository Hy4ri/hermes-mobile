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
        reqCount = 0

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        // NOTE: no mockkStatic(Dispatchers) here — a static Dispatchers mock
        // bleeds JVM-wide and breaks later classes (the same poison removed
        // from ProfileSwitchCoordinatorTest / ProfilesViewModelTest; CI
        // 2026-08-06: this class's own static IO mock cross-wired
        // COMMAND_DISPATCH captures in suite order). setMain alone is safe —
        // the slash-dispatch send path is synchronous (no IO hop).
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
        // Catch-all for tests that don't stub request() explicitly: the real
        // implementation leaks a 120s timeout job on the singleton's real-IO
        // wsScope that fires after unmockkAll and crashes a later test with
        // MockK's "can't find stub". Mirror the real send() delegation (so
        // send-based verifications still see the call) but skip the timer.
        // Must COMPLETE (Unit): a never-completing deferred freezes the VM's
        // event collector at the SESSION_CREATE-triggered session.usage await,
        // and that completion then races the per-test capture stubs — the slot
        // can end up holding session.usage instead of command.dispatch (CI run
        // 31137581335 on 4645a2b: slash-focus flake).
        // Specific per-test request() stubs registered later take precedence.
        every { HermesWsClient.request(any(), any(), any()) } answers {
            HermesWsClient.send(arg(0), arg(1)) {}
            CompletableDeferred<Any?>(Unit)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private suspend fun TestScope.createViewModelWithSession(): Pair<ChatViewModel, String> {
        val vm = ChatViewModel(app, false, fakeRepo, ioDispatcher = testDispatcher)
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

            val methodCalls = mutableListOf<String>()
            val paramsCalls = mutableListOf<Map<String, Any>>()
            var captured = false
            every {
                HermesWsClient.request(capture(methodCalls), capture(paramsCalls), any())
            } answers {
                captured = true
                CompletableDeferred<Any?>(Unit)
            }

            // /help is NOT client-special-cased -> RpcDispatch
            vm.sendMessage("/help")
            advanceUntilIdle()

            assertTrue("expected a COMMAND_DISPATCH request", captured)
            // Captures hold ALL requests — the create flow's session.usage /
            // context_breakdown can land after the dispatch one (capture race,
            // CI 2026-08-06 + 2026-08-07). Find the record, don't trust "last".
            val dispatchIndex = methodCalls.indexOf(WsMethods.COMMAND_DISPATCH)
            assertTrue("expected COMMAND_DISPATCH, got $methodCalls", dispatchIndex >= 0)
            val params = paramsCalls[dispatchIndex]
            assertEquals("help", params["name"])
            assertEquals("", params["arg"])
            assertEquals(sessionId, params["session_id"])
        }

    @Test
    fun `slash command with args forwards arg separately`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            val methodCalls = mutableListOf<String>()
            val paramsCalls = mutableListOf<Map<String, Any>>()
            every {
                HermesWsClient.request(capture(methodCalls), capture(paramsCalls), any())
            } answers {
                CompletableDeferred<Any?>(Unit)
            }

            vm.sendMessage("/queue do the thing")
            advanceUntilIdle()

            // Find the dispatch record (capture race — see the /help test).
            val dispatchIndex = methodCalls.indexOf(WsMethods.COMMAND_DISPATCH)
            assertTrue("expected COMMAND_DISPATCH, got $methodCalls", dispatchIndex >= 0)
            val params = paramsCalls[dispatchIndex]
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

            val last =
                vm.uiState.value.messages
                    .lastOrNull()
            assertEquals("⚠️ /help: session busy — /interrupt first", last?.content)
        }

    @Test
    fun `registry-miss 4018 on command dispatch falls back to slash_exec and surfaces output`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            val methodCalls = mutableListOf<String>()
            val paramsCalls = mutableListOf<Map<String, Any>>()
            every {
                HermesWsClient.request(capture(methodCalls), capture(paramsCalls), any())
            } answers {
                val m = methodCalls.last()
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
            // Captures hold ALL requests — find the records instead of trusting
            // "last" (create-flow session.usage can land after; capture race).
            val dispatchIndex = methodCalls.indexOf(WsMethods.COMMAND_DISPATCH)
            assertTrue("expected COMMAND_DISPATCH, got $methodCalls", dispatchIndex >= 0)
            val execIndex = methodCalls.indexOf(WsMethods.SLASH_EXEC)
            assertTrue("expected SLASH_EXEC fallback, got $methodCalls", execIndex >= 0)
            assertTrue("dispatch must precede fallback", dispatchIndex < execIndex)
            val params = paramsCalls[execIndex]
            assertEquals("/status", params["command"])
            assertEquals(sessionId, params["session_id"])

            // And the slash.exec output must be surfaced to the user.
            val last =
                vm.uiState.value.messages
                    .lastOrNull()
            assertEquals("STATUS: gateway reachable", last?.content)
        }

    @Test
    fun `blocklisted cli_only or TUI-only command is rejected with friendly message and no RPC`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            val rpcMethods = mutableListOf<String>()
            every {
                HermesWsClient.request(capture(rpcMethods), any(), any())
            } answers {
                CompletableDeferred<Any?>(Unit)
            }

            // /redraw is TUI-only (issue #574) — hidden from suggestions but a
            // user can still type it. It must be blocked before any RPC fires.
            vm.sendMessage("/redraw")
            advanceUntilIdle()

            val last =
                vm.uiState.value.messages
                    .lastOrNull()
            assertTrue(
                "expected a 'not supported on mobile' message, got: ${last?.content}",
                last?.content?.contains("not supported on mobile") == true,
            )
            // No command RPC may fire for a blocklisted command. The create
            // flow's session.usage/context_breakdown requests are unrelated
            // noise that can land in the capture window (capture race) — the
            // blocklist contract is about command dispatch, so assert on that.
            val commandRpc =
                rpcMethods.filter {
                    it == WsMethods.COMMAND_DISPATCH || it == WsMethods.SLASH_EXEC
                }
            assertEquals(
                "no RPC should fire for a blocklisted command, got $rpcMethods",
                emptyList<String>(),
                commandRpc,
            )
        }

    @Test
    fun `slash_exec double-fault surfaces the secondary error`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            every {
                HermesWsClient.request(any(), any(), any())
            } answers {
                val m = arg<String>(0)
                val d = CompletableDeferred<Any?>()
                if (m == WsMethods.COMMAND_DISPATCH) {
                    // Registry miss -> triggers the slash.exec fallback.
                    d.completeExceptionally(
                        HermesWsClient.HermesRpcException(
                            "not a quick/plugin/bundle/skill command: status",
                        ),
                    )
                } else {
                    // slash.exec ALSO fails (e.g. worker can't start).
                    d.completeExceptionally(
                        HermesWsClient.HermesRpcException("slash worker start failed: boom"),
                    )
                }
                d
            }

            vm.sendMessage("/status")
            advanceUntilIdle()

            // Both RPCs fail -> the secondary slash.exec error must surface.
            val last =
                vm.uiState.value.messages
                    .lastOrNull()
            assertEquals("⚠️ /status: slash worker start failed: boom", last?.content)
        }

    @Test
    fun `slash_exec blank output appends no assistant message`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            every {
                HermesWsClient.request(any(), any(), any())
            } answers {
                val m = arg<String>(0)
                val d = CompletableDeferred<Any?>()
                if (m == WsMethods.COMMAND_DISPATCH) {
                    d.completeExceptionally(
                        HermesWsClient.HermesRpcException(
                            "not a quick/plugin/bundle/skill command: status",
                        ),
                    )
                } else {
                    // slash.exec succeeded but returned no/empty output.
                    d.complete(mapOf("output" to ""))
                }
                d
            }

            val before = vm.uiState.value.messages.size
            vm.sendMessage("/status")
            advanceUntilIdle()

            // The user's "/status" message is added, but blank slash.exec output
            // must NOT append an assistant bubble — so the last message is still
            // the user's own command, and only one message was added.
            assertEquals(before + 1, vm.uiState.value.messages.size)
            assertEquals(
                "/status",
                vm.uiState.value.messages
                    .lastOrNull()
                    ?.content,
            )
        }

    @Test
    fun `slash init forwards name=init to COMMAND_DISPATCH`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            val methodCalls = mutableListOf<String>()
            val paramsCalls = mutableListOf<Map<String, Any>>()
            every {
                HermesWsClient.request(capture(methodCalls), capture(paramsCalls), any())
            } answers {
                CompletableDeferred<Any?>(Unit)
            }

            // /init generates-or-updates AGENTS.md (backend command.dispatch
            // name=="init" branch, hermes-agent tui_gateway/server.py ~L15451).
            // NOT client-special-cased -> RpcDispatch.
            vm.sendMessage("/init")
            advanceUntilIdle()

            // Find the dispatch record (capture race — see the /help test).
            val dispatchIndex = methodCalls.indexOf(WsMethods.COMMAND_DISPATCH)
            assertTrue("expected COMMAND_DISPATCH, got $methodCalls", dispatchIndex >= 0)
            val params = paramsCalls[dispatchIndex]
            assertEquals("init", params["name"])
            assertEquals("", params["arg"])
            assertEquals(sessionId, params["session_id"])
        }

    @Test
    fun `slash init with extra arg forwards arg separately`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            val methodCalls = mutableListOf<String>()
            val paramsCalls = mutableListOf<Map<String, Any>>()
            every {
                HermesWsClient.request(capture(methodCalls), capture(paramsCalls), any())
            } answers {
                CompletableDeferred<Any?>(Unit)
            }

            vm.sendMessage("/init extra context here")
            advanceUntilIdle()

            // Find the dispatch record (capture race — see the /help test).
            val dispatchIndex = methodCalls.indexOf(WsMethods.COMMAND_DISPATCH)
            assertTrue("expected COMMAND_DISPATCH, got $methodCalls", dispatchIndex >= 0)
            val params = paramsCalls[dispatchIndex]
            assertEquals("init", params["name"])
            assertEquals("extra context here", params["arg"])
            assertEquals(sessionId, params["session_id"])
        }

    @Test
    fun `slash focus forwards name=focus to COMMAND_DISPATCH`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            val methodCalls = mutableListOf<String>()
            val paramsCalls = mutableListOf<Map<String, Any>>()
            every {
                HermesWsClient.request(capture(methodCalls), capture(paramsCalls), any())
            } answers {
                CompletableDeferred<Any?>(Unit)
            }

            // /focus is the display-only focus view (backend command.dispatch
            // name=="focus" branch, hermes-agent tui_gateway/server.py ~L15522).
            // NOT client-special-cased -> RpcDispatch.
            vm.sendMessage("/focus")
            advanceUntilIdle()

            // Find the dispatch record (capture race — see the /help test).
            val dispatchIndex = methodCalls.indexOf(WsMethods.COMMAND_DISPATCH)
            assertTrue("expected COMMAND_DISPATCH, got $methodCalls", dispatchIndex >= 0)
            val params = paramsCalls[dispatchIndex]
            assertEquals("focus", params["name"])
            assertEquals("", params["arg"])
            assertEquals(sessionId, params["session_id"])
        }

    @Test
    fun `slash focus on off status forwards arg to COMMAND_DISPATCH`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            val methodCalls = mutableListOf<String>()
            val paramsCalls = mutableListOf<Map<String, Any>>()
            every {
                HermesWsClient.request(capture(methodCalls), capture(paramsCalls), any())
            } answers {
                CompletableDeferred<Any?>(Unit)
            }

            vm.sendMessage("/focus on")
            advanceUntilIdle()

            // Find the dispatch record (capture race — see the /help test).
            val dispatchIndex = methodCalls.indexOf(WsMethods.COMMAND_DISPATCH)
            assertTrue("expected COMMAND_DISPATCH, got $methodCalls", dispatchIndex >= 0)
            val params = paramsCalls[dispatchIndex]
            assertEquals("focus", params["name"])
            assertEquals("on", params["arg"])
            assertEquals(sessionId, params["session_id"])
        }

    @Test
    fun `command dispatch type=send submits the returned prompt (init)`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            val methodCalls = mutableListOf<String>()
            val paramsCalls = mutableListOf<Map<String, Any>>()
            val sentText = slot<String>()
            every {
                HermesWsClient.request(capture(methodCalls), capture(paramsCalls), any())
            } answers {
                val d = CompletableDeferred<Any?>()
                if (methodCalls.last() == WsMethods.COMMAND_DISPATCH) {
                    // Backend /init returns type:"send" with the AGENTS.md prompt.
                    d.complete(mapOf("type" to "send", "message" to "Scan this repo and write AGENTS.md"))
                } else {
                    d.complete(mapOf("session_id" to "session-xyz"))
                }
                d
            }
            // submitPrompt() forwards the returned prompt via wsClient.sendMessage.
            every {
                HermesWsClient.sendMessage(any(), capture(sentText), any())
            } answers {
                val id = "send-msg-1"
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            vm.sendMessage("/init")
            advanceUntilIdle()

            // type:"send" must be forwarded to the agent as a normal prompt turn.
            assertEquals("Scan this repo and write AGENTS.md", sentText.captured)
        }

    @Test
    fun `command dispatch type=exec surfaces output as a message (focus)`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            every {
                HermesWsClient.request(any(), any(), any())
            } answers {
                val m = arg<String>(0)
                val d = CompletableDeferred<Any?>()
                if (m == WsMethods.COMMAND_DISPATCH) {
                    // Backend /focus returns type:"exec" with a notice line.
                    d.complete(mapOf("type" to "exec", "output" to "Focus view: ON (tool progress pinned off)"))
                } else {
                    d.complete(mapOf("session_id" to "session-xyz"))
                }
                d
            }

            vm.sendMessage("/focus on")
            advanceUntilIdle()

            val last = vm.uiState.value.messages.lastOrNull()
            assertEquals("Focus view: ON (tool progress pinned off)", last?.content)
        }
}
