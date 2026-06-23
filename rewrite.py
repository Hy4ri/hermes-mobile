import re

with open('app/src/main/java/com/m57/hermescontrol/ui/chat/ChatViewModel.kt', 'r') as f:
    cvm = f.read()

# 1. ChatSearchController
with open('app/src/main/java/com/m57/hermescontrol/ui/chat/ChatSearchController.kt', 'w') as f:
    f.write('''package com.m57.hermescontrol.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatSearchController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatUiState>,
) {
    private var searchJob: Job? = null

    fun toggleSearch() {
        val current = state.value
        if (current.isSearchActive) {
            clearSearch()
        } else {
            state.update { it.copy(isSearchActive = true, searchQuery = "") }
        }
    }

    fun setSearchQuery(query: String) {
        searchJob?.cancel()

        if (query.isBlank()) {
            state.update {
                it.copy(
                    searchQuery = query,
                    searchMatchIndices = emptyList(),
                    currentSearchMatchIndex = -1,
                )
            }
            return
        }

        // Keep local state in sync immediately so UI feels responsive.
        state.update {
            it.copy(searchQuery = query)
        }

        searchJob =
            scope.launch(Dispatchers.Default) {
                val messages = state.value.messages
                val indices =
                    messages.indices.filter { idx ->
                        messages[idx].content.contains(query, ignoreCase = true)
                    }

                state.update {
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
        state.update { currentState ->
            val indices = currentState.searchMatchIndices
            if (indices.isEmpty()) return@update currentState
            val current = currentState.currentSearchMatchIndex
            val newIdx =
                when (direction) {
                    1 -> if (current >= indices.lastIndex) 0 else current + 1
                    -1 -> if (current <= 0) indices.lastIndex else current - 1
                    else -> current
                }
            currentState.copy(currentSearchMatchIndex = newIdx)
        }
    }

    fun clearSearch() {
        state.update {
            it.copy(
                isSearchActive = false,
                searchQuery = "",
                searchMatchIndices = emptyList(),
                currentSearchMatchIndex = -1,
            )
        }
    }
}
''')

# Replace toggleSearch, setSearchQuery, navigateSearchMatch, clearSearch in ChatViewModel
cvm = re.sub(r'    fun toggleSearch\(\) \{\s+val current = _uiState.value\s+if \(current.isSearchActive\) \{\s+clearSearch\(\)\s+\} else \{\s+_uiState.update \{ it.copy\(isSearchActive = true, searchQuery = ""\) \}\s+\}\s+\}', '    fun toggleSearch() = searchController.toggleSearch()', cvm)
cvm = re.sub(r'    private var searchJob: Job\? = null\s+fun setSearchQuery\(query: String\) \{.*?\n    \}', '    fun setSearchQuery(query: String) = searchController.setSearchQuery(query)', cvm, flags=re.DOTALL)
cvm = re.sub(r'    fun navigateSearchMatch\(direction: Int\) \{.*?\n    \}', '    fun navigateSearchMatch(direction: Int) = searchController.navigateSearchMatch(direction)', cvm, flags=re.DOTALL)
cvm = re.sub(r'    fun clearSearch\(\) \{.*?\n    \}', '    fun clearSearch() = searchController.clearSearch()', cvm, flags=re.DOTALL)

# Add searchController to ChatViewModel
cvm = cvm.replace('''    private val dao = HermesDatabase.get(application).chatMessageDao()''', '''    private val dao = HermesDatabase.get(application).chatMessageDao()
    private val searchController = ChatSearchController(viewModelScope, _uiState)''')


# 2. SlashCommandDispatcher
with open('app/src/main/java/com/m57/hermescontrol/ui/chat/SlashCommandDispatcher.kt', 'w') as f:
    f.write('''package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SlashCommandDispatcher(
    private val scope: CoroutineScope,
    private val onInterrupt: () -> Unit,
    private val onNewSession: () -> Unit,
    private val addAssistantMessage: (String) -> Unit,
) {
    fun dispatch(command: String) {
        val parts = command.split(" ")
        val cmd = parts[0].lowercase()

        when (cmd) {
            "/stop", "/interrupt" -> {
                onInterrupt()
            }

            "/new" -> {
                onNewSession()
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
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getStatus() }
                        }
                    when (result) {
                        is NetworkResult.Success -> {
                            val body = result.data
                            val platformsStr =
                                body.gateway_platforms?.entries?.joinToString("\\n") { (k, v) ->
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
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getSessions() }
                        }
                    when (result) {
                        is NetworkResult.Success -> {
                            val body = result.data
                            val sessionsStr =
                                body.sessions.joinToString("\\n") { s ->
                                    "• **${s.title ?: "Untitled"}** (ID: `${s.id}`, Messages: ${s.message_count ?: 0})"
                                }
                            addAssistantMessage("**Sessions List:**\\n$sessionsStr")
                        }

                        is NetworkResult.Failure -> {
                            addAssistantMessage("Failed to list sessions: ${result.error.message}")
                        }
                    }
                }
            }

            "/stats", "/system" -> {
                scope.launch {
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
}
''')

