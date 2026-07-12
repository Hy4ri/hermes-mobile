package com.m57.hermescontrol.ui.sessions

import android.app.Application
import android.util.Log
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.JsonRpcError
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockEventsFlow: MutableSharedFlow<WsEvent>
    private val mockConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    private lateinit var app: Application

    /** Captures the last (method, params) handed to HermesWsClient.send. */
    private data class Sent(val method: String, val params: Map<String, Any>?)

    private val sent = mutableListOf<Sent>()
    private var reqCount = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val testMainDispatcher = Dispatchers.Main
        reqCount = 0
        sent.clear()
        mockEventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher
        every { Dispatchers.Main } returns testMainDispatcher

        mockkObject(AuthManager)
        every { AuthManager.getToken() } returns "test-token"
        every { AuthManager.isTypingEffectEnabled() } returns true
        every { AuthManager.getTypingEffectDelayMs() } returns 30
        every { AuthManager.isAutoReconnect() } returns false
        mockkObject(HermesWsClient)

        app = mockk(relaxed = true)
        mockConnectionStatus.value = ConnectionStatus.DISCONNECTED

        every { HermesWsClient.events } returns mockEventsFlow
        every { HermesWsClient.connectionStatus } returns mockConnectionStatus
        every { HermesWsClient.connect() } answers {
            mockConnectionStatus.value = ConnectionStatus.CONNECTING
        }
        every { HermesWsClient.disconnect() } returns Unit

        // Capture every send and hand back a unique request id.
        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            sent.add(
                Sent(
                    method = arg(0),
                    params = arg<Map<String, Any>>(1),
                ),
            )
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }

        // loadSessions() hits the REST API from the screen's LaunchedEffect,
        // not the ViewModel constructor — tests don't trigger it, so no stub
        // is required here.

        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            sent.add(
                Sent(
                    method = arg(0),
                    params = arg<Map<String, Any>>(1),
                ),
            )
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.branchAndEmit(
        vm: SessionsViewModel,
        sourceId: String,
        name: String? = null,
        result: WsEvent,
    ) {
        vm.branchSession(sourceId, name)
        advanceUntilIdle()
        mockEventsFlow.emit(result)
        advanceUntilIdle()
    }

    @Test
    fun branchSession_sendsSessionBranchWithSourceId_andSetsBranchedSessionOnSuccess() =
        runTest {
            val vm = SessionsViewModel()
            branchAndEmit(
                vm,
                "parent-123",
                result =
                    WsEvent.RpcResult(
                        "req-id-1",
                        mapOf("session_id" to "new-456", "title" to "parent (branch)", "parent" to "parent-123"),
                    ),
            )

            // The correct WS method + source id were forwarded.
            val branchSent = sent.first { it.method == WsMethods.SESSION_BRANCH }
            assertEquals("parent-123", branchSent.params?.get("session_id"))

            // Success stashes the new runtime session id for navigation.
            assertEquals("new-456", vm.uiState.value.branchedSessionId)
            assertTrue(vm.uiState.value.branchingSessionIds.isEmpty())
        }

    @Test
    fun branchSession_passesNameWhenProvided() =
        runTest {
            val vm = SessionsViewModel()
            branchAndEmit(
                vm,
                "parent-123",
                name = "my-fork",
                result = WsEvent.RpcResult("req-id-1", mapOf("session_id" to "new-456")),
            )

            val branchSent = sent.first { it.method == WsMethods.SESSION_BRANCH }
            assertEquals("my-fork", branchSent.params?.get("name"))
            assertEquals("new-456", vm.uiState.value.branchedSessionId)
        }

    @Test
    fun branchSession_onRpcError_setsToastAndNoBranchedSession() =
        runTest {
            val vm = SessionsViewModel()
            branchAndEmit(
                vm,
                "parent-123",
                result = WsEvent.RpcError("req-id-1", JsonRpcError(code = 4008, message = "nothing to branch")),
            )

            assertNull(vm.uiState.value.branchedSessionId)
            assertTrue(vm.uiState.value.branchingSessionIds.isEmpty())
            assertTrue(vm.uiState.value.toastMessage?.contains("nothing to branch") == true)
        }

    @Test
    fun consumeBranchedSession_clearsPendingId() =
        runTest {
            val vm = SessionsViewModel()
            branchAndEmit(
                vm,
                "parent-123",
                result = WsEvent.RpcResult("req-id-1", mapOf("session_id" to "new-456")),
            )
            assertEquals("new-456", vm.uiState.value.branchedSessionId)
            vm.consumeBranchedSession()
            assertNull(vm.uiState.value.branchedSessionId)
        }
}
