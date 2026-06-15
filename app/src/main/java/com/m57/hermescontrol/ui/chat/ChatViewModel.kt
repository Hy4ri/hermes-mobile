package com.m57.hermescontrol.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentSessionId: String? = null,
    val sessions: List<SessionUi> = emptyList(),
    val isConnected: Boolean = false,
    val isAgentTyping: Boolean = false,
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    val isLoading: Boolean = false,
    val currentStreamingText: String = "",
    val errorMessage: String? = null,
    val clarifyRequest: ClarifyUi? = null,
    val showSessionPicker: Boolean = false,
)

data class SessionUi(
    val id: String,
    val title: String,
    val messageCount: Int = 0,
)

data class ClarifyUi(
    val text: String,
    val options: List<String>,
)

class ChatViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val wsClient = HermesWsClient
    private val pendingRequests = mutableMapOf<String, String>() // requestId -> method

    init {
        connectWebSocket()
    }

    private fun connectWebSocket() {
        val token = AuthManager.getToken() ?: return

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {
            wsClient.connect()
        }

        viewModelScope.launch {
            wsClient.events.collect { event ->
                handleWsEvent(event)
            }
        }
    }

    private fun handleWsEvent(event: WsEvent) {
        when (event) {
            is WsEvent.GatewayReady -> {
                _uiState.update { it.copy(isConnected = true, isLoading = false) }
                addSystemMessage("Connected to Hermes")
                loadSessions()
                // Create a new session if none exists
                if (_uiState.value.currentSessionId == null) {
                    createNewSession()
                }
            }

            is WsEvent.SessionInfo -> {
                // We may receive session info after creation
            }

            is WsEvent.MessageStart -> {
                _uiState.update {
                    it.copy(
                        isAgentTyping = true,
                        currentStreamingText = "",
                        isThinking = false,
                        thinkingText = "",
                    )
                }
            }

            is WsEvent.MessageToken -> {
                _uiState.update { state ->
                    val newText = state.currentStreamingText + event.token
                    // Update or create the streaming message
                    val messages = state.messages.toMutableList()
                    val streamingIdx =
                        messages.indexOfLast {
                            it.role == MessageRole.ASSISTANT && it.isStreaming
                        }
                    if (streamingIdx >= 0) {
                        messages[streamingIdx] = messages[streamingIdx].copy(content = newText)
                    } else {
                        messages.add(
                            ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = newText,
                                isStreaming = true,
                            ),
                        )
                    }
                    state.copy(
                        messages = messages,
                        currentStreamingText = newText,
                        isThinking = false,
                    )
                }
            }

            is WsEvent.ThinkingDelta -> {
                _uiState.update { state ->
                    state.copy(
                        isThinking = true,
                        thinkingText = state.thinkingText + event.token,
                    )
                }
            }

            is WsEvent.MessageComplete -> {
                _uiState.update { state ->
                    val messages = state.messages.toMutableList()
                    val streamingIdx =
                        messages.indexOfLast {
                            it.role == MessageRole.ASSISTANT && it.isStreaming
                        }
                    if (streamingIdx >= 0) {
                        messages[streamingIdx] =
                            messages[streamingIdx].copy(
                                content = event.text,
                                isStreaming = false,
                            )
                    } else {
                        messages.add(
                            ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = event.text,
                            ),
                        )
                    }
                    state.copy(
                        messages = messages,
                        isAgentTyping = false,
                        currentStreamingText = "",
                        isThinking = false,
                        thinkingText = "",
                    )
                }
            }

            is WsEvent.MessageDone -> {
                _uiState.update {
                    it.copy(isAgentTyping = false, isThinking = false, thinkingText = "")
                }
            }

            is WsEvent.ToolStart -> {
                val toolMessage =
                    ChatMessage(
                        role = MessageRole.TOOL,
                        content = event.data.toString(),
                        toolName = event.name,
                        toolStatus = ToolStatus.RUNNING,
                    )
                _uiState.update { state ->
                    state.copy(messages = state.messages + toolMessage)
                }
            }

            is WsEvent.ToolComplete -> {
                _uiState.update { state ->
                    val messages = state.messages.toMutableList()
                    val toolIdx =
                        messages.indexOfLast {
                            it.role == MessageRole.TOOL &&
                                it.toolName == event.name &&
                                it.toolStatus == ToolStatus.RUNNING
                        }
                    if (toolIdx >= 0) {
                        messages[toolIdx] =
                            messages[toolIdx].copy(
                                toolStatus = ToolStatus.COMPLETED,
                                content = event.data.toString(),
                            )
                    }
                    state.copy(messages = messages)
                }
            }

            is WsEvent.ClarifyRequest -> {
                _uiState.update {
                    it.copy(
                        clarifyRequest =
                            ClarifyUi(
                                text = event.text.orEmpty(),
                                options = event.options.orEmpty(),
                            ),
                    )
                }
            }

            is WsEvent.StatusUpdate -> {
                // Can be used for general status
            }

            is WsEvent.SessionUpdated -> {
                // Refresh sessions list
                loadSessions()
            }

            is WsEvent.RpcResult -> {
                handleRpcResult(event.id, event.result)
            }

            is WsEvent.RpcError -> {
                handleRpcError(event.id, event.error)
            }

            is WsEvent.Unknown -> {
                // Handle or ignore unknown/unhandled events
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleRpcResult(
        id: String,
        result: Any?,
    ) {
        val method = pendingRequests.remove(id) ?: return

        when (method) {
            WsMethods.SESSION_CREATE -> {
                val resultMap = result as? Map<String, Any?> ?: return
                val sessionId = resultMap["session_id"] as? String ?: return
                _uiState.update {
                    it.copy(
                        currentSessionId = sessionId,
                        isLoading = false,
                        messages = it.messages,
                    )
                }
                addSystemMessage("Session created")
                loadSessions()
            }

            WsMethods.SESSION_LIST -> {
                val resultMap = result as? Map<String, Any?> ?: return
                val sessionsList = resultMap["sessions"] as? List<Map<String, Any?>> ?: return
                val sessions =
                    sessionsList.map { s ->
                        SessionUi(
                            id = s["id"] as? String ?: "",
                            title = s["title"] as? String ?: "Untitled",
                            messageCount = (s["message_count"] as? Double)?.toInt() ?: 0,
                        )
                    }
                _uiState.update { it.copy(sessions = sessions) }
            }

            WsMethods.SESSION_RESUME -> {
                _uiState.update { it.copy(isLoading = false) }
                addSystemMessage("Session resumed")
            }

            WsMethods.SESSION_INTERRUPT -> {
                _uiState.update {
                    it.copy(isAgentTyping = false, isThinking = false)
                }
                addSystemMessage("Session interrupted")
            }
        }
    }

    private fun handleRpcError(
        id: String,
        error: Any?,
    ) {
        val method = pendingRequests.remove(id)
        val errorMsg =
            when (error) {
                is Map<*, *> -> error["message"] as? String ?: error.toString()
                else -> error.toString()
            }
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = "Error${if (method != null) " ($method)" else ""}: $errorMsg",
            )
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val sessionId = _uiState.value.currentSessionId ?: return

        val trimmed = text.trim()
        if (trimmed.startsWith("/", ignoreCase = true)) {
            handleSlashCommand(trimmed)
            return
        }

        // Add user message to UI
        val userMessage =
            ChatMessage(
                role = MessageRole.USER,
                content = text,
            )
        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isAgentTyping = true,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val requestId = wsClient.sendMessage(sessionId, text)
            pendingRequests[requestId] = WsMethods.PROMPT_SUBMIT
        }
    }

    private fun handleSlashCommand(command: String) {
        val userMsg = ChatMessage(role = MessageRole.USER, content = command)
        _uiState.update { it.copy(messages = it.messages + userMsg) }

        val parts = command.split(" ")
        val cmd = parts[0].lowercase()

        when (cmd) {
            "/stop", "/interrupt" -> {
                interruptSession()
            }

            "/new" -> {
                createNewSession()
            }

            "/help" -> {
                val helpText =
                    """
                    **Available Commands:**
                    • `/help` - Show this help menu
                    • `/status` - Check gateway and platform status
                    • `/sessions` - List all chat sessions
                    • `/stats` or `/system` - Check system resource usage
                    • `/new` - Create a new chat session
                    • `/stop` or `/interrupt` - Interrupt the active run
                    """.trimIndent()
                addAssistantMessage(helpText)
            }

            "/status" -> {
                viewModelScope.launch {
                    try {
                        val response = withContext(Dispatchers.IO) { ApiClient.hermesApi.getStatus() }
                        if (response.isSuccessful && response.body() != null) {
                            val body = response.body()!!
                            val platformsStr =
                                body.gateway_platforms?.entries?.joinToString("\n") { (k, v) ->
                                    "  • **$k**: ${v.state ?: "Unknown"}${if (v.error_code != null) " (Error: ${v.error_code})" else ""}"
                                } ?: "No active platforms"

                            val statusText =
                                """
                                **Hermes Gateway Status:**
                                • **Version:** ${body.version ?: "Unknown"}
                                • **Gateway Running:** ${body.gateway_running ?: false}
                                • **Active Sessions:** ${body.active_sessions ?: 0}
                                • **Auth Required:** ${body.auth_required ?: false}
                                
                                **Platforms:**
                                $platformsStr
                                """.trimIndent()
                            addAssistantMessage(statusText)
                        } else {
                            addAssistantMessage("Failed to retrieve status: HTTP ${response.code()}")
                        }
                    } catch (e: Exception) {
                        addAssistantMessage("Failed to retrieve status: ${e.message}")
                    }
                }
            }

            "/sessions" -> {
                viewModelScope.launch {
                    try {
                        val response = withContext(Dispatchers.IO) { ApiClient.hermesApi.getSessions() }
                        if (response.isSuccessful && response.body() != null) {
                            val body = response.body()!!
                            val sessionsStr =
                                body.sessions.joinToString("\n") { s ->
                                    "• **${s.title ?: "Untitled"}** (ID: `${s.id}`, Messages: ${s.message_count ?: 0})"
                                }
                            addAssistantMessage("**Sessions List:**\n$sessionsStr")
                        } else {
                            addAssistantMessage("Failed to list sessions: HTTP ${response.code()}")
                        }
                    } catch (e: Exception) {
                        addAssistantMessage("Failed to list sessions: ${e.message}")
                    }
                }
            }

            "/stats", "/system" -> {
                viewModelScope.launch {
                    try {
                        val response = withContext(Dispatchers.IO) { ApiClient.hermesApi.getSystemStats() }
                        if (response.isSuccessful && response.body() != null) {
                            val body = response.body()!!
                            val cpuPct = body.cpuPercent?.let { String.format("%.1f%%", it) } ?: "N/A"
                            val memPct = body.memoryPercent?.let { String.format("%.1f%%", it) } ?: "N/A"
                            val uptimeVal = body.uptime ?: "N/A"
                            val statsText =
                                """
                                **System Resource Stats:**
                                • **CPU Usage:** $cpuPct
                                • **Memory Usage:** $memPct
                                • **Uptime:** $uptimeVal
                                """.trimIndent()
                            addAssistantMessage(statsText)
                        } else {
                            addAssistantMessage("Failed to retrieve system stats: HTTP ${response.code()}")
                        }
                    } catch (e: Exception) {
                        addAssistantMessage("Failed to retrieve system stats: ${e.message}")
                    }
                }
            }

            else -> {
                addAssistantMessage("Unknown command: `$cmd`. Type `/help` to view a list of available commands.")
            }
        }
    }

    private fun addAssistantMessage(text: String) {
        val msg = ChatMessage(role = MessageRole.ASSISTANT, content = text)
        _uiState.update { it.copy(messages = it.messages + msg) }
    }

    fun interruptSession() {
        val sessionId = _uiState.value.currentSessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val requestId =
                wsClient.send(
                    WsMethods.SESSION_INTERRUPT,
                    mapOf("session_id" to sessionId),
                )
            pendingRequests[requestId] = WsMethods.SESSION_INTERRUPT
        }
    }

    fun createNewSession() {
        _uiState.update { it.copy(isLoading = true, messages = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            val requestId = wsClient.send(WsMethods.SESSION_CREATE)
            pendingRequests[requestId] = WsMethods.SESSION_CREATE
        }
    }

    fun loadSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            val requestId = wsClient.send(WsMethods.SESSION_LIST)
            pendingRequests[requestId] = WsMethods.SESSION_LIST
        }
    }

    fun switchSession(sessionId: String) {
        if (sessionId == _uiState.value.currentSessionId) return
        _uiState.update {
            it.copy(
                isLoading = true,
                messages = emptyList(),
                currentSessionId = sessionId,
                showSessionPicker = false,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val requestId =
                wsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to sessionId),
                )
            pendingRequests[requestId] = WsMethods.SESSION_RESUME
        }
        loadSessionMessages(sessionId)
    }

    private fun loadSessionMessages(sessionId: String) {
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getSessionMessages(sessionId)
                    }
                if (response.isSuccessful) {
                    val messagesList = response.body()?.messages.orEmpty()
                    val chatMessages =
                        messagesList.map { msg ->
                            val role =
                                when (msg.role?.lowercase()) {
                                    "user" -> MessageRole.USER
                                    "system" -> MessageRole.SYSTEM
                                    "tool" -> MessageRole.TOOL
                                    else -> MessageRole.ASSISTANT
                                }
                            ChatMessage(
                                role = role,
                                content = msg.content.orEmpty(),
                                isStreaming = false,
                            )
                        }
                    _uiState.update { it.copy(messages = chatMessages) }
                } else {
                    _uiState.update { it.copy(errorMessage = "Failed to load messages: HTTP ${response.code()}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to load messages: ${e.message}") }
            }
        }
    }

    fun toggleSessionPicker() {
        _uiState.update { it.copy(showSessionPicker = !it.showSessionPicker) }
    }

    fun dismissClarify() {
        _uiState.update { it.copy(clarifyRequest = null) }
    }

    fun respondToClarify(option: String) {
        _uiState.update { it.copy(clarifyRequest = null) }
        sendMessage(option)
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun reconnect() {
        _uiState.update {
            it.copy(
                isConnected = false,
                isLoading = true,
                errorMessage = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.disconnect()
        }
        viewModelScope.launch {
            delay(500)
            connectWebSocket()
        }
    }

    private fun addSystemMessage(text: String) {
        val msg = ChatMessage(role = MessageRole.SYSTEM, content = text)
        _uiState.update { it.copy(messages = it.messages + msg) }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.disconnect()
        }
    }
}
