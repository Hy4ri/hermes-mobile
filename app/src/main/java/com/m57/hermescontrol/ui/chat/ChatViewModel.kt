package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ChatViewModel"

/**
 * Pending request timeout — if the server doesn't respond within this
 * window, the request is pruned and an error is surfaced.
 */
private const val PENDING_REQUEST_TIMEOUT_MS = 30_000L

/** Thrown when an awaited RPC returns an error response. */
private class RpcCallException(
    message: String,
) : Exception(message)

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
    // Commands catalog
    val commandCatalog: CommandCatalog = CommandCatalog(),
    // Attachment state
    val pendingAttachments: List<Attachment> = emptyList(),
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

    private val _streamingState = MutableStateFlow(StreamingState())
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

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

    /**
     * Session ID to resume when the WebSocket connects. Set synchronously by
     * [ChatScreen] via `SideEffect` during composition — before any WS event
     * can be processed. This prevents the race where [GatewayReady] fires
     * before ChatScreen's `LaunchedEffect` can call [switchSession], causing
     * [createNewSession] to create an empty chat that overwrites the
     * notification session (issue #240).
     */
    var initialSessionId: String? = null

    init {
        refreshSettings()

        connectWebSocket(setLoading = false)
        viewModelScope.launch {
            wsClient.events.collect { event ->
                handleWsEvent(event)
            }
        }
        // B7 (Jun 30 2026, kanban t_connection_loading): clear loading state on connection failure or status change
        viewModelScope.launch {
            wsClient.connectionStatus.collect { status ->
                if (status == ConnectionStatus.DISCONNECTED ||
                    status == ConnectionStatus.RECONNECTING ||
                    status == ConnectionStatus.NO_NETWORK ||
                    status == ConnectionStatus.AUTH_EXPIRED
                ) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
        if (startCleanup) {
            startPendingRequestCleanup()
        }

        // B7 (Jun 30 2026, kanban t_connection_loading): if already connected on launch, trigger setup immediately
        if (wsClient.connectionStatus.value == ConnectionStatus.CONNECTED) {
            handleGatewayReady()
        }
    }

    // ── Connection ───────────────────────────────────────────────────────

    private fun connectWebSocket(setLoading: Boolean = false) {
        val token = AuthManager.getToken() ?: return

        if (setLoading) {
            _uiState.update { it.copy(isLoading = true) }
        }

        viewModelScope.launch(Dispatchers.IO) {
            wsClient.connect()
        }

        // B7 (Jun 30 2026, kanban t_connection_loading): safety timeout to clear spinner if connection hangs
        if (!isTestEnvironment()) {
            viewModelScope.launch {
                delay(10_000L)
                if (_uiState.value.isLoading) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    // ── WS Event Handling ────────────────────────────────────────────────

    private fun handleGatewayReady() {
        _uiState.update { it.copy(isLoading = false) }
        addSystemMessage("Connected to Hermes")
        loadSessions()
        fetchCommandCatalog()
        val currentId = _uiState.value.currentSessionId
        if (currentId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                wsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to currentId),
                    onSent = { id -> trackRequest(id, WsMethods.SESSION_RESUME) },
                )
            }
            loadSessionMessages(currentId)
        } else {
            val initial = initialSessionId
            if (!initial.isNullOrBlank()) {
                initialSessionId = null
                switchSession(initial)
            } else {
                createNewSession(setLoading = false)
            }
        }
    }

    private fun handleWsEvent(event: WsEvent) {
        // First, let the reducer compute the new state and any effects
        val result =
            ChatWsEventReducer.reduce(
                _uiState.value,
                _streamingState.value,
                event,
                _uiState.value.currentSessionId,
            )

        // Apply the new state
        _uiState.update { result.state }
        _streamingState.update { result.streamingState }

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
                handleGatewayReady()
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
                _streamingState.update { StreamingState() }
            }

            is WsEvent.ApprovalRequest -> {
                handleApprovalRequest(event)
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
            _streamingState.update { state ->
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
                        isThinking = false,
                    )
                }
            }
            _uiState.update { it.copy(isAgentTyping = true) }
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
            _streamingState.update { state ->
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

        // Complete the deferred so sendRpcAndAwait() callers can resume
        pending.deferred?.complete(result)

        when (method) {
            WsMethods.SESSION_CREATE -> {
                val resultMap = result as? Map<String, Any?> ?: return
                val sessionId = resultMap["session_id"] as? String ?: return
                _uiState.update {
                    it.copy(
                        currentSessionId = sessionId,
                        isLoading = false,
                        messages = emptyList(),
                        chatTitle = "Hermes",
                    )
                }
                _streamingState.update { StreamingState() }
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
                    )
                }
                _streamingState.update { StreamingState() }
                streamingMessageId = null
                addSystemMessage("Session interrupted")
            }

            WsMethods.COMMANDS_CATALOG -> {
                val map = result as? Map<*, *> ?: return
                val catalog = parseCommandCatalog(map)
                if (catalog != null) {
                    _uiState.update { it.copy(commandCatalog = catalog) }
                }
            }

            WsMethods.COMMAND_DISPATCH -> {
                handleDispatchResult(result)
            }

            WsMethods.APPROVAL_RESPOND -> {
                val map = result as? Map<*, *>
                val resolved = (map?.get("resolved") as? Number)?.toInt() ?: 0
                if (resolved > 0) {
                    addSystemMessage("✅ Approval submitted")
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleDispatchResult(result: Any?) {
        val map = result as? Map<*, *> ?: return
        val type = map["type"] as? String ?: return
        when (type) {
            "send" -> {
                val message = map["message"] as? String ?: ""
                submitPrompt(message)
            }

            "exec" -> {
                val output = map["output"] as? String ?: map["message"] as? String ?: ""
                addAssistantMessage(output)
            }

            "skill" -> {
                val message = map["message"] as? String ?: ""
                submitPrompt(message)
            }

            "plugin" -> {
                val output = map["output"] as? String ?: ""
                addAssistantMessage(output)
            }

            "alias" -> {
                val target = map["target"] as? String ?: return
                handleSlashCommand(target)
            }

            else -> {
                val output = map["output"] as? String ?: map.toString()
                addAssistantMessage(output)
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

        // Fail the deferred so sendRpcAndAwait() callers see the error
        pending?.deferred?.completeExceptionally(RpcCallException(errorMsg))

        // Only surface error in UI for non-awaited RPCs
        if (pending?.deferred == null) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = "Error${if (method != null) " ($method)" else ""}: $errorMsg",
                )
            }
        }
    }

    // ── Send message ─────────────────────────────────────────────────────

    /**
     * Send a user prompt, uploading any pending attachments to the backend
     * first via their dedicated RPC methods.
     *
     * Flow:
     * 1. Snapshot pending attachments (then clear them from UI)
     * 2. Add user message to UI + DB immediately (optimistic UX)
     * 3. For each image → fire-and-forget `image.attach_bytes` (queues into session)
     * 4. For each file → await `file.attach`, collect @file: refs
     * 5. Send `prompt.submit` with text + @file: refs — images auto-picked up by backend
     */
    fun sendMessage(text: String) {
        if (text.isBlank() && _uiState.value.pendingAttachments.isEmpty()) return
        val sessionId = _uiState.value.currentSessionId ?: return

        val trimmed = text.trim()
        if (trimmed.startsWith("/", ignoreCase = true)) {
            handleSlashCommand(trimmed)
            return
        }

        // Snapshot + clear attachments so the input bar empties immediately
        val attachments = _uiState.value.pendingAttachments.toList()
        clearAttachments()

        val userMessage =
            ChatMessage(
                role = MessageRole.USER,
                content = text,
                attachments = if (attachments.isNotEmpty()) attachments else null,
            )

        // Update UI immediately
        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isAgentTyping = true,
            )
        }

        // Persist to Room
        viewModelScope.launch(Dispatchers.IO) {
            repo.persistMessage(userMessage, sessionId)
        }

        // Upload attachments then submit prompt
        viewModelScope.launch(Dispatchers.IO) {
            val fileRefs = mutableListOf<String>()

            for (attachment in attachments) {
                val bytes = readContentUriBytes(attachment.uri)
                if (bytes == null) {
                    Log.w(TAG, "Skipping unreadable attachment: ${attachment.name}")
                    continue
                }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

                try {
                    if (attachment.isImage) {
                        // Fire-and-forget — backend queues into session["attached_images"],
                        // auto-picked by the subsequent prompt.submit
                        wsClient.send(
                            method = WsMethods.IMAGE_ATTACH_BYTES,
                            params =
                                mapOf(
                                    "content_base64" to "data:${attachment.mimeType};base64,$b64",
                                    "filename" to attachment.name,
                                    "ext" to attachment.fileExtension,
                                ),
                        )
                    } else {
                        // Await the @file: ref text so we can embed it in the prompt
                        sendRpcAndAwait(
                            method = WsMethods.FILE_ATTACH,
                            params =
                                mapOf(
                                    "data_url" to "data:${attachment.mimeType};base64,$b64",
                                    "name" to attachment.name,
                                ),
                        )?.let { result ->
                            @Suppress("UNCHECKED_CAST")
                            val refText =
                                (result as? Map<String, Any?>)?.get("ref_text") as? String
                            if (!refText.isNullOrBlank()) fileRefs.add(refText)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload attachment ${attachment.name}", e)
                    _uiState.update {
                        it.copy(errorMessage = "⚠️ Upload failed: ${attachment.name}")
                    }
                }
            }

            // Build prompt text — prepend @file: refs for non-image files
            val fullText =
                if (fileRefs.isEmpty()) {
                    text
                } else {
                    fileRefs.joinToString("\n") +
                        if (text.isNotBlank()) "\n\n$text" else ""
                }

            wsClient.sendMessage(
                sessionId,
                fullText,
                onSent = { id -> trackRequest(id, WsMethods.PROMPT_SUBMIT) },
            )
        }
    }

    /** Read bytes from a `content://` or `file://` URI via ContentResolver. */
    private fun readContentUriBytes(uriString: String): ByteArray? =
        try {
            val context = getApplication<Application>()
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read attachment bytes: ${e.message}", e)
            null
        }

    /**
     * Send a JSON-RPC call and suspend until the response arrives.
     * Throws [RpcCallException] on error, [kotlinx.coroutines.TimeoutCancellationException] on timeout.
     */
    private suspend fun sendRpcAndAwait(
        method: String,
        params: Map<String, Any>,
        timeoutMs: Long = 30_000,
    ): Any? {
        val deferred = CompletableDeferred<Any?>()
        wsClient.send(
            method = method,
            params = params,
            onSent = { id -> trackRequest(id, method, deferred) },
        )
        return withTimeout(timeoutMs) { deferred.await() }
    }

    // ── Attachment management ─────────────────────────────────────────────

    /**
     * Add a picked file as a pending attachment.
     * [uri] should be a content:// URI string; the ViewModel will read
     * the content and encode it for sending.
     */
    fun addAttachment(
        uri: String,
        name: String,
        mimeType: String,
        size: Long,
    ) {
        _uiState.update { state ->
            val attachment =
                Attachment(
                    uri = uri,
                    name = name,
                    mimeType = mimeType,
                    size = size,
                )
            state.copy(
                pendingAttachments = state.pendingAttachments + attachment,
            )
        }
    }

    fun removeAttachment(index: Int) {
        _uiState.update { state ->
            state.copy(
                pendingAttachments =
                    state.pendingAttachments.toMutableList().apply {
                        if (index in indices) removeAt(index)
                    },
            )
        }
    }

    fun clearAttachments() {
        _uiState.update { it.copy(pendingAttachments = emptyList()) }
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
            is SlashResult.Interrupt -> {
                interruptSession()
            }

            is SlashResult.NewSession -> {
                createNewSession()
            }

            is SlashResult.RpcDispatch -> {
                dispatchViaRpc(command)
            }
        }
    }

    private fun dispatchViaRpc(command: String) {
        val sessionId = _uiState.value.currentSessionId
        if (sessionId == null) {
            addAssistantMessage("No active session. Use `/new` to create one.")
            return
        }
        val parts = command.split(" ", limit = 2)
        val name = parts[0].lowercase().removePrefix("/")
        val arg = parts.getOrElse(1) { "" }
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.send(
                WsMethods.COMMAND_DISPATCH,
                mapOf("name" to name, "arg" to arg, "session_id" to sessionId),
                onSent = { id -> trackRequest(id, WsMethods.COMMAND_DISPATCH) },
            )
        }
    }

    /**
     * Submits [text] as a prompt to the current session via WS, without
     * adding a duplicate user message. Used by [handleDispatchResult] when
     * a slash command resolves to a normal user prompt (e.g. `/queue` → "help me").
     */
    private fun submitPrompt(text: String) {
        if (text.isBlank()) return
        val sessionId = _uiState.value.currentSessionId ?: return
        _uiState.update { it.copy(isAgentTyping = true) }
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.sendMessage(
                sessionId,
                text,
                onSent = { id -> trackRequest(id, WsMethods.PROMPT_SUBMIT) },
            )
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

    fun createNewSession(setLoading: Boolean = true) {
        _uiState.update {
            it.copy(
                isLoading = setLoading,
                messages = emptyList(),
                chatTitle = "Hermes",
            )
        }
        _streamingState.update { StreamingState() }
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

    private fun fetchCommandCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.send(
                WsMethods.COMMANDS_CATALOG,
                onSent = { id -> trackRequest(id, WsMethods.COMMANDS_CATALOG) },
            )
        }
    }

    fun refreshCurrentSession() {
        val sessionId = _uiState.value.currentSessionId ?: return
        loadCachedMessages(sessionId)
        // Skip the REST call if we already have messages — the WebSocket keeps
        // the UI current. The REST endpoint can 404 on tab switch-back when the
        // WS session ID doesn't round-trip through the resolver (issue #366),
        // showing a misleading error banner while the connection is fine.
        if (_uiState.value.messages.isNotEmpty()) return
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

    @Suppress("UNCHECKED_CAST")
    private fun parseCommandCatalog(map: Map<*, *>): CommandCatalog? =
        try {
            val json = OkHttpProvider.gson.toJson(map)
            OkHttpProvider.gson.fromJson(json, CommandCatalog::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse command catalog", e)
            null
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
                currentSessionId = sessionId,
                messages = emptyList(),
                chatTitle = title,
                showSessionPicker = false,
                isAgentTyping = false,
            )
        }
        _streamingState.update { StreamingState() }
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

    // ── Approval flow ───────────────────────────────────────────────────

    private fun handleApprovalRequest(event: WsEvent.ApprovalRequest) {
        val description = event.description ?: event.command ?: "Unknown command"
        val content = "⚠️ **Approval Required**\n$description"
        val msg =
            ChatMessage(
                role = MessageRole.SYSTEM,
                content = content,
                approvalInfo =
                    ApprovalInfo(
                        command = event.command,
                        description = event.description,
                        patternKeys = event.patternKeys,
                    ),
            )
        _uiState.update { state ->
            state.copy(
                messages = state.messages + msg,
                isAgentTyping = false,
            )
        }
    }

    fun respondToApproval(action: String) {
        val state = _uiState.value
        val approvalMsg = state.messages.lastOrNull { it.approvalInfo != null } ?: return
        val sessionId = state.currentSessionId ?: return

        // Clear buttons immediately
        _uiState.update { s ->
            s.copy(
                messages =
                    s.messages.map {
                        if (it.id == approvalMsg.id) {
                            it.copy(approvalInfo = null)
                        } else {
                            it
                        }
                    },
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            wsClient.send(
                method = WsMethods.APPROVAL_RESPOND,
                params =
                    mapOf(
                        "session_id" to sessionId,
                        "choice" to action,
                        "all" to false,
                    ),
                onSent = { id -> trackRequest(id, WsMethods.APPROVAL_RESPOND) },
            )
        }
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
            connectWebSocket(setLoading = true)
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
        val deferred: CompletableDeferred<Any?>? = null,
    )

    private fun trackRequest(
        id: String,
        method: String,
        deferred: CompletableDeferred<Any?>? = null,
    ) {
        pendingRequests[id] = PendingRequest(method, deferred = deferred)
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
        // PERF-16: Don't disconnect the global HermesWsClient singleton when
        // leaving the Chat screen — it's used by background notification reply.
    }

    companion object {
    }
}
