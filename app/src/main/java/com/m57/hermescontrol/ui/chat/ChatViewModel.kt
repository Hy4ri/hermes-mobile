package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.util.Base64
import android.util.Base64OutputStream
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.config.openSessionTab
import com.m57.hermescontrol.data.config.setQueuedPrompt
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.toJsonElement
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ChatViewModel"

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    /** Durable database/session-list identity. Never replace with the live gateway id. */
    val currentSessionId: String? = null,
    /** Ephemeral in-memory gateway identity used only for session-scoped WS calls. */
    val gatewaySessionId: String? = null,
    val sessions: List<SessionUi> = emptyList(),
    val chatTitle: String = "Cassy",
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val isAgentTyping: Boolean = false,
    val isThinking: Boolean = false,
    val thinkingText: String = "",
    val isLoading: Boolean = false,
    /** Standalone streaming message — rendered after the main list. */
    val streamingMessage: ChatMessage? = null,
    val errorMessage: String? = null,
    // Background job completion toast (issue #527) — non-blocking snackbar
    val backgroundCompleteMessage: String? = null,
    val clarifyRequest: ClarifyUi? = null,
    // Sudo / secret prompts — surfaced as dialogs (issue #524)
    val sudoPrompt: SudoPromptUi? = null,
    val secretPrompt: SecretPromptUi? = null,
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
    val queuedPrompt: QueuedPromptUi? = null,
    val yoloEnabled: Boolean = false,
    val yoloUpdating: Boolean = false,
) {
    /** Convenience — derived from [connectionStatus]. */
    val isConnected: Boolean get() = connectionStatus == ConnectionStatus.CONNECTED
}

data class QueuedPromptUi(
    val text: String,
    val attachments: List<Attachment> = emptyList(),
)

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

/** Transient — not persisted. Holds a pending sudo.password request. */
data class SudoPromptUi(
    val requestId: String?,
    val sessionId: String?,
)

/** Transient — not persisted. Holds a pending secret (token/password) request. */
data class SecretPromptUi(
    val requestId: String?,
    val sessionId: String?,
)

