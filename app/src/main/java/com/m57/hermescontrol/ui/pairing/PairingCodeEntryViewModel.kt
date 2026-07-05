package com.m57.hermescontrol.ui.pairing

import android.app.Application
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
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

/**
 * UI state for the pairing code entry screen.
 */
data class PairingCodeEntryUiState(
    val manualCode: String = "",
    val isConnecting: Boolean = false,
    val connectionSuccess: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * ViewModel for the manual code entry pairing flow.
 *
 * Accepts pairing strings in two formats handled by [onCodeDetected]:
 *  - `hermes://connect?host=...&port=...&token=...` (URI format)
 *  - Base64-encoded JSON with `host`, `port`, `token` fields
 */
class PairingCodeEntryViewModel(
    private val app: Application,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PairingCodeEntryUiState())
    val uiState: StateFlow<PairingCodeEntryUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "PairingCodeEntryVM"
    }

    fun onManualCodeChange(value: String) {
        _uiState.update { it.copy(manualCode = value.trim(), errorMessage = null) }
    }

    /**
     * Process a pairing code (from manual entry).
     *
     * Supported formats:
     *  1. `hermes://connect?host=<host>&port=<port>&token=<token>`
     *  2. Base64-encoded JSON with `host`, `port`, `token` keys
     *  3. Raw token string (>= 32 alphanumeric chars) — uses defaults
     */
    fun onCodeDetected(code: String) {
        if (code.isBlank()) return
        val trimmed = code.trim()

        _uiState.update { it.copy(isConnecting = true, errorMessage = null) }
        Log.d(TAG, "Processing pairing code (${trimmed.take(64)}…)")

        try {
            // ── Format 1: hermes://connect URI ──────────────────────────
            if (trimmed.startsWith("hermes://connect?", ignoreCase = true)) {
                val uri = android.net.Uri.parse(trimmed)
                val host = uri.getQueryParameter("host")
                val port = uri.getQueryParameter("port")
                val token = uri.getQueryParameter("token")
                if (host != null && port != null && token != null) {
                    connectToDashboard(host, port.toIntOrNull() ?: 9119, token)
                    return
                }
                // URI parsed but missing required params
                setError(app.getString(R.string.connect_error_missing_fields))
                return
            }

            // ── Format 2: Base64-encoded JSON ───────────────────────────
            try {
                val decodedBytes = Base64.decode(trimmed, Base64.DEFAULT)
                val decodedString = String(decodedBytes, Charsets.UTF_8)
                if (decodedString.startsWith("{") && decodedString.endsWith("}")) {
                    val json = OkHttpProvider.json.parseToJsonElement(decodedString).jsonObject
                    val host = json["host"]?.jsonPrimitive?.content
                    val port = json["port"]?.jsonPrimitive?.content
                    val token = json["token"]?.jsonPrimitive?.content
                    // Accept raw "host" + "port" + "token", or a composite "pairing" payload
                    if (host != null && port != null && token != null) {
                        connectToDashboard(host, port.toIntOrNull() ?: 9119, token)
                        return
                    }
                    // JSON parsed but missing required fields
                    setError(app.getString(R.string.connect_error_missing_fields))
                    return
                }
                // Decoded to string but not JSON
                setError(app.getString(R.string.connect_error_malformed))
                return
            } catch (_: IllegalArgumentException) {
                // Not valid Base64 — check if it looks like a raw token
                if (trimmed.length >= 32 && trimmed.matches(Regex("[A-Za-z0-9_\\-]+"))) {
                    connectToDashboard("127.0.0.1", 9119, trimmed)
                    return
                }
                setError(app.getString(R.string.connect_error_malformed))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected parse error", e)
            setError(e.message ?: app.getString(R.string.connect_error_malformed))
        }
    }

    private fun connectToDashboard(
        host: String,
        port: Int,
        token: String,
    ) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    val tempApi = ApiClient.createTempService(host, port, token)
                    safeApiCall { tempApi.getStatus() }
                }

            when (result) {
                is NetworkResult.Success -> {
                    AuthManager.setToken(token)
                    AuthManager.setHost(host)
                    AuthManager.setPort(port)
                    ApiClient.rebuild()
                    _uiState.update { it.copy(isConnecting = false, connectionSuccess = true) }
                }

                is NetworkResult.Failure -> {
                    val msg = buildErrorMessage(result)
                    setError(msg)
                }
            }
        }
    }

    private fun buildErrorMessage(result: NetworkResult.Failure): String =
        when (val err = result.error) {
            is NetworkError.Http -> {
                when (err.code) {
                    401 -> app.getString(R.string.connect_error_401)
                    403 -> app.getString(R.string.connect_error_403)
                    else -> app.getString(R.string.connect_error_http_code, err.code)
                }
            }

            is NetworkError.AuthExpired -> {
                app.getString(R.string.connect_error_401)
            }

            is NetworkError.Connection -> {
                app.getString(R.string.connect_error_connection_failed, err.cause.message ?: "")
            }

            is NetworkError.Unknown -> {
                app.getString(R.string.connect_error_connection_failed, err.cause.message ?: "")
            }
        }

    private fun setError(msg: String) {
        _uiState.update { it.copy(isConnecting = false, errorMessage = msg) }
    }
}

class PairingCodeEntryViewModelFactory(
    private val app: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = PairingCodeEntryViewModel(app) as T
}
