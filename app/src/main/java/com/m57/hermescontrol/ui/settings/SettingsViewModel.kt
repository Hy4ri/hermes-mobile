package com.m57.hermescontrol.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.config.resolveBaseUrl
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val baseUrl: String = ServerEndpoint.DEFAULT_BASE_URL,
    val transportWarning: String? = null,
    val token: String = "",
    val autoReconnect: Boolean = true,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val useDynamicColors: Boolean = true,
    val themePreset: ThemePreset = ThemePreset.DEFAULT,
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val isSaved: Boolean = false,
    val typingEffectEnabled: Boolean = false,
    val typingEffectDelayMs: Int = 30,
    val profiles: List<ConnectionProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val renameProfileName: String = "",
    val appLanguage: String = "system",
    // Profile add/edit dialog
    val showProfileDialog: Boolean = false,
    val editingProfileId: String? = null,
    val dialogProfileName: String = "",
    val dialogProfileBaseUrl: String = ServerEndpoint.DEFAULT_BASE_URL,
    val dialogProfileToken: String = "",
    val dialogProfileError: String? = null,
    // Delete confirmation
    val showDeleteConfirm: Boolean = false,
    val profileToDeleteId: String? = null,
    val profileToDeleteName: String = "",
    val navigateToLogin: Boolean = false,
)

class SettingsViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(ioDispatcher) {
            loadSettings()
        }
    }

    private suspend fun loadSettings() {
        val selectedId = AuthManager.getSelectedProfileId()
        val baseUrl = AuthManager.getBaseUrl()
        val token = AuthManager.getToken() ?: ""
        val autoReconnect = AuthManager.isAutoReconnect()
        val themePreference = AuthManager.getThemePreference()
        val useDynamicColors = AuthManager.isUseDynamicColors()
        val themePreset = AuthManager.getThemePreset()
        val typingEffectEnabled = AuthManager.isTypingEffectEnabled()
        val typingEffectDelayMs = AuthManager.getTypingEffectDelayMs()
        val profiles = AuthManager.getConnectionProfiles()
        val appLanguage = AuthManager.getAppLanguage()
        val renameProfileName =
            profiles.firstOrNull { p -> p.id == selectedId }?.name ?: ""
        val transportWarning =
            runCatching {
                ServerEndpoint.parse(baseUrl, CleartextPolicy.ALLOW_WITH_WARNING).securityWarning
            }.getOrNull()
        _uiState.update {
            it.copy(
                baseUrl = baseUrl,
                transportWarning = transportWarning,
                token = token,
                autoReconnect = autoReconnect,
                themePreference = themePreference,
                useDynamicColors = useDynamicColors,
                themePreset = themePreset,
                typingEffectEnabled = typingEffectEnabled,
                typingEffectDelayMs = typingEffectDelayMs,
                profiles = profiles,
                selectedProfileId = selectedId,
                renameProfileName = renameProfileName,
                appLanguage = appLanguage,
            )
        }
    }

    fun selectProfile(profileId: String?) {
        AuthManager.setSelectedProfileId(profileId)
        viewModelScope.launch(ioDispatcher) { loadSettings() }
        ApiClient.rebuild()
    }

    fun onRenameProfileNameChange(value: String) {
        _uiState.update { it.copy(renameProfileName = value, isSaved = false) }
    }

    fun renameProfile() {
        val currentId = _uiState.value.selectedProfileId ?: return
        val newName = _uiState.value.renameProfileName.trim()
        if (newName.isBlank()) return
        val updatedProfiles =
            AuthManager.getConnectionProfiles().map {
                if (it.id == currentId) it.copy(name = newName) else it
            }
        AuthManager.saveConnectionProfiles(updatedProfiles)
        viewModelScope.launch(ioDispatcher) { loadSettings() }
    }

    fun deleteProfile(profileId: String) {
        val updatedProfiles = AuthManager.getConnectionProfiles().filter { it.id != profileId }
        AuthManager.saveConnectionProfiles(updatedProfiles)
        AuthManager.setProfileToken(profileId, null)
        // Never leave selection null (issue #478): if the deleted profile was selected,
        // fall back to the default profile instead of clearing selection.
        if (AuthManager.getSelectedProfileId() == profileId) {
            if (updatedProfiles.none { it.id == AuthManager.DEFAULT_PROFILE_ID }) {
                AuthManager.ensureDefaultProfile()
            }
            AuthManager.setSelectedProfileId(AuthManager.DEFAULT_PROFILE_ID)
        }
        viewModelScope.launch(ioDispatcher) { loadSettings() }
        ApiClient.rebuild()
    }

    // ── Profile add/edit dialog ──────────────────────────────────────────

    fun openAddProfile() {
        _uiState.update {
            it.copy(
                showProfileDialog = true,
                editingProfileId = null,
                dialogProfileName = "",
                dialogProfileBaseUrl = ServerEndpoint.DEFAULT_BASE_URL,
                dialogProfileToken = "",
                dialogProfileError = null,
            )
        }
    }

    fun openEditProfile(profileId: String) {
        val profile = AuthManager.getConnectionProfiles().firstOrNull { it.id == profileId } ?: return
        val token = AuthManager.getProfileToken(profileId) ?: ""
        _uiState.update {
            it.copy(
                showProfileDialog = true,
                editingProfileId = profileId,
                dialogProfileName = profile.name,
                dialogProfileBaseUrl = profile.resolveBaseUrl(AuthManager.getBaseUrl()),
                dialogProfileToken = token,
                dialogProfileError = null,
            )
        }
    }

    fun closeProfileDialog() {
        _uiState.update { it.copy(showProfileDialog = false, editingProfileId = null) }
    }

    fun onDialogProfileNameChange(value: String) {
        _uiState.update { it.copy(dialogProfileName = value) }
    }

    fun onDialogProfileBaseUrlChange(value: String) {
        val trimmed = value.trim()
        val error =
            runCatching {
                ServerEndpoint.parse(trimmed, CleartextPolicy.ALLOW_WITH_WARNING)
            }.exceptionOrNull()?.message
        _uiState.update { it.copy(dialogProfileBaseUrl = trimmed, dialogProfileError = error) }
    }

    fun onDialogProfileTokenChange(value: String) {
        _uiState.update { it.copy(dialogProfileToken = value.trim()) }
    }

    fun saveProfileFromDialog() {
        val state = _uiState.value
        val name = state.dialogProfileName.trim()
        val baseUrl = state.dialogProfileBaseUrl.trim()

        if (name.isBlank()) return
        val normalized =
            runCatching {
                ServerEndpoint.parse(baseUrl, CleartextPolicy.ALLOW_WITH_WARNING).baseUrl.toString()
            }.getOrNull() ?: return
        val token = state.dialogProfileToken

        val profiles = AuthManager.getConnectionProfiles().toMutableList()
        val editingId = state.editingProfileId

        if (editingId != null) {
            // Update existing profile
            val index = profiles.indexOfFirst { it.id == editingId }
            if (index == -1) return
            val oldToken = AuthManager.getProfileToken(editingId)
            profiles[index] = profiles[index].copy(name = name, baseUrl = normalized)
            AuthManager.saveConnectionProfiles(profiles)
            if (token != oldToken) {
                AuthManager.setProfileToken(editingId, token)
            }
        } else {
            // Add new profile
            val newProfile =
                ConnectionProfile(
                    name = name,
                    baseUrl = normalized,
                )
            profiles.add(newProfile)
            AuthManager.saveConnectionProfiles(profiles)
            AuthManager.setProfileToken(newProfile.id, "")
            AuthManager.setSelectedProfileId(newProfile.id)
            _uiState.update { it.copy(navigateToLogin = true) }
        }

        closeProfileDialog()
        viewModelScope.launch(ioDispatcher) { loadSettings() }
        ApiClient.rebuild()
    }

    fun clearNavigateToLogin() {
        _uiState.update { it.copy(navigateToLogin = false) }
    }

    // ── Delete confirmation ──────────────────────────────────────────────

    fun requestDeleteProfile(profileId: String) {
        val profile = AuthManager.getConnectionProfiles().firstOrNull { it.id == profileId }
        _uiState.update {
            it.copy(
                showDeleteConfirm = true,
                profileToDeleteId = profileId,
                profileToDeleteName = profile?.name ?: "",
            )
        }
    }

    fun confirmDeleteProfile() {
        val profileId = _uiState.value.profileToDeleteId ?: return
        deleteProfile(profileId)
        _uiState.update {
            it.copy(showDeleteConfirm = false, profileToDeleteId = null, profileToDeleteName = "")
        }
    }

    fun cancelDeleteProfile() {
        _uiState.update {
            it.copy(showDeleteConfirm = false, profileToDeleteId = null, profileToDeleteName = "")
        }
    }

    fun onBaseUrlChange(value: String) {
        val trimmed = value.trim()
        val transportWarning =
            runCatching {
                ServerEndpoint.parse(trimmed, CleartextPolicy.ALLOW_WITH_WARNING).securityWarning
            }.getOrNull()
        _uiState.update { it.copy(baseUrl = trimmed, transportWarning = transportWarning, isSaved = false) }
    }

    fun onTokenChange(value: String) {
        _uiState.update { it.copy(token = value.trim(), isSaved = false) }
    }

    fun onAutoReconnectChange(enabled: Boolean) {
        _uiState.update { it.copy(autoReconnect = enabled, isSaved = false) }
        AuthManager.setAutoReconnect(enabled)
    }

    fun onThemeChange(theme: ThemePreference) {
        _uiState.update { it.copy(themePreference = theme, isSaved = false) }
        AuthManager.setThemePreference(theme)
    }

    fun onUseDynamicColorsChange(enabled: Boolean) {
        _uiState.update { it.copy(useDynamicColors = enabled, isSaved = false) }
        AuthManager.setUseDynamicColors(enabled)
    }

    fun onThemePresetChange(preset: ThemePreset) {
        _uiState.update { it.copy(themePreset = preset, isSaved = false) }
        AuthManager.setThemePreset(preset)
    }

    fun onAppLanguageChange(code: String) {
        _uiState.update { it.copy(appLanguage = code, isSaved = false) }
        AuthManager.setAppLanguage(code)
    }

    fun onTypingEffectEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(typingEffectEnabled = enabled, isSaved = false) }
        AuthManager.setTypingEffectEnabled(enabled)
    }

    fun onTypingEffectDelayMsChange(delayMs: Int) {
        _uiState.update { it.copy(typingEffectDelayMs = delayMs, isSaved = false) }
        AuthManager.setTypingEffectDelayMs(delayMs)
    }

    /** Clear all auth credentials — logs out and returns to landing screen. */
    fun logout() {
        AuthManager.setToken(null)
        AuthManager.setSessionCookie(null)
        AuthManager.setWsAuthParam("token")
        HermesWsClient.disconnect()
        // Don't rebuild ApiClient here — let the navigation complete first
    }

    fun save() {
        val state = _uiState.value
        val normalized =
            runCatching {
                ServerEndpoint.parse(state.baseUrl, CleartextPolicy.ALLOW_WITH_WARNING).baseUrl.toString()
            }.getOrNull() ?: ServerEndpoint.DEFAULT_BASE_URL

        AuthManager.setBaseUrl(normalized)
        AuthManager.setToken(state.token)
        AuthManager.setAutoReconnect(state.autoReconnect)
        // B6 (Jun 18 2026, kanban t_86e9be9b): persist theme choice so it
        // survives a cold start (was previously dropped on save()).
        AuthManager.setThemePreference(state.themePreference)
        AuthManager.setUseDynamicColors(state.useDynamicColors)
        AuthManager.setThemePreset(state.themePreset)
        AuthManager.setTypingEffectEnabled(state.typingEffectEnabled)
        AuthManager.setTypingEffectDelayMs(state.typingEffectDelayMs)
        ApiClient.rebuild()

        viewModelScope.launch(ioDispatcher) {
            loadSettings()
            _uiState.update { it.copy(isSaved = true, testResult = null) }
        }
    }

    fun testConnection() {
        val state = _uiState.value
        if (state.token.isBlank()) {
            _uiState.update { it.copy(testResult = "❌ Token is required") }
            return
        }

        // Save first so ApiClient uses updated settings
        save()

        _uiState.update { it.copy(isTesting = true, testResult = null) }

        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.getStatus() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(isTesting = false, testResult = "✅ Connected successfully")
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testResult = "❌ ${result.error.message}",
                        )
                    }
                }
            }
        }
    }
}