# Add SlashCommandDispatcher to ChatViewModel
cvm = cvm.replace('''    private val searchController = ChatSearchController(viewModelScope, _uiState)''', '''    private val searchController = ChatSearchController(viewModelScope, _uiState)
    private val slashCommandDispatcher = SlashCommandDispatcher(
        scope = viewModelScope,
        onInterrupt = { interruptSession() },
        onNewSession = { createNewSession() },
        addAssistantMessage = { text -> addAssistantMessage(text) }
    )''')


cvm = re.sub(r'    private fun handleSlashCommand\(command: String\) \{.*?\n    \}\n\n    private fun addAssistantMessage', '    private fun addAssistantMessage', cvm, flags=re.DOTALL)


cvm = re.sub(r'''        if \(trimmed\.startsWith\("/", ignoreCase = true\)\) \{
            handleSlashCommand\(trimmed\)
            return
        \}''', '''        if (trimmed.startsWith("/", ignoreCase = true)) {
            val userMsg = ChatMessage(role = MessageRole.USER, content = trimmed)
            val sessionId = _uiState.value.currentSessionId

            _uiState.update { state ->
                state.copy(
                    messages = state.messages + userMsg,
                    isAgentTyping = true,
                )
            }

            if (sessionId != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    dao.upsert(userMsg.toEntity(sessionId))
                }
            }
            slashCommandDispatcher.dispatch(trimmed)
            _uiState.update { it.copy(isAgentTyping = false) }
            return
        }''', cvm)

# 3. ChatPersistenceRepository
with open('app/src/main/java/com/m57/hermescontrol/ui/chat/ChatPersistenceRepository.kt', 'w') as f:
    f.write('''package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.ChatMessageDao
import com.m57.hermescontrol.data.local.toEntity
import com.m57.hermescontrol.data.local.toUiModel
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatPersistenceRepository(
    private val dao: ChatMessageDao,
) {
    suspend fun loadCachedMessages(sessionId: String): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            dao.getMessagesForSession(sessionId).map { it.toUiModel() }
        }
    }

    suspend fun saveMessage(
        message: ChatMessage,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            dao.upsert(message.toEntity(sessionId))
        }
    }

    suspend fun saveMessages(
        messages: List<ChatMessage>,
        sessionId: String,
    ) {
        withContext(Dispatchers.IO) {
            dao.upsertAll(messages.map { it.toEntity(sessionId) })
        }
    }

    suspend fun fetchSessionMessages(sessionId: String): NetworkResult<List<ChatMessage>> {
        val result =
            withContext(Dispatchers.IO) {
                safeApiCall { ApiClient.hermesApi.getSessionMessages(sessionId) }
            }

        return when (result) {
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
                        val stableId = "rest-$sessionId-$index"
                        val ts = msg.timestamp?.toLongOrNull() ?: System.currentTimeMillis()
                        ChatMessage(
                            id = stableId,
                            role = role,
                            content = msg.content.orEmpty(),
                            timestamp = ts,
                            isStreaming = false,
                        )
                    }

                NetworkResult.Success(chatMessages)
            }
            is NetworkResult.Failure -> {
                NetworkResult.Failure(result.error)
            }
        }
    }
}
''')

cvm = cvm.replace('''    private val dao = HermesDatabase.get(application).chatMessageDao()
    private val searchController = ChatSearchController(viewModelScope, _uiState)''', '''    private val dao = HermesDatabase.get(application).chatMessageDao()
    private val persistenceRepository = ChatPersistenceRepository(dao)
    private val searchController = ChatSearchController(viewModelScope, _uiState)''')

cvm = cvm.replace('dao.upsert(userMsg.toEntity(sessionId))', 'persistenceRepository.saveMessage(userMsg, sessionId)')
cvm = cvm.replace('dao.upsert(msg.toEntity(sessionId))', 'persistenceRepository.saveMessage(msg, sessionId)')
cvm = cvm.replace('dao.upsert(userMessage.toEntity(sessionId))', 'persistenceRepository.saveMessage(userMessage, sessionId)')
cvm = cvm.replace('dao.upsert(msgToPersist.toEntity(sid))', 'persistenceRepository.saveMessage(msgToPersist, sid)')
cvm = cvm.replace('dao.upsert(orphan.toEntity(sessionId))', 'persistenceRepository.saveMessage(orphan, sessionId)')


