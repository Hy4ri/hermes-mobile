package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatCredentialPromptsDelegateTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val uiState = MutableStateFlow(ChatUiState(currentSessionId = "sess-1"))

    private val sentMethods = mutableListOf<String>()
    private val sentParams = mutableListOf<Map<String, Any>>()

    private lateinit var delegate: ChatCredentialPromptsDelegate

    @Before
    fun setUp() {
        sentMethods.clear()
        sentParams.clear()
        uiState.value = ChatUiState(currentSessionId = "sess-1")
        delegate =
            ChatCredentialPromptsDelegate(
                scope = testScope,
                ioDispatcher = testDispatcher,
                uiState = uiState,
                wsSend = { method, params, _ ->
                    sentMethods.add(method)
                    sentParams.add(params)
                },
                trackRequest = { _, _ -> },
            )
    }

    @Test
    fun testVaultUnlock_request_and_respond() {
        delegate.handleVaultUnlockRequest(
            WsEvent.VaultUnlockRequest(
                requestId = "req-1",
                sessionId = "sess-1",
                backend = "onepassword",
                displayName = "1Password",
            ),
        )

        assertNotNull(uiState.value.vaultUnlockPrompt)
        assertEquals("req-1", uiState.value.vaultUnlockPrompt?.requestId)
        assertEquals("1Password", uiState.value.vaultUnlockPrompt?.displayName)

        delegate.respondToVaultUnlock("master-pass")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(uiState.value.vaultUnlockPrompt)
        assertEquals(listOf(WsMethods.VAULT_UNLOCK_RESPOND), sentMethods)
        assertEquals("master-pass", sentParams.first()["password"])
        assertEquals("req-1", sentParams.first()["request_id"])
    }

    @Test
    fun testVaultUnlock_dismiss_sendsEmptyPassword() {
        delegate.handleVaultUnlockRequest(
            WsEvent.VaultUnlockRequest(
                requestId = "req-1",
                sessionId = "sess-1",
            ),
        )

        delegate.dismissVaultUnlock()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(uiState.value.vaultUnlockPrompt)
        assertEquals(listOf(WsMethods.VAULT_UNLOCK_RESPOND), sentMethods)
        assertEquals("", sentParams.first()["password"])
    }

    @Test
    fun testVaultSaveLogin_request_respond_and_expire() {
        delegate.handleVaultSaveLoginRequest(
            WsEvent.VaultSaveLoginRequest(
                requestId = "req-save",
                sessionId = "sess-1",
                origin = "https://github.com",
                site = "GitHub",
            ),
        )

        assertNotNull(uiState.value.vaultSaveLoginPrompt)
        assertEquals("GitHub", uiState.value.vaultSaveLoginPrompt?.site)

        delegate.respondToVaultSaveLogin("user@example.com", "secret123")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(uiState.value.vaultSaveLoginPrompt)
        assertEquals(listOf(WsMethods.VAULT_SAVE_LOGIN_RESPOND), sentMethods)
        val loginJsonStr = sentParams.first()["login"] as String
        val json = Json.parseToJsonElement(loginJsonStr).jsonObject
        assertEquals("user@example.com", json["identifier"]?.jsonPrimitive?.content)
        assertEquals("secret123", json["password"]?.jsonPrimitive?.content)
    }

    @Test
    fun testVaultSaveLogin_dismiss_sendsEmptyLogin() {
        delegate.handleVaultSaveLoginRequest(
            WsEvent.VaultSaveLoginRequest(
                requestId = "req-save",
                sessionId = "sess-1",
            ),
        )

        delegate.dismissVaultSaveLogin()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(uiState.value.vaultSaveLoginPrompt)
        assertEquals(listOf(WsMethods.VAULT_SAVE_LOGIN_RESPOND), sentMethods)
        assertEquals("", sentParams.first()["login"])
    }

    @Test
    fun testVaultCode_request_respond_and_dismiss() {
        delegate.handleVaultCodeRequest(
            WsEvent.VaultCodeRequest(
                requestId = "req-code",
                sessionId = "sess-1",
                site = "GitHub",
                hint = "SMS to phone",
            ),
        )

        assertNotNull(uiState.value.vaultCodePrompt)
        assertEquals("GitHub", uiState.value.vaultCodePrompt?.site)

        delegate.respondToVaultCode("654321")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(uiState.value.vaultCodePrompt)
        assertEquals(listOf(WsMethods.VAULT_CODE_RESPOND), sentMethods)
        assertEquals("654321", sentParams.first()["code"])
    }

    @Test
    fun testVaultCode_dismiss_sendsEmptyCode() {
        delegate.handleVaultCodeRequest(
            WsEvent.VaultCodeRequest(
                requestId = "req-code",
                sessionId = "sess-1",
            ),
        )

        delegate.dismissVaultCode()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(uiState.value.vaultCodePrompt)
        assertEquals(listOf(WsMethods.VAULT_CODE_RESPOND), sentMethods)
        assertEquals("", sentParams.first()["code"])
    }
}
