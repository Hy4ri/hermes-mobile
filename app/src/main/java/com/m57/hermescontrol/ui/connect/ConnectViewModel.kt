package com.m57.hermescontrol.ui.connect

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.config.resolveBaseUrl
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConnectUiState(
    val token: String = "",
    val baseUrl: String = ServerEndpoint.DEFAULT_BASE_URL,
    val transportWarning: String? = null,
    val isConnecting: Boolean = false,
    val connectionSuccess: Boolean = false,
    val errorMessage: String? = null,
    val profileName: String = "",
    val saveProfile: Boolean = false,
    val profiles: List<ConnectionProfile> = emptyList(),
    val selectedProfile: ConnectionProfile? = null,
)

class ConnectViewModel(
    private val app: Application,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    init {
        loadSavedValues()
    }

    fun loadSavedValues() {
        val savedToken = AuthManager.getToken() ?: ""
        val savedBaseUrl = AuthManager.getBaseUrl()
        val profiles = AuthManager.getConnectionProfiles()
        val selectedId = AuthManager.getSelectedProfileId()
        val selectedProfile = profiles.firstOrNull { it.id == selectedId }
        _uiState.update {
            it.copy(
                token = savedToken,
                baseUrl = selectedProfile?.resolveBaseUrl(savedBaseUrl) ?: savedBaseUrl,
                profiles = profiles,
                selectedProfile = selectedProfile,
                profileName = selectedProfile?.name ?: "",
            )
        }
    }

    fun onProfileNameChange(value: String) {
        _uiState.update { it.copy(profileName = value, errorMessage = null) }
    }

    fun onSaveProfileChange(value: Boolean) {
        _uiState.update { it.copy(saveProfile = value) }
    }

    fun selectProfile(profile: ConnectionProfile) {
        AuthManager.setSelectedProfileId(profile.id)
        val token = AuthManager.getProfileToken(profile.id) ?: ""
        _uiState.update {
            it.copy(
                selectedProfile = profile,
                profileName = profile.name,
                baseUrl = profile.resolveBaseUrl(AuthManager.getBaseUrl()),
                token = token,
            )
        }
    }

    fun onTokenChange(value: String) {
        _uiState.update { it.copy(token = value.trim(), errorMessage = null) }
    }

    fun onBaseUrlChange(value: String) {
        val trimmed = value.trim()
        val warning =
            runCatching {
                ServerEndpoint.parse(trimmed, CleartextPolicy.ALLOW_WITH_WARNING).securityWarning
            }.getOrNull()
        _uiState.update { it.copy(baseUrl = trimmed, transportWarning = warning, errorMessage = null) }
    }

    fun connect() {
        val state = _uiState.value
        if (state.token.isBlank()) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.connect_error_token_required)) }
            return
        }
        val endpoint =
            runCatching { ServerEndpoint.parseForBuild(state.baseUrl) }.getOrNull()
        if (endpoint == null) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.connect_error_url_invalid)) }
            return
        }

        _uiState.update { it.copy(isConnecting = true, errorMessage = null) }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    val tempApi = ApiClient.createTempService(endpoint.baseUrl.toString(), state.token)
                    safeApiCall { tempApi.getStatus() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    // Persist credentials to the selected (Default) profile upon successful verification.
                    AuthManager.setToken(state.token)
                    AuthManager.setBaseUrl(state.baseUrl)
                    ApiClient.rebuild()

                    if (state.saveProfile) {
                        val currentProfiles = AuthManager.getConnectionProfiles()
                        val existingIndex =
                            currentProfiles.indexOfFirst {
                                it.name.equals(
                                    state.profileName,
                                    ignoreCase = true,
                                )
                            }
                        val targetProfile =
                            if (existingIndex >= 0) {
                                currentProfiles[existingIndex].copy(baseUrl = state.baseUrl)
                            } else {
                                ConnectionProfile(
                                    name = state.profileName,
                                    baseUrl = state.baseUrl,
                                )
                            }
                        val updatedProfiles =
                            if (existingIndex >= 0) {
                                currentProfiles.mapIndexed { idx, p -> if (idx == existingIndex) targetProfile else p }
                            } else {
                                currentProfiles + targetProfile
                            }
                        AuthManager.saveConnectionProfiles(updatedProfiles)
                        AuthManager.setSelectedProfileId(targetProfile.id)
                        AuthManager.setProfileToken(targetProfile.id, state.token)
                    } else {
                        // No explicit profile name — store the connection on the default profile.
                        AuthManager.ensureDefaultSelected()
                        AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
                        AuthManager.setProfileToken(AuthManager.DEFAULT_PROFILE_ID, state.token)
                    }
                    ApiClient.rebuild()
                    _uiState.update {
                        it.copy(isConnecting = false, connectionSuccess = true, errorMessage = null)
                    }
                }

                is NetworkResult.Failure -> {
                    val msg =
                        when (val err = result.error) {
                            is NetworkError.Http -> {
                                when (err.code) {
                                    401 -> {
                                        AuthManager.setToken(null)
                                        if (AuthManager.getSelectedProfileId() != null) {
                                            AuthManager.setProfileToken(AuthManager.getSelectedProfileId()!!, null)
                                        }
                                        app.getString(R.string.connect_error_401)
                                    }

                                    403 -> {
                                        app.getString(R.string.connect_error_403)
                                    }

                                    else -> {
                                        String.format(app.getString(R.string.connect_error_http_code), err.code)
                                    }
                                }
                            }

                            is NetworkError.AuthExpired -> {
                                AuthManager.setToken(null)
                                if (AuthManager.getSelectedProfileId() != null) {
                                    AuthManager.setProfileToken(AuthManager.getSelectedProfileId()!!, null)
                                }
                                app.getString(R.string.connect_error_401)
                            }

                            is NetworkError.Connection -> {
                                val causeMessage = err.cause.message ?: ""
                                when {
                                    causeMessage.contains(
                                        "timeout",
                                        true,
                                    ) -> {
                                        app.getString(R.string.connect_error_timeout)
                                    }

                                    causeMessage.contains(
                                        "refused",
                                        true,
                                    ) -> {
                                        app.getString(R.string.connect_error_refused)
                                    }

                                    causeMessage.contains(
                                        "resolve",
                                        true,
                                    ) -> {
                                        app.getString(R.string.connect_error_resolve)
                                    }

                                    else -> {
                                        String.format(
                                            app.getString(R.string.connect_error_connection_failed),
                                            err.cause.message ?: "",
                                        )
                                    }
                                }
                            }

                            is NetworkError.Unknown -> {
                                val causeMessage = err.cause.message ?: ""
                                when {
                                    causeMessage.contains(
                                        "timeout",
                                        true,
                                    ) -> {
                                        app.getString(R.string.connect_error_timeout)
                                    }

                                    causeMessage.contains(
                                        "refused",
                                        true,
                                    ) -> {
                                        app.getString(R.string.connect_error_refused)
                                    }

                                    causeMessage.contains(
                                        "resolve",
                                        true,
                                    ) -> {
                                        app.getString(R.string.connect_error_resolve)
                                    }

                                    else -> {
                                        String.format(
                                            app.getString(R.string.connect_error_connection_failed),
                                            err.cause.message ?: "",
                                        )
                                    }
                                }
                            }
                        }
                    _uiState.update { it.copy(isConnecting = false, errorMessage = msg) }
                }
            }
        }
    }
}

class ConnectViewModelFactory(
    private val app: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ConnectViewModel(app) as T
}
