package com.m57.hermescontrol.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.ScreenRegistry
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.theme.BottomNavDisplayMode
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
    val host: String = "127.0.0.1",
    val port: String = "9119",
    val token: String = "",
    val autoReconnect: Boolean = true,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val useDynamicColors: Boolean = true,
    val themePreset: ThemePreset = ThemePreset.DEFAULT,
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val isSaved: Boolean = false,
    val selectedNavItems: List<String> = emptyList(),
    val typingEffectEnabled: Boolean = false,
    val typingEffectDelayMs: Int = 30,
    val profiles: List<com.m57.hermescontrol.data.model.ConnectionProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val renameProfileName: String = "",
    val bottomNavDisplayMode: BottomNavDisplayMode = BottomNavDisplayMode.ICON_AND_TEXT,
    // Profile add/edit dialog
    val showProfileDialog: Boolean = false,
    val editingProfileId: String? = null,
    val dialogProfileName: String = "",
    val dialogProfileHost: String = "",
    val dialogProfilePort: String = "",
    val dialogProfileToken: String = "",
    // Delete confirmation
    val showDeleteConfirm: Boolean = false,
    val profileToDeleteId: String? = null,
    val profileToDeleteName: String = "",
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
        val host = AuthManager.getHost()
        val port = AuthManager.getPort().toString()
        val token = AuthManager.getToken() ?: ""
        val autoReconnect = AuthManager.isAutoReconnect()
        val themePreference = AuthManager.getThemePreference()
        val useDynamicColors = AuthManager.isUseDynamicColors()
        val themePreset = AuthManager.getThemePreset()
        val selectedNavItems = AuthManager.getBottomNavItems()
        val typingEffectEnabled = AuthManager.isTypingEffectEnabled()
        val typingEffectDelayMs = AuthManager.getTypingEffectDelayMs()
        val profiles = AuthManager.getConnectionProfiles()
        val bottomNavDisplayMode = AuthManager.getBottomNavDisplayMode()
        val renameProfileName =
            profiles.firstOrNull { p -> p.id == selectedId }?.name ?: ""
        _uiState.update {
            it.copy(
                host = host,
                port = port,
                token = token,
                autoReconnect = autoReconnect,
                themePreference = themePreference,
                useDynamicColors = useDynamicColors,
                themePreset = themePreset,
                selectedNavItems = selectedNavItems,
                typingEffectEnabled = typingEffectEnabled,
                typingEffectDelayMs = typingEffectDelayMs,
                profiles = profiles,
                selectedProfileId = selectedId,
                renameProfileName = renameProfileName,
                bottomNavDisplayMode = bottomNavDisplayMode,
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
        if (AuthManager.getSelectedProfileId() == profileId) {
            AuthManager.setSelectedProfileId(null)
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
                dialogProfileHost = "127.0.0.1",
                dialogProfilePort = "9119",
                dialogProfileToken = "",
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
                dialogProfileHost = profile.host,
                dialogProfilePort = profile.port.toString(),
                dialogProfileToken = token,
            )
        }
    }

    fun closeProfileDialog() {
        _uiState.update {
            it.copy(showProfileDialog = false, editingProfileId = null)
        }
    }

    fun onDialogProfileNameChange(value: String) {
        _uiState.update { it.copy(dialogProfileName = value) }
    }

    fun onDialogProfileHostChange(value: String) {
        _uiState.update { it.copy(dialogProfileHost = value.trim()) }
    }

    fun onDialogProfilePortChange(value: String) {
        _uiState.update { it.copy(dialogProfilePort = value.filter { c -> c.isDigit() }) }
    }

    fun onDialogProfileTokenChange(value: String) {
        _uiState.update { it.copy(dialogProfileToken = value.trim()) }
    }

    fun saveProfileFromDialog() {
        val state = _uiState.value
        val name = state.dialogProfileName.trim()
        val host = state.dialogProfileHost.trim()
        val port = state.dialogProfilePort.toIntOrNull() ?: return
        val token = state.dialogProfileToken

        if (name.isBlank() || host.isBlank()) return

        val profiles = AuthManager.getConnectionProfiles().toMutableList()
        val editingId = state.editingProfileId

        if (editingId != null) {
            // Update existing profile
            val index = profiles.indexOfFirst { it.id == editingId }
            if (index == -1) return
            val oldToken = AuthManager.getProfileToken(editingId)
            profiles[index] = profiles[index].copy(name = name, host = host, port = port)
            AuthManager.saveConnectionProfiles(profiles)
            if (token != oldToken) {
                AuthManager.setProfileToken(editingId, token)
            }
        } else {
            // Add new profile
            val newProfile =
                com.m57.hermescontrol.data.model.ConnectionProfile(
                    name = name,
                    host = host,
                    port = port,
                )
            profiles.add(newProfile)
            AuthManager.saveConnectionProfiles(profiles)
            AuthManager.setProfileToken(newProfile.id, token)
        }

        closeProfileDialog()
        viewModelScope.launch(ioDispatcher) { loadSettings() }
        ApiClient.rebuild()
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

    fun onHostChange(value: String) {
        _uiState.update { it.copy(host = value.trim(), isSaved = false) }
    }

    fun onPortChange(value: String) {
        _uiState.update { it.copy(port = value.filter { c -> c.isDigit() }, isSaved = false) }
    }

    fun onTokenChange(value: String) {
        _uiState.update { it.copy(token = value.trim(), isSaved = false) }
    }

    fun onAutoReconnectChange(enabled: Boolean) {
        _uiState.update { it.copy(autoReconnect = enabled, isSaved = false) }
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

    fun onBottomNavDisplayModeChange(mode: BottomNavDisplayMode) {
        _uiState.update { it.copy(bottomNavDisplayMode = mode, isSaved = false) }
        AuthManager.setBottomNavDisplayMode(mode)
    }

    fun onTypingEffectEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(typingEffectEnabled = enabled, isSaved = false) }
    }

    fun onTypingEffectDelayMsChange(delayMs: Int) {
        _uiState.update { it.copy(typingEffectDelayMs = delayMs, isSaved = false) }
    }

    /** Clear all auth credentials — logs out and returns to landing screen. */
    fun logout() {
        AuthManager.setToken(null)
        AuthManager.setSessionCookie(null)
        AuthManager.setWsAuthParam("token")
        HermesWsClient.disconnect()
        // Don't rebuild ApiClient here — let the navigation complete first
    }

    /** All screens available for bottom-nav selection (name → display label). */
    val availableNavItems: List<Pair<String, Int>> =
        ScreenRegistry.ALL_SCREENS.map { screen ->
            screen.key::class.simpleName!! to screen.labelRes
        }

    /** Add a nav item to the bottom bar (max 5). Auto-saves. */
    fun addNavItem(name: String) {
        val current = _uiState.value.selectedNavItems
        if (name in current || current.size >= 5) return
        val updated = current + name
        _uiState.update { it.copy(selectedNavItems = updated) }
        AuthManager.setBottomNavItems(updated)
    }

    /** Remove a nav item from the bottom bar. Auto-saves. */
    fun removeNavItem(name: String) {
        val updated = _uiState.value.selectedNavItems - name
        _uiState.update { it.copy(selectedNavItems = updated) }
        AuthManager.setBottomNavItems(updated)
    }

    /** Reorder the selected nav items. Auto-saves. */
    fun reorderNavItems(items: List<String>) {
        _uiState.update { it.copy(selectedNavItems = items) }
        AuthManager.setBottomNavItems(items)
    }

    fun save() {
        val state = _uiState.value
        val port = state.port.toIntOrNull() ?: 9119

        AuthManager.setHost(state.host)
        AuthManager.setPort(port)
        AuthManager.setToken(state.token)
        AuthManager.setAutoReconnect(state.autoReconnect)
        // B6 (Jun 18 2026, kanban t_86e9be9b): persist theme choice so it
        // survives a cold start (was previously dropped on save()).
        AuthManager.setThemePreference(state.themePreference)
        AuthManager.setUseDynamicColors(state.useDynamicColors)
        AuthManager.setThemePreset(state.themePreset)
        AuthManager.setBottomNavDisplayMode(state.bottomNavDisplayMode)
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
