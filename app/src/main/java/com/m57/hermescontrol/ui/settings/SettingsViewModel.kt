package com.m57.hermescontrol.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.theme.ThemePreference
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
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val isSaved: Boolean = false,
)

class SettingsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        _uiState.update {
            it.copy(
                host = AuthManager.getHost(),
                port = AuthManager.getPort().toString(),
                token = AuthManager.getToken() ?: "",
                autoReconnect = AuthManager.isAutoReconnect(),
            )
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
    }

    fun save() {
        val state = _uiState.value
        val port = state.port.toIntOrNull() ?: 9119

        AuthManager.setHost(state.host)
        AuthManager.setPort(port)
        AuthManager.setToken(state.token)
        AuthManager.setAutoReconnect(state.autoReconnect)
        ApiClient.rebuild()

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
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getStatus()
                    }
                if (response.isSuccessful) {
                    _uiState.update {
                        it.copy(isTesting = false, testResult = "✅ Connected successfully")
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            testResult = "❌ HTTP ${response.code()}: ${response.message()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        testResult = "❌ ${e.message ?: "Connection failed"}",
                    )
                }
            }
        }
    }
}