cvm = re.sub(r'                    val entities = chatMessages\.map \{ it\.toEntity\(sessionId\) \}\n                    withContext\(Dispatchers\.IO\) \{\n                        dao\.upsertAll\(entities\)\n                    \}', '                    persistenceRepository.saveMessages(chatMessages, sessionId)', cvm)


# 4. ChatWsEventReducer
with open('app/src/main/java/com/m57/hermescontrol/ui/chat/ChatWsEventReducer.kt', 'w') as f:
    f.write('''package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent

data class ReducerResult(
    val state: ChatUiState,
    val messageToPersist: ChatMessage? = null,
    val sessionIdToPersist: String? = null,
    val reloadSessions: Boolean = false,
    val trackRpcResultId: String? = null,
    val trackRpcErrorId: String? = null,
)

class ChatWsEventReducer {

    private fun isCurrentSession(state: ChatUiState, eventSessionId: String?): Boolean {
        if (eventSessionId == null) return true
        return eventSessionId == state.currentSessionId
    }

    fun reduce(state: ChatUiState, event: WsEvent, streamingMessageId: String?): Pair<ReducerResult, String?> {
        var currentStreamingMessageId = streamingMessageId

        when (event) {
            is WsEvent.GatewayReady -> {
                return Pair(ReducerResult(state), currentStreamingMessageId)
            }
            is WsEvent.SessionInfo -> {
                return Pair(ReducerResult(state), currentStreamingMessageId)
            }
            is WsEvent.MessageStart -> {
                if (!isCurrentSession(state, event.sessionId)) return Pair(ReducerResult(state), currentStreamingMessageId)

                var orphanToPersist: ChatMessage? = null
                val sessionId = state.currentSessionId

                val msg = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "",
                    isStreaming = true,
                )
                currentStreamingMessageId = msg.id

                val messages = state.messages.toMutableList()
                val existing = state.streamingMessage
                if (existing != null && existing.content.isNotEmpty()) {
                    val finalized = existing.copy(isStreaming = false)
                    messages.add(finalized)
                    orphanToPersist = finalized
                }

                val newState = state.copy(
                    messages = messages,
                    isAgentTyping = true,
                    streamingMessage = msg,
                    isThinking = false,
                    thinkingText = "",
                )

                return Pair(ReducerResult(newState, messageToPersist = orphanToPersist, sessionIdToPersist = sessionId), currentStreamingMessageId)
            }
            is WsEvent.MessageToken -> {
                if (!isCurrentSession(state, event.sessionId)) return Pair(ReducerResult(state), currentStreamingMessageId)

                val current = state.streamingMessage
                if (current != null) {
                    val newState = state.copy(
                        streamingMessage = current.copy(
                            content = current.content + event.token,
                        ),
                        isThinking = false,
                    )
                    return Pair(ReducerResult(newState), currentStreamingMessageId)
                } else {
                    val msg = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = event.token,
                        isStreaming = true,
                    )
                    currentStreamingMessageId = msg.id
                    val newState = state.copy(
                        streamingMessage = msg,
                        isAgentTyping = true,
                        isThinking = false,
                    )
                    return Pair(ReducerResult(newState), currentStreamingMessageId)
                }
            }
            is WsEvent.ThinkingDelta -> {
                if (!isCurrentSession(state, event.sessionId)) return Pair(ReducerResult(state), currentStreamingMessageId)
                val newState = state.copy(
                    isThinking = true,
                    thinkingText = state.thinkingText + event.token,
                )
                return Pair(ReducerResult(newState), currentStreamingMessageId)
            }
            is WsEvent.MessageComplete -> {
                if (!isCurrentSession(state, event.sessionId)) return Pair(ReducerResult(state), currentStreamingMessageId)

                val streaming = state.streamingMessage
                val msg = if (streaming != null) {
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

                val sessionId = state.currentSessionId
                val newState = state.copy(
                    messages = state.messages + msg,
                    streamingMessage = null,
                    isAgentTyping = false,
                    isThinking = false,
                    thinkingText = "",
                )
                currentStreamingMessageId = null

                return Pair(ReducerResult(newState, messageToPersist = msg, sessionIdToPersist = sessionId, reloadSessions = true), currentStreamingMessageId)
            }
            is WsEvent.MessageDone -> {
                if (!isCurrentSession(state, event.sessionId)) return Pair(ReducerResult(state), currentStreamingMessageId)

                val streaming = state.streamingMessage
                val msg = streaming?.copy(isStreaming = false)
                val sessionId = state.currentSessionId

                val newState = if (msg != null) {
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
                currentStreamingMessageId = null

                return Pair(ReducerResult(newState, messageToPersist = msg, sessionIdToPersist = sessionId), currentStreamingMessageId)
            }
            is WsEvent.ToolStart -> {
                if (!isCurrentSession(state, event.sessionId)) return Pair(ReducerResult(state), currentStreamingMessageId)

                var orphanToPersist: ChatMessage? = null
                val messages = state.messages.toMutableList()
                val existing = state.streamingMessage
                if (existing != null && existing.content.isNotEmpty()) {
                    val finalized = existing.copy(isStreaming = false)
                    messages.add(finalized)
                    orphanToPersist = finalized
                }
                currentStreamingMessageId = null

                val msg = ChatMessage(
                    role = MessageRole.TOOL,
                    content = "Tool execution: ${event.name}\\nArgs: ${com.google.gson.Gson().toJson(event.data)}",
                    isStreaming = false,
                )
                messages.add(msg)

                val sessionId = state.currentSessionId
                val newState = state.copy(
                    messages = messages,
                    isAgentTyping = true,
                    streamingMessage = null,
                    isThinking = false,
                    thinkingText = "",
                )
                return Pair(ReducerResult(newState, messageToPersist = msg, sessionIdToPersist = sessionId), currentStreamingMessageId)
            }
            is WsEvent.ToolComplete -> {
                if (!isCurrentSession(state, event.sessionId)) return Pair(ReducerResult(state), currentStreamingMessageId)
                val messages = state.messages.toMutableList()
                if (messages.isNotEmpty() && messages.last().role == MessageRole.TOOL) {
                    messages[messages.lastIndex] = messages.last().copy(
                        content = com.google.gson.Gson().toJson(event.data),
                        toolStatus = ToolStatus.COMPLETED,
                    )
                } else {
                    messages.add(ChatMessage(
                        role = MessageRole.TOOL,
                        content = com.google.gson.Gson().toJson(event.data),
                        isStreaming = false,
                        toolStatus = ToolStatus.COMPLETED,
                    ))
                }
                val msg = messages.last()

                val sessionId = state.currentSessionId
                val newState = state.copy(
                    messages = messages,
                )
                return Pair(ReducerResult(newState, messageToPersist = msg, sessionIdToPersist = sessionId), currentStreamingMessageId)
            }
            is WsEvent.ClarifyRequest -> {
                if (!isCurrentSession(state, event.sessionId)) return Pair(ReducerResult(state), currentStreamingMessageId)
                val newState = state.copy(
                    clarifyRequest = ClarifyUi(
                        text = event.text.orEmpty(),
                        options = event.options.orEmpty(),
                        clarifyId = event.clarifyId,
                    ),
                )
                return Pair(ReducerResult(newState), currentStreamingMessageId)
            }
            is WsEvent.StatusUpdate -> {
                return Pair(ReducerResult(state), currentStreamingMessageId)
            }
            is WsEvent.SessionUpdated -> {
                return Pair(ReducerResult(state, reloadSessions = true), currentStreamingMessageId)
            }
            is WsEvent.RpcResult -> {
                return Pair(ReducerResult(state, trackRpcResultId = event.id), currentStreamingMessageId)
            }
            is WsEvent.RpcError -> {
                return Pair(ReducerResult(state, trackRpcErrorId = event.id), currentStreamingMessageId)
            }
            is WsEvent.Unknown -> {
                return Pair(ReducerResult(state), currentStreamingMessageId)
            }
        }
    }
}
''')


