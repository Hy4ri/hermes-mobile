package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.JsonRpcError
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockEventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val testMainDispatcher = Dispatchers.Main

        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher
        every { Dispatchers.Main } returns testMainDispatcher

        mockkObject(AuthManager)
        mockkObject(HermesWsClient)

        every { AuthManager.getToken() } returns "test-token"
        every { HermesWsClient.events } returns mockEventsFlow
        every { HermesWsClient.connect() } returns Unit
        every { HermesWsClient.disconnect() } returns Unit

        // Default mock stubs for requests returning unique IDs
        var reqCount = 0
        every { HermesWsClient.send(any(), any()) } answers {
            reqCount++
            "req-id-$reqCount"
        }
        every { HermesWsClient.sendMessage(any(), any()) } answers {
            reqCount++
            "req-msg-$reqCount"
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun testInitialStateAndConnection() =
        runTest {
            val viewModel = ChatViewModel()
            advanceUntilIdle()

            verify { HermesWsClient.connect() }
            assertTrue(viewModel.uiState.value.isLoading)
            assertFalse(viewModel.uiState.value.isConnected)
        }

    @Test
    fun testGatewayReady_createsSessionIfNoneExists() =
        runTest {
            val viewModel = ChatViewModel()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.isConnected)
            // Note: GatewayReady immediately calls createNewSession(), which sets isLoading = true and clears messages
            assertTrue(state.isLoading)
            assertEquals(0, state.messages.size)

            // Verify that list sessions and create session requests are triggered
            verify { HermesWsClient.send(WsMethods.SESSION_LIST) }
            verify { HermesWsClient.send(WsMethods.SESSION_CREATE) }
        }

    @Test
    fun testSessionCreateRpcResult() =
        runTest {
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any()) } answers {
                createReqId = "custom-create-id"
                createReqId
            }

            val viewModel = ChatViewModel()
            advanceUntilIdle()

            // Trigger GatewayReady -> triggers createNewSession
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // Feed SESSION_CREATE result
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("session-123", state.currentSessionId)
            assertFalse(state.isLoading)
            // Connected to Hermes was cleared when createNewSession() was called, so only "Session created" exists
            assertEquals(1, state.messages.size)
            assertEquals("Session created", state.messages[0].content)
        }

    @Test
    fun testSessionListRpcResult() =
        runTest {
            var listReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_LIST, any()) } answers {
                listReqId = "custom-list-id"
                listReqId
            }

            val viewModel = ChatViewModel()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // Feed SESSION_LIST result
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    listReqId,
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

            val state = viewModel.uiState.value
            assertEquals(1, state.sessions.size)
            assertEquals("session-123", state.sessions[0].id)
            assertEquals("My Session Title", state.sessions[0].title)
            assertEquals(12, state.sessions[0].messageCount)
        }

    @Test
    fun testMessageStreamingFlow() =
        runTest {
            val viewModel = ChatViewModel()
            advanceUntilIdle()

            // Set active session ID first
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // Stream Start
            mockEventsFlow.emit(WsEvent.MessageStart("session-123"))
            advanceUntilIdle()

            var state = viewModel.uiState.value
            assertTrue(state.isAgentTyping)
            assertEquals("", state.currentStreamingText)
            assertFalse(state.isThinking)
            assertEquals("", state.thinkingText)

            // Thinking Delta 1
            mockEventsFlow.emit(WsEvent.ThinkingDelta("Thinking...", "session-123"))
            advanceUntilIdle()
            state = viewModel.uiState.value
            assertTrue(state.isThinking)
            assertEquals("Thinking...", state.thinkingText)

            // Thinking Delta 2
            mockEventsFlow.emit(WsEvent.ThinkingDelta(" deeper", "session-123"))
            advanceUntilIdle()
            state = viewModel.uiState.value
            assertTrue(state.isThinking)
            assertEquals("Thinking... deeper", state.thinkingText)

            // Token 1
            mockEventsFlow.emit(WsEvent.MessageToken("Hello", "session-123"))
            advanceUntilIdle()
            state = viewModel.uiState.value
            assertFalse(state.isThinking)
            assertEquals("Hello", state.currentStreamingText)
            // System message was cleared when createNewSession() was called, so only Assistant streaming message is present
            assertEquals(1, state.messages.size)
            assertEquals("Hello", state.messages[0].content)
            assertTrue(state.messages[0].isStreaming)

            // Token 2
            mockEventsFlow.emit(WsEvent.MessageToken(" world", "session-123"))
            advanceUntilIdle()
            state = viewModel.uiState.value
            assertEquals("Hello world", state.currentStreamingText)
            assertEquals(1, state.messages.size)
            assertEquals("Hello world", state.messages[0].content)
            assertTrue(state.messages[0].isStreaming)

            // Complete
            mockEventsFlow.emit(WsEvent.MessageComplete("Hello world!", "session-123"))
            advanceUntilIdle()
            state = viewModel.uiState.value
            assertFalse(state.isAgentTyping)
            assertEquals("", state.currentStreamingText)
            assertFalse(state.isThinking)
            assertEquals("", state.thinkingText)
            assertEquals(1, state.messages.size)
            assertEquals("Hello world!", state.messages[0].content)
            assertFalse(state.messages[0].isStreaming)
        }

    @Test
    fun testClarifyRequestAndRespond() =
        runTest {
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any()) } answers {
                createReqId = "create-req-clarify"
                createReqId
            }

            val viewModel = ChatViewModel()
            advanceUntilIdle()

            // Set session ID
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.ClarifyRequest("Please choose:", listOf("Yes", "No")))
            advanceUntilIdle()

            var state = viewModel.uiState.value
            assertEquals("Please choose:", state.clarifyRequest?.text)
            assertEquals(listOf("Yes", "No"), state.clarifyRequest?.options)

            // Respond to clarify
            viewModel.respondToClarify("Yes")
            advanceUntilIdle()

            // verify clarify request is dismissed, and user message is sent
            state = viewModel.uiState.value
            assertNull(state.clarifyRequest)
            // Session created (System) + user message = 2 messages
            assertEquals(2, state.messages.size)
            assertEquals("Session created", state.messages[0].content)
            assertEquals("Yes", state.messages[1].content)
            assertEquals(MessageRole.USER, state.messages[1].role)
        }

    @Test
    fun testSendMessage() =
        runTest {
            val viewModel = ChatViewModel()
            advanceUntilIdle()

            // Trigger GatewayReady -> triggers createSession -> feed result to set active session
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any()) } answers {
                createReqId = "create-req"
                createReqId
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            // Now send message
            viewModel.sendMessage("Hello Hermes")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            // Connected to Hermes was cleared, so only Session created (System) + Hello Hermes (User) = 2 messages
            assertEquals(2, state.messages.size)
            assertEquals("Session created", state.messages[0].content)
            assertEquals("Hello Hermes", state.messages[1].content)
            assertEquals(MessageRole.USER, state.messages[1].role)
            assertTrue(state.isAgentTyping)

            verify { HermesWsClient.sendMessage("session-123", "Hello Hermes") }
        }

    @Test
    fun testSwitchSession() =
        runTest {
            val viewModel = ChatViewModel()
            advanceUntilIdle()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("session-456", state.currentSessionId)
            // B3 (Jun 18 2026, kanban t_33da8a97): loadSessionMessages now
            // sets isLoading=false on its error/success branches (previously
            // never reset on error, leaving isLoading stuck at true). Since
            // ApiClient.hermesApi is not mocked in this test suite, the real
            // Retrofit call fails → catch branch sets isLoading=false and
            // records errorMessage. Assert the new correct behavior.
            assertFalse("isLoading should be false once loadSessionMessages settles", state.isLoading)
            assertTrue("messages should be cleared by switchSession", state.messages.isEmpty())
            assertNotNull("errorMessage should be set on load failure", state.errorMessage)

            verify { HermesWsClient.send(WsMethods.SESSION_RESUME, mapOf("session_id" to "session-456")) }
        }

    @Test
    fun testRpcErrorHandling() =
        runTest {
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any()) } answers {
                createReqId = "create-req-err"
                createReqId
            }

            val viewModel = ChatViewModel()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // Emit RpcError
            mockEventsFlow.emit(
                WsEvent.RpcError(
                    createReqId,
                    JsonRpcError(code = -32603, message = "Internal error during creation"),
                ),
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertTrue(state.errorMessage!!.contains("Internal error during creation"))
            assertTrue(state.errorMessage.contains(WsMethods.SESSION_CREATE))
        }
}
