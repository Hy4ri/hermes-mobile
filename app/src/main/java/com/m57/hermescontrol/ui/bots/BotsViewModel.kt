package com.m57.hermescontrol.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.BotAvatarMeta
import com.m57.hermescontrol.data.model.BotRosterMeta
import com.m57.hermescontrol.data.model.CreateProfileRequest
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BotsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val profiles: List<ProfileInfo> = emptyList(),
    val activeProfileName: String? = null,
    val searchQuery: String = "",
    val showHidden: Boolean = false,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
) {
    val hasHiddenBots: Boolean
        get() = profiles.any { it.isHidden }

    val activeNowBots: List<ProfileInfo>
        get() {
            val nowSeconds = System.currentTimeMillis() / 1000
            return profiles.filter { profile ->
                profile.worker_session != null ||
                    profile.name == activeProfileName ||
                    ((profile.canonical_session?.last_active ?: 0L) > nowSeconds - 90) ||
                    ((profile.last_session?.last_active ?: 0L) > nowSeconds - 90)
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
                                ?: 0L
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
            val profilesResult = safeApiCall { ApiClient.hermesApi.getProfiles() }
            val activeResult = safeApiCall { ApiClient.hermesApi.getActiveProfile() }

            if (profilesResult is NetworkResult.Success) {
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

    private fun wsClientConfigureBot(
        name: String,
        meta: BotRosterMeta,
    ) {
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
            }

        HermesWsClient.send(
            WsMethods.PROFILES_CONFIGURE,
            mapOf(
                "name" to name,
                "ui_meta" to mapOf("hermes-bots" to metaMap),
            ),
        )
    }
}
