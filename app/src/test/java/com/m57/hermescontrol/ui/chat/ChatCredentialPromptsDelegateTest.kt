package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatCredentialPromptsDelegateTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val uiState = MutableStateFlow(ChatUiState(currentSessionId = "sess-1"))
    private val sentMethods = mutableListOf<String>()
    private val sentParams = mutableListOf<Map<String, Any>>()
    private val trackedRequests = mutableListOf<Pair<String, String>>()

    private val delegate =
        ChatCredentialPromptsDelegate(
            scope = testScope,
            ioDispatcher = testDispatcher,
            uiState = uiState,
            wsSend = { method, params, onSent ->
                sentMethods.add(method)
                sentParams.add(params)
                onSent?.invoke("req-${sentMethods.size}")
            },
            trackRequest = { id, method -> trackedRequests.add(id to method) },
        )

    @Test
    fun handleSudoRequest_andRespond_clearsPromptAndSendsRpc() =
        testScope.runTest {
            delegate.handleSudoRequest(WsEvent.SudoRequest(requestId = "r-sudo", sessionId = "s-1"))
            assertEquals("r-sudo", uiState.value.sudoPrompt?.requestId)
            assertFalse(uiState.value.isAgentTyping)

            delegate.respondToSudo("secret-pass")
            assertNull(uiState.value.sudoPrompt)

            advanceUntilIdle()
            assertEquals(listOf(WsMethods.SUDO_RESPOND), sentMethods)
            assertEquals("secret-pass", sentParams.first()["password"])
            assertEquals("r-sudo", sentParams.first()["request_id"])
            assertEquals("s-1", sentParams.first()["session_id"])
        }

    @Test
    fun handleSecretRequest_andDismiss_sendsEmptyValue() =
        testScope.runTest {
            delegate.handleSecretRequest(
                WsEvent.SecretRequest(
                    requestId = "r-sec",
                    sessionId = "s-1",
                    envVar = "OPENAI_API_KEY",
                    prompt = "Enter key",
                ),
            )
            assertEquals("OPENAI_API_KEY", uiState.value.secretPrompt?.envVar)

            delegate.dismissSecret()
            assertNull(uiState.value.secretPrompt)

            advanceUntilIdle()
            assertEquals(listOf(WsMethods.SECRET_RESPOND), sentMethods)
            assertEquals("", sentParams.first()["value"])
            assertEquals("r-sec", sentParams.first()["request_id"])
        }

    @Test
    fun expireHandlers_ignoreMismatchedRequestId() {
        delegate.handleSudoRequest(WsEvent.SudoRequest(requestId = "keep-me", sessionId = "s-1"))
        delegate.handleSudoExpire(WsEvent.SudoExpire(requestId = "other-req", sessionId = "s-1"))
        assertNotNull(uiState.value.sudoPrompt)

        delegate.handleSudoExpire(WsEvent.SudoExpire(requestId = "keep-me", sessionId = "s-1"))
        assertNull(uiState.value.sudoPrompt)
    }
}
