package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
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
    // Cached settings
    val typingEffectEnabled: Boolean = true,
    val typingEffectDelayMs: Int = 30,
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
    private val repo =
        ChatPersistenceRepository(
            HermesDatabase.get(application).chatMessageDao(),
        )
    private val slashDispatcher = SlashCommandDispatcher()
    private val searchController = ChatSearchController()

    /** Tracks the ID of the currently streaming assistant message. */
    @Volatile
    private var streamingMessageId: String? = null

    private var pendingCleanupJob: Job? = null

    private val streamingBuffer = java.lang.StringBuilder()
    private var lastFlushMs = 0L

    private val thinkingBuffer = java.lang.StringBuilder()
    private var lastThinkingFlushMs = 0L

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
        refreshSettings()
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

    /**
     * Session ID to resume when the WebSocket connects. Set synchronously by
     * [ChatScreen] via `SideEffect` during composition — before any WS event
     * can be processed. This prevents the race where [GatewayReady] fires
     * before ChatScreen's `LaunchedEffect` can call [switchSession], causing
     * [createNewSession] to create an empty chat that overwrites the
     * notification session (issue #240).
     */
    var initialSessionId: String? = null

    private fun handleWsEvent(event: WsEvent) {
        // First, let the reducer compute the new state and any effects
        val result = ChatWsEventReducer.reduce(_uiState.value, event, _uiState.value.currentSessionId)

        // Apply the new state
        _uiState.update { result.state }

        // Process side-effects from the reducer
        for (effect in result.effects) {
            when (effect) {
                is ReducerEffect.PersistMessage -> {
                    viewModelScope.launch(Dispatchers.IO) {
                        repo.persistMessage(effect.message, effect.sessionId)
                    }
                }

                is ReducerEffect.CreateNewSession -> {
                    createNewSession()
                }

                is ReducerEffect.LoadSessions -> {
                    loadSessions()
                }

                is ReducerEffect.RefreshSessions -> {
                    loadSessions()
                }
            }
        }

        // Handle complex events that need ViewModel-specific context
        when (event) {
            is WsEvent.GatewayReady -> {
                _uiState.update { it.copy(isLoading = false) }
                addSystemMessage("Connected to Hermes")
                loadSessions()
                if (_uiState.value.currentSessionId == null) {
                    val initial = initialSessionId
                    if (!initial.isNullOrBlank()) {
                        initialSessionId = null
                        switchSession(initial)
                    } else {
                        createNewSession()
                    }
                }
            }

            is WsEvent.MessageToken -> {
                handleMessageToken(event)
            }

            is WsEvent.ThinkingDelta -> {
                handleThinkingDelta(event)
            }

            is WsEvent.MessageStart -> {
                streamingMessageId =
                    java.util.UUID
                        .randomUUID()
                        .toString()
                streamingBuffer.clear()
                thinkingBuffer.clear()
                lastFlushMs = 0L
                lastThinkingFlushMs = 0L
            }

            is WsEvent.MessageComplete -> {
                // Buffers cleared before reduce; ViewModel resets them after
                streamingMessageId = null
                streamingBuffer.clear()
                thinkingBuffer.clear()
            }

            is WsEvent.MessageDone -> {
                streamingMessageId = null
                streamingBuffer.clear()
                thinkingBuffer.clear()
            }

            is WsEvent.ToolStart -> {
                // Reset streaming state when a tool starts
                streamingBuffer.clear()
                thinkingBuffer.clear()
            }

            is WsEvent.RpcResult -> {
                handleRpcResult(event.id, event.result)
            }

            is WsEvent.RpcError -> {
                handleRpcError(event.id, event.error)
            }

            is WsEvent.SessionUpdated -> {
                loadSessions()
            }

            is WsEvent.ClarifyRequest -> {
                _uiState.update {
                    it.copy(
                        isAgentTyping = false,
                    )
                }
            }

            else -> { /* reducer handles these */ }
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

    private fun handleMessageToken(event: WsEvent.MessageToken) {
        if (!isCurrentSession(event.sessionId)) return

        streamingBuffer.append(event.token)
        val now = System.currentTimeMillis()

        // Always flush in tests, or if enough time has passed
        val shouldFlush = (now - lastFlushMs >= 33L) || lastFlushMs == 0L || isTestEnvironment()
        if (shouldFlush) {
            val currentContent = streamingBuffer.toString()
            lastFlushMs = now
            _uiState.update { state ->
                val current = state.streamingMessage
                if (current != null) {
                    state.copy(
                        streamingMessage =
                            current.copy(
                                content = currentContent,
                            ),
                        isThinking = false,
                    )
                } else {
                    // Fallback: no MessageStart was received — create one now
                    val msg =
                        ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = currentContent,
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
    }

    private fun handleThinkingDelta(event: WsEvent.ThinkingDelta) {
        if (!isCurrentSession(event.sessionId)) return

        thinkingBuffer.append(event.token)
        val now = System.currentTimeMillis()

        // Always flush in tests, or if enough time has passed
        val shouldFlush = (now - lastThinkingFlushMs >= 33L) || lastThinkingFlushMs == 0L || isTestEnvironment()
        if (shouldFlush) {
            val currentContent = thinkingBuffer.toString()
            lastThinkingFlushMs = now
            _uiState.update { state ->
                state.copy(
                    isThinking = true,
                    thinkingText = currentContent,
                )
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
                        chatTitle = "Hermes",
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
                streamingBuffer.clear()
                thinkingBuffer.clear()
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
            repo.persistMessage(userMessage, sessionId)
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
                repo.persistMessage(userMsg, sessionId)
            }
        }

        when (val result = slashDispatcher.dispatch(command)) {
            is SlashResult.Message -> {
                addAssistantMessage(result.text)
            }

            is SlashResult.Interrupt -> {
                interruptSession()
            }

            is SlashResult.NewSession -> {
                createNewSession()
            }

            is SlashResult.FetchStatus -> {
                fetchAndDisplayStatus()
            }

            is SlashResult.FetchSessions -> {
                fetchAndDisplaySessions()
            }

            is SlashResult.FetchStats -> {
                fetchAndDisplayStats()
            }

            is SlashResult.Unknown -> {
                addAssistantMessage(
                    "Unknown command: `${result.cmd}`. Type `/help` to view a list of available commands.",
                )
            }
        }
    }

    private fun fetchAndDisplayStatus() {
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
                    addAssistantMessage(
                        """
                        **Hermes Gateway Status:**
                        • **Version:** ${body.version ?: "Unknown"}
                        • **Gateway Running:** ${body.gateway_running ?: false}
                        • **Active Sessions:** ${body.active_sessions ?: 0}
                        • **Auth Required:** ${body.auth_required ?: false}

                        **Platforms:**
                        $platformsStr
                        """.trimIndent(),
                    )
                }

                is NetworkResult.Failure -> {
                    addAssistantMessage("Failed to retrieve status: ${result.error.message}")
                }
            }
        }
    }

    private fun fetchAndDisplaySessions() {
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

    private fun fetchAndDisplayStats() {
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
                    addAssistantMessage(
                        """
                        **System Resource Stats:**
                        • **CPU Usage:** $cpuPct
                        • **Memory Usage:** $memPct
                        • **Uptime:** ${body.uptime ?: "N/A"}
                        """.trimIndent(),
                    )
                }

                is NetworkResult.Failure -> {
                    addAssistantMessage("Failed to retrieve system stats: ${result.error.message}")
                }
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
                repo.persistMessage(msg, sessionId)
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
                chatTitle = "Hermes",
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

    fun refreshCurrentSession() {
        val sessionId = _uiState.value.currentSessionId ?: return
        loadCachedMessages(sessionId)
        loadSessionMessages(sessionId)
    }

    fun refreshSettings() {
        _uiState.update { state ->
            state.copy(
                typingEffectEnabled = AuthManager.isTypingEffectEnabled(),
                typingEffectDelayMs = AuthManager.getTypingEffectDelayMs(),
            )
        }
    }

    fun switchSession(sessionId: String) {
        if (sessionId == _uiState.value.currentSessionId) return

        // Reset streaming state
        streamingMessageId = null
        streamingBuffer.clear()
        thinkingBuffer.clear()

        _uiState.update {
            val title = it.sessions.find { s -> s.id == sessionId }?.title ?: "Hermes"
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
        // Step 4: Refresh session list to get latest titles
        loadSessions()
    }

    private fun loadCachedMessages(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cachedMessages = repo.loadMessages(sessionId)
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
                    withContext(Dispatchers.IO) {
                        repo.persistMessages(chatMessages, sessionId)
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
            repo.persistMessage(userMessage, sessionId)
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
                repo.persistMessage(msg, sessionId)
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
                val indices = searchController.findMatches(messages, query)

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
            val newIdx =
                searchController.navigate(
                    currentIndex = state.currentSearchMatchIndex,
                    matchCount = indices.size,
                    direction = direction,
                )
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

    private var isTestEnv: Boolean? = null

    private fun isTestEnvironment(): Boolean {
        if (isTestEnv == null) {
            isTestEnv =
                try {
                    Class.forName("org.junit.Test")
                    true
                } catch (e: ClassNotFoundException) {
                    false
                }
        }
        return isTestEnv == true
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        pendingCleanupJob?.cancel()
        wsClient.disconnect()
    }

    companion object {
        private val gson = Gson()
    }
}
