package com.m57.hermescontrol.ui.chat

import android.app.Application
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.model.CompressionSummary
import com.m57.hermescontrol.data.model.PaginationInfo
import com.m57.hermescontrol.data.model.SessionCompressResponse
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.model.SessionMessagesResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatCompressRpcTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockEventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    private val mockConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    private lateinit var app: Application
    private lateinit var fakeRepo: FakeChatPersistenceRepository
    private var reqCount = 0
    private var sessionCreateRequestId: String? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        reqCount = 0
        sessionCreateRequestId = null

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkObject(AuthManager)
        every { AuthManager.getPinnedModels() } returns emptyList()
        every { AuthManager.dataScopeFlow } returns MutableStateFlow<DataScope?>(null)
        every { AuthManager.activeProfileId } returns MutableStateFlow<String?>(null)
        every { AuthManager.currentDataScope() } returns null
        every { AuthManager.getToken() } returns "test-token"
        every { AuthManager.getBaseUrl() } returns "http://test.local/"
        every { AuthManager.getSelectedProfileId() } returns null
        every { AuthManager.getBusySendMode() } returns com.m57.hermescontrol.data.model.BusySendMode.CORRECT
        every { AuthManager.isTypingEffectEnabled() } returns true
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

        mockkObject(HermesWsClient)
        every { HermesWsClient.events } returns mockEventsFlow
        every { HermesWsClient.connectionStatus } returns mockConnectionStatus
        every { HermesWsClient.connect() } answers {
            mockConnectionStatus.value = ConnectionStatus.CONNECTING
        }
        every { HermesWsClient.disconnect() } returns Unit

        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            if (arg<String>(0) == WsMethods.SESSION_CREATE) {
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
        every { HermesWsClient.request(any(), any(), any()) } answers {
            HermesWsClient.send(arg(0), arg(1)) {}
            CompletableDeferred<Any?>(Unit)
        }

        mockkObject(ApiClient)
        coEvery { ApiClient.hermesApi.getModelInfo() } returns retrofit2.Response.success(mockk(relaxed = true))
        coEvery { ApiClient.hermesApi.getProfiles() } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model
                    .ProfilesResponse(emptyList()),
            )

        mockkObject(HermesDatabase)
        mockkObject(ProfileSwitchCoordinator)
        every { ProfileSwitchCoordinator.switched } returns MutableSharedFlow<String>()
        every { ProfileSwitchCoordinator.connectionSwitched } returns MutableSharedFlow<String>()

        app = mockk(relaxed = true)
        fakeRepo = FakeChatPersistenceRepository()
        mockConnectionStatus.value = ConnectionStatus.DISCONNECTED
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private suspend fun TestScope.createViewModelWithSession(
        runtimeId: String = "session-xyz",
        storageId: String = runtimeId,
        onHistoryReplaced: (() -> Unit)? = null,
    ): Pair<ChatViewModel, String> {
        val vm =
            ChatViewModel(
                app,
                startCleanup = false,
                repo = fakeRepo,
                slashUsageStore = FakeSlashUsageStore(),
                searchDispatcher = testDispatcher,
                ioDispatcher = testDispatcher,
                historyDispatcher = testDispatcher,
                sendStore = ChatSendStore(),
                onCompressionHistoryReplacedForTest = onHistoryReplaced,
            )
        advanceUntilIdle()
        mockConnectionStatus.value = ConnectionStatus.CONNECTED
        mockEventsFlow.emit(WsEvent.GatewayReady(null))
        advanceUntilIdle()
        val createRequestId = requireNotNull(sessionCreateRequestId) { "session.create was not sent" }
        mockEventsFlow.emit(
            WsEvent.RpcResult(createRequestId, mapOf("session_id" to runtimeId, "stored_session_id" to storageId)),
        )
        advanceUntilIdle()
        return Pair(vm, storageId)
    }

    @Test
    fun `compress targets runtime ID and persists feedback under stored history ID`() =
        runTest {
            val (vm, storageId) = createViewModelWithSession(runtimeId = "runtime-123", storageId = "history-456")
            val paramsCalls = mutableListOf<Map<String, Any>>()
            every {
                HermesWsClient.request(WsMethods.SESSION_COMPRESS, capture(paramsCalls), any())
            } answers {
                CompletableDeferred<Any?>(
                    buildJsonObject {
                        put("status", "ok")
                        put("message", "Context compressed under stored history")
                    },
                )
            }

            vm.sendMessage("/compress preserve decisions")
            advanceUntilIdle()

            assertEquals(JsonPrimitive("runtime-123"), paramsCalls.single()["session_id"])
            assertEquals(JsonPrimitive("preserve decisions"), paramsCalls.single()["focus_topic"])
            assertEquals(storageId, vm.uiState.value.currentSessionId)
            assertTrue(fakeRepo.loadMessages(storageId).any { it.content == "Context compressed under stored history" })
            assertTrue(fakeRepo.loadMessages("runtime-123").isEmpty())
        }

    @Test
    fun `compact alias targets runtime ID when stored history ID differs`() =
        runTest {
            val (vm, _) = createViewModelWithSession(runtimeId = "runtime-123", storageId = "history-456")
            val paramsCalls = mutableListOf<Map<String, Any>>()
            every {
                HermesWsClient.request(WsMethods.SESSION_COMPRESS, capture(paramsCalls), any())
            } answers {
                CompletableDeferred<Any?>(buildJsonObject { put("status", "ok") })
            }

            vm.sendMessage("/compact")
            advanceUntilIdle()

            assertEquals(JsonPrimitive("runtime-123"), paramsCalls.single()["session_id"])
            assertFalse(paramsCalls.single().containsKey("focus_topic"))
        }

    @Test
    fun `compress calls SESSION_COMPRESS not command dispatch or slash exec`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            val methodCalls = mutableListOf<String>()
            every {
                HermesWsClient.request(capture(methodCalls), any(), any())
            } answers {
                CompletableDeferred<Any?>(null)
            }

            vm.sendMessage("/compress")
            advanceUntilIdle()

            assertTrue("expected SESSION_COMPRESS in $methodCalls", WsMethods.SESSION_COMPRESS in methodCalls)
            assertTrue("expected NO command.dispatch", WsMethods.COMMAND_DISPATCH !in methodCalls)
            assertTrue("expected NO slash.exec", "slash.exec" !in methodCalls)
        }

    @Test
    fun `compress accepts compressed status emitted by the real gateway`() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            every {
                HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any())
            } answers {
                CompletableDeferred<Any?>(
                    buildJsonObject {
                        put("status", "compressed")
                        put("removed", 20)
                        put("before_messages", 48)
                        put("after_messages", 28)
                        put(
                            "summary",
                            buildJsonObject { put("headline", "Context compressed by the real gateway") },
                        )
                    },
                )
            }

            vm.sendMessage("/compress")
            advanceUntilIdle()

            val lastMessage =
                vm.uiState.value.messages
                    .last()
            assertEquals("Context compressed by the real gateway", lastMessage.content)
        }

    @Test
    fun `compress request parameters contain session_id and omit focus_topic when blank`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            val methodCalls = mutableListOf<String>()
            val paramsCalls = mutableListOf<Map<String, Any>>()
            every {
                HermesWsClient.request(capture(methodCalls), capture(paramsCalls), any())
            } answers {
                CompletableDeferred<Any?>(null)
            }

            vm.sendMessage("/compress")
            advanceUntilIdle()

            val compressIndex = methodCalls.indexOf(WsMethods.SESSION_COMPRESS)
            assertTrue("expected SESSION_COMPRESS in $methodCalls", compressIndex >= 0)
            val params = paramsCalls[compressIndex]
            assertEquals(JsonPrimitive(sessionId), params["session_id"])
            assertFalse("focus_topic should be omitted when blank", params.containsKey("focus_topic"))
        }

    @Test
    fun `compress request parameters forward focus_topic when non-blank`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            val methodCalls = mutableListOf<String>()
            val paramsCalls = mutableListOf<Map<String, Any>>()
            every {
                HermesWsClient.request(capture(methodCalls), capture(paramsCalls), any())
            } answers {
                CompletableDeferred<Any?>(null)
            }

            vm.sendMessage("/compress auth decisions")
            advanceUntilIdle()

            val compressIndex = methodCalls.indexOf(WsMethods.SESSION_COMPRESS)
            assertTrue("expected SESSION_COMPRESS in $methodCalls", compressIndex >= 0)
            val params = paramsCalls[compressIndex]
            assertEquals(JsonPrimitive(sessionId), params["session_id"])
            assertEquals(JsonPrimitive("auth decisions"), params["focus_topic"])
        }

    @Test
    fun `compact behaves identically to compress omitting focus_topic when blank`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            val methodCalls = mutableListOf<String>()
            val paramsCalls = mutableListOf<Map<String, Any>>()
            every {
                HermesWsClient.request(capture(methodCalls), capture(paramsCalls), any())
            } answers {
                CompletableDeferred<Any?>(null)
            }

            vm.sendMessage("/compact")
            advanceUntilIdle()

            val compressIndex = methodCalls.indexOf(WsMethods.SESSION_COMPRESS)
            assertTrue("expected SESSION_COMPRESS in $methodCalls", compressIndex >= 0)
            val params = paramsCalls[compressIndex]
            assertEquals(JsonPrimitive(sessionId), params["session_id"])
            assertFalse("focus_topic should be omitted when blank", params.containsKey("focus_topic"))
            assertTrue(WsMethods.COMMAND_DISPATCH !in methodCalls)
        }

    @Test
    fun `compact forwards focus_topic when non-blank`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            val methodCalls = mutableListOf<String>()
            val paramsCalls = mutableListOf<Map<String, Any>>()
            every {
                HermesWsClient.request(capture(methodCalls), capture(paramsCalls), any())
            } answers {
                CompletableDeferred<Any?>(null)
            }

            vm.sendMessage("/compact architecture details")
            advanceUntilIdle()

            val compressIndex = methodCalls.indexOf(WsMethods.SESSION_COMPRESS)
            assertTrue("expected SESSION_COMPRESS in $methodCalls", compressIndex >= 0)
            val params = paramsCalls[compressIndex]
            assertEquals(JsonPrimitive(sessionId), params["session_id"])
            assertEquals(JsonPrimitive("architecture details"), params["focus_topic"])
        }

    @Test
    fun `final response summary headline token_line note added to messages`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            val responseJson =
                buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                    put(
                        "summary",
                        buildJsonObject {
                            put("headline", JsonPrimitive("Context compressed"))
                            put("token_line", JsonPrimitive("Tokens: 120k -> 35k (70% saved)"))
                            put("note", JsonPrimitive("Trimmed 14 earlier turns"))
                        },
                    )
                }

            every {
                HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any())
            } answers {
                CompletableDeferred<Any?>(responseJson)
            }

            vm.sendMessage("/compress")
            advanceUntilIdle()

            val lastAssistantMessage =
                vm.uiState.value.messages
                    .lastOrNull { it.role == MessageRole.ASSISTANT }
            val expectedContent = "Context compressed\nTokens: 120k -> 35k (70% saved)\nTrimmed 14 earlier turns"
            assertEquals(expectedContent, lastAssistantMessage?.content)
        }

    @Test
    fun `returned messages replace and reconcile visible live transcript`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            val serverMessages =
                listOf(
                    SessionMessage(
                        id = 101,
                        role = "user",
                        content = JsonPrimitive("Original prompt"),
                    ),
                    SessionMessage(
                        id = 102,
                        role = "assistant",
                        content = JsonPrimitive("Compressed response"),
                    ),
                )

            val responseJson =
                buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                    put(
                        "summary",
                        buildJsonObject {
                            put("headline", JsonPrimitive("Summary headline"))
                        },
                    )
                    put(
                        "messages",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(101))
                                    put("role", JsonPrimitive("user"))
                                    put("content", JsonPrimitive("Original prompt"))
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(102))
                                    put("role", JsonPrimitive("assistant"))
                                    put("content", JsonPrimitive("Compressed response"))
                                },
                            )
                        },
                    )
                }

            every {
                HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any())
            } answers {
                CompletableDeferred<Any?>(responseJson)
            }

            vm.sendMessage("/compress")
            advanceUntilIdle()

            val messages = vm.uiState.value.messages
            // The transcript contains serverMessages, plus the assistant summary feedback
            val userMsg = messages.firstOrNull { it.role == MessageRole.USER && it.content == "Original prompt" }
            assertEquals("Original prompt", userMsg?.content)
            val serverAssistantMsg =
                messages.firstOrNull {
                    it.role == MessageRole.ASSISTANT && it.content == "Compressed response"
                }
            assertTrue("expected replacement assistant message", serverAssistantMsg != null)
            val summaryMsg = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
            assertEquals("Summary headline", summaryMsg?.content)
        }

    @Test
    fun `compression does not erase an earlier durable local command`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            val earlier = ChatMessage(role = MessageRole.USER, content = "/help")
            fakeRepo.persistMessage(earlier, sessionId)
            val response =
                buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                    put("message", JsonPrimitive("Compressed."))
                    put(
                        "messages",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(1))
                                    put("role", JsonPrimitive("user"))
                                    put("content", JsonPrimitive("Summary"))
                                },
                            )
                        },
                    )
                }
            every { HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any()) } returns
                CompletableDeferred(response)
            vm.sendMessage("/compress")
            advanceUntilIdle()
            assertTrue(fakeRepo.loadMessages(sessionId).any { it.id == earlier.id && it.content == "/help" })
        }

    @Test
    fun `confirmed UUID alias is archived rather than returned as an unconfirmed local after compression`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            val alias = ChatMessage(role = MessageRole.USER, content = "Old prompt", restId = "rest-$sessionId-50")
            val pending = ChatMessage(role = MessageRole.USER, content = "Still pending")
            fakeRepo.persistMessage(alias, sessionId)
            fakeRepo.persistMessage(pending, sessionId)
            every { HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any()) } returns
                CompletableDeferred(
                    buildJsonObject {
                        put("status", JsonPrimitive("ok"))
                        put(
                            "messages",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(1))
                                        put("role", JsonPrimitive("user"))
                                        put("content", JsonPrimitive("Summary"))
                                    },
                                )
                            },
                        )
                    },
                )
            vm.sendMessage("/compress")
            advanceUntilIdle()
            assertFalse(
                vm.uiState.value.messages
                    .any { it.id == alias.id || it.content == "Old prompt" },
            )
            assertTrue(
                vm.uiState.value.messages
                    .any { it.id == pending.id },
            )
            val stored = requireNotNull(fakeRepo.dao.getMessage(alias.id))
            assertEquals("Old prompt", stored.content)
            assertEquals(alias.restId, stored.restId)
            assertEquals("COMPRESSED_ALIAS", stored.messageProvenance)
            assertFalse(fakeRepo.replaceCanonicalHistory(sessionId, emptyList()).any { it.id == alias.id })
        }

    @Test
    fun `malformed compression response reports error without claiming success`() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            every { HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any()) } returns
                CompletableDeferred<Any?>(buildJsonArray { add(JsonPrimitive("unexpected")) })
            vm.sendMessage("/compress")
            advanceUntilIdle()
            val feedback =
                vm.uiState.value.messages
                    .last { it.role == MessageRole.ASSISTANT }
                    .content
            assertTrue(feedback.contains("/compress:"))
            assertFalse(feedback.contains("Context compressed."))
            assertFalse(vm.uiState.value.isCompressing)
        }

    @Test
    fun `late compression result cannot modify another selected session`() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            val deferred = CompletableDeferred<Any?>()
            every { HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any()) } returns deferred
            vm.sendMessage("/compress")
            testDispatcher.scheduler.runCurrent()
            val other = ChatMessage(role = MessageRole.USER, content = "/other-local")
            fakeRepo.persistMessage(other, "other-session")
            vm.switchSession("other-session")
            testDispatcher.scheduler.runCurrent()
            deferred.complete(
                buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                    put("message", JsonPrimitive("Old session compressed"))
                    put(
                        "messages",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(1))
                                    put("role", JsonPrimitive("assistant"))
                                    put("content", JsonPrimitive("Old session summary"))
                                },
                            )
                        },
                    )
                },
            )
            advanceUntilIdle()
            assertEquals("other-session", vm.uiState.value.currentSessionId)
            assertTrue(fakeRepo.loadMessages("other-session").any { it.id == other.id })
            assertFalse(
                vm.uiState.value.messages.any {
                    it.content == "Old session summary" ||
                        it.content == "Old session compressed"
                },
            )
        }

    @Test
    fun `compression replacement keeps command and feedback after refreshed history and cache reload`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            val old =
                ChatMessage(
                    id = "rest-$sessionId-50",
                    role = MessageRole.USER,
                    content = "Old prompt",
                    timestamp = 900L,
                )
            fakeRepo.persistMessage(old, sessionId)
            val response =
                buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                    put("message", JsonPrimitive("Compressed."))
                    put(
                        "messages",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(1))
                                    put("role", JsonPrimitive("user"))
                                    put("content", JsonPrimitive("Summary"))
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(2))
                                    put("role", JsonPrimitive("assistant"))
                                    put("content", JsonPrimitive("Fresh answer"))
                                },
                            )
                        },
                    )
                }
            every { HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any()) } returns
                CompletableDeferred(response)

            vm.sendMessage("/compress")
            advanceUntilIdle()

            // The session-start marker is local user data, not disposable server history.
            val expected = listOf("Summary", "Fresh answer", "Session created", "/compress", "Compressed.")
            assertEquals(
                expected,
                vm.uiState.value.messages
                    .map { it.content },
            )
            val restored = mergeCachedTranscriptPage(fakeRepo.loadPage(sessionId, null, 150).messages, emptyList())
            assertEquals(expected, restored.map { it.content })
            val refreshed =
                listOf(
                    ChatMessage(id = "rest-$sessionId-0", role = MessageRole.USER, content = "Summary", timestamp = 1L),
                    ChatMessage(
                        id = "rest-$sessionId-1",
                        role = MessageRole.ASSISTANT,
                        content = "Fresh answer",
                        timestamp = 2L,
                    ),
                )
            assertEquals(expected, mergeTranscriptWithLive(refreshed, restored).map { it.content })
            assertEquals(
                expected,
                mergeTranscriptWithLive(
                    refreshed,
                    mergeTranscriptWithLive(refreshed, restored),
                ).map {
                    it.content
                },
            )
        }

    @Test
    fun `bare and whitespace queue commands and usage survive compression and repository reload`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            val sendsBefore = reqCount
            assertTrue(vm.sendMessage("/queue"))
            assertTrue(vm.sendMessage("/queue    "))
            advanceUntilIdle()
            val local =
                vm.uiState.value.messages.filter {
                    it.content.startsWith("/queue") ||
                        it.content == "usage: /queue <prompt>"
                }
            assertEquals(
                listOf("/queue", "usage: /queue <prompt>", "/queue", "usage: /queue <prompt>"),
                local.map { it.content },
            )
            assertEquals(4, local.map { it.id }.distinct().size)
            assertEquals(sendsBefore, reqCount)
            val durableBefore = fakeRepo.loadMessages(sessionId)
            assertTrue(local.all { row -> durableBefore.any { it.id == row.id && it.content == row.content } })

            every { HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any()) } returns
                CompletableDeferred(
                    buildJsonObject {
                        put("status", JsonPrimitive("ok"))
                        put("message", JsonPrimitive("Compressed."))
                        put(
                            "messages",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(1))
                                        put("role", JsonPrimitive("user"))
                                        put("content", JsonPrimitive("Summary"))
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(2))
                                        put("role", JsonPrimitive("assistant"))
                                        put("content", JsonPrimitive("Fresh answer"))
                                    },
                                )
                            },
                        )
                    },
                )
            vm.sendMessage("/compress")
            advanceUntilIdle()
            val freshRepo = FakeChatPersistenceRepository(fakeRepo.dao)
            val restored = mergeCachedTranscriptPage(freshRepo.loadPage(sessionId, null, 150).messages, emptyList())
            val refreshed =
                listOf(
                    ChatMessage(id = "rest-$sessionId-0", role = MessageRole.USER, content = "Summary", timestamp = 1L),
                    ChatMessage(
                        id = "rest-$sessionId-1",
                        role = MessageRole.ASSISTANT,
                        content = "Fresh answer",
                        timestamp = 2L,
                    ),
                )
            val once = mergeTranscriptWithLive(refreshed, restored)
            val twice = mergeTranscriptWithLive(refreshed, once)
            val expected =
                listOf("Summary", "Fresh answer", "Session created") + local.map { it.content } +
                    listOf("/compress", "Compressed.")
            assertEquals(
                expected,
                vm.uiState.value.messages
                    .map { it.content },
            )
            assertEquals(expected, restored.map { it.content })
            assertEquals(expected, once.map { it.content })
            assertEquals(expected, twice.map { it.content })
            assertEquals(local.map { it.id }, twice.filter { it.id in local.map { row -> row.id } }.map { it.id })
        }

    @Test
    fun `local command appended after replacement transaction retains identity and anchor across cache and REST`() =
        runTest {
            lateinit var vm: ChatViewModel
            var callbackCount = 0
            vm =
                createViewModelWithSession(onHistoryReplaced = {
                    callbackCount++
                    val requestsBeforeUpdate = reqCount
                    assertTrue(vm.sendMessage("/update")) // Client-side dialog, never a gateway request.
                    assertEquals(requestsBeforeUpdate, reqCount)
                }).first
            val sessionId = requireNotNull(vm.uiState.value.currentSessionId)
            val replacement =
                buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                    put("message", JsonPrimitive("Compressed."))
                    put(
                        "messages",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(1))
                                    put("role", JsonPrimitive("user"))
                                    put("content", JsonPrimitive("Summary"))
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(2))
                                    put("role", JsonPrimitive("assistant"))
                                    put("content", JsonPrimitive("Fresh answer"))
                                },
                            )
                        },
                    )
                }
            every { HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any()) } returns
                CompletableDeferred(replacement)
            vm.sendMessage("/compress")
            advanceUntilIdle()
            assertEquals(1, callbackCount)
            val expected = listOf("Summary", "Fresh answer", "Session created", "/compress", "/update", "Compressed.")
            val command =
                vm.uiState.value.messages
                    .single { it.content == "/update" }
            assertEquals(
                expected,
                vm.uiState.value.messages
                    .map { it.content },
            )
            assertEquals(1L, command.localAnchorOrder)
            assertEquals(null, command.localPredecessorId)
            val cached = mergeCachedTranscriptPage(fakeRepo.loadPage(sessionId, null, 150).messages, emptyList())
            assertEquals(expected, cached.map { it.content })
            assertEquals(command.id, cached.single { it.content == "/update" }.id)
            assertEquals(1L, cached.single { it.id == command.id }.localAnchorOrder)
            val rest =
                listOf(
                    ChatMessage(id = "rest-$sessionId-0", role = MessageRole.USER, content = "Summary", timestamp = 1L),
                    ChatMessage(
                        id = "rest-$sessionId-1",
                        role = MessageRole.ASSISTANT,
                        content = "Fresh answer",
                        timestamp = 2L,
                    ),
                )
            val once = mergeTranscriptWithLive(rest, cached)
            val twice = mergeTranscriptWithLive(rest, once)
            assertEquals(expected, once.map { it.content })
            assertEquals(expected, twice.map { it.content })
            assertEquals(command.id, twice.single { it.content == "/update" }.id)
            assertEquals(1L, twice.single { it.id == command.id }.localAnchorOrder)
        }

    @Test
    fun `idless compression fallback preserves cached local command when REST refresh fails`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()
            val api = mockk<com.m57.hermescontrol.data.remote.HermesApiService>(relaxed = true)
            every { ApiClient.hermesApi } returns api
            val canonical =
                SessionMessagesResponse(
                    messages = listOf(SessionMessage(id = 1, role = "user", content = JsonPrimitive("Canonical"))),
                    pagination = PaginationInfo(order = "latest"),
                )
            coEvery { api.getSessionMessages(sessionId, any(), any(), any(), any()) } returns
                retrofit2.Response.success(canonical)
            every { HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any()) } returns
                CompletableDeferred(
                    buildJsonObject {
                        put("status", JsonPrimitive("ok"))
                        put("message", JsonPrimitive("First compression"))
                        put(
                            "messages",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", JsonPrimitive(1))
                                        put("role", JsonPrimitive("user"))
                                        put("content", JsonPrimitive("Canonical"))
                                    },
                                )
                            },
                        )
                    },
                )
            vm.sendMessage("/compress")
            advanceUntilIdle()
            vm.refreshCurrentSession()
            advanceUntilIdle() // Negotiate latest paging before an id-less RPC result.
            val before = fakeRepo.loadPage(sessionId, null, 150).messages.map { it.id to it.content }
            coEvery { api.getSessionMessages(sessionId, any(), any(), any(), any()) } returns
                retrofit2.Response.error(503, okhttp3.ResponseBody.create(null, "unavailable"))
            every { HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any()) } returns
                CompletableDeferred(
                    buildJsonObject {
                        put("status", JsonPrimitive("ok"))
                        put("message", JsonPrimitive("Idless"))
                        put(
                            "messages",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("role", JsonPrimitive("user"))
                                        put("content", JsonPrimitive("Partial history"))
                                    },
                                )
                            },
                        )
                    },
                )
            vm.sendMessage("/compress")
            advanceUntilIdle()
            val cached = fakeRepo.loadPage(sessionId, null, 150).messages
            assertTrue(before.all { (id, content) -> cached.any { it.id == id && it.content == content } })
            assertFalse(cached.any { it.content == "Partial history" })
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content == "Canonical" },
            )
            assertTrue(
                vm.uiState.value.messages
                    .any { it.content.contains("History refresh incomplete") },
            )
        }

    @Test
    fun `fetchContextUsage is triggered after successful compression`() =
        runTest {
            val (vm, sessionId) = createViewModelWithSession()

            val methodCalls = mutableListOf<String>()
            every {
                HermesWsClient.request(capture(methodCalls), any(), any())
            } answers {
                val method = arg<String>(0)
                if (method == WsMethods.SESSION_COMPRESS) {
                    val responseJson =
                        buildJsonObject {
                            put("status", JsonPrimitive("ok"))
                            put("message", JsonPrimitive("Context compressed."))
                        }
                    CompletableDeferred<Any?>(responseJson)
                } else {
                    CompletableDeferred<Any?>(Unit)
                }
            }

            methodCalls.clear()

            vm.sendMessage("/compress")
            advanceUntilIdle()

            // fetchContextUsage calls SESSION_CONTEXT_BREAKDOWN and SESSION_USAGE with sessionId
            assertTrue(
                "expected SESSION_CONTEXT_BREAKDOWN in $methodCalls",
                WsMethods.SESSION_CONTEXT_BREAKDOWN in methodCalls,
            )
            assertTrue(
                "expected SESSION_USAGE in $methodCalls",
                WsMethods.SESSION_USAGE in methodCalls,
            )
        }

    @Test
    fun `isCompressing is reset to false on success`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            val deferred = CompletableDeferred<Any?>()
            every {
                HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any())
            } returns deferred

            vm.sendMessage("/compress")
            // Progress before completion
            testDispatcher.scheduler.runCurrent()
            assertTrue("isCompressing should be true while in flight", vm.uiState.value.isCompressing)
            assertEquals("⏳ Compressing context...", vm.uiState.value.compressionStatus)

            deferred.complete(
                buildJsonObject {
                    put("status", JsonPrimitive("ok"))
                },
            )
            advanceUntilIdle()

            assertFalse("isCompressing should be reset to false", vm.uiState.value.isCompressing)
            assertEquals(null, vm.uiState.value.compressionStatus)
        }

    @Test
    fun `isCompressing is reset to false on RPC error`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            val deferred = CompletableDeferred<Any?>()
            every {
                HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any())
            } returns deferred

            vm.sendMessage("/compress")
            testDispatcher.scheduler.runCurrent()
            assertTrue(vm.uiState.value.isCompressing)

            deferred.completeExceptionally(
                HermesWsClient.HermesRpcException("compression failed on backend", 4018),
            )
            advanceUntilIdle()

            assertFalse("isCompressing should be false on RPC error", vm.uiState.value.isCompressing)
            assertEquals(null, vm.uiState.value.compressionStatus)
            val errorMsg =
                vm.uiState.value.messages
                    .lastOrNull { it.role == MessageRole.ASSISTANT }
            assertTrue(errorMsg?.content?.contains("compression failed on backend") == true)
        }

    @Test
    fun `isCompressing is reset to false on generic exception`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            val deferred = CompletableDeferred<Any?>()
            every {
                HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any())
            } returns deferred

            vm.sendMessage("/compress")
            testDispatcher.scheduler.runCurrent()
            assertTrue(vm.uiState.value.isCompressing)

            deferred.completeExceptionally(RuntimeException("network disconnected"))
            advanceUntilIdle()

            assertFalse("isCompressing should be false on generic exception", vm.uiState.value.isCompressing)
            assertEquals(null, vm.uiState.value.compressionStatus)
            val errorMsg =
                vm.uiState.value.messages
                    .lastOrNull { it.role == MessageRole.ASSISTANT }
            assertTrue(errorMsg?.content?.contains("network disconnected") == true)
        }

    @Test
    fun `duplicate compress while isCompressing is true is a no-op`() =
        runTest {
            val (vm, _) = createViewModelWithSession()

            var compressCalls = 0
            val deferred = CompletableDeferred<Any?>()
            every {
                HermesWsClient.request(WsMethods.SESSION_COMPRESS, any(), any())
            } answers {
                compressCalls++
                deferred
            }

            vm.sendMessage("/compress")
            testDispatcher.scheduler.runCurrent()
            assertTrue(vm.uiState.value.isCompressing)
            assertEquals(1, compressCalls)

            // Second invocation while in flight
            vm.sendMessage("/compress")
            testDispatcher.scheduler.runCurrent()
            assertEquals("duplicate call should not trigger another RPC", 1, compressCalls)

            // Also check /compact is blocked while in flight
            vm.sendMessage("/compact")
            testDispatcher.scheduler.runCurrent()
            assertEquals("compact while compressing should not trigger another RPC", 1, compressCalls)

            deferred.complete(null)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.isCompressing)
        }
}