cvm = cvm.replace('''    private val persistenceRepository = ChatPersistenceRepository(dao)
    private val searchController = ChatSearchController(viewModelScope, _uiState)
    private val slashCommandDispatcher = SlashCommandDispatcher(
        scope = viewModelScope,
        onInterrupt = { interruptSession() },
        onNewSession = { createNewSession() },
        addAssistantMessage = { text -> addAssistantMessage(text) }
    )''', '''    private val persistenceRepository = ChatPersistenceRepository(dao)
    private val searchController = ChatSearchController(viewModelScope, _uiState)
    private val slashCommandDispatcher = SlashCommandDispatcher(
        scope = viewModelScope,
        onInterrupt = { interruptSession() },
        onNewSession = { createNewSession() },
        addAssistantMessage = { text -> addAssistantMessage(text) }
    )
    private val wsEventReducer = ChatWsEventReducer()''')


cvm = re.sub(r'    private fun isCurrentSession\(eventSessionId: String\?\): Boolean \{.*?\n    \}\n', '', cvm, flags=re.DOTALL)
cvm = re.sub(r'    private fun handleMessageStart\(event: WsEvent\.MessageStart\) \{.*?\n    \}\n', '', cvm, flags=re.DOTALL)
cvm = re.sub(r'    private fun handleMessageToken\(event: WsEvent\.MessageToken\) \{.*?\n    \}\n', '', cvm, flags=re.DOTALL)
cvm = re.sub(r'    private fun handleThinkingDelta\(event: WsEvent\.ThinkingDelta\) \{.*?\n    \}\n', '', cvm, flags=re.DOTALL)
cvm = re.sub(r'    private fun handleMessageComplete\(event: WsEvent\.MessageComplete\) \{.*?\n    \}\n', '', cvm, flags=re.DOTALL)
cvm = re.sub(r'    private fun handleMessageDone\(event: WsEvent\.MessageDone\) \{.*?\n    \}\n', '', cvm, flags=re.DOTALL)
cvm = re.sub(r'    private fun handleToolStart\(event: WsEvent\.ToolStart\) \{.*?\n    \}\n', '', cvm, flags=re.DOTALL)
cvm = re.sub(r'    private fun handleToolComplete\(event: WsEvent\.ToolComplete\) \{.*?\n    \}\n', '', cvm, flags=re.DOTALL)


