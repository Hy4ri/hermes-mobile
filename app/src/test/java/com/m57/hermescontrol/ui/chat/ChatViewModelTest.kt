package com.m57.hermescontrol.ui.chat

import android.app.Application
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.ChatMessageDao
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.JsonRpcError
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val mockConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    private lateinit var app: Application
    private val mockDao: ChatMessageDao = mockk(relaxed = true)
    private val mockDb: HermesDatabase = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val testMainDispatcher = Dispatchers.Main

        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher
        every { Dispatchers.Main } returns testMainDispatcher

        mockkObject(AuthManager)
        mockkObject(HermesWsClient)
        mockkObject(ApiClient)
        mockkObject(HermesDatabase)

        app = mockk(relaxed = true)

        mockConnectionStatus.value = ConnectionStatus.DISCONNECTED

        every { AuthManager.getToken() } returns "test-token"
        every { HermesWsClient.events } returns mockEventsFlow
        every { HermesWsClient.connectionStatus } returns mockConnectionStatus
        every { HermesWsClient.connect() } returns Unit
        every { HermesWsClient.disconnect() } returns Unit
        every { mockDb.chatMessageDao() } returns mockDao
        every { HermesDatabase.get(any()) } returns mockDb
        coEvery { mockDao.getMessagesForSession(any()) } returns emptyList()
        coEvery { mockDao.upsert(any()) } returns Unit
        coEvery { mockDao.upsertAll(any()) } returns Unit

        // Default mock stubs for requests returning unique IDs
        var reqCount = 0
        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            val onSent = arg<((String) -> Unit)?>(2)
            onSent?.invoke(id)
            id
        }
        every { HermesWsClient.sendMessage(any(), any(), any()) } answers {
            reqCount++
            val id = "req-msg-$reqCount"
            val onSent = arg<((String) -> Unit)?>(2)
            onSent?.invoke(id)
            id
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── TEST-13: Slash command handling ──────────────────────────────────

    @Test
    fun testSlashCommand_help_addsHelpMessage() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Setup active session
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-help-test"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            viewModel.sendMessage("/help")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.messages.any { it.content.contains("Available Commands") })
            assertTrue(state.messages.any { it.content.contains("/status") })
            assertTrue(state.messages.any { it.content.contains("/new") })
        }

    @Test
    fun testSlashCommand_new_createsNewSession() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Setup active session
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-new-test"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            viewModel.sendMessage("/new")
            advanceUntilIdle()

            // /new should trigger createNewSession which sends SESSION_CREATE
            verify(atLeast = 1) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun testSlashCommand_stop_sendsInterrupt() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Setup active session
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-stop-test"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            viewModel.sendMessage("/stop")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.PROMPT_INTERRUPT, any(), any()) }
        }

    @Test
    fun testSlashCommand_interrupt_sendsInterrupt() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-int-test"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            viewModel.sendMessage("/interrupt")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.PROMPT_INTERRUPT, any(), any()) }
        }

    @Test
    fun testSlashCommand_unknown_showsErrorMessage() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-unk-test"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            viewModel.sendMessage("/nonexistent")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.messages.any { it.content.contains("Unknown command") })
            assertTrue(state.messages.any { it.content.contains("/nonexistent") })
        }

    @Test
    fun testSlashCommand_status_withSyncSession_routesToSlash() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Setup active session
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-status-test"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            every { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) } answers {
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke("list-req")
                "list-req"
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            // Stub the API call — without this, mockkObject returns null mock and getStatus call fails
            every { ApiClient.hermesApi.getStatus() } returns mockk(relaxed = true) {
                coEvery { execute() } returns mockk {
                    every { isSuccessful } returns true
                    every { body() } returns null
                }
            }

            viewModel.sendMessage("/status")
            advanceUntilIdle()

            // Should have dispatched the API call
            verify { ApiClient.hermesApi.getStatus() }
        }

    @Test
    fun testSlashCommand_sessions_withSyncSession_routesToSlash() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-sess-test"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            every { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) } answers {
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke("list-req")
                "list-req"
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            every { ApiClient.hermesApi.getSessions() } returns mockk(relaxed = true) {
                coEvery { execute() } returns mockk {
                    every { isSuccessful } returns true
                    every { body() } returns null
                }
            }

            viewModel.sendMessage("/sessions")
            advanceUntilIdle()

            verify { ApiClient.hermesApi.getSessions() }
        }

    @Test
    fun testSlashCommand_stats_withSyncSession_routesToSlash() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-stats-test"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            every { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) } answers {
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke("list-req")
                "list-req"
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            every { ApiClient.hermesApi.getSystemStats() } returns mockk(relaxed = true) {
                coEvery { execute() } returns mockk {
                    every { isSuccessful } returns true
                    every { body() } returns null
                }
            }

            viewModel.sendMessage("/stats")
            advanceUntilIdle()

            verify { ApiClient.hermesApi.getSystemStats() }
        }

    @Test
    fun testInitialStateAndConnection() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            verify { HermesWsClient.connect() }
            assertTrue(viewModel.uiState.value.isLoading)
            assertFalse(viewModel.uiState.value.isConnected)
        }

    @Test
    fun testGatewayReady_createsSessionIfNoneExists() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.isConnected)
            // Note: GatewayReady immediately calls createNewSession(), which sets isLoading = true and clears messages
            assertTrue(state.isLoading)
            assertEquals(0, state.messages.size)

            // Verify that list sessions and create session requests are triggered
            verify { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) }
            verify { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun testGatewayReady_withInitialSessionId_switchesToIt() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Set the initial session ID (normally done by ChatScreen SideEffect)
            viewModel.initialSessionId = "session-from-notification"
            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.isConnected)
            // Should NOT have called createNewSession — switchSession was used instead
            assertEquals("session-from-notification", state.currentSessionId)

            // Verify SESSION_RESUME was sent instead of SESSION_CREATE
            verify { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) }
            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to "session-from-notification"),
                    any(),
                )
            }
            // SESSION_CREATE must NOT be sent
            verify(inverse = true) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun testSessionCreateRpcResult() =
        runTest {
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "custom-create-id"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }

            val viewModel = ChatViewModel(app, startCleanup = false)
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
            every { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) } answers {
                listReqId = "custom-list-id"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(listReqId)
                listReqId
            }

            val viewModel = ChatViewModel(app, startCleanup = false)
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
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Trigger GatewayReady -> triggers createSession -> feed result to set active session to session-123
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-req-stream"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            // Stream Start
            mockEventsFlow.emit(WsEvent.MessageStart("session-123"))
            advanceUntilIdle()

            var state = viewModel.uiState.value
            assertTrue(state.isAgentTyping)
            assertNotNull(state.streamingMessage)
            assertEquals("", state.streamingMessage?.content)
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
            assertNotNull(state.streamingMessage)
            assertEquals("Hello", state.streamingMessage?.content)
            // Streaming message is not in the main messages list yet, only "Session created" exists
            assertEquals(1, state.messages.size)

            // Token 2
            mockEventsFlow.emit(WsEvent.MessageToken(" world", "session-123"))
            advanceUntilIdle()
            state = viewModel.uiState.value
            assertNotNull(state.streamingMessage)
            assertEquals("Hello world", state.streamingMessage?.content)
            assertEquals(1, state.messages.size)

            // Complete
            mockEventsFlow.emit(WsEvent.MessageComplete("Hello world!", "session-123"))
            advanceUntilIdle()
            state = viewModel.uiState.value
            assertFalse(state.isAgentTyping)
            assertNull(state.streamingMessage)
            assertFalse(state.isThinking)
            assertEquals("", state.thinkingText)
            assertEquals(2, state.messages.size)
            assertEquals("Session created", state.messages[0].content)
            assertEquals("Hello world!", state.messages[1].content)
            assertFalse(state.messages[1].isStreaming)
        }

    @Test
    fun testClarifyRequestAndRespond() =
        runTest {
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-req-clarify"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }

            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Set session ID
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.ClarifyRequest("Please choose:", listOf("Yes", "No"), "clarify-123"))
            advanceUntilIdle()

            var state = viewModel.uiState.value
            assertEquals("Please choose:", state.clarifyRequest?.text)
            assertEquals(listOf("Yes", "No"), state.clarifyRequest?.options)
            assertEquals("clarify-123", state.clarifyRequest?.clarifyId)

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

            verify {
                HermesWsClient.send(
                    method = WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to "session-123",
                            "response" to "Yes",
                            "answer" to "Yes",
                            "clarify_id" to "clarify-123",
                            "request_id" to "clarify-123",
                        ),
                    onSent = any(),
                )
            }
        }

    @Test
    fun testClarifyRequestCustomResponse() =
        runTest {
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-req-clarify-custom"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }

            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Set session ID
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.ClarifyRequest("Please explain:", emptyList(), "clarify-456"))
            advanceUntilIdle()

            var state = viewModel.uiState.value
            assertEquals("Please explain:", state.clarifyRequest?.text)
            assertTrue(state.clarifyRequest?.options.isNullOrEmpty())
            assertEquals("clarify-456", state.clarifyRequest?.clarifyId)

            // Respond to clarify with custom text
            viewModel.respondToClarify("This is my custom response text")
            advanceUntilIdle()

            // verify clarify request is dismissed, and user message is sent
            state = viewModel.uiState.value
            assertNull(state.clarifyRequest)
            assertEquals(2, state.messages.size)
            assertEquals("Session created", state.messages[0].content)
            assertEquals("This is my custom response text", state.messages[1].content)
            assertEquals(MessageRole.USER, state.messages[1].role)

            verify {
                HermesWsClient.send(
                    method = WsMethods.CLARIFY_RESPOND,
                    params =
                        mapOf(
                            "session_id" to "session-123",
                            "response" to "This is my custom response text",
                            "answer" to "This is my custom response text",
                            "clarify_id" to "clarify-456",
                            "request_id" to "clarify-456",
                        ),
                    onSent = any(),
                )
            }
        }

    @Test
    fun testSendMessage() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Trigger GatewayReady -> triggers createSession -> feed result to set active session
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-req"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
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

            verify { HermesWsClient.sendMessage("session-123", "Hello Hermes", any()) }
        }

    @Test
    fun testSwitchSession() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
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

            verify { HermesWsClient.send(WsMethods.SESSION_RESUME, mapOf("session_id" to "session-456"), any()) }
        }

    @Test
    fun testRpcErrorHandling() =
        runTest {
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-req-err"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }

            val viewModel = ChatViewModel(app, startCleanup = false)
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

    @Test
    fun testToolExecution_finalizesPreviousStreamingMessage() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Setup active session
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-req-tool-test"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            // Start typing some text
            mockEventsFlow.emit(WsEvent.MessageStart("session-123"))
            mockEventsFlow.emit(WsEvent.MessageToken("Calculating sum", "session-123"))
            advanceUntilIdle()

            // Start tool call
            mockEventsFlow.emit(WsEvent.ToolStart("calculator", mapOf("input" to "2+2")))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            // Messages should be: 1. "Session created" 2. "Calculating sum" (finalized) 3. Tool bubble
            assertEquals(3, state.messages.size)
            assertEquals("Session created", state.messages[0].content)
            assertEquals("Calculating sum", state.messages[1].content)
            assertEquals(MessageRole.ASSISTANT, state.messages[1].role)
            assertFalse(state.messages[1].isStreaming)
            assertEquals(MessageRole.TOOL, state.messages[2].role)
            assertNull(state.streamingMessage)
        }

    @Test
    fun testMessageStart_finalizesPreviousStreamingMessage() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Setup active session
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-req-msg-test"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            // Start typing message 1
            mockEventsFlow.emit(WsEvent.MessageStart("session-123"))
            mockEventsFlow.emit(WsEvent.MessageToken("First response segment", "session-123"))
            advanceUntilIdle()

            // Start typing message 2 without message 1 complete
            mockEventsFlow.emit(WsEvent.MessageStart("session-123"))
            mockEventsFlow.emit(WsEvent.MessageToken("Second response segment", "session-123"))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            // Messages should contain: 1. "Session created" 2. "First response segment" (finalized)
            assertEquals(2, state.messages.size)
            assertEquals("Session created", state.messages[0].content)
            assertEquals("First response segment", state.messages[1].content)
            assertFalse(state.messages[1].isStreaming)

            // Active streaming message should be the second segment
            assertNotNull(state.streamingMessage)
            assertEquals("Second response segment", state.streamingMessage?.content)
            assertTrue(state.streamingMessage?.isStreaming == true)
        }

    @Test
    fun testToolExecution_serializesDataAsJson() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Setup active session
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-req-tool-json-test"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            // Start tool call
            mockEventsFlow.emit(
                WsEvent.ToolStart(
                    name = "calculator",
                    data =
                        mapOf(
                            "input" to "2+2",
                            "nested" to mapOf("key" to "value"),
                        ),
                ),
            )
            advanceUntilIdle()

            var state = viewModel.uiState.value
            assertEquals(2, state.messages.size)
            assertEquals(MessageRole.TOOL, state.messages[1].role)
            assertEquals("{\"input\":\"2+2\",\"nested\":{\"key\":\"value\"}}", state.messages[1].content)
            assertEquals(ToolStatus.RUNNING, state.messages[1].toolStatus)

            // Complete tool call
            mockEventsFlow.emit(WsEvent.ToolComplete("calculator", mapOf("result" to "4", "exit_code" to 0)))
            advanceUntilIdle()

            state = viewModel.uiState.value
            assertEquals(2, state.messages.size)
            assertEquals("{\"result\":\"4\",\"exit_code\":0}", state.messages[1].content)
            assertEquals(ToolStatus.COMPLETED, state.messages[1].toolStatus)
        }

    @Test
    fun testSessionMismatchEventsAreIgnored() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Setup active session "session-active"
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-req-mismatch-test"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-active")))
            advanceUntilIdle()

            // Emit events for another session "session-other" — should be ignored
            mockEventsFlow.emit(
                WsEvent.ToolStart(
                    name = "calculator",
                    data = mapOf("input" to "2+2"),
                    sessionId = "session-other",
                ),
            )
            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = "Choose:",
                    options = listOf("Yes"),
                    clarifyId = "clarify-1",
                    sessionId = "session-other",
                ),
            )
            mockEventsFlow.emit(WsEvent.MessageStart("session-other"))
            mockEventsFlow.emit(WsEvent.MessageToken("Hello", "session-other"))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            // Only the "Session created" message should be present (mismatch events ignored)
            assertEquals(1, state.messages.size)
            assertEquals("Session created", state.messages[0].content)
            assertNull(state.streamingMessage)
            assertNull(state.clarifyRequest)
        }

    @Test
    fun testReconnectDoesNotDuplicateEventCollection() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Setup active session
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-req-reconnect-test"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            // Trigger reconnect
            viewModel.reconnect()
            advanceUntilIdle()

            // Emit a message token
            mockEventsFlow.emit(WsEvent.MessageStart("session-123"))
            mockEventsFlow.emit(WsEvent.MessageToken("Hello", "session-123"))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            // Content should be "Hello", not "HelloHello" (no duplicate collectors running)
            assertEquals("Hello", state.streamingMessage?.content)
        }

    @Test
    fun testMessageCompleteWithoutStreaming_upsertsAssistantMessage() =
        runTest {
            val viewModel = ChatViewModel(app, startCleanup = false)
            advanceUntilIdle()

            // Setup active session
            var createReqId = ""
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                createReqId = "create-req-complete-test"
                val onSent = arg<((String) -> Unit)?>(2)
                onSent?.invoke(createReqId)
                createReqId
            }
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            mockEventsFlow.emit(WsEvent.RpcResult(createReqId, mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            // Emit MessageComplete without preceding MessageStart / MessageToken
            mockEventsFlow.emit(WsEvent.MessageComplete("Fully complete message", "session-123"))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(2, state.messages.size)
            assertEquals("Fully complete message", state.messages[1].content)
            assertEquals(MessageRole.ASSISTANT, state.messages[1].role)

            // Verify upsert was called for the new message
            coVerify { mockDao.upsert(any()) }
        }
}
