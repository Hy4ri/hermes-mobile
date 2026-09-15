package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Owns sudo and secret prompt state, timeouts, dismissal, and response RPCs.
 *
 * Extracted behavior-preservingly from [ChatViewModel] (issue #524).
 */
class ChatCredentialPromptsDelegate(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val wsSend: (method: String, params: Map<String, Any>, onSent: ((String) -> Unit)?) -> Unit,
    private val trackRequest: (id: String, method: String) -> Unit,
    private val respondToServerRequest: ((String, JsonElement) -> Unit)? = null,
) {
    private fun sendResponse(
        serverRequestId: String?,
        result: JsonElement,
        legacyMethod: String,
        legacyParams: Map<String, Any>,
    ) {
        if (serverRequestId != null && respondToServerRequest != null) {
            respondToServerRequest.invoke(serverRequestId, result)
        } else {
            wsSend(legacyMethod, legacyParams) { id -> trackRequest(id, legacyMethod) }
        }
    }

    /**
     * New gateways use a same-ID JSON-RPC response; legacy notifications fall
     * back to sudo.respond.
     */
    fun handleSudoRequest(event: WsEvent.SudoRequest) {
        uiState.update {
            it.copy(
                sudoPrompt = SudoPromptUi(event.requestId, event.sessionId, event.serverRequestId),
                isAgentTyping = false,
            )
        }
    }

    /**
     * Backend sudo timeout (120s) — clear only the matching dialog so a late
     * expire for an old prompt never kills the current one.
     */
    fun handleSudoExpire(event: WsEvent.SudoExpire) {
        uiState.update { state ->
            val current = state.sudoPrompt ?: return@update state
            if (event.serverRequestId != null && current.serverRequestId != event.serverRequestId) {
                return@update state
            }
            if (event.serverRequestId == null && event.requestId != null && current.requestId != event.requestId) {
                return@update state
            }
            state.copy(sudoPrompt = null)
        }
    }

    /**
     * New gateways use a same-ID JSON-RPC response; legacy notifications fall
     * back to secret.respond.
     */
    fun handleSecretRequest(event: WsEvent.SecretRequest) {
        uiState.update {
            it.copy(
                secretPrompt =
                    SecretPromptUi(
                        event.requestId,
                        event.sessionId,
                        event.envVar,
                        event.prompt,
                        event.serverRequestId,
                    ),
                isAgentTyping = false,
            )
        }
    }

    /**
     * Backend secret timeout — match-only clear like [handleSudoExpire].
     */
    fun handleSecretExpire(event: WsEvent.SecretExpire) {
        uiState.update { state ->
            val current = state.secretPrompt ?: return@update state
            if (event.serverRequestId != null && current.serverRequestId != event.serverRequestId) {
                return@update state
            }
            if (event.serverRequestId == null && event.requestId != null && current.requestId != event.requestId) {
                return@update state
            }
            state.copy(secretPrompt = null)
        }
    }

    /**
     * Cancel → send empty password (desktop parity). Backend treats empty sudo as
     * failed sudo (no command runs), unblocking the turn instantly.
     */
    fun dismissSudo() {
        val prompt = uiState.value.sudoPrompt ?: return
        val sessionId = prompt.sessionId ?: uiState.value.currentSessionId
        uiState.update { it.copy(sudoPrompt = null) }
        if (sessionId == null) return
        scope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "password" to "",
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            sendResponse(
                prompt.serverRequestId,
                buildJsonObject { put("value", "") },
                WsMethods.SUDO_RESPOND,
                params,
            )
        }
    }

    /**
     * Cancel → send empty value (desktop parity). Backend secret_cb returns
     * skipped=True on empty, unblocking the turn instantly.
     */
    fun dismissSecret() {
        val prompt = uiState.value.secretPrompt ?: return
        val sessionId = prompt.sessionId ?: uiState.value.currentSessionId
        uiState.update { it.copy(secretPrompt = null) }
        if (sessionId == null) return
        scope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "value" to "",
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            sendResponse(
                prompt.serverRequestId,
                buildJsonObject { put("value", "") },
                WsMethods.SECRET_RESPOND,
                params,
            )
        }
    }

    /**
     * Send the user's sudo password back to the gateway. Clear prompt immediately,
     * then fire the RPC.
     */
    fun respondToSudo(password: String) {
        val prompt = uiState.value.sudoPrompt ?: return
        val sessionId = prompt.sessionId ?: uiState.value.currentSessionId ?: return
        if (password.isBlank()) return

        uiState.update { it.copy(sudoPrompt = null) }

        scope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "password" to password,
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            sendResponse(
                prompt.serverRequestId,
                buildJsonObject { put("value", password) },
                WsMethods.SUDO_RESPOND,
                params,
            )
        }
    }

    /**
     * Send the user's secret value back to the gateway.
     */
    fun respondToSecret(value: String) {
        val prompt = uiState.value.secretPrompt ?: return
        val sessionId = prompt.sessionId ?: uiState.value.currentSessionId ?: return
        if (value.isBlank()) return

        uiState.update { it.copy(secretPrompt = null) }

        scope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "value" to value,
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            sendResponse(
                prompt.serverRequestId,
                buildJsonObject { put("value", value) },
                WsMethods.SECRET_RESPOND,
                params,
            )
        }
    }

    // ── Vault prompts (issue #1090) ───────────────────────────────────────

    /**
     * The agent needs to unlock a password manager (1Password, Bitwarden).
     * New gateways use a same-ID JSON-RPC response; legacy notifications fall
     * back to vault.unlock.respond.
     */
    fun handleVaultUnlockRequest(event: WsEvent.VaultUnlockRequest) {
        uiState.update {
            it.copy(
                vaultUnlockPrompt =
                    VaultUnlockPromptUi(
                        requestId = event.requestId,
                        sessionId = event.sessionId,
                        backend = event.backend,
                        displayName = event.displayName,
                        serverRequestId = event.serverRequestId,
                    ),
                isAgentTyping = false,
            )
        }
    }

    fun handleVaultUnlockExpire(event: WsEvent.VaultUnlockExpire) {
        uiState.update { state ->
            val current = state.vaultUnlockPrompt ?: return@update state
            if (event.serverRequestId != null && current.serverRequestId != event.serverRequestId) {
                return@update state
            }
            if (event.serverRequestId == null && event.requestId != null && current.requestId != event.requestId) {
                return@update state
            }
            state.copy(vaultUnlockPrompt = null)
        }
    }

    /**
     * Cancel/dismiss → send empty password (keeps manager locked, unblocks turn).
     */
    fun dismissVaultUnlock() {
        val prompt = uiState.value.vaultUnlockPrompt ?: return
        val sessionId = prompt.sessionId ?: uiState.value.currentSessionId
        uiState.update { it.copy(vaultUnlockPrompt = null) }
        if (sessionId == null) return
        scope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "password" to "",
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            sendResponse(
                prompt.serverRequestId,
                buildJsonObject { put("value", "") },
                WsMethods.VAULT_UNLOCK_RESPOND,
                params,
            )
        }
    }

    fun respondToVaultUnlock(password: String) {
        val prompt = uiState.value.vaultUnlockPrompt ?: return
        val sessionId = prompt.sessionId ?: uiState.value.currentSessionId ?: return
        if (password.isBlank()) return

        uiState.update { it.copy(vaultUnlockPrompt = null) }

        scope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "password" to password,
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            sendResponse(
                prompt.serverRequestId,
                buildJsonObject { put("value", password) },
                WsMethods.VAULT_UNLOCK_RESPOND,
                params,
            )
        }
    }

    /**
     * The agent captured credentials on a login page and asks whether to store them in the vault.
     */
    fun handleVaultSaveLoginRequest(event: WsEvent.VaultSaveLoginRequest) {
        uiState.update {
            it.copy(
                vaultSaveLoginPrompt =
                    VaultSaveLoginPromptUi(
                        requestId = event.requestId,
                        sessionId = event.sessionId,
                        origin = event.origin,
                        site = event.site,
                        serverRequestId = event.serverRequestId,
                    ),
                isAgentTyping = false,
            )
        }
    }

    fun handleVaultSaveLoginExpire(event: WsEvent.VaultSaveLoginExpire) {
        uiState.update { state ->
            val current = state.vaultSaveLoginPrompt ?: return@update state
            if (event.serverRequestId != null && current.serverRequestId != event.serverRequestId) {
                return@update state
            }
            if (event.serverRequestId == null && event.requestId != null && current.requestId != event.requestId) {
                return@update state
            }
            state.copy(vaultSaveLoginPrompt = null)
        }
    }

    /**
     * Decline saving → send empty login (backend treats empty login as declined).
     */
    fun dismissVaultSaveLogin() {
        val prompt = uiState.value.vaultSaveLoginPrompt ?: return
        val sessionId = prompt.sessionId ?: uiState.value.currentSessionId
        uiState.update { it.copy(vaultSaveLoginPrompt = null) }
        if (sessionId == null) return
        scope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "login" to "",
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            sendResponse(
                prompt.serverRequestId,
                buildJsonObject { put("value", "") },
                WsMethods.VAULT_SAVE_LOGIN_RESPOND,
                params,
            )
        }
    }

    fun respondToVaultSaveLogin(
        identifier: String,
        password: String,
    ) {
        val prompt = uiState.value.vaultSaveLoginPrompt ?: return
        val sessionId = prompt.sessionId ?: uiState.value.currentSessionId ?: return
        if (identifier.isBlank() || password.isBlank()) return

        uiState.update { it.copy(vaultSaveLoginPrompt = null) }

        scope.launch(ioDispatcher) {
            val loginJson =
                buildJsonObject {
                    put("identifier", identifier)
                    put("password", password)
                }.toString()
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "login" to loginJson,
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            sendResponse(
                prompt.serverRequestId,
                buildJsonObject { put("value", loginJson) },
                WsMethods.VAULT_SAVE_LOGIN_RESPOND,
                params,
            )
        }
    }

    /**
     * The site requested a 2FA/MFA verification code.
     */
    fun handleVaultCodeRequest(event: WsEvent.VaultCodeRequest) {
        uiState.update {
            it.copy(
                vaultCodePrompt =
                    VaultCodePromptUi(
                        requestId = event.requestId,
                        sessionId = event.sessionId,
                        site = event.site,
                        hint = event.hint,
                        serverRequestId = event.serverRequestId,
                    ),
                isAgentTyping = false,
            )
        }
    }

    fun handleVaultCodeExpire(event: WsEvent.VaultCodeExpire) {
        uiState.update { state ->
            val current = state.vaultCodePrompt ?: return@update state
            if (event.serverRequestId != null && current.serverRequestId != event.serverRequestId) {
                return@update state
            }
            if (event.serverRequestId == null && event.requestId != null && current.requestId != event.requestId) {
                return@update state
            }
            state.copy(vaultCodePrompt = null)
        }
    }

    /**
     * Skip entering code → send empty code.
     */
    fun dismissVaultCode() {
        val prompt = uiState.value.vaultCodePrompt ?: return
        val sessionId = prompt.sessionId ?: uiState.value.currentSessionId
        uiState.update { it.copy(vaultCodePrompt = null) }
        if (sessionId == null) return
        scope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "code" to "",
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            sendResponse(
                prompt.serverRequestId,
                buildJsonObject { put("value", "") },
                WsMethods.VAULT_CODE_RESPOND,
                params,
            )
        }
    }

    fun respondToVaultCode(code: String) {
        val prompt = uiState.value.vaultCodePrompt ?: return
        val sessionId = prompt.sessionId ?: uiState.value.currentSessionId ?: return
        if (code.isBlank()) return

        uiState.update { it.copy(vaultCodePrompt = null) }

        scope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "code" to code,
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            sendResponse(
                prompt.serverRequestId,
                buildJsonObject { put("value", code) },
                WsMethods.VAULT_CODE_RESPOND,
                params,
            )
        }
    }
}