cvm = re.sub(r'''    private fun handleWsEvent\(event: WsEvent\) \{
        when \(event\) \{
            is WsEvent\.GatewayReady -> \{
                _uiState\.update \{ it\.copy\(isLoading = false\) \}
                addSystemMessage\("Connected to Hermes"\)
                loadSessions\(\)
                if \(_uiState\.value\.currentSessionId == null\) \{
                    val initial = initialSessionId
                    if \(!initial\.isNullOrBlank\(\)\) \{
                        initialSessionId = null
                        switchSession\(initial\)
                    \} else \{
                        createNewSession\(\)
                    \}
                \}
            \}

            is WsEvent\.SessionInfo -> \{\}

            is WsEvent\.MessageStart -> \{
                handleMessageStart\(event\)
            \}

            is WsEvent\.MessageToken -> \{
                handleMessageToken\(event\)
            \}

            is WsEvent\.ThinkingDelta -> \{
                handleThinkingDelta\(event\)
            \}

            is WsEvent\.MessageComplete -> \{
                handleMessageComplete\(event\)
            \}

            is WsEvent\.MessageDone -> \{
                handleMessageDone\(event\)
            \}

            is WsEvent\.ToolStart -> \{
                handleToolStart\(event\)
            \}

            is WsEvent\.ToolComplete -> \{
                handleToolComplete\(event\)
            \}

            is WsEvent\.ClarifyRequest -> \{
                if \(!isCurrentSession\(event\.sessionId\)\) return
                _uiState\.update \{
                    it\.copy\(
                        clarifyRequest =
                            ClarifyUi\(
                                text = event\.text\.orEmpty\(\),
                                options = event\.options\.orEmpty\(\),
                                clarifyId = event\.clarifyId,
                            \),
                    \)
                \}
            \}

            is WsEvent\.StatusUpdate -> \{\}

            is WsEvent\.SessionUpdated -> \{
                loadSessions\(\)
            \}

            is WsEvent\.RpcResult -> \{
                handleRpcResult\(event\.id, event\.result\)
            \}

            is WsEvent\.RpcError -> \{
                handleRpcError\(event\.id, event\.error\)
            \}

            is WsEvent\.Unknown -> \{\}
        \}
    \}''', '''    private fun handleWsEvent(event: WsEvent) {
        val (reducerResult, newStreamingId) = wsEventReducer.reduce(_uiState.value, event, streamingMessageId)
        streamingMessageId = newStreamingId
        _uiState.value = reducerResult.state

        reducerResult.messageToPersist?.let { msg ->
            val sid = reducerResult.sessionIdToPersist ?: _uiState.value.currentSessionId
            if (sid != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    persistenceRepository.saveMessage(msg, sid)
                }
            }
        }

        if (reducerResult.reloadSessions) {
            loadSessions()
        }

        reducerResult.trackRpcResultId?.let { id ->
            handleRpcResult(id, (event as WsEvent.RpcResult).result)
        }

        reducerResult.trackRpcErrorId?.let { id ->
            handleRpcError(id, (event as WsEvent.RpcError).error)
        }

        when(event) {
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
            else -> {}
        }
    }''', cvm)

with open('app/src/main/java/com/m57/hermescontrol/ui/chat/ChatViewModel.kt', 'w') as f:
    f.write(cvm)
