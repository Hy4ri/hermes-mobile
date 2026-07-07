package com.m57.hermescontrol.ui.connect

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ConnectUiState(
    val token: String = "",
    val host: String = "127.0.0.1",
    val port: String = "9119",
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
        val savedHost = AuthManager.getHost()
        val savedPort = AuthManager.getPort()
        val profiles = AuthManager.getConnectionProfiles()
        val selectedId = AuthManager.getSelectedProfileId()
        val selectedProfile = profiles.firstOrNull { it.id == selectedId }
        _uiState.update {
            it.copy(
                token = savedToken,
                host = selectedProfile?.host ?: savedHost,
                port = (selectedProfile?.port ?: savedPort).toString(),
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
                host = profile.host,
                port = profile.port.toString(),
                token = token,
            )
        }
    }

    fun onTokenChange(value: String) {
        _uiState.update { it.copy(token = value.trim(), errorMessage = null) }
    }

    fun onHostChange(value: String) {
        _uiState.update { it.copy(host = value.trim(), errorMessage = null) }
    }

    fun onPortChange(value: String) {
        _uiState.update { it.copy(port = value.filter { c -> c.isDigit() }, errorMessage = null) }
    }

    fun connect() {
        val state = _uiState.value
        if (state.token.isBlank()) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.connect_error_token_required)) }
            return
        }
        if (state.host.isBlank()) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.connect_error_host_required)) }
            return
        }
        val port = state.port.toIntOrNull()
        if (port == null || port !in 1..65535) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.connect_error_port_invalid)) }
            return
        }

        _uiState.update { it.copy(isConnecting = true, errorMessage = null) }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    val tempApi = ApiClient.createTempService(state.host, port, state.token)
                    safeApiCall { tempApi.getStatus() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    // Persist credentials to the selected (Default) profile upon successful verification.
                    // setToken/setHost/setPort delegate to the currently-selected profile, which is
                    // always non-null after issue #478 (never the legacy standalone path).
                    AuthManager.setToken(state.token)
                    AuthManager.setHost(state.host)
                    AuthManager.setPort(port)
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
                                currentProfiles[existingIndex].copy(host = state.host, port = port)
                            } else {
                                ConnectionProfile(
                                    name = state.profileName,
                                    host = state.host,
                                    port = port,
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

    fun onPairingString(value: String) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return

        try {
            if (trimmed.startsWith("hermes://connect?", ignoreCase = true)) {
                val uri = android.net.Uri.parse(trimmed)
                val host = uri.getQueryParameter("host")
                val port = uri.getQueryParameter("port")
                val token = uri.getQueryParameter("token")
                if (host != null && port != null && token != null) {
                    _uiState.update {
                        it.copy(
                            host = host,
                            port = port,
                            token = token,
                            errorMessage = null,
                        )
                    }
                    connect()
                    return
                }
            }

            // Try decoding as Base64 JSON
            try {
                val decodedBytes = android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
                val decodedString = String(decodedBytes, Charsets.UTF_8)
                if (decodedString.startsWith("{") && decodedString.endsWith("}")) {
                    val json = OkHttpProvider.json.parseToJsonElement(decodedString).jsonObject
                    val host = json["host"]?.jsonPrimitive?.content
                    val port = json["port"]?.jsonPrimitive?.content
                    val token = json["token"]?.jsonPrimitive?.content
                    if (host != null && port != null && token != null) {
                        _uiState.update {
                            it.copy(
                                host = host,
                                port = port,
                                token = token,
                                errorMessage = null,
                            )
                        }
                        connect()
                        return
                    }
                    // Decoded valid JSON but missing required fields
                    _uiState.update {
                        it.copy(
                            errorMessage = app.getString(R.string.connect_error_missing_fields),
                        )
                    }
                    return
                }
                // Decoded to valid string but not JSON shape — not a pairing string
                _uiState.update {
                    it.copy(
                        errorMessage = app.getString(R.string.connect_error_malformed),
                    )
                }
                return
            } catch (e: IllegalArgumentException) {
                // Not valid Base64 — check if input looks like a raw token
                if (trimmed.length >= 32 && trimmed.matches(Regex("[A-Za-z0-9_\\-]+"))) {
                    _uiState.update { it.copy(token = trimmed, errorMessage = null) }
                } else {
                    _uiState.update {
                        it.copy(
                            errorMessage = app.getString(R.string.connect_error_malformed),
                        )
                    }
                }
                return
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage =
                            String.format(
                                app.getString(R.string.connect_error_parse_failed),
                                e.message ?: "",
                            ),
                    )
                }
                return
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    errorMessage = String.format(app.getString(R.string.connect_error_parse_failed), e.message ?: ""),
                )
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
