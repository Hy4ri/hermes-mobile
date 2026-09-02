package com.m57.hermescontrol.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.BotRosterMeta
import com.m57.hermescontrol.data.model.CreateProfileRequest
import com.m57.hermescontrol.data.model.GroupChatRoomMeta
import com.m57.hermescontrol.data.model.GroupChatSyncSnapshot
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.toJsonElement
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

enum class BotsTab {
    BOTS,
    GROUPS,
}

data class GroupInfo(
    val name: String,
    val members: List<ProfileInfo>,
    val lastActivity: Long = 0L,
)

data class BotsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val profiles: List<ProfileInfo> = emptyList(),
    val activeProfileName: String? = null,
    val searchQuery: String = "",
    val showHidden: Boolean = false,
    val hiddenProfiles: Set<String> = emptySet(),
    val selectedTab: BotsTab = BotsTab.BOTS,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
) {
    val hasHiddenBots: Boolean
        get() = profiles.any { it.isHidden || it.name in hiddenProfiles }

    val allGroups: List<GroupInfo>
        get() {
            val defaultProfile =
                profiles.find { it.is_default == true || it.name == "default" }
                    ?: profiles.firstOrNull()
            val syncSnapshot = defaultProfile?.groupChatSyncSnapshot()
            val deletedKeys =
                syncSnapshot
                    ?.deleted
                    ?.keys
                    .orEmpty()
                    .map { it.lowercase() }

            val groupNameMap = linkedMapOf<String, String>()

            // 1. Collect from bot metadata (both `groups` array and `group` scalar)
            for (profile in profiles) {
                for (g in profile.botMeta()?.allGroups.orEmpty()) {
                    val trimmed = g.trim()
                    if (trimmed.isNotBlank()) {
                        val lower = trimmed.lowercase()
                        if (!groupNameMap.containsKey(lower)) {
                            groupNameMap[lower] = trimmed
                        }
                    }
                }
            }

            // 2. Collect from cross-device snapshot rooms (Desktop parity)
            val snapshotRooms = syncSnapshot?.rooms.orEmpty()
            for ((key, room) in snapshotRooms) {
                if (room.tombstone == true) continue
                val roomKeyLower = key.lowercase()
                if (deletedKeys.contains(roomKeyLower) ||
                    deletedKeys.contains("name:${room.name?.lowercase()}") ||
                    deletedKeys.contains("id:${room.roomId?.lowercase()}")
                ) {
                    continue
                }
                val roomName =
                    room.name?.trim()?.takeIf { it.isNotBlank() }
                        ?: key.removePrefix("name:").removePrefix("id:").trim()
                if (roomName.isNotBlank()) {
                    val lower = roomName.lowercase()
                    if (!groupNameMap.containsKey(lower)) {
                        groupNameMap[lower] = roomName
                    }
                }
            }

            return groupNameMap.values
                .map { gName ->
                    val lower = gName.lowercase()
                    val matchingRoom =
                        snapshotRooms[gName]
                            ?: snapshotRooms["name:$gName"]
                            ?: snapshotRooms["id:$gName"]
                            ?: snapshotRooms.values.find { it.name.equals(gName, ignoreCase = true) }

                    val roomMemberNames = matchingRoom?.memberNames.orEmpty().map { it.lowercase() }

                    val matchedProfiles =
                        profiles
                            .filter { profile ->
                                val botGroups = profile.botMeta()?.allGroups.orEmpty()
                                botGroups.any { it.equals(gName, ignoreCase = true) } ||
                                    roomMemberNames.contains(profile.name.lowercase()) ||
                                    roomMemberNames.contains(profile.effectiveTitle.lowercase())
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
                                val local = profiles.find { it.name.equals(mName, ignoreCase = true) }
                                matchedProfiles.add(local ?: ProfileInfo(name = mName))
                            }
                        }
                    }

                    var groupMembers: List<ProfileInfo> = matchedProfiles

                    // Fallback: If group name was composed from bot names (e.g. "default, scoutbot")
                    if (groupMembers.isEmpty() && gName.contains(",")) {
                        val targetNames = gName.split(",").map { it.trim().lowercase() }
                        groupMembers = profiles.filter { targetNames.contains(it.name.lowercase()) }
                    }

                    val lastAt =
                        matchingRoom?.log?.lastOrNull()?.at
                            ?: matchingRoom?.updatedAt
                            ?: 0L

                    GroupInfo(
                        name = gName,
                        members = groupMembers,
                        lastActivity = lastAt,
                    )
                }.sortedWith(
                    compareByDescending<GroupInfo> { it.lastActivity }
                        .thenBy { it.name },
                )
        }

    val displayGroups: List<GroupInfo>
        get() {
            val query = searchQuery.trim().lowercase()
            return allGroups.filter { group ->
                if (query.isBlank()) return@filter true
                group.name.lowercase().contains(query) ||
                    group.members.any {
                        it.name.lowercase().contains(query) ||
                            it.effectiveTitle.lowercase().contains(query)
                    }
            }
        }

    val activeNowBots: List<ProfileInfo>
        get() {
            val nowSeconds = System.currentTimeMillis() / 1000.0
            return profiles.filter { profile ->
                profile.worker_session != null ||
                    profile.name == activeProfileName ||
                    ((profile.canonical_session?.last_active ?: 0.0) > nowSeconds - 90) ||
                    ((profile.last_session?.last_active ?: 0.0) > nowSeconds - 90)
            }
        }

    val displayProfiles: List<ProfileInfo>
        get() {
            val query = searchQuery.trim().lowercase()
            return profiles
                .filter { profile ->
                    val isHidden = profile.isHidden || profile.name in hiddenProfiles
                    if (!showHidden && isHidden) return@filter false
                    if (query.isBlank()) return@filter true
                    profile.name.lowercase().contains(query) ||
                        profile.effectiveTitle.lowercase().contains(query) ||
                        profile.effectiveDescription.lowercase().contains(query)
                }.sortedWith(
                    compareByDescending<ProfileInfo> { it.name == activeProfileName }
                        .thenByDescending {
                            it.canonical_session?.last_active
                                ?: it.last_session?.last_active
                                ?: 0.0
                        }.thenBy { it.name },
                )
        }
}

class BotsViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    autoLoad: Boolean = true,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(BotsUiState())
    val uiState: StateFlow<BotsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(hiddenProfiles = AuthManager.getHiddenProfiles().toSet()) }
        if (autoLoad) {
            loadBots()
        }
    }

    fun loadBots(isRefresh: Boolean = false) {
        _uiState.update {
            if (isRefresh) {
                it.copy(isRefreshing = true, errorMessage = null)
            } else {
                it.copy(isLoading = true, errorMessage = null)
            }
        }
        viewModelScope.launch(ioDispatcher) {
            // First try fetching profiles via WebSocket RPC (profiles.list) which includes ui_meta (groups, custom avatars).
            var profilesWithMeta: List<ProfileInfo>? = null
            try {
                val rpcResult = HermesWsClient.request(WsMethods.PROFILES_LIST).await()
                val jsonElement =
                    when (rpcResult) {
                        is JsonElement -> rpcResult
                        null -> null
                        else -> rpcResult.toJsonElement()
                    }
                if (jsonElement != null) {
                    val resp = OkHttpProvider.json.decodeFromJsonElement<ProfilesResponse>(jsonElement)
                    if (!resp.profiles.isNullOrEmpty()) {
                        profilesWithMeta = resp.profiles
                    }
                }
            } catch (_: Exception) {
                // Fallback to REST API below
            }

            val profilesResult =
                if (profilesWithMeta != null) {
                    null
                } else {
                    safeApiCall { ApiClient.hermesApi.getProfiles() }
                }
            val activeResult = safeApiCall { ApiClient.hermesApi.getActiveProfile() }

            if (profilesWithMeta != null) {
                val activeName = (activeResult as? NetworkResult.Success)?.data?.active
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        profiles = profilesWithMeta,
                        activeProfileName = activeName ?: it.activeProfileName,
                        hiddenProfiles = AuthManager.getHiddenProfiles().toSet(),
                        errorMessage = null,
                    )
                }
            } else if (profilesResult is NetworkResult.Success) {
                val activeName = (activeResult as? NetworkResult.Success)?.data?.active
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        profiles = profilesResult.data.profiles.orEmpty(),
                        activeProfileName = activeName ?: it.activeProfileName,
                        hiddenProfiles = AuthManager.getHiddenProfiles().toSet(),
                        errorMessage = null,
                    )
                }
            } else {
                val err =
                    (profilesResult as? NetworkResult.Failure)?.error?.message
                        ?: "Failed to load bots"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = err,
                    )
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSelectedTab(tab: BotsTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun toggleShowHidden() {
        _uiState.update { it.copy(showHidden = !it.showHidden) }
    }

    suspend fun selectBot(bot: ProfileInfo): Boolean {
        val result = ProfileSwitchCoordinator.switchProfile(bot.name)
        return result is NetworkResult.Success
    }

    fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun createBot(
        name: String,
        title: String,
        description: String,
        shape: String,
        color: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val req =
                CreateProfileRequest(
                    name = name,
                    description = description.ifBlank { null },
                    clone_from_default = false,
                )
            val result = safeApiCall { ApiClient.hermesApi.createProfile(req) }
            if (result is NetworkResult.Success) {
                // Configure UI metadata (avatar, custom title) and bot SOUL via RPC
                val botMeta =
                    BotRosterMeta(
                        title = title.ifBlank { null },
                        description = description.ifBlank { null },
                        avatar =
                            BotAvatarMeta(
                                shape = shape,
                                color = color,
                            ),
                    )
                val botSoul = composeBotSoul(name, title, description)
                wsClientConfigureBot(name, botMeta, soul = botSoul)
                loadBots()
                onSuccess()
            } else {
                val err =
                    (result as? NetworkResult.Failure)?.error?.message
                        ?: "Failed to create bot"
                _uiState.update { it.copy(errorMessage = err) }
            }
        }
    }

    fun updateBotMeta(
        name: String,
        title: String,
        description: String,
        shape: String,
        color: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val bot = _uiState.value.profiles.find { it.name == name }
            val existingMeta = bot?.botMeta() ?: BotRosterMeta()
            val updatedMeta =
                existingMeta.copy(
                    title = title.ifBlank { null },
                    description = description.ifBlank { null },
                    avatar =
                        BotAvatarMeta(
                            shape = shape,
                            color = color,
                        ),
                )
            try {
                wsClientConfigureBot(name, updatedMeta).await()
            } catch (_: Exception) {
            }
            loadBots()
            onSuccess()
        }
    }

    fun deleteBot(
        name: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val result = safeApiCall { ApiClient.hermesApi.deleteProfile(name) }
            if (result is NetworkResult.Success) {
                loadBots()
                onSuccess()
            } else {
                val err =
                    (result as? NetworkResult.Failure)?.error?.message
                        ?: "Failed to delete bot"
                _uiState.update { it.copy(errorMessage = err) }
            }
        }
    }

    fun createGroupChat(
        groupName: String,
        botNames: List<String>,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val currentProfiles = _uiState.value.profiles
            // 1. Update bot metadata on each member bot
            for (name in botNames) {
                val bot = currentProfiles.find { it.name == name } ?: continue
                val existingGroups = bot.botMeta()?.allGroups.orEmpty()
                if (!existingGroups.any { it.equals(groupName, ignoreCase = true) }) {
                    val updatedMeta =
                        (bot.botMeta() ?: BotRosterMeta()).copy(
                            groups = existingGroups + groupName,
                            group = existingGroups.firstOrNull() ?: groupName,
                        )
                    try {
                        wsClientConfigureBot(name, updatedMeta).await()
                    } catch (_: Exception) {
                    }
                }
            }

            // 2. Also register room in hermes-bots-groups sync snapshot on default profile
            try {
                val defaultProfile =
                    currentProfiles.find { it.is_default == true || it.name == "default" }
                        ?: currentProfiles.firstOrNull()
                if (defaultProfile != null) {
                    val existingSnapshot =
                        defaultProfile.groupChatSyncSnapshot()
                            ?: GroupChatSyncSnapshot(version = 3, rooms = emptyMap())
                    val roomKey = "name:$groupName"
                    val newRoom =
                        GroupChatRoomMeta(
                            name = groupName,
                            members = botNames.map { JsonPrimitive(it) },
                            updatedAt = System.currentTimeMillis(),
                            createdAt = System.currentTimeMillis(),
                        )
                    val updatedRooms = existingSnapshot.rooms.orEmpty() + (roomKey to newRoom)
                    val updatedDeleted =
                        existingSnapshot.deleted.orEmpty().filterKeys {
                            !it.equals(roomKey, ignoreCase = true) && !it.equals(groupName, ignoreCase = true)
                        }
                    val newSnapshot =
                        existingSnapshot.copy(
                            version = 3,
                            updatedAt = System.currentTimeMillis(),
                            rooms = updatedRooms,
                            deleted = updatedDeleted,
                        )
                    HermesWsClient
                        .request(
                            WsMethods.PROFILES_CONFIGURE,
                            mapOf(
                                "name" to defaultProfile.name,
                                "ui_meta" to mapOf("hermes-bots-groups" to newSnapshot.toMap()),
                            ),
                        ).await()
                }
            } catch (_: Exception) {
            }

            loadBots()
            onSuccess()
        }
    }

    fun disbandGroupChat(
        groupName: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch(ioDispatcher) {
            val currentProfiles = _uiState.value.profiles
            // 1. Remove from all member bots' metadata
            for (bot in currentProfiles) {
                val existingGroups = bot.botMeta()?.allGroups.orEmpty()
                if (existingGroups.any { it.equals(groupName, ignoreCase = true) }) {
                    val filtered = existingGroups.filterNot { it.equals(groupName, ignoreCase = true) }
                    val updatedMeta =
                        (bot.botMeta() ?: BotRosterMeta()).copy(
                            groups = filtered,
                            group = filtered.firstOrNull(),
                        )
                    try {
                        wsClientConfigureBot(bot.name, updatedMeta).await()
                    } catch (_: Exception) {
                    }
                }
            }

            // 2. Remove room from hermes-bots-groups snapshot and register tombstone in deleted map
            try {
                val defaultProfile =
                    currentProfiles.find { it.is_default == true || it.name == "default" }
                        ?: currentProfiles.firstOrNull()
                if (defaultProfile != null) {
                    val existingSnapshot = defaultProfile.groupChatSyncSnapshot()
                    if (existingSnapshot != null) {
                        val updatedRooms =
                            existingSnapshot.rooms.orEmpty().filterKeys { key ->
                                val room = existingSnapshot.rooms?.get(key)
                                !key.equals(groupName, ignoreCase = true) &&
                                    !key.equals("name:$groupName", ignoreCase = true) &&
                                    !key.equals("id:$groupName", ignoreCase = true) &&
                                    !(room?.name.equals(groupName, ignoreCase = true))
                            }
                        val deletedMap =
                            existingSnapshot.deleted.orEmpty() + ("name:$groupName" to System.currentTimeMillis())
                        val newSnapshot =
                            existingSnapshot.copy(
                                version = 3,
                                updatedAt = System.currentTimeMillis(),
                                rooms = updatedRooms,
                                deleted = deletedMap,
                            )
                        HermesWsClient
                            .request(
                                WsMethods.PROFILES_CONFIGURE,
                                mapOf(
                                    "name" to defaultProfile.name,
                                    "ui_meta" to mapOf("hermes-bots-groups" to newSnapshot.toMap()),
                                ),
                            ).await()
                    }
                }
            } catch (_: Exception) {
            }

            loadBots()
            onSuccess()
        }
    }

    private fun composeBotSoul(
        name: String,
        title: String,
        description: String,
    ): String {
        val displayName = title.ifBlank { name }
        val lines = mutableListOf<String>()
        lines.add("# $displayName")
        lines.add("")
        if (title.isNotBlank()) lines.add("**Role:** $title")
        if (description.isNotBlank()) lines.add("**Mission:** $description")
        lines.add("")
        lines.add("You are $displayName, a persistent named agent (profile `$name`) on this machine.")
        lines.add("You keep your own memory, skills, and conversation history across sessions.")
        return lines.joinToString("\n")
    }

    private fun wsClientConfigureBot(
        name: String,
        meta: BotRosterMeta,
        soul: String? = null,
    ): kotlinx.coroutines.CompletableDeferred<Any?> {
        val metaMap =
            buildMap<String, Any> {
                meta.title?.let { put("title", it) }
                meta.description?.let { put("description", it) }
                meta.avatar?.let { av ->
                    put(
                        "avatar",
                        buildMap<String, Any> {
                            av.shape?.let { put("shape", it) }
                            av.color?.let { put("color", it) }
                            av.icon?.let { put("icon", it) }
                        },
                    )
                }
                if (!meta.groups.isNullOrEmpty()) {
                    put("groups", meta.groups)
                }
            }

        val params =
            buildMap<String, Any> {
                put("name", name)
                put("ui_meta", mapOf("hermes-bots" to metaMap))
                soul?.let { put("soul", it) }
            }

        return HermesWsClient.request(
            WsMethods.PROFILES_CONFIGURE,
            params,
        )
    }
}
