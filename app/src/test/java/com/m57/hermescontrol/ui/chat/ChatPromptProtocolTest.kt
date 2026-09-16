package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPromptProtocolTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun clarifyServerResponse_mergesLockedAnswers_withoutEmptyOverwrite() {
        val scope = TestScope(dispatcher)
        val uiState =
            MutableStateFlow(
                ChatUiState(
                    currentSessionId = "session-1",
                    clarifyRequest =
                        ClarifyUi(
                            text = "",
                            questions =
                                listOf(
                                    ClarifyQuestionUi(qid = "q0", question = "First?"),
                                    ClarifyQuestionUi(qid = "q1", question = "Second?"),
                                ),
                            serverRequestId = "srq-clarify",
                            lockedAnswers = mapOf("q0" to "yes"),
                        ),
                ),
            )
        var responseId: String? = null
        var response: JsonElement? = null

        val delegate =
            ChatClarifyDelegate(
                uiState = uiState,
                scope = scope,
                ioDispatcher = dispatcher,
                persistMessage = { _, _ -> },
                respondToServerRequest = { id, result ->
                    responseId = id
                    response = result
                },
            )

        delegate.respondToClarifyBatch(mapOf("q0" to "", "q1" to "new answer"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("srq-clarify", responseId)
        val answers = response?.jsonObject?.get("answers")?.jsonObject
        assertNotNull(answers)
        assertEquals("yes", answers?.get("q0")?.jsonPrimitive?.content)
        assertEquals("new answer", answers?.get("q1")?.jsonPrimitive?.content)
    }

    @Test
    fun credentialPrompt_serverRequestId_controls_response_and_cancellation() {
        val scope = TestScope(dispatcher)
        val uiState = MutableStateFlow(ChatUiState(currentSessionId = "session-1"))
        val sentMethods = mutableListOf<String>()
        var responseId: String? = null
        var response: JsonElement? = null
        val delegate =
            ChatCredentialPromptsDelegate(
                scope = scope,
                ioDispatcher = dispatcher,
                uiState = uiState,
                wsSend = { method, _, _ -> sentMethods += method },
                trackRequest = { _, _ -> },
                respondToServerRequest = { id, result ->
                    responseId = id
                    response = result
                },
            )

        delegate.handleSudoRequest(
            WsEvent.SudoRequest(
                requestId = "legacy-sudo-id",
                sessionId = "session-1",
                serverRequestId = "srq-sudo",
            ),
        )
        delegate.handleSudoExpire(WsEvent.SudoExpire(null, "session-1", serverRequestId = "srq-other"))
        assertNotNull(uiState.value.sudoPrompt)

        delegate.respondToSudo("password")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("srq-sudo", responseId)
        assertEquals(
            "password",
            response
                ?.jsonObject
                ?.get("value")
                ?.jsonPrimitive
                ?.content,
        )
        assertNull(uiState.value.sudoPrompt)
        assertEquals(emptyList<String>(), sentMethods)

        delegate.handleSudoRequest(WsEvent.SudoRequest("legacy-sudo-2", "session-1"))
        delegate.respondToSudo("legacy-password")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(WsMethods.SUDO_RESPOND), sentMethods)
    }

    @Test
    fun secretAndVaultServerRequests_useValueResultPayloads() {
        val scope = TestScope(dispatcher)
        val uiState = MutableStateFlow(ChatUiState(currentSessionId = "session-1"))
        val responses = mutableListOf<Pair<String, JsonElement>>()
        val delegate =
            ChatCredentialPromptsDelegate(
                scope = scope,
                ioDispatcher = dispatcher,
                uiState = uiState,
                wsSend = { _, _, _ -> error("new prompt must not use legacy response") },
                trackRequest = { _, _ -> },
                respondToServerRequest = { id, result -> responses += id to result },
            )

        delegate.handleSecretRequest(WsEvent.SecretRequest(null, "session-1", serverRequestId = "srq-secret"))
        delegate.respondToSecret("secret-value")
        delegate.handleVaultCodeRequest(WsEvent.VaultCodeRequest(null, "session-1", serverRequestId = "srq-code"))
        delegate.respondToVaultCode("123456")
        delegate.handleVaultUnlockRequest(
            WsEvent.VaultUnlockRequest(null, "session-1", serverRequestId = "srq-unlock"),
        )
        delegate.respondToVaultUnlock("vault-password")
        delegate.handleVaultSaveLoginRequest(
            WsEvent.VaultSaveLoginRequest(null, "session-1", serverRequestId = "srq-save"),
        )
        delegate.respondToVaultSaveLogin("alice", "login-password")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("srq-secret", "srq-code", "srq-unlock", "srq-save"), responses.map { it.first })
        assertEquals(
            "secret-value",
            responses[0]
                .second.jsonObject["value"]
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "123456",
            responses[1]
                .second.jsonObject["value"]
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "vault-password",
            responses[2]
                .second.jsonObject["value"]
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "{\"identifier\":\"alice\",\"password\":\"login-password\"}",
            responses[3]
                .second.jsonObject["value"]
                ?.jsonPrimitive
                ?.content,
        )
    }
}
