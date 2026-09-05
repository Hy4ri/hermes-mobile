package com.m57.hermescontrol.ui.bots.group

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.GroupChatRoomLease
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
import com.m57.hermescontrol.ui.chat.tool.ToolResultSummary
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID

data class GroupChatUiState(
    val groupName: String = "",
    val members: List<ProfileInfo> = emptyList(),
    val messages: List<GroupChatMessage> = emptyList(),
    val activeSpeaker: String? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val maxBotMessages: Int = DEFAULT_MAX_BOT_MESSAGES,
    val maxContinuationPasses: Int = DEFAULT_MAX_CONTINUATION_PASSES,
    val systemPrompt: String? = null,
)

data class MemberSession(
    val runtimeSessionId: String,
    val storedSessionId: String? = null,
)

const val DEFAULT_MAX_BOT_MESSAGES = 6
const val DEFAULT_MAX_CONTINUATION_PASSES = 2
const val MAX_ALLOWED_BOT_MESSAGES = 50
const val MAX_ALLOWED_CONTINUATION_PASSES = 20
const val WARN_MAX_BOT_MESSAGES = 20
const val WARN_MAX_CONTINUATION_PASSES = 6
private const val LEASE_STEP_TTL_MS = 180_000L
private const val GROUP_TURN_TIMEOUT_MS = 180_000L
private const val GROUP_TURN_HARD_CAP_MS = 20 * 60_000L
private const val HISTORY_LIMIT = 10

