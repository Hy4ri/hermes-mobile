package com.m57.hermescontrol.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.theme.BottomNavDisplayMode
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
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
)

class SettingsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        val selectedId = AuthManager.getSelectedProfileId()
        _uiState.update {
            it.copy(
                host = AuthManager.getHost(),
                port = AuthManager.getPort().toString(),
                token = AuthManager.getToken() ?: "",
                autoReconnect = AuthManager.isAutoReconnect(),
                // B6 (Jun 18 2026, kanban t_86e9be9b): restore user's theme
                // choice from persistent storage on init. Previously this slot
                // was missing — always defaulted to ThemePreference.SYSTEM.
                themePreference = AuthManager.getThemePreference(),
                useDynamicColors = AuthManager.isUseDynamicColors(),
                themePreset = AuthManager.getThemePreset(),
                selectedNavItems = AuthManager.getBottomNavItems(),
                typingEffectEnabled = AuthManager.isTypingEffectEnabled(),
                typingEffectDelayMs = AuthManager.getTypingEffectDelayMs(),
                profiles = AuthManager.getConnectionProfiles(),
                selectedProfileId = selectedId,
                renameProfileName =
                    AuthManager
                        .getConnectionProfiles()
                        .firstOrNull { p ->
                            p.id == selectedId
                        }?.name ?: "",
                bottomNavDisplayMode = AuthManager.getBottomNavDisplayMode(),
            )
        }
    }

    fun selectProfile(profileId: String?) {
        AuthManager.setSelectedProfileId(profileId)
        loadSettings()
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
        loadSettings()
    }

    fun deleteProfile(profileId: String) {
        val updatedProfiles = AuthManager.getConnectionProfiles().filter { it.id != profileId }
        AuthManager.saveConnectionProfiles(updatedProfiles)
        AuthManager.setProfileToken(profileId, null)
        if (AuthManager.getSelectedProfileId() == profileId) {
            AuthManager.setSelectedProfileId(null)
        }
        loadSettings()
        ApiClient.rebuild()
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

    /** All screens available for bottom-nav selection (name → display label). */
    val availableNavItems: List<Pair<String, Int>> =
        listOf(
            "ChatScreen" to R.string.screen_chat,
            "SkillsScreen" to R.string.screen_skills,
            "CronJobsScreen" to R.string.screen_cron,
            "SystemScreen" to R.string.screen_system,
            "SettingsScreen" to R.string.screen_settings,
            "ProfilesScreen" to R.string.screen_profiles,
            "WebhooksScreen" to R.string.screen_webhooks,
            "GatewayScreen" to R.string.screen_gateway,
            "HistoryScreen" to R.string.screen_history,
            "ToolsetsScreen" to R.string.screen_toolsets,
            "PluginsScreen" to R.string.screen_plugins,
            "ConfigScreen" to R.string.screen_config,
            "McpServersScreen" to R.string.screen_mcp_servers,
            "ModelScreen" to R.string.screen_models,
            "PairingScreen" to R.string.screen_pairing,
            "KeysScreen" to R.string.screen_keys,
            "ChannelsScreen" to R.string.screen_channels,
            "LogsScreen" to R.string.screen_logs,
            "KanbanScreen" to R.string.screen_kanban,
            "AchievementsScreen" to R.string.screen_achievements,
        )

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

        loadSettings()
        _uiState.update { it.copy(isSaved = true, testResult = null) }
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
                withContext(Dispatchers.IO) {
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
