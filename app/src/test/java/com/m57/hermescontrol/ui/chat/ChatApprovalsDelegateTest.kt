package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.contract.RpcMethod
import com.m57.hermescontrol.data.ws.contract.TypedRpcSender
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatApprovalsDelegateTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val uiState = MutableStateFlow(ChatUiState(currentSessionId = "storage-session-1"))
    private var runtimeId: String? = null
    private val sentMethods = mutableListOf<String>()
    private val sentParams = mutableListOf<JsonObject>()
    private val trackedRequests = mutableListOf<Pair<String, String>>()
    private val systemMessages = mutableListOf<String>()

    private val delegate =
        ChatApprovalsDelegate(
            scope = testScope,
            ioDispatcher = testDispatcher,
            uiState = uiState,
            runtimeSessionId = { runtimeId },
            rpc =
                object : TypedRpcSender {
                    override fun <P> send(
                        method: RpcMethod<P, *>,
                        params: P,
                        onSent: ((String) -> Unit)?,
                    ): String {
                        sentMethods.add(method.name)
                        sentParams.add(
                            OkHttpProvider.json.encodeToJsonElement(method.params, params) as JsonObject,
                        )
                        val id = "req-id-${sentMethods.size}"
                        onSent?.invoke(id)
                        return id
                    }
                },
            trackRequest = { id, method -> trackedRequests.add(id to method) },
            addSystemMessage = { text -> systemMessages.add(text) },
        )

    @Test
    fun parseApprovalMap_honorsSmartDeniedAndChoicePermutations() {
        val denied =
            parseApprovalMap(
                mapOf("smart_denied" to true),
                sessionId = "s1",
            )
        assertEquals(listOf("once", "deny"), denied.choices)

        val full =
            parseApprovalMap(
                mapOf("allow_session" to true, "allow_permanent" to true),
                sessionId = "s1",
            )
        assertEquals(listOf("once", "session", "always", "deny"), full.choices)

        val noAlways =
            parseApprovalMap(
                mapOf("allow_session" to true, "allow_permanent" to false),
                sessionId = "s1",
            )
        assertEquals(listOf("once", "session", "deny"), noAlways.choices)

        val explicit =
            parseApprovalMap(
                mapOf("choices" to listOf("custom", "deny")),
                sessionId = "s1",
            )
        assertEquals(listOf("custom", "deny"), explicit.choices)
    }

    @Test
    fun handleApprovalRequest_appendsSystemMessageAndSendsAckWithPreferredRuntimeId() =
        testScope.runTest {
            runtimeId = "runtime-session-xyz"
            val event =
                WsEvent.ApprovalRequest(
                    command = "git status",
                    description = "inspect working tree",
                    patternKeys = null,
                    sessionId = "event-session",
                    requestId = "req-123",
                )

            delegate.handleApprovalRequest(event)
            advanceUntilIdle()

            val lastMsg = uiState.value.messages.last()
            assertEquals(MessageRole.SYSTEM, lastMsg.role)
            assertEquals("**Approval Required**\ninspect working tree", lastMsg.content)
            assertEquals("req-123", lastMsg.approvalInfo?.requestId)
            assertFalse(uiState.value.isAgentTyping)

            assertEquals(listOf(WsMethods.APPROVAL_RECEIVED), sentMethods)
            assertEquals(JsonPrimitive("runtime-session-xyz"), sentParams.first()["session_id"])
            assertEquals(JsonPrimitive("req-123"), sentParams.first()["request_id"])
        }

    @Test
    fun respondToApproval_clearsButtonsImmediatelyAndSendsChoiceOnce() =
        testScope.runTest {
            runtimeId = "runtime-session-xyz"
            delegate.handleApprovalRequest(
                WsEvent.ApprovalRequest(
                    command = "ls",
                    description = null,
                    patternKeys = null,
                    sessionId = null,
                    requestId = "req-999",
                ),
            )
            advanceUntilIdle()
            sentMethods.clear()
            sentParams.clear()

            delegate.respondToApproval("approve")
            val messageBeforeIdle = uiState.value.messages.last()
            assertNull(messageBeforeIdle.approvalInfo)

            advanceUntilIdle()
            assertEquals(listOf(WsMethods.APPROVAL_RESPOND, WsMethods.APPROVAL_PENDING), sentMethods)
            assertEquals(JsonPrimitive("once"), sentParams[0]["choice"])
            assertEquals(JsonPrimitive("req-999"), sentParams[0]["request_id"])
            assertEquals(JsonPrimitive(false), sentParams[0]["all"])
            assertEquals(JsonPrimitive("runtime-session-xyz"), sentParams[0]["session_id"])
            assertEquals(JsonPrimitive("runtime-session-xyz"), sentParams[1]["session_id"])
        }

    @Test
    fun maybeSurfacePendingApproval_dedupesExistingOnScreenRequestId() {
        delegate.handleApprovalRequest(
            WsEvent.ApprovalRequest(
                command = "ls",
                description = null,
                patternKeys = null,
                sessionId = null,
                requestId = "dup-1",
            ),
        )
        val initialCount = uiState.value.messages.size

        delegate.maybeSurfacePendingApproval(
            mapOf("command" to "ls", "request_id" to "dup-1"),
            sessionId = "s1",
        )
        assertEquals(initialCount, uiState.value.messages.size)

        delegate.maybeSurfacePendingApproval(
            mapOf("command" to "pwd", "request_id" to "new-2"),
            sessionId = "s1",
        )
        assertEquals(initialCount + 1, uiState.value.messages.size)
        assertEquals(
            "new-2",
            uiState.value.messages
                .last()
                .approvalInfo
                ?.requestId,
        )
    }

    @Test
    fun handleApprovalPendingResult_dropsMissingRequestId_andDedupesAlreadyShown() {
        val initialCount = uiState.value.messages.size

        // No request_id -> dropped entirely
        delegate.handleApprovalPendingResult(
            mapOf("approvals" to listOf(mapOf("command" to "rm -rf /"))),
        )
        assertEquals(initialCount, uiState.value.messages.size)

        // Valid request_id -> surfaced
        delegate.handleApprovalPendingResult(
            mapOf("approvals" to listOf(mapOf("command" to "git pull", "request_id" to "r-valid"))),
        )
        assertEquals(initialCount + 1, uiState.value.messages.size)
        assertEquals(
            "r-valid",
            uiState.value.messages
                .last()
                .approvalInfo
                ?.requestId,
        )

        // Duplicate request_id -> ignored
        delegate.handleApprovalPendingResult(
            mapOf("approvals" to listOf(mapOf("command" to "git pull", "request_id" to "r-valid"))),
        )
        assertEquals(initialCount + 1, uiState.value.messages.size)
    }

    @Test
    fun handleApprovalRespondResult_appendsDesktopParitySystemMessage() {
        delegate.handleApprovalRespondResult(mapOf("resolved" to 1))
        assertEquals(listOf("Approval submitted"), systemMessages)
    }

    @Test
    fun cancelServerRequest_matchesServerRequestIdOnly() {
        delegate.handleApprovalRequest(
            WsEvent.ApprovalRequest(
                command = "rm -rf /tmp/x",
                description = "dangerous",
                patternKeys = null,
                sessionId = "session-1",
                requestId = "approval-1",
                serverRequestId = "srq-approval-1",
            ),
        )

        delegate.cancelServerRequest("srq-other")
        assertNotNull(
            uiState.value.messages
                .last()
                .approvalInfo,
        )

        delegate.cancelServerRequest("srq-approval-1")
        assertNull(
            uiState.value.messages
                .last()
                .approvalInfo,
        )
    }

    @Test
    fun liveServerApproval_upgradesMatchingPendingApprovalWithoutDuplicating() {
        delegate.handleApprovalRequest(
            WsEvent.ApprovalRequest(
                command = "git status",
                description = "inspect",
                patternKeys = null,
                sessionId = "session-1",
                requestId = "approval-business-id",
            ),
        )
        delegate.handleApprovalRequest(
            WsEvent.ApprovalRequest(
                command = "git status",
                description = "inspect",
                patternKeys = null,
                sessionId = "session-1",
                requestId = "approval-business-id",
                serverRequestId = "srq-approval",
            ),
        )

        assertEquals(1, uiState.value.messages.size)
        assertEquals(
            "srq-approval",
            uiState.value.messages
                .single()
                .approvalInfo
                ?.serverRequestId,
        )
    }

    @Test
    fun serverApproval_usesChoiceAndAllResultPayload() {
        val responses = mutableListOf<Pair<String, JsonElement>>()
        val serverDelegate =
            ChatApprovalsDelegate(
                scope = testScope,
                ioDispatcher = testDispatcher,
                uiState = uiState,
                runtimeSessionId = { "session-1" },
                rpc =
                    object : TypedRpcSender {
                        override fun <P> send(
                            method: RpcMethod<P, *>,
                            params: P,
                            onSent: ((String) -> Unit)?,
                        ): String = error("new approval must not use legacy response")
                    },
                trackRequest = { _, _ -> },
                addSystemMessage = { },
                respondToServerRequest = { id, result -> responses += id to result },
            )
        serverDelegate.handleApprovalRequest(
            WsEvent.ApprovalRequest(
                command = "rm -rf /tmp/x",
                description = "dangerous",
                patternKeys = null,
                sessionId = "session-1",
                requestId = "approval-1",
                serverRequestId = "srq-approval",
            ),
        )

        serverDelegate.respondToApproval("approve")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("srq-approval", responses.single().first)
        assertEquals(
            "once",
            responses
                .single()
                .second.jsonObject["choice"]
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "false",
            responses
                .single()
                .second.jsonObject["all"]
                ?.jsonPrimitive
                ?.content,
        )
    }
}