class GroupChatViewModel(
    private var groupName: String = "",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ViewModel() {
    private val _uiState = MutableStateFlow(GroupChatUiState(groupName = groupName))
    val uiState: StateFlow<GroupChatUiState> = _uiState.asStateFlow()

    private val localDriverId = "android_" + UUID.randomUUID().toString().take(8)
    private var activeEpoch: String = ""

    // Map of bot.name -> seen count of non-system messages
    private val memberWatermarks = mutableMapOf<String, Int>()

    // Map of bot.name -> active session info in this group
    private val memberSessions = mutableMapOf<String, MemberSession>()

    // In-flight turn completion deferreds keyed by session_id
    private val inFlightTurns = mutableMapOf<String, CompletableDeferred<String>>()

    // Map of session_id -> active streaming message ID
    private val activeStreamMsgId = mutableMapOf<String, String>()

    // Map of session_id -> bot profile
    private val sessionToBot = mutableMapOf<String, ProfileInfo>()

    // Map of session_id -> last activity epoch ms (streaming token, tool call, thinking)
    private val sessionLastActivity = mutableMapOf<String, Long>()

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
        activeEpoch = UUID.randomUUID().toString()
        memberWatermarks.clear()
        memberSessions.clear()
        inFlightTurns.clear()
        activeStreamMsgId.clear()
        sessionToBot.clear()
        sessionLastActivity.clear()
        _uiState.value = GroupChatUiState(groupName = name)
        loadGroup()
    }

    private fun extractToolCommand(data: Map<String, Any?>?): String? {
        if (data == null) return null
        val cmd = (data["command"] as? String)?.trim()
        if (!cmd.isNullOrBlank()) return cmd
        val code = (data["code"] as? String)?.trim()
        if (!code.isNullOrBlank()) return code
        val query = (data["query"] as? String)?.trim()
        if (!query.isNullOrBlank()) return query
        val path = (data["path"] as? String)?.trim()
        if (!path.isNullOrBlank()) return path
        val pattern = (data["pattern"] as? String)?.trim()
        if (!pattern.isNullOrBlank()) return pattern
        return null
    }

    private fun extractToolSummary(data: Map<String, Any?>?): String? {
        if (data == null) return null
        val cmd = (data["command"] as? String)?.trim()
        if (!cmd.isNullOrBlank()) return cmd.take(35)
        val query = (data["query"] as? String)?.trim()
        if (!query.isNullOrBlank()) return query.take(35)
        val path = (data["path"] as? String)?.trim()
        if (!path.isNullOrBlank()) return path.take(35)
        val pattern = (data["pattern"] as? String)?.trim()
        if (!pattern.isNullOrBlank()) return pattern.take(35)
        return null
    }

    private fun extractToolOutput(data: Map<String, Any?>?): String? {
        if (data == null) return null
        val rawStr =
            (data["output"] as? String)
                ?: (data["stdout"] as? String)
                ?: (data["result"] as? String)
        if (!rawStr.isNullOrBlank()) {
            return rawStr
        }

        val resultElement = data["result"]?.toJsonElement() ?: data["output"]?.toJsonElement() ?: data.toJsonElement()
        val summary = ToolResultSummary.formatToolResultSummary(resultElement)
        return summary.ifBlank { null }
    }

    private fun observeWsEvents() {
        viewModelScope.launch(ioDispatcher) {
            HermesWsClient.events.collect { event ->
                when (event) {
                    is WsEvent.MessageToken -> {
                        val sid = event.sessionId?.trim('\"', ' ')
                        if (sid != null) {
                            sessionLastActivity[sid] = System.currentTimeMillis()
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

                    is WsEvent.ToolStart -> {
                        val sid = event.sessionId?.trim('\"', ' ')
                        if (sid != null) {
                            sessionLastActivity[sid] = System.currentTimeMillis()
                            val streamId = activeStreamMsgId[sid]
                            val bot = sessionToBot[sid]
                            val toolName = event.name ?: "tool"
                            val toolId =
                                (event.data?.get("tool_id") as? String)?.ifBlank { null }
                                    ?: UUID.randomUUID().toString()
                            val summary = extractToolSummary(event.data)
                            val command = extractToolCommand(event.data)
                            val toolCall =
                                GroupChatToolCall(
                                    id = toolId,
                                    name = toolName,
                                    summary = summary,
                                    command = command,
                                    isRunning = true,
                                )
                            if (streamId != null && bot != null) {
                                _uiState.update { state ->
                                    val existing = state.messages.find { it.id == streamId }
                                    val updatedMessages =
                                        if (existing != null) {
                                            state.messages.map { msg ->
                                                if (msg.id == streamId) {
                                                    msg.copy(toolCalls = msg.toolCalls + toolCall)
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
                                                    text = "",
                                                    isStreaming = true,
                                                    toolCalls = listOf(toolCall),
                                                )
                                        }
                                    state.copy(messages = updatedMessages)
                                }
                            }
                        }
                    }

                    is WsEvent.ToolComplete -> {
                        val sid = event.sessionId?.trim('\"', ' ')
                        if (sid != null) {
                            sessionLastActivity[sid] = System.currentTimeMillis()
                            val streamId = activeStreamMsgId[sid]
                            val toolId = event.data?.get("tool_id") as? String
                            val toolName = event.name
                            val output = extractToolOutput(event.data)
                            val exitCode =
                                when (val rawCode = event.data?.get("exit_code")) {
                                    is Number -> rawCode.toInt()
                                    is String -> rawCode.toIntOrNull()
                                    else -> null
                                }
                            val isError =
                                (exitCode != null && exitCode != 0) ||
                                    (event.data?.get("is_error") as? Boolean == true) ||
                                    (event.data?.get("error") != null)
                            if (streamId != null) {
                                _uiState.update { state ->
                                    val existing = state.messages.find { it.id == streamId }
                                    if (existing != null) {
                                        val updatedTools =
                                            existing.toolCalls.map { tool ->
                                                if ((toolId != null && tool.id == toolId) ||
                                                    (toolId == null && tool.name == toolName && tool.isRunning) ||
                                                    (toolId == null && toolName == null && tool.isRunning)
                                                ) {
                                                    tool.copy(
                                                        isRunning = false,
                                                        output = output ?: tool.output,
                                                        exitCode = exitCode ?: tool.exitCode,
                                                        isError = isError || tool.isError,
                                                    )
                                                } else {
                                                    tool
                                                }
                                            }
                                        state.copy(
                                            messages =
                                                state.messages.map { msg ->
                                                    if (msg.id == streamId) {
                                                        msg.copy(toolCalls = updatedTools)
                                                    } else {
                                                        msg
                                                    }
                                                },
                                        )
                                    } else {
                                        state
                                    }
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
                // Hydrate previous group chat messages from cross-device sync snapshot
                val defaultProfile =
                    allProfiles.find { it.is_default == true || it.name == "default" }
                        ?: allProfiles.firstOrNull()
                val syncSnapshot = defaultProfile?.groupChatSyncSnapshot()

                val matchingRoom =
                    syncSnapshot?.rooms?.get(groupName)
                        ?: syncSnapshot?.rooms?.get("name:$groupName")
                        ?: syncSnapshot?.rooms?.get("id:$groupName")
                        ?: syncSnapshot?.rooms?.values?.find { it.name.equals(groupName, ignoreCase = true) }

                val roomMemberNames = matchingRoom?.memberNames.orEmpty().map { it.lowercase() }

                val matchedProfiles =
                    allProfiles
                        .filter {
                            val botGroups = it.botMeta()?.allGroups.orEmpty()
                            botGroups.any { g -> g.equals(groupName, ignoreCase = true) } ||
                                roomMemberNames.contains(it.name.lowercase()) ||
                                roomMemberNames.contains(it.effectiveTitle.lowercase())
                        }.toMutableList()

                // If room listed members (e.g. remote bots or bots not in local profiles), ensure they are seated
                if (matchingRoom != null && roomMemberNames.isNotEmpty()) {
                    for (mName in matchingRoom.memberNames) {
                        val exists =
                            matchedProfiles.any {
                                it.name.equals(mName, ignoreCase = true) ||
                                    it.effectiveTitle.equals(mName, ignoreCase = true)
                            }
                        if (!exists) {
                            val local = allProfiles.find { it.name.equals(mName, ignoreCase = true) }
                            matchedProfiles.add(local ?: ProfileInfo(name = mName))
                        }
                    }
                }

                var groupMembers: List<ProfileInfo> = matchedProfiles

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

                var initialMessages = _uiState.value.messages
                if (initialMessages.isEmpty() && syncSnapshot?.rooms != null) {
                    val syncedLog = matchingRoom?.log.orEmpty()
                    if (syncedLog.isNotEmpty()) {
                        initialMessages =
                            syncedLog.map { entry ->
                                val isUser = entry.from?.kind != "member"
                                val senderName =
                                    entry.from
                                        ?.name
                                        .orEmpty()
                                        .ifBlank { if (isUser) "user" else "bot" }
                                val memberProfile =
                                    allProfiles.find {
                                        it.name.equals(senderName, ignoreCase = true) ||
                                            it.effectiveTitle.equals(senderName, ignoreCase = true)
                                    }
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

                val nonSystemCount = initialMessages.filter { !it.isSystem }.size
                for (member in groupMembers) {
                    memberWatermarks[member.name] = nonSystemCount
                }

                val maxMessages = matchingRoom?.maxBotMessages ?: DEFAULT_MAX_BOT_MESSAGES
                val maxPasses = matchingRoom?.maxContinuationPasses ?: DEFAULT_MAX_CONTINUATION_PASSES
                val roomSystemPrompt = matchingRoom?.systemPrompt

                _uiState.update {
                    it.copy(
                        members = groupMembers,
                        messages = initialMessages,
                        maxBotMessages = maxMessages,
                        maxContinuationPasses = maxPasses,
                        systemPrompt = roomSystemPrompt,
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

    private fun findRoomEntry(rooms: Map<String, GroupChatRoomMeta>?): Pair<String, GroupChatRoomMeta?> {
        if (rooms == null) return ("name:$groupName" to null)
        val exact =
            rooms.entries.find {
                it.key == groupName || it.key == "name:$groupName" || it.key == "id:$groupName"
            }
        if (exact != null) return (exact.key to exact.value)
        val byName = rooms.entries.find { it.value.name.equals(groupName, ignoreCase = true) }
        if (byName != null) return (byName.key to byName.value)
        return ("name:$groupName" to null)
    }

    fun updateGroupLimits(
        maxMessages: Int,
        maxPasses: Int,
        systemPrompt: String? = null,
    ) {
        val clampedMessages = maxMessages.coerceIn(1, MAX_ALLOWED_BOT_MESSAGES)
        val clampedPasses = maxPasses.coerceIn(0, MAX_ALLOWED_CONTINUATION_PASSES)
        val cleanPrompt = systemPrompt?.trim()?.ifBlank { null }

        _uiState.update {
            it.copy(
                maxBotMessages = clampedMessages,
                maxContinuationPasses = clampedPasses,
                systemPrompt = cleanPrompt,
            )
        }

        viewModelScope.launch(ioDispatcher) {
            try {
                val allProfiles = fetchProfiles()
                val defaultProfile =
                    allProfiles.find { it.is_default == true || it.name == "default" } ?: return@launch
                val existingSnapshot =
                    defaultProfile.groupChatSyncSnapshot()
                        ?: GroupChatSyncSnapshot(version = 3, rooms = emptyMap())

                val now = System.currentTimeMillis()
                val (targetKey, existingRoom) = findRoomEntry(existingSnapshot.rooms)

                val updatedRoom =
                    (existingRoom ?: GroupChatRoomMeta(name = groupName, createdAt = now)).copy(
                        name = groupName,
                        members = _uiState.value.members.map { JsonPrimitive(it.name) },
                        maxBotMessages = clampedMessages,
                        maxContinuationPasses = clampedPasses,
                        systemPrompt = cleanPrompt,
                        updatedAt = now,
                    )

                val updatedRooms = existingSnapshot.rooms.orEmpty().toMutableMap()
                updatedRooms[targetKey] = updatedRoom

                val newSnapshot =
                    existingSnapshot.copy(
                        version = 3,
                        updatedAt = now,
                        rooms = updatedRooms,
                    )

                val uiMetaPayload = mapOf("hermes-bots-groups" to newSnapshot.toMap())

                HermesWsClient
                    .request(
                        WsMethods.PROFILES_CONFIGURE,
                        mapOf(
                            "name" to defaultProfile.name,
                            "ui_meta" to uiMetaPayload,
                        ),
                    ).await()
            } catch (e: Exception) {
                Log.w("GroupChatViewModel", "updateGroupLimits failed: ${e.message}")
            }
        }
    }

    private suspend fun ensureMemberSession(
        bot: ProfileInfo,
        forceRefresh: Boolean = false,
    ): MemberSession? {
        if (!forceRefresh) {
            val cached = memberSessions[bot.name]
            if (cached != null) {
                return cached
            }
        } else {
            memberSessions.remove(bot.name)
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

    private suspend fun acquireOrVerifyLease(
        defaultProfile: ProfileInfo,
        epoch: String,
    ): Boolean {
        try {
            val syncSnapshot = defaultProfile.groupChatSyncSnapshot()
            val (targetKey, existingRoom) = findRoomEntry(syncSnapshot?.rooms)
            val currentLease = existingRoom?.lease
            val now = System.currentTimeMillis()

            if (currentLease?.driverId != null &&
                currentLease.driverId != localDriverId &&
                (currentLease.expiresAt ?: 0L) > now
            ) {
                val remainingSec = ((currentLease.expiresAt ?: 0L) - now) / 1000
                Log.i(
                    "GroupChatViewModel",
                    "Active lease held by ${currentLease.driverId} (expires in ${remainingSec}s). Passive mode.",
                )
                return false
            }

            val myLease =
                GroupChatRoomLease(
                    driverId = localDriverId,
                    epoch = epoch,
                    expiresAt = now + LEASE_STEP_TTL_MS,
                )

            val existingSnapshot = syncSnapshot ?: GroupChatSyncSnapshot(version = 3, rooms = emptyMap())
            val updatedRoom =
                (existingRoom ?: GroupChatRoomMeta(name = groupName, createdAt = now)).copy(
                    name = groupName,
                    members = _uiState.value.members.map { JsonPrimitive(it.name) },
                    lease = myLease,
                    maxBotMessages = _uiState.value.maxBotMessages,
                    maxContinuationPasses = _uiState.value.maxContinuationPasses,
                    systemPrompt = _uiState.value.systemPrompt,
                    updatedAt = now,
                )

            val updatedRooms = existingSnapshot.rooms.orEmpty().toMutableMap()
            updatedRooms[targetKey] = updatedRoom
            val newSnapshot = existingSnapshot.copy(version = 3, updatedAt = now, rooms = updatedRooms)
            val uiMetaPayload = mapOf("hermes-bots-groups" to newSnapshot.toMap())

            HermesWsClient
                .request(
                    WsMethods.PROFILES_CONFIGURE,
                    mapOf(
                        "name" to defaultProfile.name,
                        "ui_meta" to uiMetaPayload,
                    ),
                ).await()

            return true
        } catch (e: Exception) {
            Log.w("GroupChatViewModel", "acquireOrVerifyLease failed: ${e.message}")
            return true
        }
    }

    private suspend fun executeBotTurn(
        bot: ProfileInfo,
        epoch: String,
        members: List<ProfileInfo>,
    ): String? {
        if (activeEpoch != epoch) return null

        val currentNonSystemMessages = _uiState.value.messages.filter { !it.isSystem }
        val seen = memberWatermarks[bot.name] ?: 0
        val delta = currentNonSystemMessages.drop(seen)

        if (delta.isEmpty() && currentNonSystemMessages.isNotEmpty()) {
            return null
        }

        _uiState.update { it.copy(activeSpeaker = bot.effectiveTitle) }

        val prompt =
            GroupChatMentions.buildTurnPrompt(
                groupName = groupName,
                viewer = bot,
                peers = members.filter { it.name != bot.name },
                recentLog = currentNonSystemMessages,
                historyLimit = HISTORY_LIMIT,
                customRoomPrompt = _uiState.value.systemPrompt,
            )

        val turnStartTime = System.currentTimeMillis()
        val streamMsgId = UUID.randomUUID().toString()
        var activeRuntimeId: String? = null
        var activeStoredId: String? = null
        try {
            var sessionInfo = ensureMemberSession(bot)
            if (sessionInfo == null) {
                Log.w("GroupChatViewModel", "Could not obtain session for ${bot.name}")
                return null
            }
            var runtimeId = sessionInfo.runtimeSessionId
            var storedId = sessionInfo.storedSessionId
            activeRuntimeId = runtimeId
            activeStoredId = storedId

            activeStreamMsgId[runtimeId] = streamMsgId
            storedId?.let { activeStreamMsgId[it] = streamMsgId }

            sessionToBot[runtimeId] = bot
            storedId?.let { sessionToBot[it] = bot }

            ActiveSessionHolder.set(runtimeId, runtimeId)

            val turnDeferred = CompletableDeferred<String>()
            inFlightTurns[runtimeId] = turnDeferred
            storedId?.let { inFlightTurns[it] = turnDeferred }

            try {
                HermesWsClient
                    .request(
                        WsMethods.PROMPT_SUBMIT,
                        mapOf("session_id" to runtimeId, "text" to prompt),
                    ).await()
            } catch (submitErr: Exception) {
                Log.w(
                    "GroupChatViewModel",
                    "prompt.submit failed for ${bot.name} on $runtimeId: ${submitErr.message}, recreating session",
                )
                activeStreamMsgId.remove(runtimeId)
                storedId?.let { activeStreamMsgId.remove(it) }
                sessionToBot.remove(runtimeId)
                storedId?.let { sessionToBot.remove(it) }
                inFlightTurns.remove(runtimeId)
                storedId?.let { inFlightTurns.remove(it) }

                sessionInfo = ensureMemberSession(bot, forceRefresh = true)
                if (sessionInfo == null) return null

                runtimeId = sessionInfo.runtimeSessionId
                storedId = sessionInfo.storedSessionId
                activeRuntimeId = runtimeId
                activeStoredId = storedId

                activeStreamMsgId[runtimeId] = streamMsgId
                storedId?.let { activeStreamMsgId[it] = streamMsgId }
                sessionToBot[runtimeId] = bot
                storedId?.let { sessionToBot[it] = bot }
                ActiveSessionHolder.set(runtimeId, runtimeId)
                inFlightTurns[runtimeId] = turnDeferred
                storedId?.let { inFlightTurns[it] = turnDeferred }

                try {
                    HermesWsClient
                        .request(
                            WsMethods.PROMPT_SUBMIT,
                            mapOf("session_id" to runtimeId, "text" to prompt),
                        ).await()
                } catch (retryErr: Exception) {
                    Log.e("GroupChatViewModel", "Retry prompt.submit failed for ${bot.name}: ${retryErr.message}")
                    return null
                }
            }

            var replyText: String? = null
            var deadline = turnStartTime + GROUP_TURN_TIMEOUT_MS
            val hardCapDeadline = turnStartTime + GROUP_TURN_HARD_CAP_MS

            while (System.currentTimeMillis() < deadline) {
                val pollResult =
                    withTimeoutOrNull(2000L) {
                        turnDeferred.await()
                    }
                if (pollResult != null) {
                    replyText = pollResult
                    break
                }
                if (activeEpoch != epoch) break

                // Adaptive extension: if bot is actively working (tokens/tools streamed in last 60s), extend deadline
                val lastActivity =
                    listOfNotNull(
                        activeRuntimeId?.let { sessionLastActivity[it] },
                        activeStoredId?.let { sessionLastActivity[it] },
                    ).maxOrNull() ?: turnStartTime

                val isBusy = (System.currentTimeMillis() - lastActivity) < 60_000L
                if (isBusy) {
                    deadline =
                        minOf(hardCapDeadline, maxOf(deadline, System.currentTimeMillis() + GROUP_TURN_TIMEOUT_MS))
                }
            }
            inFlightTurns.remove(runtimeId)
            storedId?.let { inFlightTurns.remove(it) }

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
                                    profile = bot.name,
                                )
                            }
                        if (res is NetworkResult.Success) {
                            val lastAssistant =
                                res.data.messages.reversed().firstOrNull { msg ->
                                    val ts = msg.timestampEpochMs
                                    msg.role == "assistant" &&
                                        (ts == null || ts >= (turnStartTime - 5000L))
                                }
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

            if (activeEpoch != epoch) {
                _uiState.update { state ->
                    state.copy(messages = state.messages.filter { it.id != streamMsgId })
                }
                return null
            }

            if (replyText != null && !isPass(replyText) && replyText.isNotBlank()) {
                _uiState.update { state ->
                    val existing = state.messages.find { it.id == streamMsgId }
                    val finalTools =
                        existing?.toolCalls?.map { it.copy(isRunning = false) } ?: emptyList()
                    val finalMsg =
                        GroupChatMessage(
                            id = streamMsgId,
                            senderName = bot.name,
                            senderDisplayName = bot.effectiveTitle,
                            isUser = false,
                            avatarMeta = bot.botMeta()?.avatar,
                            text = replyText.trim(),
                            isStreaming = false,
                            toolCalls = finalTools,
                        )
                    val updatedList =
                        if (existing != null) {
                            state.messages.map { if (it.id == streamMsgId) finalMsg else it }
                        } else {
                            state.messages + finalMsg
                        }
                    state.copy(messages = updatedList)
                }
                return replyText.trim()
            } else if (replyText != null && isPass(replyText)) {
                _uiState.update { state ->
                    val existing = state.messages.find { it.id == streamMsgId }
                    val passMsg =
                        GroupChatMessage(
                            id = streamMsgId,
                            senderName = bot.name,
                            senderDisplayName = bot.effectiveTitle,
                            isUser = false,
                            isSystem = true,
                            isPass = true,
                            text = "${bot.effectiveTitle} passed",
                        )
                    val updatedList =
                        if (existing != null) {
                            state.messages.map { if (it.id == streamMsgId) passMsg else it }
                        } else {
                            state.messages + passMsg
                        }
                    state.copy(messages = updatedList)
                }
                return null
            } else {
                _uiState.update { state ->
                    state.copy(messages = state.messages.filter { it.id != streamMsgId })
                }
                return null
            }
        } catch (e: Exception) {
            Log.w("GroupChatViewModel", "Turn failed for ${bot.name}: ${e.message}")
            _uiState.update { state ->
                state.copy(messages = state.messages.filter { it.id != streamMsgId })
            }
            return null
        } finally {
            activeRuntimeId?.let {
                activeStreamMsgId.remove(it)
                sessionToBot.remove(it)
                inFlightTurns.remove(it)
                sessionLastActivity.remove(it)
            }
            activeStoredId?.let {
                activeStreamMsgId.remove(it)
                sessionToBot.remove(it)
                inFlightTurns.remove(it)
                sessionLastActivity.remove(it)
            }
        }
    }

    fun stopGeneration() {
        Log.i("GroupChatViewModel", "stopGeneration requested by user")
        val interruptedEpoch = UUID.randomUUID().toString()
        activeEpoch = interruptedEpoch

        // Send interrupt signal to any actively streaming or in-flight sessions
        val activeSessions = activeStreamMsgId.keys.toList() + inFlightTurns.keys.toList()
        for (sid in activeSessions.distinct()) {
            try {
                HermesWsClient.send(
                    WsMethods.SESSION_INTERRUPT,
                    mapOf("session_id" to sid),
                )
            } catch (e: Exception) {
                Log.w("GroupChatViewModel", "Failed to send interrupt for session $sid: ${e.message}")
            }
        }

        // Clean up transient in-flight tracking
        inFlightTurns.values.forEach { it.complete("") }
        inFlightTurns.clear()
        activeStreamMsgId.clear()
        sessionToBot.clear()

        _uiState.update { state ->
            // Remove any pending streaming placeholder messages
            val cleanedMessages = state.messages.filter { !it.isStreaming }
            val stopMessage =
                GroupChatMessage(
                    id = UUID.randomUUID().toString(),
                    senderName = "system",
                    senderDisplayName = "System",
                    isUser = false,
                    isSystem = true,
                    text = STOPPED_SYSTEM_TEXT,
                )
            state.copy(
                messages = cleanedMessages + stopMessage,
                activeSpeaker = null,
            )
        }
        persistSyncSnapshot(_uiState.value.messages, extendLease = false)
    }

    private fun isStopCommand(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized in setOf("/stop", "/interrupt", "/pause", "/cancel", "stop", "/brake")
    }

    fun sendMessage(rawText: String) {
        val text = rawText.trim()
        Log.d("GroupChatViewModel", "sendMessage called with: '$text'")
        if (text.isBlank()) return

        if (isStopCommand(text)) {
            stopGeneration()
            return
        }

        val members = _uiState.value.members
        if (members.isEmpty()) {
            Log.w("GroupChatViewModel", "sendMessage: No members in group, ignoring")
            return
        }

        val epoch = UUID.randomUUID().toString()
        activeEpoch = epoch

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
        persistSyncSnapshot(_uiState.value.messages, extendLease = true)

        viewModelScope.launch(ioDispatcher) {
            val allProfiles = fetchProfiles()
            val defaultProfile =
                allProfiles.find { it.is_default == true || it.name == "default" }
                    ?: allProfiles.firstOrNull()

            if (defaultProfile != null) {
                val hasLease = acquireOrVerifyLease(defaultProfile, epoch)
                if (!hasLease) {
                    Log.i("GroupChatViewModel", "Passive mode active — skipping local turn drive")
                    return@launch
                }
            }

            var postedBotMessages = 0
            var continuationsCount = 0

            val maxBotLimit = _uiState.value.maxBotMessages
            val maxPassLimit = _uiState.value.maxContinuationPasses

            val initialResponders = GroupChatMentions.resolveResponders(text, members).toMutableList()
            val remainingInitial = initialResponders.toMutableList()

            // ── Round 1: Initial Turn ──
            while (remainingInitial.isNotEmpty()) {
                if (activeEpoch != epoch) return@launch
                if (postedBotMessages >= maxBotLimit) break

                val bot = remainingInitial.removeAt(0)
                val reply = executeBotTurn(bot, epoch, members)
                val nonSystemSize =
                    _uiState.value.messages
                        .filter { !it.isSystem }
                        .size
                memberWatermarks[bot.name] = nonSystemSize

                if (reply != null) {
                    postedBotMessages++
                    persistSyncSnapshot(_uiState.value.messages, extendLease = true)
                }
            }

            // ── Reactive Continuation Passes ──
            while (continuationsCount < maxPassLimit && postedBotMessages < maxBotLimit) {
                if (activeEpoch != epoch) return@launch

                val currentLog = _uiState.value.messages.filter { !it.isSystem }
                val userIndex = currentLog.indexOfLast { it.isUser }
                val recentAssistantMsgs =
                    if (userIndex >= 0) currentLog.drop(userIndex + 1) else currentLog

                val pendingCitedBots = mutableListOf<ProfileInfo>()
                for (msg in recentAssistantMsgs) {
                    val parsed =
                        GroupChatMentions.parseMentions(
                            text = msg.text,
                            members = members,
                            excludedSpeaker = msg.senderName,
                        )
                    for (citedName in parsed.mentionedBots) {
                        val bot = members.find { it.name.equals(citedName, ignoreCase = true) }
                        if (bot != null && !pendingCitedBots.any { it.name == bot.name }) {
                            val seenCount = memberWatermarks[bot.name] ?: 0
                            if (seenCount < currentLog.size) {
                                pendingCitedBots.add(bot)
                            }
                        }
                    }
                }

                if (pendingCitedBots.isEmpty()) {
                    // Quiescence / Natural Settle reached
                    break
                }

                continuationsCount++
                val continuationResponders = pendingCitedBots.toMutableList()

                while (continuationResponders.isNotEmpty()) {
                    if (activeEpoch != epoch) return@launch
                    if (postedBotMessages >= maxBotLimit) break

                    val bot = continuationResponders.removeAt(0)
                    val reply = executeBotTurn(bot, epoch, members)
                    val nonSystemSize =
                        _uiState.value.messages
                            .filter { !it.isSystem }
                            .size
                    memberWatermarks[bot.name] = nonSystemSize

                    if (reply != null) {
                        postedBotMessages++
                        persistSyncSnapshot(_uiState.value.messages, extendLease = true)
                    }
                }
            }

            // ── Capped Exit Banner Check ──
            val isBudgetExceeded =
                postedBotMessages >= maxBotLimit || continuationsCount >= maxPassLimit
            val hasUnfinishedWork = remainingInitial.isNotEmpty() || hasPendingMentions(members)

            if (isBudgetExceeded && hasUnfinishedWork && activeEpoch == epoch) {
                val cappedMessage =
                    GroupChatMessage(
                        id = UUID.randomUUID().toString(),
                        senderName = "system",
                        senderDisplayName = "System",
                        isUser = false,
                        isSystem = true,
                        text = CAPPED_SYSTEM_TEXT,
                    )
                _uiState.update { it.copy(messages = it.messages + cappedMessage) }
                persistSyncSnapshot(_uiState.value.messages, extendLease = false)
            }

            _uiState.update { it.copy(activeSpeaker = null) }
        }
    }

    private fun hasPendingMentions(members: List<ProfileInfo>): Boolean {
        val currentLog = _uiState.value.messages.filter { !it.isSystem }
        val userIndex = currentLog.indexOfLast { it.isUser }
        val recentAssistantMsgs =
            if (userIndex >= 0) currentLog.drop(userIndex + 1) else currentLog

        for (msg in recentAssistantMsgs) {
            val parsed =
                GroupChatMentions.parseMentions(
                    text = msg.text,
                    members = members,
                    excludedSpeaker = msg.senderName,
                )
            for (citedName in parsed.mentionedBots) {
                val bot = members.find { it.name.equals(citedName, ignoreCase = true) }
                if (bot != null) {
                    val seenCount = memberWatermarks[bot.name] ?: 0
                    if (seenCount < currentLog.size) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun persistSyncSnapshot(
        currentMessages: List<GroupChatMessage>,
        extendLease: Boolean = true,
    ) {
        viewModelScope.launch(ioDispatcher) {
            try {
                val allProfiles = fetchProfiles()
                val defaultProfile =
                    allProfiles.find { it.is_default == true || it.name == "default" } ?: return@launch
                val existingSnapshot =
                    defaultProfile.groupChatSyncSnapshot()
                        ?: GroupChatSyncSnapshot(version = 3, rooms = emptyMap())

                val logEntries =
                    currentMessages.filter { !it.isSystem }.takeLast(32).map { msg ->
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

                val now = System.currentTimeMillis()
                val (targetKey, existingRoom) = findRoomEntry(existingSnapshot.rooms)

                val updatedLease =
                    if (extendLease) {
                        GroupChatRoomLease(
                            driverId = localDriverId,
                            epoch = activeEpoch,
                            expiresAt = now + LEASE_STEP_TTL_MS,
                        )
                    } else {
                        existingRoom?.lease
                    }

                val updatedRoom =
                    (existingRoom ?: GroupChatRoomMeta(name = groupName, createdAt = now)).copy(
                        name = groupName,
                        members = _uiState.value.members.map { JsonPrimitive(it.name) },
                        log = logEntries,
                        lease = updatedLease,
                        maxBotMessages = _uiState.value.maxBotMessages,
                        maxContinuationPasses = _uiState.value.maxContinuationPasses,
                        systemPrompt = _uiState.value.systemPrompt,
                        updatedAt = now,
                    )

                val updatedRooms = existingSnapshot.rooms.orEmpty().toMutableMap()
                updatedRooms[targetKey] = updatedRoom
                val newSnapshot =
                    existingSnapshot.copy(
                        version = 3,
                        updatedAt = now,
                        rooms = updatedRooms,
                    )

                val uiMetaPayload = mapOf("hermes-bots-groups" to newSnapshot.toMap())

                HermesWsClient
                    .request(
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

    private fun isPass(text: String): Boolean {
        val clean = text.trim().lowercase()
        return clean == "(pass)" || clean == "pass" || clean == "(pass)." || clean == "pass."
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
