package com.m57.hermescontrol.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConnectUiState(
    val token: String = "",
    val host: String = "127.0.0.1",
    val port: String = "9119",
    val isConnecting: Boolean = false,
    val connectionSuccess: Boolean = false,
    val errorMessage: String? = null,
)

class ConnectViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ConnectUiState())
    val uiState: StateFlow<ConnectUiState> = _uiState.asStateFlow()

    init {
        // Load saved values
        val savedToken = AuthManager.getToken() ?: ""
        val savedHost = AuthManager.getHost()
        val savedPort = AuthManager.getPort()
        _uiState.update {
            it.copy(
                token = savedToken,
                host = savedHost,
                port = savedPort.toString(),
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
            _uiState.update { it.copy(errorMessage = "Token is required") }
            return
        }
        if (state.host.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Host is required") }
            return
        }
        val port = state.port.toIntOrNull()
        if (port == null || port !in 1..65535) {
            _uiState.update { it.copy(errorMessage = "Port must be between 1 and 65535") }
            return
        }

        _uiState.update { it.copy(isConnecting = true, errorMessage = null) }

        // Save settings before testing so ApiClient uses the right base URL
        AuthManager.setToken(state.token)
        AuthManager.setHost(state.host)
        AuthManager.setPort(port)
        ApiClient.rebuild()

        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getStatus()
                    }
                if (response.isSuccessful) {
                    _uiState.update {
                        it.copy(isConnecting = false, connectionSuccess = true, errorMessage = null)
                    }
                } else {
                    val code = response.code()
                    val msg =
                        when (code) {
                            401 -> {
                                AuthManager.setToken(null)
                                "Invalid token (401 Unauthorized)"
                            }

                            403 -> {
                                "Access denied (403 Forbidden)"
                            }

                            else -> {
                                "Server returned HTTP $code"
                            }
                        }
                    _uiState.update { it.copy(isConnecting = false, errorMessage = msg) }
                }
            } catch (e: Exception) {
                val msg =
                    when {
                        e.message?.contains("timeout", true) == true -> "Connection timed out"
                        e.message?.contains("refused", true) == true -> "Connection refused – is Hermes running?"
                        e.message?.contains("resolve", true) == true -> "Could not resolve host"
                        else -> "Connection failed: ${e.message}"
                    }
                _uiState.update { it.copy(isConnecting = false, errorMessage = msg) }
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
                    val json =
                        com.google.gson.JsonParser
                            .parseString(decodedString)
                            .asJsonObject
                    val host = json.get("host")?.asString
                    val port = json.get("port")?.asInt?.toString() ?: json.get("port")?.asString
                    val token = json.get("token")?.asString
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
                            errorMessage = "Malformed pairing string — missing host, port, or token",
                        )
                    }
                    return
                }
                // Decoded to valid string but not JSON shape — not a pairing string
                _uiState.update {
                    it.copy(
                        errorMessage = "Malformed pairing string — expected URL or Base64-encoded JSON",
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
                            errorMessage = "Malformed pairing string — expected URL or Base64-encoded JSON",
                        )
                    }
                }
                return
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to parse pairing string: ${e.message}") }
                return
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "Failed to parse pairing string: ${e.message}") }
        }
    }
}