class ChatViewModel(
    application: Application,
    private val startCleanup: Boolean,
    private val repo: ChatPersistenceRepository =
        ChatPersistenceRepository(
            HermesDatabase.get(application).chatMessageDao(),
        ),
    private val storedActiveSessionId: () -> String? = {
        AuthManager.serverStore.getLatestState().activeSessionId
    },
    private val persistActiveSession: (String) -> Unit = { sessionId ->
        AuthManager.serverStore.update { state -> state.openSessionTab(sessionId) }
    },
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, startCleanup = true)

    // ── Internal state ───────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(ChatUiState())

    private val _streamingState = MutableStateFlow(StreamingState())
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    private val wsClient = HermesWsClient

    private val slashDispatcher = SlashCommandDispatcher()
    private val searchDelegate =
        ChatSearchDelegate(
            scope = viewModelScope,
            uiState = _uiState,
        )
    private val attachmentsDelegate = ChatAttachmentsDelegate(uiState = _uiState)

    private val streamingController =
        ChatStreamingController(
            scope = viewModelScope,
            uiState = _uiState,
            streamingState = _streamingState,
            isCurrentSession = { sessionId -> isCurrentSession(sessionId) },
            isTestEnvironment = { isTestEnvironment() },
        )

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

    /**
     * True while the initial session list decides whether history can be
     * resumed. A cold start must not create an empty chat before that list is
     * available.
     */
    private var awaitingInitialSessionSelection = false
    private val queuedPrompts = ConcurrentHashMap<String, QueuedPromptUi>()
    private val historyLoadsInFlight = ConcurrentHashMap.newKeySet<String>()

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
                    // Fail any in-flight awaited RPCs so callers don't hang
                    // across the disconnect (delegated to HermesWsClient, issue #526).
                    wsClient.rejectAllPending()
                }
            }
        }
        if (wsClient.connectionStatus.value == ConnectionStatus.CONNECTED) {
            handleGatewayReady()
        }
        if (!isTestEnvironment()) {
            startLiveSyncLoop()
        }
    }

    // ── Connection ───────────────────────────────────────────────────────

    private fun connectWebSocket(setLoading: Boolean = false) {
        val token = AuthManager.getToken() ?: return

        // Don't disturb an already-working (or already-recovering) connection.
        // HermesWsClient is a global singleton shared by every tab; the chat tab
        // is recreated on every open, so calling connect() here must be a no-op
        // unless the singleton is in a terminal state. Re-entering connect() while
        // it is CONNECTING/RECONNECTING races the in-flight socket and can leave
        // the status stuck on RECONNECTING (see HermesWsClient.connect).
        val status = wsClient.connectionStatus.value
        if (status == ConnectionStatus.CONNECTING ||
            status == ConnectionStatus.RECONNECTING ||
            status == ConnectionStatus.AUTH_EXPIRED
        ) {
            return
        }

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
        addSystemMessage(getApplication<Application>().getString(R.string.chat_system_connected))
        loadSessions()
        fetchCommandCatalog()
        // Codex-style follow-up semantics: a race at turn teardown must queue,
        // never interrupt the active turn.
        if (!isTestEnvironment()) {
            wsClient.send(
                WsMethods.CONFIG_SET,
                mapOf("key" to "busy", "value" to "queue"),
            )
        }
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
                awaitingInitialSessionSelection = true
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
                routingSessionId(event),
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
                streamingController.handleMessageToken(event)
            }

            is WsEvent.ThinkingDelta -> {
                streamingController.handleThinkingDelta(event)
            }

            is WsEvent.ReasoningDelta -> {
                streamingController.handleReasoningDelta(event)
            }

            is WsEvent.MessageStart -> {
                streamingController.beginStreamingMessage()
            }

            is WsEvent.MessageComplete -> {
                // Buffers cleared before reduce; ViewModel resets them after
                streamingController.resetStreaming()
                scheduleQueuedPromptDrain(event.sessionId)
            }

            is WsEvent.MessageDone -> {
                streamingController.resetStreaming()
                scheduleQueuedPromptDrain(event.sessionId)
            }

            is WsEvent.ToolStart -> {
                // Reset streaming state when a tool starts
                streamingController.resetStreaming()
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

            is WsEvent.SessionInfo -> {
                val enabled = event.data?.get("yolo") as? Boolean
                if (enabled != null) {
                    _uiState.update { it.copy(yoloEnabled = enabled, yoloUpdating = false) }
                }
            }

            is WsEvent.ClarifyRequest -> {
                _uiState.update {
                    it.copy(
                        isAgentTyping = false,
                    )
                }
                _streamingState.update { StreamingState() }
                streamingController.resetStreaming()
            }

            is WsEvent.ApprovalRequest -> {
                handleApprovalRequest(event)
            }

            is WsEvent.SudoRequest -> {
                handleSudoRequest(event)
            }

            is WsEvent.SecretRequest -> {
                handleSecretRequest(event)
            }

            is WsEvent.GatewayError -> {
                // Reducer already set errorMessage; no extra VM work needed.
            }

            is WsEvent.BackgroundComplete -> {
                // Reducer already set backgroundCompleteMessage; the UI observes
                // it via a LaunchedEffect and triggers the snackbar.
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
        val state = _uiState.value
        return eventSessionId == state.currentSessionId || eventSessionId == state.gatewaySessionId
    }

    private fun routingSessionId(event: WsEvent): String? {
        val eventSessionId = event.sessionIdOrNull()
        return if (eventSessionId == null || isCurrentSession(eventSessionId)) {
            eventSessionId ?: _uiState.value.gatewaySessionId ?: _uiState.value.currentSessionId
        } else {
            _uiState.value.gatewaySessionId ?: _uiState.value.currentSessionId
        }
    }

    // ── RPC response handling ────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun handleRpcResult(
        id: String,
        result: Any?,
    ) {
        val method = idToMethod.remove(id) ?: return
        when (method) {
            WsMethods.SESSION_CREATE -> {
                val resultMap = result as? Map<String, Any?> ?: return
                val gatewaySessionId = resultMap["session_id"] as? String ?: return
                val sessionId =
                    resultMap["stored_session_id"] as? String
                        ?: resultMap["session_key"] as? String
                        ?: gatewaySessionId
                _uiState.update {
                    it.copy(
                        currentSessionId = sessionId,
                        gatewaySessionId = gatewaySessionId,
                        isLoading = false,
                        messages = emptyList(),
                        chatTitle = "Cassy",
                    )
                }
                // Mirror and persist the active session so a cold app start
                // resumes this conversation instead of creating a duplicate.
                ActiveSessionHolder.set(sessionId)
                persistActiveSession(sessionId)
                _streamingState.update { StreamingState() }
                addSystemMessage(
                    getApplication<Application>().getString(R.string.chat_system_session_created),
                    persist = true,
                )
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

                if (awaitingInitialSessionSelection && _uiState.value.currentSessionId == null) {
                    awaitingInitialSessionSelection = false
                    val storedId = storedActiveSessionId()
                    val targetId =
                        storedId?.takeIf { id -> sessions.any { it.id == id } }
                            ?: sessions.firstOrNull()?.id
                    if (targetId != null) {
                        switchSession(targetId)
                    } else {
                        createNewSession(setLoading = false)
                    }
                }
            }

            WsMethods.SESSION_RESUME -> {
                val resultMap = result as? Map<String, Any?>
                val gatewaySessionId = resultMap?.get("session_id") as? String
                val sessionId =
                    (resultMap?.get("resumed") as? String)
                        ?: (resultMap?.get("session_key") as? String)
                        ?: _uiState.value.currentSessionId
                val running = resultMap?.get("running") as? Boolean ?: false

                // B8 (Jun 20 2026, kanban t_session_resume): do NOT reload
                // cached messages here — switchSession() already did so before
                // the WS round-trip. Calling loadCachedMessages() here would
                // overwrite any message the user sent between switchSession() and
                // the server ack, making the chat appear to go blank.
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentSessionId = sessionId,
                        gatewaySessionId = gatewaySessionId,
                        isAgentTyping = running,
                    )
                }
                // Mirror and persist the active session app-wide.
                ActiveSessionHolder.set(sessionId)
                sessionId?.let(persistActiveSession)
                addSystemMessage(getApplication<Application>().getString(R.string.chat_system_session_resumed))
                if (!running) scheduleQueuedPromptDrain(gatewaySessionId)
            }

            WsMethods.SESSION_INTERRUPT -> {
                _uiState.update {
                    it.copy(
                        isAgentTyping = false,
                    )
                }
                _streamingState.update { StreamingState() }
                streamingController.resetStreaming()
                addSystemMessage(getApplication<Application>().getString(R.string.chat_system_session_interrupted))
                _uiState.value.currentSessionId?.let(::loadSessionMessages)
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
                    addSystemMessage(getApplication<Application>().getString(R.string.chat_system_approval_submitted))
                }
            }

            WsMethods.CONFIG_SET -> {
                val map = result as? Map<*, *>
                if (map?.get("key") == "yolo") {
                    val enabled = map["value"]?.toString() == "1"
                    _uiState.update { it.copy(yoloEnabled = enabled, yoloUpdating = false) }
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
        val method = idToMethod.remove(id) ?: return
        val errorMsg =
            when (error) {
                is Map<*, *> -> error["message"] as? String ?: error.toString()
                else -> error.toString()
            }

        // Surface error in UI (these are server-pushed RpcError for
        // fire-and-forget RPCs — awaited RPCs handle their own failure
        // via the HermesWsClient.request() deferred).
        // A stale live gateway id is recoverable. Keep the durable session
        // selected, clear only the ephemeral binding, and retry a clean resume.
        if (method == WsMethods.SESSION_RESUME && errorMsg.contains("session not found", ignoreCase = true)) {
            val durableId = _uiState.value.currentSessionId
            _uiState.update { it.copy(gatewaySessionId = null, isLoading = false) }
            durableId?.let(::loadSessionMessages)
            loadSessions()
            return
        }

        if (method == WsMethods.CONFIG_SET) {
            _uiState.update {
                it.copy(
                    yoloUpdating = false,
                    errorMessage = getApplication<Application>().getString(R.string.yolo_update_failed),
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = "Error ($method): $errorMsg",
            )
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
        val state = _uiState.value
        val sessionId = state.currentSessionId ?: return

        val trimmed = text.trim()
        if (trimmed.startsWith("/", ignoreCase = true)) {
            handleSlashCommand(trimmed)
            return
        }

        if (state.isAgentTyping || state.gatewaySessionId == null) {
            queuePrompt(sessionId, text, state.pendingAttachments)
            clearAttachments()
            return
        }

        sendMessageNow(sessionId, state.gatewaySessionId, text)
    }

    private fun queuePrompt(
        sessionId: String,
        text: String,
        attachments: List<Attachment>,
    ) {
        val queued = QueuedPromptUi(text = text.trim(), attachments = attachments.toList())
        queuedPrompts[sessionId] = queued
        persistQueuedPrompt(sessionId, queued.text)
        _uiState.update { it.copy(queuedPrompt = queued) }
    }

    private fun queuedPromptFor(sessionId: String): QueuedPromptUi? =
        queuedPrompts[sessionId]
            ?: runCatching {
                AuthManager.serverStore
                    .getLatestState()
                    .sessionPreferences
                    .firstOrNull { it.sessionId == sessionId }
                    ?.queuedPromptText
            }.getOrNull()?.let { QueuedPromptUi(it) }

    private fun scheduleQueuedPromptDrain(eventSessionId: String?) {
        if (eventSessionId != null && !isCurrentSession(eventSessionId)) return
        viewModelScope.launch {
            delay(200L)
            drainQueuedPromptIfIdle()
        }
    }

    private fun drainQueuedPromptIfIdle() {
        val state = _uiState.value
        val sessionId = state.currentSessionId ?: return
        val gatewaySessionId = state.gatewaySessionId ?: return
        if (state.isAgentTyping) return
        val queued = queuedPrompts.remove(sessionId) ?: state.queuedPrompt ?: return
        persistQueuedPrompt(sessionId, null)
        _uiState.update { it.copy(queuedPrompt = null) }
        sendMessageNow(sessionId, gatewaySessionId, queued.text, queued.attachments)
    }

    private fun sendMessageNow(
        sessionId: String,
        gatewaySessionId: String,
        text: String,
        queuedAttachments: List<Attachment>? = null,
    ) {
        // Snapshot + clear attachments so the input bar empties immediately
        val attachments = queuedAttachments ?: _uiState.value.pendingAttachments.toList()
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
                val b64 = readContentUriBase64(attachment.uri)
                if (b64 == null) {
                    Log.w(TAG, "Skipping unreadable attachment: ${attachment.name}")
                    continue
                }

                try {
                    if (attachment.isImage) {
                        // Fire-and-forget — backend queues into session["attached_images"],
                        // auto-picked by the subsequent prompt.submit
                        wsClient.send(
                            method = WsMethods.IMAGE_ATTACH_BYTES,
                            params =
                                mapOf(
                                    "session_id" to gatewaySessionId,
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
                                    "session_id" to gatewaySessionId,
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
                gatewaySessionId,
                fullText,
                onSent = { id -> trackRequest(id, WsMethods.PROMPT_SUBMIT) },
            )
        }
    }

    /** Read and encode a `content://` or `file://` URI to Base64 via ContentResolver, avoiding large allocations. */
    private suspend fun readContentUriBase64(uriString: String): String? =
        try {
            val context = getApplication<Application>()
            val uri = uriString.toUri()
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ByteArrayOutputStream().use { encoded ->
                    Base64OutputStream(encoded, Base64.NO_WRAP).use { base64 ->
                        val buffer = ByteArray(1024 * 128) // 128KB chunk
                        var bytesRead: Int

                        while (stream.read(buffer).also { bytesRead = it } != -1) {
                            base64.write(buffer, 0, bytesRead)
                            yield() // Prevent blocking the thread during large reads
                        }
                    }
                    encoded.toString("UTF-8")
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to read and encode attachment: ${e.message}", e)
            null
        }

    /**
     * Send a JSON-RPC call and suspend until the response arrives, delegating
     * the deferred + 120s timeout to [HermesWsClient.request] (issue #526).
     * Throws [HermesWsClient.HermesRpcException] on RPC error, or
     * [kotlinx.coroutines.TimeoutCancellationException] if the server never
     * answers within the timeout.
     */
    private suspend fun sendRpcAndAwait(
        method: String,
        params: Map<String, Any>,
    ): Any? = HermesWsClient.request(method, params).await()

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
    ) = attachmentsDelegate.addAttachment(uri, name, mimeType, size)

    fun removeAttachment(index: Int) = attachmentsDelegate.removeAttachment(index)

    fun clearAttachments() = attachmentsDelegate.clearAttachments()

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
        val sessionId = activeGatewaySessionId()
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
        val sessionId = activeGatewaySessionId() ?: return
        _uiState.update { it.copy(isAgentTyping = true) }
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.sendMessage(
                sessionId,
                text,
                onSent = { id -> trackRequest(id, WsMethods.PROMPT_SUBMIT) },
            )
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
        val sessionId = activeGatewaySessionId() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.send(
                WsMethods.SESSION_INTERRUPT,
                mapOf("session_id" to sessionId),
                onSent = { id -> trackRequest(id, WsMethods.SESSION_INTERRUPT) },
            )
        }
    }

    fun createNewSession(setLoading: Boolean = true) {
        awaitingInitialSessionSelection = false
        _uiState.update {
            it.copy(
                isLoading = setLoading,
                currentSessionId = null,
                gatewaySessionId = null,
                messages = emptyList(),
                chatTitle = "Cassy",
                queuedPrompt = null,
                isAgentTyping = false,
            )
        }
        _streamingState.update { StreamingState() }
        streamingController.resetStreaming()
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
        viewModelScope.launch {
            loadCachedMessages(sessionId).join()
            loadSessionMessages(sessionId)
        }
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
            val jsonElement = map.toJsonElement()
            OkHttpProvider.json.decodeFromJsonElement<CommandCatalog>(jsonElement)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse command catalog", e)
            null
        }

    fun switchSession(sessionId: String) {
        if (sessionId == _uiState.value.currentSessionId && _uiState.value.gatewaySessionId != null) {
            refreshCurrentSession()
            return
        }

        // Reset streaming state
        streamingController.resetStreaming()
        _uiState.update {
            val title = it.sessions.find { s -> s.id == sessionId }?.title ?: "Cassy"
            it.copy(
                isLoading = true,
                currentSessionId = sessionId,
                gatewaySessionId = null,
                messages = emptyList(),
                chatTitle = title,
                showSessionPicker = false,
                isAgentTyping = false,
                queuedPrompt = queuedPromptFor(sessionId),
            )
        }
        // Mirror and persist the active session app-wide.
        ActiveSessionHolder.set(sessionId)
        persistActiveSession(sessionId)
        _streamingState.update { StreamingState() }
        viewModelScope.launch {
            // Step 1: Load cached messages first — instant display
            loadCachedMessages(sessionId).join()
            // Step 2: Resume session on server
            launch(Dispatchers.IO) {
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
    }

    private fun loadCachedMessages(sessionId: String): Job =
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

    private fun loadSessionMessages(sessionId: String) {
        viewModelScope.launch {
            if (!historyLoadsInFlight.add(sessionId)) return@launch
            try {
                val loadStartedAt = System.currentTimeMillis()
                val result =
                    withContext(Dispatchers.IO) {
                        safeApiCall { ApiClient.hermesApi.getSessionMessages(sessionId) }
                    }
                when (result) {
                    is NetworkResult.Success -> {
                        val chatMessages = SessionHistoryMapper.map(sessionId, result.data.messages.orEmpty())
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
                            val localOnly =
                                state.messages.filter { message ->
                                    !message.id.startsWith("rest-") && message.timestamp >= loadStartedAt
                                }
                            state.copy(
                                messages = chatMessages + localOnly,
                                isLoading = false,
                            )
                        }
                    }

                    is NetworkResult.Failure -> {
                        // Don't overwrite messages on REST failure — cached messages
                        // are still valid.
                        Log.w(TAG, "Session history refresh failed (${result.error::class.simpleName})")
                        val missingSession =
                            result.error.message.orEmpty().let { message ->
                                message.contains("404") || message.contains("session not found", ignoreCase = true)
                            }
                        _uiState.update {
                            if (it.currentSessionId != sessionId) return@update it
                            it.copy(
                                isLoading = false,
                                errorMessage =
                                    if (missingSession) {
                                        null
                                    } else {
                                        getApplication<Application>().getString(R.string.chat_error_history_load)
                                    },
                            )
                        }
                        if (missingSession) loadSessions()
                    }
                }
            } finally {
                historyLoadsInFlight.remove(sessionId)
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
        val durableSessionId = _uiState.value.currentSessionId ?: return
        val sessionId = activeGatewaySessionId() ?: return
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
            repo.persistMessage(userMessage, durableSessionId)
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

    fun clearBackgroundComplete() {
        _uiState.update { it.copy(backgroundCompleteMessage = null) }
    }

    fun setYoloEnabled(enabled: Boolean) {
        if (_uiState.value.yoloUpdating) return
        _uiState.update { it.copy(yoloUpdating = true) }
        viewModelScope.launch(Dispatchers.IO) {
            wsClient.send(
                method = WsMethods.CONFIG_SET,
                params =
                    mapOf(
                        "key" to "yolo",
                        "value" to if (enabled) "on" else "off",
                        "scope" to "global",
                    ),
                onSent = { id -> trackRequest(id, WsMethods.CONFIG_SET) },
            )
        }
    }

    fun shouldShowYoloWarning(): Boolean =
        runCatching { !AuthManager.serverStore.getLatestState().yoloWarningDismissed }.getOrDefault(true)

    fun dismissYoloWarningForever() {
        runCatching { AuthManager.serverStore.update { it.copy(yoloWarningDismissed = true) } }
    }

    fun cancelQueuedPrompt() {
        val sessionId = _uiState.value.currentSessionId ?: return
        queuedPrompts.remove(sessionId)
        persistQueuedPrompt(sessionId, null)
        _uiState.update { it.copy(queuedPrompt = null) }
    }

    fun takeQueuedPromptForEdit(): String? {
        val text = _uiState.value.queuedPrompt?.text ?: return null
        cancelQueuedPrompt()
        return text
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
        val sessionId = state.gatewaySessionId ?: return

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

    // ── Sudo / secret prompt flow (issue #524) ──────────────────────────

    /**
     * The agent needs the user's sudo password. Previously dropped → agent
     * hung forever. Now we surface a secure dialog and reply via sudo.respond.
     */
    private fun handleSudoRequest(event: WsEvent.SudoRequest) {
        _uiState.update {
            it.copy(
                sudoPrompt = SudoPromptUi(event.requestId, event.sessionId),
                isAgentTyping = false,
            )
        }
    }

    /**
     * The agent needs a secret value (token/password). Previously dropped →
     * agent hung forever. Now we surface a secure dialog and reply via
     * secret.respond.
     */
    private fun handleSecretRequest(event: WsEvent.SecretRequest) {
        _uiState.update {
            it.copy(
                secretPrompt = SecretPromptUi(event.requestId, event.sessionId),
                isAgentTyping = false,
            )
        }
    }

    fun dismissSudo() {
        _uiState.update { it.copy(sudoPrompt = null) }
    }

    fun dismissSecret() {
        _uiState.update { it.copy(secretPrompt = null) }
    }

    /**
     * Send the user's sudo password back to the gateway. Mirrors
     * respondToApproval: clear the prompt immediately, then fire the RPC.
     */
    fun respondToSudo(password: String) {
        val prompt = _uiState.value.sudoPrompt ?: return
        val sessionId = prompt.sessionId ?: activeGatewaySessionId() ?: return
        if (password.isBlank()) return

        _uiState.update { it.copy(sudoPrompt = null) }

        viewModelScope.launch(Dispatchers.IO) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "password" to password,
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            wsClient.send(
                method = WsMethods.SUDO_RESPOND,
                params = params,
                onSent = { id -> trackRequest(id, WsMethods.SUDO_RESPOND) },
            )
        }
    }

    /**
     * Send the user's secret value back to the gateway. Mirrors respondToSudo.
     */
    fun respondToSecret(value: String) {
        val prompt = _uiState.value.secretPrompt ?: return
        val sessionId = prompt.sessionId ?: activeGatewaySessionId() ?: return
        if (value.isBlank()) return

        _uiState.update { it.copy(secretPrompt = null) }

        viewModelScope.launch(Dispatchers.IO) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "value" to value,
                )
            prompt.requestId?.let { id -> params["request_id"] = id }
            wsClient.send(
                method = WsMethods.SECRET_RESPOND,
                params = params,
                onSent = { id -> trackRequest(id, WsMethods.SECRET_RESPOND) },
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
            wsClient.rejectAllPending()
            wsClient.disconnect()
        }
        viewModelScope.launch {
            delay(500)
            connectWebSocket(setLoading = true)
        }
    }

    fun relogin(
        username: String,
        password: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val host = AuthManager.getHost()
            val port = AuthManager.getPort()
            val baseUrl = "http://$host:$port"
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            val jsonBody =
                JSONObject()
                    .put("provider", "basic")
                    .put("username", username)
                    .put("password", password)
                    .put("next", "")
                    .toString()

            try {
                val loginClient =
                    com.m57.hermescontrol.data.remote.OkHttpProvider.probe
                        .newBuilder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                val loginReq =
                    Request
                        .Builder()
                        .url("$baseUrl/auth/password-login")
                        .header("Content-Type", "application/json")
                        .post(jsonBody.toRequestBody(jsonMediaType))
                        .build()
                loginClient.newCall(loginReq).execute().use { loginResp ->
                    if (!loginResp.isSuccessful) {
                        val msg =
                            when (loginResp.code) {
                                401 -> "Invalid username or password (401)"
                                403 -> "Forbidden (403)"
                                else -> "HTTP error code: ${loginResp.code}"
                            }
                        withContext(Dispatchers.Main) {
                            onResult(false, msg)
                        }
                        return@launch
                    }
                }

                val ticketClient =
                    com.m57.hermescontrol.data.remote.OkHttpProvider.base
                        .newBuilder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                val ticketReq =
                    Request
                        .Builder()
                        .url("$baseUrl/api/auth/ws-ticket")
                        .post("{}".toRequestBody(jsonMediaType))
                        .build()
                ticketClient.newCall(ticketReq).execute().use { ticketResp ->
                    if (!ticketResp.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            onResult(false, "Failed to mint WS ticket: HTTP ${ticketResp.code}")
                        }
                        return@launch
                    }

                    val body = ticketResp.body.string()
                    val ticket = JSONObject(body).optString("ticket").takeIf { it.isNotBlank() }

                    if (ticket.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            onResult(false, "Invalid ticket returned from server")
                        }
                        return@launch
                    }

                    AuthManager.setWsAuthParam("ticket")
                    AuthManager.setToken(ticket)

                    withContext(Dispatchers.Main) {
                        onResult(true, null)
                        reconnect()
                    }
                }
            } catch (e: java.io.IOException) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Connection failed: ${e.message}")
                }
            } catch (e: org.json.JSONException) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Connection failed: ${e.message}")
                }
            }
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

    /** Maps an in-flight RPC id → its method, purely for UI error labeling. The deferred/timeout lives in [HermesWsClient.request] (issue #526). */
    private val idToMethod = ConcurrentHashMap<String, String>()

    private fun trackRequest(
        id: String,
        method: String,
    ) {
        idToMethod[id] = method
    }

    private fun activeGatewaySessionId(): String? = _uiState.value.gatewaySessionId ?: _uiState.value.currentSessionId

    private fun persistQueuedPrompt(
        sessionId: String,
        text: String?,
    ) {
        runCatching { AuthManager.serverStore.update { it.setQueuedPrompt(sessionId, text) } }
    }

    private fun startLiveSyncLoop() {
        viewModelScope.launch {
            var tick = 0
            while (isActive) {
                delay(1_500L)
                if (wsClient.connectionStatus.value != ConnectionStatus.CONNECTED) continue
                tick++
                if (tick % 2 == 0) loadSessions()
                if (tick % 2 == 0) refreshLiveRunState()
                val durableId = _uiState.value.currentSessionId
                val historyReconciliationDue =
                    if (_uiState.value.isAgentTyping) tick % 4 == 0 else tick % 10 == 0
                if (durableId != null && historyReconciliationDue) {
                    loadSessionMessages(durableId)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun refreshLiveRunState() {
        val state = _uiState.value
        val durableId = state.currentSessionId ?: return
        val gatewayId = state.gatewaySessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result =
                runCatching {
                    wsClient
                        .request(
                            method = WsMethods.SESSION_ACTIVE_LIST,
                            params = mapOf("current_session_id" to gatewayId),
                            timeoutMs = 5_000L,
                        ).await() as? Map<String, Any?>
                }.getOrNull() ?: return@launch
            val rows = result["sessions"] as? List<Map<String, Any?>> ?: return@launch
            val row =
                rows.firstOrNull { item ->
                    item["id"] == gatewayId || item["session_id"] == gatewayId || item["session_key"] == durableId
                }
            val running =
                row?.get("running") as? Boolean
                    ?: (row?.get("status") as? String)?.let { it == "streaming" || it == "running" }
                    ?: false
            val wasRunning = _uiState.value.isAgentTyping
            _uiState.update { current ->
                if (current.currentSessionId == durableId) current.copy(isAgentTyping = running) else current
            }
            if (wasRunning && !running) {
                loadSessionMessages(durableId)
                withContext(Dispatchers.Main) { drainQueuedPromptIfIdle() }
            }
        }
    }

    // ── Search ────────────────────────────────────────────────────────────
    // Compatibility façade: stable public API around ChatSearchDelegate.
    // These thin delegates keep ChatViewModel's public surface intact while
    // the search logic now lives in the delegate. Safe to remove once all
    // callers migrate directly to the delegate.

    fun toggleSearch() = searchDelegate.toggleSearch()

    fun setSearchQuery(query: String) = searchDelegate.setSearchQuery(query)

    fun navigateSearchMatch(direction: Int) = searchDelegate.navigateSearchMatch(direction)

    fun clearSearch() = searchDelegate.clearSearch()

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
        // PERF-16: Don't disconnect the global HermesWsClient singleton when
        // leaving the Chat screen — it's used by background notification reply.
    }

    companion object {
    }
}

private fun WsEvent.sessionIdOrNull(): String? =
    when (this) {
        is WsEvent.MessageStart -> sessionId
        is WsEvent.MessageToken -> sessionId
        is WsEvent.ThinkingDelta -> sessionId
        is WsEvent.ReasoningDelta -> sessionId
        is WsEvent.ReasoningAvailable -> sessionId
        is WsEvent.MessageComplete -> sessionId
        is WsEvent.MessageDone -> sessionId
        is WsEvent.ToolStart -> sessionId
        is WsEvent.ToolComplete -> sessionId
        is WsEvent.ClarifyRequest -> sessionId
        is WsEvent.ApprovalRequest -> sessionId
        is WsEvent.SudoRequest -> sessionId
        is WsEvent.SecretRequest -> sessionId
        else -> null
    }
