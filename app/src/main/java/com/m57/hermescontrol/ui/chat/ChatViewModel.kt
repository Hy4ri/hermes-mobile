package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.local.toEntity
import com.m57.hermescontrol.data.local.toUiModel
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ChatViewModel"

/**
 * Pending request timeout — if the server doesn't respond within this
 * window, the request is pruned and an error is surfaced.
 */
private const val PENDING_REQUEST_TIMEOUT_MS = 30_000L

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentSessionId: String? = null,
    val sessions: List<SessionUi> = emptyList(),
    val chatTitle: String = "Hermes",
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val isAgentTyping: Boolean = false,
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    val isLoading: Boolean = false,
    /** Standalone streaming message — rendered after the main list. */
    val streamingMessage: ChatMessage? = null,
    val errorMessage: String? = null,
    val clarifyRequest: ClarifyUi? = null,
    val showSessionPicker: Boolean = false,
    // Search state
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchMatchIndices: List<Int> = emptyList(),
    val currentSearchMatchIndex: Int = -1,
) {
    /** Convenience — derived from [connectionStatus]. */
    val isConnected: Boolean get() = connectionStatus == ConnectionStatus.CONNECTED
}

data class SessionUi(
    val id: String,
    val title: String,
    val messageCount: Int = 0,
)

data class ClarifyUi(
    val text: String,
    val options: List<String>,
    val clarifyId: String? = null,
)

