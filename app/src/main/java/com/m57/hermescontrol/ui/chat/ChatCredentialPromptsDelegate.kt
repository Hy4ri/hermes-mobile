package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
) {
    /**
     * The agent needs the user's sudo password. Surface a secure dialog and
     * reply via sudo.respond.
     */
    fun handleSudoRequest(event: WsEvent.SudoRequest) {
        uiState.update {
            it.copy(
                sudoPrompt = SudoPromptUi(event.requestId, event.sessionId),
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
            if (event.requestId != null && current.requestId != null &&
                event.requestId != current.requestId
            ) {
                return@update state
            }
            state.copy(sudoPrompt = null)
        }
    }

    /**
     * The agent needs a secret value (token/password). Surface a secure dialog
     * and reply via secret.respond.
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
            if (event.requestId != null && current.requestId != null &&
                event.requestId != current.requestId
            ) {
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
            wsSend(
                WsMethods.SUDO_RESPOND,
                params,
            ) { id -> trackRequest(id, WsMethods.SUDO_RESPOND) }
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
            wsSend(
                WsMethods.SECRET_RESPOND,
                params,
            ) { id -> trackRequest(id, WsMethods.SECRET_RESPOND) }
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
            wsSend(
                WsMethods.SUDO_RESPOND,
                params,
            ) { id -> trackRequest(id, WsMethods.SUDO_RESPOND) }
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
            wsSend(
                WsMethods.SECRET_RESPOND,
                params,
            ) { id -> trackRequest(id, WsMethods.SECRET_RESPOND) }
        }
    }
}
