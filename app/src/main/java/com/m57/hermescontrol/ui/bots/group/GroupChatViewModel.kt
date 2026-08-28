package com.m57.hermescontrol.ui.bots.group

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.GroupChatRoomMeta
import com.m57.hermescontrol.data.model.GroupChatSyncFrom
import com.m57.hermescontrol.data.model.GroupChatSyncLogEntry
import com.m57.hermescontrol.data.model.GroupChatSyncSnapshot
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.toJsonElement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

data class GroupChatUiState(
    val groupName: String = "",
    val members: List<ProfileInfo> = emptyList(),
    val messages: List<GroupChatMessage> = emptyList(),
    val activeSpeaker: String? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

data class MemberSession(
    val runtimeSessionId: String,
    val storedSessionId: String? = null,
)

class GroupChatViewModel(
    private var groupName: String = "",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ViewModel() {
    private val _uiState = MutableStateFlow(GroupChatUiState(groupName = groupName))
    val uiState: StateFlow<GroupChatUiState> = _uiState.asStateFlow()

    // Map of bot.name -> active session info in this group
    private val memberSessions = mutableMapOf<String, MemberSession>()

    // In-flight turn completion deferreds keyed by session_id
    private val inFlightTurns = mutableMapOf<String, CompletableDeferred<String>>()

    // Map of session_id -> active streaming message ID
    private val activeStreamMsgId = mutableMapOf<String, String>()

    // Map of session_id -> bot profile
    private val sessionToBot = mutableMapOf<String, ProfileInfo>()

    init {
        if (groupName.isNotBlank()) {
            loadGroup()
        }
        observeWsEvents()
    }

    /**
     * Point the shared instance at [name] and drop all state from the
     * previously viewed group chat so one group's data never bleeds into another.
     */
    fun setGroup(name: String) {
        if (name == groupName && _uiState.value.groupName.isNotBlank()) return
        groupName = name
        memberSessions.clear()
        inFlightTurns.clear()
        activeStreamMsgId.clear()
        sessionToBot.clear()
        _uiState.value = GroupChatUiState(groupName = name)
        loadGroup()
    }

    private fun observeWsEvents() {
        viewModelScope.launch(ioDispatcher) {
            HermesWsClient.events.collect { event ->
                when (event) {
                    is WsEvent.MessageToken -> {
                        val sid = event.sessionId?.trim('\"', ' ')
                        if (sid != null) {
                            val streamId = activeStreamMsgId[sid]
                            val bot = sessionToBot[sid]
                            if (streamId != null && bot != null && event.token.isNotEmpty()) {
                                _uiState.update { state ->
                                    val existing = state.messages.find { it.id == streamId }
                                    val updatedMessages =
                                        if (existing != null) {
                                            state.messages.map { msg ->
                                                if (msg.id == streamId) {
                                                    msg.copy(text = msg.text + event.token)
                                                } else {
                                                    msg
                                                }
                                            }
                                        } else {
                                            state.messages +
                                                GroupChatMessage(
                                                    id = streamId,
                                                    senderName = bot.name,
                                                    senderDisplayName = bot.effectiveTitle,
                                                    isUser = false,
                                                    avatarMeta = bot.botMeta()?.avatar,
                                                    text = event.token,
                                                    isStreaming = true,
                                                )
                                        }
                                    state.copy(messages = updatedMessages)
                                }
                            }
                        }
                    }

                    is WsEvent.MessageComplete -> {
                        val sid = event.sessionId?.trim('\"', ' ')
                        if (sid != null) {
                            Log.d("GroupChatViewModel", "MessageComplete for session $sid: '${event.text.take(50)}'")
                            inFlightTurns[sid]?.complete(event.text)
                        }
                    }

                    else -> {}
                }
            }
        }
    }

    private suspend fun fetchProfiles(): List<ProfileInfo> {
        try {
            val rpcResult = HermesWsClient.request(WsMethods.PROFILES_LIST).await()
            val jsonElement =
                when (rpcResult) {
                    is JsonElement -> rpcResult
                    null -> null
                    else -> rpcResult.toJsonElement()
                }
            if (jsonElement != null) {
                val resp = json.decodeFromJsonElement<ProfilesResponse>(jsonElement)
                if (!resp.profiles.isNullOrEmpty()) {
                    return resp.profiles
                }
            }
        } catch (_: Exception) {
            // Fallback to REST API below
        }
        val result = safeApiCall { ApiClient.hermesApi.getProfiles() }
        return (result as? NetworkResult.Success)?.data?.profiles.orEmpty()
    }

    fun loadGroup() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val allProfiles = fetchProfiles()
            if (allProfiles.isNotEmpty()) {
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

                // Hydrate previous group chat messages from cross-device sync snapshot
                val defaultProfile = allProfiles.find { it.is_default == true || it.name == "default" }
                val syncSnapshotElement = defaultProfile?.ui_meta?.get("hermes-bots-groups")
                val syncSnapshot =
                    syncSnapshotElement?.let { el ->
                        try {
                            json.decodeFromJsonElement<GroupChatSyncSnapshot>(el)
                        } catch (e: Exception) {
                            Log.w("GroupChatViewModel", "Failed to decode sync snapshot: ${e.message}")
                            null
                        }
                    }

                var initialMessages = _uiState.value.messages
                if (initialMessages.isEmpty() && syncSnapshot?.rooms != null) {
                    val matchingRoom =
                        syncSnapshot.rooms[groupName]
                            ?: syncSnapshot.rooms["name:$groupName"]
                            ?: syncSnapshot.rooms["id:$groupName"]
                            ?: syncSnapshot.rooms.values.find { it.name.equals(groupName, ignoreCase = true) }

                    val syncedLog = matchingRoom?.log.orEmpty()
                    if (syncedLog.isNotEmpty()) {
                        initialMessages =
                            syncedLog.map { entry ->
                                val isUser = entry.from?.kind != "member"
                                val senderName = entry.from?.name.orEmpty().ifBlank { if (isUser) "user" else "bot" }
                                val memberProfile = allProfiles.find { it.name.equals(senderName, ignoreCase = true) }
                                GroupChatMessage(
                                    id = entry.id ?: UUID.randomUUID().toString(),
                                    senderName = memberProfile?.name ?: senderName,
                                    senderDisplayName =
                                        if (isUser) "You" else (memberProfile?.effectiveTitle ?: senderName),
                                    isUser = isUser,
                                    avatarMeta = memberProfile?.botMeta()?.avatar,
                                    text = entry.text.orEmpty(),
                                    timestamp = entry.at ?: System.currentTimeMillis(),
                                    thread = entry.thread,
                                )
                            }
                        Log.d("GroupChatViewModel", "Hydrated ${initialMessages.size} messages from sync snapshot")
                    }
                }

                _uiState.update {
                    it.copy(
                        members = groupMembers,
                        messages = initialMessages,
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

    private suspend fun ensureMemberSession(bot: ProfileInfo): MemberSession? {
        val cached = memberSessions[bot.name]
        if (cached != null) {
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
            val sessionInfo = extractSessionInfo(res)
            if (sessionInfo != null) {
                memberSessions[bot.name] = sessionInfo
                return sessionInfo
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
        persistSyncSnapshot(_uiState.value.messages)

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

                val streamMsgId = UUID.randomUUID().toString()
                var activeRuntimeId: String? = null
                var activeStoredId: String? = null
                try {
                    val sessionInfo = ensureMemberSession(bot)
                    if (sessionInfo == null) {
                        Log.w("GroupChatViewModel", "Could not obtain session for ${bot.name}")
                        continue
                    }
                    val runtimeId = sessionInfo.runtimeSessionId
                    val storedId = sessionInfo.storedSessionId
                    activeRuntimeId = runtimeId
                    activeStoredId = storedId

                    activeStreamMsgId[runtimeId] = streamMsgId
                    storedId?.let { activeStreamMsgId[it] = streamMsgId }

                    sessionToBot[runtimeId] = bot
                    storedId?.let { sessionToBot[it] = bot }

                    // Set ActiveSessionHolder so the active WebSocket context matches this turn
                    ActiveSessionHolder.set(runtimeId, runtimeId)

                    // Register in-flight listener
                    val turnDeferred = CompletableDeferred<String>()
                    inFlightTurns[runtimeId] = turnDeferred
                    storedId?.let { inFlightTurns[it] = turnDeferred }

                    // Submit prompt via sendMessage
                    HermesWsClient.sendMessage(
                        sessionId = runtimeId,
                        text = prompt,
                    )

                    // Await event or polling fallback
                    var replyText =
                        withTimeoutOrNull(45_000L) {
                            turnDeferred.await()
                        }
                    inFlightTurns.remove(runtimeId)
                    storedId?.let { inFlightTurns.remove(it) }

                    // Polling fallback if event dropped or session was reaped
                    if (replyText == null) {
                        val targetIds = listOfNotNull(storedId, runtimeId).distinct()
                        for (tid in targetIds) {
                            try {
                                val res =
                                    safeApiCall {
                                        ApiClient.hermesApi.getSessionMessages(
                                            sessionId = tid,
                                            limit = 10,
                                            order = "latest",
                                        )
                                    }
                                if (res is NetworkResult.Success) {
                                    val lastAssistant =
                                        res.data.messages.reversed().firstOrNull { it.role == "assistant" }
                                    val candidate = lastAssistant?.contentText?.trim()
                                    if (!candidate.isNullOrBlank()) {
                                        replyText = candidate
                                        break
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("GroupChatViewModel", "REST fallback failed for $tid: ${e.message}")
                            }
                        }
                    }

                    if (replyText != null && !isPass(replyText) && replyText.isNotBlank()) {
                        _uiState.update { state ->
                            val existing = state.messages.find { it.id == streamMsgId }
                            val finalMsg =
                                GroupChatMessage(
                                    id = streamMsgId,
                                    senderName = bot.name,
                                    senderDisplayName = bot.effectiveTitle,
                                    isUser = false,
                                    avatarMeta = bot.botMeta()?.avatar,
                                    text = replyText.trim(),
                                    isStreaming = false,
                                )
                            val updatedList =
                                if (existing != null) {
                                    state.messages.map { if (it.id == streamMsgId) finalMsg else it }
                                } else {
                                    state.messages + finalMsg
                                }
                            state.copy(messages = updatedList)
                        }
                        persistSyncSnapshot(_uiState.value.messages)
                    } else {
                        _uiState.update { state ->
                            state.copy(messages = state.messages.filter { it.id != streamMsgId })
                        }
                    }
                } catch (e: Exception) {
                    Log.w("GroupChatViewModel", "Turn failed for ${bot.name}: ${e.message}")
                    _uiState.update { state ->
                        state.copy(messages = state.messages.filter { it.id != streamMsgId })
                    }
                } finally {
                    activeRuntimeId?.let {
                        activeStreamMsgId.remove(it)
                        sessionToBot.remove(it)
                        inFlightTurns.remove(it)
                    }
                    activeStoredId?.let {
                        activeStreamMsgId.remove(it)
                        sessionToBot.remove(it)
                        inFlightTurns.remove(it)
                    }
                }
            }
            _uiState.update { it.copy(activeSpeaker = null) }
        }
    }

    private fun persistSyncSnapshot(currentMessages: List<GroupChatMessage>) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val allProfiles = fetchProfiles()
                val defaultProfile =
                    allProfiles.find { it.is_default == true || it.name == "default" } ?: return@launch
                val existingElement = defaultProfile.ui_meta?.get("hermes-bots-groups")
                val existingSnapshot =
                    existingElement?.let {
                        try {
                            json.decodeFromJsonElement<GroupChatSyncSnapshot>(it)
                        } catch (_: Exception) {
                            null
                        }
                    } ?: GroupChatSyncSnapshot(version = 3, rooms = emptyMap())

                val logEntries =
                    currentMessages.takeLast(32).map { msg ->
                        GroupChatSyncLogEntry(
                            id = msg.id,
                            from =
                                GroupChatSyncFrom(
                                    kind = if (msg.isUser) "user" else "member",
                                    name = if (msg.isUser) "You" else msg.senderName,
                                ),
                            text = msg.text,
                            at = msg.timestamp,
                            thread = msg.thread,
                        )
                    }

                val roomKey = "name:$groupName"
                val updatedRoom =
                    GroupChatRoomMeta(
                        name = groupName,
                        members = _uiState.value.members.map { JsonPrimitive(it.name) },
                        log = logEntries,
                        updatedAt = System.currentTimeMillis(),
                    )

                val updatedRooms = (existingSnapshot.rooms.orEmpty() + (roomKey to updatedRoom))
                val newSnapshot =
                    existingSnapshot.copy(
                        version = 3,
                        updatedAt = System.currentTimeMillis(),
                        rooms = updatedRooms,
                    )

                val uiMetaPayload = mapOf("hermes-bots-groups" to snapshotToMap(newSnapshot))

                HermesWsClient.request(
                    WsMethods.PROFILES_CONFIGURE,
                    mapOf(
                        "name" to defaultProfile.name,
                        "ui_meta" to uiMetaPayload,
                    ),
                ).await()
            } catch (e: Exception) {
                Log.w("GroupChatViewModel", "persistSyncSnapshot failed: ${e.message}")
            }
        }
    }

    private fun snapshotToMap(snapshot: GroupChatSyncSnapshot): Map<String, Any?> {
        val roomsMap = mutableMapOf<String, Any?>()
        snapshot.rooms?.forEach { (key, room) ->
            val roomMap = mutableMapOf<String, Any?>()
            room.name?.let { roomMap["name"] = it }
            room.roomId?.let { roomMap["roomId"] = it }
            room.picture?.let { roomMap["picture"] = it }
            room.image?.let { roomMap["image"] = it }
            room.updatedAt?.let { roomMap["updatedAt"] = it }
            room.createdAt?.let { roomMap["createdAt"] = it }
            room.revision?.let { roomMap["revision"] = it }
            room.members?.let { members ->
                roomMap["members"] =
                    members.mapNotNull {
                        when (it) {
                            is JsonPrimitive -> it.content
                            is JsonObject -> it["name"]?.jsonPrimitive?.content
                            else -> null
                        }
                    }
            }
            room.log?.let { log ->
                roomMap["log"] =
                    log.map { entry ->
                        buildMap<String, Any?> {
                            entry.id?.let { put("id", it) }
                            entry.text?.let { put("text", it) }
                            entry.at?.let { put("at", it) }
                            entry.thread?.let { put("thread", it) }
                            entry.from?.let { f ->
                                put(
                                    "from",
                                    buildMap<String, Any?> {
                                        f.kind?.let { put("kind", it) }
                                        f.name?.let { put("name", it) }
                                        f.source?.let { put("source", it) }
                                    },
                                )
                            }
                        }
                    }
            }
            roomsMap[key] = roomMap
        }

        return buildMap {
            put("version", snapshot.version ?: 3)
            put("updatedAt", snapshot.updatedAt ?: System.currentTimeMillis())
            put("rooms", roomsMap)
            snapshot.deleted?.let { put("deleted", it) }
        }
    }

    private fun isPass(text: String): Boolean {
        val clean = text.trim().lowercase()
        return clean == "(pass)" || clean == "pass"
    }

    private fun extractSessionInfo(response: Any?): MemberSession? {
        if (response is Map<*, *>) {
            val runtimeId =
                response["session_id"]?.toString()?.trim('\"', ' ')
                    ?: (response["result"] as? Map<*, *>)?.get("session_id")?.toString()?.trim('\"', ' ')
            val storedId =
                response["stored_session_id"]?.toString()?.trim('\"', ' ')
                    ?: (response["result"] as? Map<*, *>)?.get("stored_session_id")?.toString()?.trim('\"', ' ')
            if (!runtimeId.isNullOrBlank()) {
                return MemberSession(runtimeSessionId = runtimeId, storedSessionId = storedId)
            }
        }
        return null
    }
}