class ChatViewModel(
    application: Application,
    private val startCleanup: Boolean,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, startCleanup = true)

    // ── Internal state ───────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(ChatUiState())

    private val wsClient = HermesWsClient
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    private val dao = HermesDatabase.get(application).chatMessageDao()

    /** Tracks the ID of the currently streaming assistant message. */
    @Volatile
    private var streamingMessageId: String? = null

    private var pendingCleanupJob: Job? = null

    // ── Public state ─────────────────────────────────────────────────────

    /**
     * Combined UI state: merges internal state with the WS connection status
     * flow so there is a single source of truth for connection state.
     */
    val uiState: StateFlow<ChatUiState> =
        combine(
            _uiState,
            wsClient.connectionStatus,
        ) { state, connStatus ->
            state.copy(connectionStatus = connStatus)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            _uiState.value,
        )

    init {
        connectWebSocket()
        viewModelScope.launch {
            wsClient.events.collect { event ->
                handleWsEvent(event)
            }
        }
        if (startCleanup) {
            startPendingRequestCleanup()
        }
    }

    // ── Connection ───────────────────────────────────────────────────────

    private fun connectWebSocket() {
        val token = AuthManager.getToken() ?: return

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {
            wsClient.connect()
        }
    }

    // ── WS Event Handling ────────────────────────────────────────────────

    private fun handleWsEvent(event: WsEvent) {
        when (event) {
            is WsEvent.GatewayReady -> {
                _uiState.update { it.copy(isLoading = false) }
                addSystemMessage("Connected to Hermes")
                loadSessions()
                if (_uiState.value.currentSessionId == null) {
                    createNewSession()
                }
            }

            is WsEvent.SessionInfo -> {}

            is WsEvent.MessageStart -> {
                handleMessageStart(event)
            }

            is WsEvent.MessageToken -> {
                handleMessageToken(event)
            }

            is WsEvent.ThinkingDelta -> {
                handleThinkingDelta(event)
            }

            is WsEvent.MessageComplete -> {
                handleMessageComplete(event)
            }

            is WsEvent.MessageDone -> {
                handleMessageDone(event)
            }

            is WsEvent.ToolStart -> {
                handleToolStart(event)
            }

            is WsEvent.ToolComplete -> {
                handleToolComplete(event)
            }

            is WsEvent.ClarifyRequest -> {
                if (!isCurrentSession(event.sessionId)) return
                _uiState.update {
                    it.copy(
                        clarifyRequest =
                            ClarifyUi(
                                text = event.text.orEmpty(),
                                options = event.options.orEmpty(),
                                clarifyId = event.clarifyId,
                            ),
                    )
                }
            }

            is WsEvent.StatusUpdate -> {}

            is WsEvent.SessionUpdated -> {
                loadSessions()
            }

            is WsEvent.RpcResult -> {
                handleRpcResult(event.id, event.result)
            }

            is WsEvent.RpcError -> {
                handleRpcError(event.id, event.error)
            }

            is WsEvent.Unknown -> {}
        }
    }

    // ── Message streaming ────────────────────────────────────────────────

    /**
     * Checks if an incoming WS event belongs to the currently active
     * session. Returns true if the event should be processed.
     */
    private fun isCurrentSession(eventSessionId: String?): Boolean {
        // If the event has no session ID, process it (legacy compatibility)
        if (eventSessionId == null) return true
        return eventSessionId == _uiState.value.currentSessionId
    }

    private fun handleMessageStart(event: WsEvent.MessageStart) {
        if (!isCurrentSession(event.sessionId)) return

        var orphanToPersist: ChatMessage? = null
        val sessionId = _uiState.value.currentSessionId

        // Create the streaming message as a standalone state field.
        val msg =
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                isStreaming = true,
            )
        streamingMessageId = msg.id

        _uiState.update { state ->
            val messages = state.messages.toMutableList()
            val existing = state.streamingMessage
            if (existing != null && existing.content.isNotEmpty()) {
                val finalized = existing.copy(isStreaming = false)
                messages.add(finalized)
                orphanToPersist = finalized
            }
            state.copy(
                messages = messages,
                isAgentTyping = true,
                streamingMessage = msg,
                isThinking = false,
                thinkingText = "",
            )
        }

        // Persist — OUTSIDE update{}
        val orphan = orphanToPersist
        if (orphan != null && sessionId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                dao.upsert(orphan.toEntity(sessionId))
            }
        }
    }

    private fun handleMessageToken(event: WsEvent.MessageToken) {
        if (!isCurrentSession(event.sessionId)) return
        _uiState.update { state ->
            val current = state.streamingMessage
            if (current != null) {
                state.copy(
                    streamingMessage =
                        current.copy(
                            content = current.content + event.token,
                        ),
                    isThinking = false,
                )
            } else {
                // Fallback: no MessageStart was received — create one now
                val msg =
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = event.token,
                        isStreaming = true,
                    )
                streamingMessageId = msg.id
                state.copy(
                    streamingMessage = msg,
                    isAgentTyping = true,
                    isThinking = false,
                )
            }
        }
    }

    private fun handleThinkingDelta(event: WsEvent.ThinkingDelta) {
        if (!isCurrentSession(event.sessionId)) return
        _uiState.update { state ->
            state.copy(
                isThinking = true,
                thinkingText = state.thinkingText + event.token,
            )
        }
    }

    private fun handleMessageComplete(event: WsEvent.MessageComplete) {
        if (!isCurrentSession(event.sessionId)) return

        var finalizedMsg: ChatMessage? = null
        var sessionId: String? = null

        _uiState.update { state ->
            val streaming = state.streamingMessage
            val msg =
                if (streaming != null) {
                    streaming.copy(
                        content = event.text,
                        isStreaming = false,
                    )
                } else {
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = event.text,
                    )
                }
            finalizedMsg = msg
            sessionId = state.currentSessionId
            state.copy(
                messages = state.messages + msg,
                streamingMessage = null,
                isAgentTyping = false,
                isThinking = false,
                thinkingText = "",
            )
        }
        streamingMessageId = null

        // Persist finalized message — OUTSIDE update{} to avoid CAS retry
        val msgToPersist = finalizedMsg
        val sid = sessionId ?: _uiState.value.currentSessionId
        if (msgToPersist != null && sid != null) {
            viewModelScope.launch(Dispatchers.IO) {
                dao.upsert(msgToPersist.toEntity(sid))
            }
        }
    }

    private fun handleMessageDone(event: WsEvent.MessageDone) {
        if (!isCurrentSession(event.sessionId)) return

        // If we still have an un-finalized streaming message (no MessageComplete
        // was sent, which happens on interrupts), fold it into the list.
        var orphan: ChatMessage? = null
        var sessionId: String? = null

        _uiState.update { state ->
            val streaming = state.streamingMessage
            val msg = streaming?.copy(isStreaming = false)
            orphan = msg
            sessionId = state.currentSessionId
            if (msg != null) {
                state.copy(
                    messages = state.messages + msg,
                    streamingMessage = null,
                    isAgentTyping = false,
                    isThinking = false,
                    thinkingText = "",
                )
            } else {
                state.copy(
                    isAgentTyping = false,
                    isThinking = false,
                    thinkingText = "",
                )
            }
        }
        streamingMessageId = null

        // Persist orphaned streaming message
        val msgToPersist = orphan
        val sid = sessionId ?: _uiState.value.currentSessionId
        if (msgToPersist != null && sid != null) {
            viewModelScope.launch(Dispatchers.IO) {
                dao.upsert(msgToPersist.toEntity(sid))
            }
        }
    }

    // ── Tool events ──────────────────────────────────────────────────────

    private fun handleToolStart(event: WsEvent.ToolStart) {
        if (!isCurrentSession(event.sessionId)) return
        var orphanToPersist: ChatMessage? = null
        val sessionId = _uiState.value.currentSessionId

        val contentJson = event.data?.let { Gson().toJson(it) } ?: ""
        val toolMessage =
            ChatMessage(
                role = MessageRole.TOOL,
                content = contentJson,
                toolName = event.name,
                toolStatus = ToolStatus.RUNNING,
            )

        _uiState.update { state ->
            val messages = state.messages.toMutableList()
            val existing = state.streamingMessage
            if (existing != null && existing.content.isNotEmpty()) {
                val finalized = existing.copy(isStreaming = false)
                messages.add(finalized)
                orphanToPersist = finalized
            }
            messages.add(toolMessage)
            state.copy(
                messages = messages,
                streamingMessage = null,
            )
        }

        // Persist — OUTSIDE update{}
        val orphan = orphanToPersist
        if (sessionId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                if (orphan != null) {
                    dao.upsert(orphan.toEntity(sessionId))
                }
                dao.upsert(toolMessage.toEntity(sessionId))
            }
        }
    }

    private fun handleToolComplete(event: WsEvent.ToolComplete) {
        if (!isCurrentSession(event.sessionId)) return
        var completedTool: ChatMessage? = null

        _uiState.update { state ->
            val messages = state.messages.toMutableList()
            val toolIdx =
                messages.indexOfLast {
                    it.role == MessageRole.TOOL &&
                        it.toolName == event.name &&
                        it.toolStatus == ToolStatus.RUNNING
                }
            if (toolIdx >= 0) {
                val contentJson = event.data?.let { Gson().toJson(it) } ?: ""
                val updated =
                    messages[toolIdx].copy(
                        toolStatus = ToolStatus.COMPLETED,
                        content = contentJson,
                    )
                messages[toolIdx] = updated
                completedTool = updated
            }
            state.copy(messages = messages)
        }

        // Persist — OUTSIDE update{}
        val tool = completedTool
        val sessionId = _uiState.value.currentSessionId
        if (tool != null && sessionId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                dao.upsert(tool.toEntity(sessionId))
            }
        }
    }

    // ── RPC response handling ────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun handleRpcResult(
        id: String,
        result: Any?,
    ) {
        val pending = pendingRequests.remove(id) ?: return
        val method = pending.method

        when (method) {
            WsMethods.SESSION_CREATE -> {
                val resultMap = result as? Map<String, Any?> ?: return
                val sessionId = resultMap["session_id"] as? String ?: return
                _uiState.update {
                    it.copy(
                        currentSessionId = sessionId,
                        isLoading = false,
                        messages = emptyList(),
                        streamingMessage = null,
                    )
                }
                addSystemMessage("Session created", persist = true)
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
                _uiState.update { state ->
                    val newTitle = sessions.find { s -> s.id == state.currentSessionId }?.title
                    state.copy(
                        sessions = sessions,
                        chatTitle = newTitle ?: state.chatTitle,
                    )
                }
            }

            WsMethods.SESSION_RESUME -> {
                val resultMap = result as? Map<String, Any?>
                val sessionId =
                    (resultMap?.get("session_id") as? String)
                        ?: _uiState.value.currentSessionId

                // B8 (Jun 20 2026, kanban t_session_resume): do NOT reload
                // cached messages here — switchSession() already did so before
                // the WS round-trip. Calling loadCachedMessages() here would
                // overwrite any message the user sent between switchSession() and
                // the server ack, making the chat appear to go blank.
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentSessionId = sessionId,
                    )
                }
                addSystemMessage("Session resumed")
            }

            WsMethods.SESSION_INTERRUPT -> {
                _uiState.update {
                    it.copy(
                        isAgentTyping = false,
                        isThinking = false,
                        streamingMessage = null,
                    )
                }
                streamingMessageId = null
                addSystemMessage("Session interrupted")
            }
        }
    }

    private fun handleRpcError(
        id: String,
        error: Any?,
    ) {
        val pending = pendingRequests.remove(id)
        val method = pending?.method
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

    // ── Send message ─────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val sessionId = _uiState.value.currentSessionId ?: return

        val trimmed = text.trim()
        if (trimmed.startsWith("/", ignoreCase = true)) {
            handleSlashCommand(trimmed)
            return
        }

        val userMessage =
            ChatMessage(
                role = MessageRole.USER,
                content = text,
            )

        // Update UI — no DB writes inside update{}
        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isAgentTyping = true,
            )
        }

        // Persist to Room — OUTSIDE update{}
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsert(userMessage.toEntity(sessionId))
        }

        viewModelScope.launch(Dispatchers.IO) {
            wsClient.sendMessage(
                sessionId,
                text,
                onSent = { id -> trackRequest(id, WsMethods.PROMPT_SUBMIT) },
            )
        }
    }

    private fun handleSlashCommand(command: String) {
        val userMsg = ChatMessage(role = MessageRole.USER, content = command)
        val sessionId = _uiState.value.currentSessionId

        _uiState.update { it.copy(messages = it.messages + userMsg) }

        // Persist — OUTSIDE update{}
        if (sessionId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                dao.upsert(userMsg.toEntity(sessionId))
            }
        }

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
                    val result =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getStatus() }
                        }
                    when (result) {
                        is NetworkResult.Success -> {
                            val body = result.data
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
                        }

                        is NetworkResult.Failure -> {
                            addAssistantMessage("Failed to retrieve status: ${result.error.message}")
                        }
                    }
                }
            }

            "/sessions" -> {
                viewModelScope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getSessions() }
                        }
                    when (result) {
                        is NetworkResult.Success -> {
                            val body = result.data
                            val sessionsStr =
                                body.sessions.joinToString("\n") { s ->
                                    "• **${s.title ?: "Untitled"}** (ID: `${s.id}`, Messages: ${s.message_count ?: 0})"
                                }
                            addAssistantMessage("**Sessions List:**\n$sessionsStr")
                        }

                        is NetworkResult.Failure -> {
                            addAssistantMessage("Failed to list sessions: ${result.error.message}")
                        }
                    }
                }
            }

            "/stats", "/system" -> {
                viewModelScope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getSystemStats() }
                        }
                    when (result) {
                        is NetworkResult.Success -> {
                            val body = result.data
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
                        }

                        is NetworkResult.Failure -> {
                            addAssistantMessage("Failed to retrieve system stats: ${result.error.message}")
                        }
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

        // Persist — OUTSIDE update{}
        val sessionId = _uiState.value.currentSessionId
        if (sessionId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                dao.upsert(msg.toEntity(sessionId))
            }
        }
    }

    // ── Session management ───────────────────────────────────────────────

    fun interruptSession() {
        val sessionId = _uiState.value.currentSessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.send(
                WsMethods.SESSION_INTERRUPT,
                mapOf("session_id" to sessionId),
                onSent = { id -> trackRequest(id, WsMethods.SESSION_INTERRUPT) },
            )
        }
    }

    fun createNewSession() {
        _uiState.update {
            it.copy(
                isLoading = true,
                messages = emptyList(),
                streamingMessage = null,
            )
        }
        streamingMessageId = null
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.send(
                WsMethods.SESSION_CREATE,
                onSent = { id -> trackRequest(id, WsMethods.SESSION_CREATE) },
            )
        }
    }

    fun loadSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.send(
                WsMethods.SESSION_LIST,
                onSent = { id -> trackRequest(id, WsMethods.SESSION_LIST) },
            )
        }
    }

    fun switchSession(sessionId: String) {
        if (sessionId == _uiState.value.currentSessionId) return

        // Reset streaming state
        streamingMessageId = null

        _uiState.update {
            val title = it.sessions.find { s -> s.id == sessionId }?.title ?: it.chatTitle
            it.copy(
                isLoading = true,
                messages = emptyList(),
                streamingMessage = null,
                currentSessionId = sessionId,
                chatTitle = title,
                showSessionPicker = false,
                isAgentTyping = false,
                isThinking = false,
                thinkingText = "",
            )
        }
        // Step 1: Load cached messages first — instant display
        loadCachedMessages(sessionId)
        // Step 2: Resume session on server
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.send(
                WsMethods.SESSION_RESUME,
                mapOf("session_id" to sessionId),
                onSent = { id -> trackRequest(id, WsMethods.SESSION_RESUME) },
            )
        }
        // Step 3: Load fresh messages from REST and merge
        loadSessionMessages(sessionId)
    }

    private fun loadCachedMessages(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = dao.getMessagesForSession(sessionId)
            val cachedMessages = cached.map { it.toUiModel() }
            _uiState.update { state ->
                // Only replace if still showing this session
                if (state.currentSessionId == sessionId) {
                    state.copy(messages = cachedMessages, isLoading = false)
                } else {
                    state
                }
            }
        }
    }

    private fun loadSessionMessages(sessionId: String) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getSessionMessages(sessionId) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val messagesList = result.data.messages.orEmpty()
                    val chatMessages =
                        messagesList.mapIndexed { index, msg ->
                            val role =
                                when (msg.role?.lowercase()) {
                                    "user" -> MessageRole.USER
                                    "system" -> MessageRole.SYSTEM
                                    "tool" -> MessageRole.TOOL
                                    else -> MessageRole.ASSISTANT
                                }
                            // Use stable IDs based on session + index to prevent
                            // Room duplicates when re-loading the same session.
                            val stableId = "rest-$sessionId-$index"
                            // Preserve original timestamp from the API when available
                            val ts = msg.timestamp?.toLongOrNull() ?: System.currentTimeMillis()
                            ChatMessage(
                                id = stableId,
                                role = role,
                                content = msg.content.orEmpty(),
                                timestamp = ts,
                                isStreaming = false,
                            )
                        }
                    // Persist loaded messages to Room for offline access
                    val entities = chatMessages.map { it.toEntity(sessionId) }
                    withContext(Dispatchers.IO) {
                        dao.upsertAll(entities)
                    }
                    _uiState.update { state ->
                        // Only update if still on the same session
                        if (state.currentSessionId != sessionId) return@update state

                        // Merge: keep any user messages sent between cache load
                        // and REST response (they won't be in the REST response
                        // yet), plus preserve any active streaming message.
                        val existingIds = chatMessages.mapTo(HashSet(chatMessages.size)) { it.id }
                        val localOnly = state.messages.filter { it.id !in existingIds }
                        state.copy(
                            messages = chatMessages + localOnly,
                            isLoading = false,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    // Don't overwrite messages on REST failure — cached messages
                    // are still valid.
                    _uiState.update {
                        if (it.currentSessionId != sessionId) return@update it
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load messages: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── UI actions ───────────────────────────────────────────────────────

    fun toggleSessionPicker() {
        _uiState.update { it.copy(showSessionPicker = !it.showSessionPicker) }
    }

    fun dismissClarify() {
        _uiState.update { it.copy(clarifyRequest = null) }
    }

    fun respondToClarify(option: String) {
        val sessionId = _uiState.value.currentSessionId ?: return
        val clarifyId = _uiState.value.clarifyRequest?.clarifyId
        _uiState.update { it.copy(clarifyRequest = null) }

        val userMessage =
            ChatMessage(
                role = MessageRole.USER,
                content = option,
            )

        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isAgentTyping = true,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            dao.upsert(userMessage.toEntity(sessionId))
        }

        viewModelScope.launch(Dispatchers.IO) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "response" to option,
                    "answer" to option,
                )
            if (clarifyId != null) {
                params["clarify_id"] = clarifyId
                params["request_id"] = clarifyId
            }
            wsClient.send(
                method = WsMethods.CLARIFY_RESPOND,
                params = params,
                onSent = { id -> trackRequest(id, WsMethods.CLARIFY_RESPOND) },
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun reconnect() {
        _uiState.update {
            it.copy(
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

    private fun addSystemMessage(
        text: String,
        persist: Boolean = false,
    ) {
        val msg = ChatMessage(role = MessageRole.SYSTEM, content = text)
        val sessionId = _uiState.value.currentSessionId

        _uiState.update { it.copy(messages = it.messages + msg) }

        // Persist — OUTSIDE update{}
        if (persist && sessionId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                dao.upsert(msg.toEntity(sessionId))
            }
        }
    }

    // ── Pending request tracking ─────────────────────────────────────────

    private data class PendingRequest(
        val method: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private fun trackRequest(
        id: String,
        method: String,
    ) {
        pendingRequests[id] = PendingRequest(method)
    }

    /**
     * Periodically prunes stale pending requests that the server never
     * responded to. Surfaces a timeout error for the user.
     */
    private fun startPendingRequestCleanup() {
        pendingCleanupJob =
            viewModelScope.launch {
                while (true) {
                    delay(PENDING_REQUEST_TIMEOUT_MS)
                    val now = System.currentTimeMillis()
                    val stale =
                        pendingRequests.entries.filter {
                            now - it.value.createdAt > PENDING_REQUEST_TIMEOUT_MS
                        }
                    for (entry in stale) {
                        pendingRequests.remove(entry.key)
                        Log.w(TAG, "Request timed out: ${entry.value.method} (id=${entry.key})")
                    }
                }
            }
    }

    // ── Search ────────────────────────────────────────────────────────────

    fun toggleSearch() {
        val current = _uiState.value
        if (current.isSearchActive) {
            clearSearch()
        } else {
            _uiState.update { it.copy(isSearchActive = true, searchQuery = "") }
        }
    }

    private var searchJob: Job? = null

    fun setSearchQuery(query: String) {
        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    searchQuery = query,
                    searchMatchIndices = emptyList(),
                    currentSearchMatchIndex = -1,
                )
            }
            return
        }

        // Keep local state in sync immediately so UI feels responsive.
        _uiState.update {
            it.copy(searchQuery = query)
        }

        searchJob =
            viewModelScope.launch(Dispatchers.Default) {
                val messages = _uiState.value.messages
                val indices =
                    messages.indices.filter { idx ->
                        messages[idx].content.contains(query, ignoreCase = true)
                    }

                _uiState.update {
                    // Only update indices if the search query hasn't changed in the meantime
                    if (it.searchQuery == query) {
                        it.copy(
                            searchMatchIndices = indices,
                            currentSearchMatchIndex = if (indices.isNotEmpty()) 0 else -1,
                        )
                    } else {
                        it
                    }
                }
            }
    }

    fun navigateSearchMatch(direction: Int) {
        _uiState.update { state ->
            val indices = state.searchMatchIndices
            if (indices.isEmpty()) return@update state
            val current = state.currentSearchMatchIndex
            val newIdx =
                when (direction) {
                    1 -> if (current >= indices.lastIndex) 0 else current + 1
                    -1 -> if (current <= 0) indices.lastIndex else current - 1
                    else -> current
                }
            state.copy(currentSearchMatchIndex = newIdx)
        }
    }

    fun clearSearch() {
        _uiState.update {
            it.copy(
                isSearchActive = false,
                searchQuery = "",
                searchMatchIndices = emptyList(),
                currentSearchMatchIndex = -1,
            )
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        pendingCleanupJob?.cancel()
        wsClient.disconnect()
    }
}
