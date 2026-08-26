package com.m57.hermescontrol.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.BotRosterMeta
import com.m57.hermescontrol.data.model.CreateProfileRequest
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
import kotlinx.serialization.json.decodeFromJsonElement

enum class BotsTab {
    BOTS,
    GROUPS,
}

data class GroupInfo(
    val name: String,
    val members: List<ProfileInfo>,
)

data class BotsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val profiles: List<ProfileInfo> = emptyList(),
    val activeProfileName: String? = null,
    val searchQuery: String = "",
    val showHidden: Boolean = false,
    val selectedTab: BotsTab = BotsTab.BOTS,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
) {
    val hasHiddenBots: Boolean
        get() = profiles.any { it.isHidden }

    val allGroups: List<GroupInfo>
        get() {
            val groupNames =
                profiles
                    .flatMap { it.botMeta()?.groups.orEmpty() }
                    .distinct()
                    .sorted()
            return groupNames.map { gName ->
                GroupInfo(
                    name = gName,
                    members = profiles.filter { it.botMeta()?.groups.orEmpty().contains(gName) },
                )
            }
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
                    if (!showHidden && profile.isHidden) return@filter false
                    if (query.isBlank()) return@filter true
                    profile.name.lowercase().contains(query) ||
                        profile.effectiveTitle.lowercase().contains(query) ||
                        profile.effectiveDescription.lowercase().contains(query)
                }
                .sortedWith(
                    compareByDescending<ProfileInfo> { it.name == activeProfileName }
                        .thenByDescending {
                            it.canonical_session?.last_active
                                ?: it.last_session?.last_active
                                ?: 0.0
                        }
                        .thenBy { it.name },
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
                    clone_from_default = true,
                )
            val result = safeApiCall { ApiClient.hermesApi.createProfile(req) }
            if (result is NetworkResult.Success) {
                // Configure UI metadata (avatar, custom title) via RPC
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
                wsClientConfigureBot(name, botMeta)
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
            for (name in botNames) {
                val bot = currentProfiles.find { it.name == name } ?: continue
                val existingGroups = bot.botMeta()?.groups.orEmpty()
                if (!existingGroups.contains(groupName)) {
                    val updatedMeta =
                        (bot.botMeta() ?: BotRosterMeta()).copy(
                            groups = existingGroups + groupName,
                        )
                    try {
                        wsClientConfigureBot(name, updatedMeta).await()
                    } catch (_: Exception) {
                    }
                }
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
            for (bot in currentProfiles) {
                val existingGroups = bot.botMeta()?.groups.orEmpty()
                if (existingGroups.contains(groupName)) {
                    val updatedMeta =
                        (bot.botMeta() ?: BotRosterMeta()).copy(
                            groups = existingGroups - groupName,
                        )
                    try {
                        wsClientConfigureBot(bot.name, updatedMeta).await()
                    } catch (_: Exception) {
                    }
                }
            }
            loadBots()
            onSuccess()
        }
    }

    private fun wsClientConfigureBot(
        name: String,
        meta: BotRosterMeta,
    ): kotlinx.coroutines.CompletableDeferred<Any?> {
        val metaMap =
            buildMap<String, Any?> {
                put("title", meta.title)
                put("description", meta.description)
                meta.avatar?.let { av ->
                    put(
                        "avatar",
                        buildMap<String, Any?> {
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

        return HermesWsClient.request(
            WsMethods.PROFILES_CONFIGURE,
            mapOf(
                "name" to name,
                "ui_meta" to mapOf("hermes-bots" to metaMap),
            ),
        )
    }
}
