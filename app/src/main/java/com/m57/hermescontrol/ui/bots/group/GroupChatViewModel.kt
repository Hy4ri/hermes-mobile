package com.m57.hermescontrol.ui.bots.group

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class GroupChatUiState(
    val groupName: String = "",
    val members: List<ProfileInfo> = emptyList(),
    val messages: List<GroupChatMessage> = emptyList(),
    val activeSpeaker: String? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

class GroupChatViewModel(
    private val groupName: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(GroupChatUiState(groupName = groupName))
    val uiState: StateFlow<GroupChatUiState> = _uiState.asStateFlow()

    // Map of bot.name -> active runtime session_id in this group
    private val memberSessions = mutableMapOf<String, String>()

    // In-flight turn completion deferreds keyed by session_id
    private val inFlightTurns = mutableMapOf<String, CompletableDeferred<String>>()

    init {
        loadGroup()
        observeWsEvents()
    }

    private fun observeWsEvents() {
        viewModelScope.launch(ioDispatcher) {
            HermesWsClient.events.collect { event ->
                when (event) {
                    is WsEvent.MessageComplete -> {
                        val sid = event.sessionId?.trim('\"', ' ')
                        if (sid != null && inFlightTurns.containsKey(sid)) {
                            Log.d("GroupChatViewModel", "MessageComplete for session $sid: '${event.text.take(50)}'")
                            inFlightTurns[sid]?.complete(event.text)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun loadGroup() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = safeApiCall { ApiClient.hermesApi.getProfiles() }
            if (result is NetworkResult.Success) {
                val allProfiles = result.data.profiles
                var groupMembers =
                    allProfiles.filter {
                        val botGroups = it.botMeta()?.groups.orEmpty()
                        botGroups.contains(groupName) || botGroups.any { g -> g.equals(groupName, ignoreCase = true) }
                    }

                // Fallback: If group name was composed from bot names (e.g. "default, scoutbot")
                if (groupMembers.isEmpty() && groupName.contains(",")) {
                    val targetNames = groupName.split(",").map { it.trim().lowercase() }
                    groupMembers = allProfiles.filter { targetNames.contains(it.name.lowercase()) }
                }

                // Final safety fallback: If 0 members found, use all non-hidden bots
                if (groupMembers.isEmpty()) {
                    groupMembers = allProfiles.filter { !it.isHidden }
                }

                Log.d(
                    "GroupChatViewModel",
                    "Resolved ${groupMembers.size} members for group '$groupName': ${groupMembers.map { it.name }}",
                )
                _uiState.update {
                    it.copy(
                        members = groupMembers,
                        isLoading = false,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load group members",
                    )
                }
            }
        }
    }

    private suspend fun ensureMemberSession(bot: ProfileInfo): String? {
        val cached = memberSessions[bot.name]
        if (!cached.isNullOrBlank()) {
            return cached
        }

        val title = "Group: $groupName"
        try {
            val createParams =
                buildMap<String, Any> {
                    put("profile", bot.name)
                    put("title", title)
                    put("source", "desktop")
                    put("hidden", true)
                }
            val deferred = HermesWsClient.request(WsMethods.SESSION_CREATE, createParams)
            val res = deferred.await()
            val sid = extractSessionId(res)
            if (!sid.isNullOrBlank()) {
                memberSessions[bot.name] = sid
                return sid
            }
        } catch (e: Exception) {
            Log.w("GroupChatViewModel", "session.create failed for ${bot.name}: ${e.message}")
        }
        return null
    }

    fun sendMessage(rawText: String) {
        val text = rawText.trim()
        Log.d("GroupChatViewModel", "sendMessage called with: '$text'")
        if (text.isBlank()) return

        val members = _uiState.value.members
        if (members.isEmpty()) {
            Log.w("GroupChatViewModel", "sendMessage: No members in group, ignoring")
            return
        }

        val userMessage =
            GroupChatMessage(
                id = UUID.randomUUID().toString(),
                senderName = "user",
                senderDisplayName = "You",
                isUser = true,
                text = text,
            )

        _uiState.update {
            it.copy(messages = it.messages + userMessage)
        }

        viewModelScope.launch(ioDispatcher) {
            val responders = GroupChatMentions.resolveResponders(text, members)
            Log.d("GroupChatViewModel", "Responders: ${responders.map { it.name }}")

            for (bot in responders) {
                _uiState.update { it.copy(activeSpeaker = bot.effectiveTitle) }
                val prompt =
                    GroupChatMentions.buildTurnPrompt(
                        groupName = groupName,
                        viewer = bot,
                        peers = members.filter { it.name != bot.name },
                        recentLog = _uiState.value.messages,
                    )

                try {
                    val sessionId = ensureMemberSession(bot)
                    if (sessionId.isNullOrBlank()) {
                        Log.w("GroupChatViewModel", "Could not obtain sessionId for ${bot.name}")
                        continue
                    }

                    // Set ActiveSessionHolder so the active WebSocket context matches this turn
                    ActiveSessionHolder.set(sessionId, sessionId)

                    // Register in-flight listener
                    val turnDeferred = CompletableDeferred<String>()
                    inFlightTurns[sessionId] = turnDeferred

                    // Submit prompt via sendMessage
                    HermesWsClient.sendMessage(
                        sessionId = sessionId,
                        text = prompt,
                    )

                    // Await event or polling fallback
                    var replyText =
                        withTimeoutOrNull(45_000L) {
                            turnDeferred.await()
                        }
                    inFlightTurns.remove(sessionId)

                    // Polling fallback if event dropped
                    if (replyText == null) {
                        try {
                            val state =
                                HermesWsClient.request(
                                    WsMethods.SESSION_RESUME,
                                    mapOf("session_id" to sessionId, "profile" to bot.name),
                                ).await()
                            val msgs = extractMessageList(state)
                            replyText = pickAssistantReply(msgs)
                        } catch (_: Exception) {
                        }
                    }

                    if (replyText != null && !isPass(replyText)) {
                        val botMessage =
                            GroupChatMessage(
                                id = UUID.randomUUID().toString(),
                                senderName = bot.name,
                                senderDisplayName = bot.effectiveTitle,
                                isUser = false,
                                avatarMeta = bot.botMeta()?.avatar,
                                text = replyText,
                            )
                        _uiState.update { it.copy(messages = it.messages + botMessage) }
                    }
                } catch (e: Exception) {
                    Log.w("GroupChatViewModel", "Turn failed for ${bot.name}: ${e.message}")
                }
            }
            _uiState.update { it.copy(activeSpeaker = null) }
        }
    }

    private fun isPass(text: String): Boolean {
        val clean = text.trim().lowercase()
        return clean == "(pass)" || clean == "pass"
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractMessageList(response: Any?): List<Map<String, Any?>> {
        if (response is Map<*, *>) {
            val list = response["messages"] as? List<*>
            return list?.filterIsInstance<Map<String, Any?>>().orEmpty()
        }
        return emptyList()
    }

    private fun pickAssistantReply(messages: List<Map<String, Any?>>): String? {
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            val role = msg["role"]?.toString()
            if (role == "assistant") {
                val content = msg["content"] ?: msg["text"]
                if (content != null) {
                    val trimmed = content.toString().trim()
                    if (trimmed.isNotBlank()) return trimmed
                }
            }
        }
        return null
    }

    private fun extractSessionId(response: Any?): String? {
        if (response is Map<*, *>) {
            val raw =
                response["session_id"]
                    ?: response["stored_session_id"]
                    ?: (response["result"] as? Map<*, *>)?.get("session_id")
            if (raw != null) {
                return raw.toString().trim('\"', ' ')
            }
        }
        return null
    }
}
