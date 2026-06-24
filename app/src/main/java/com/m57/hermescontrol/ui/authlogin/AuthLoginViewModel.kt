package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * What auth the dashboard requires.
 */
enum class DashboardAuthMode {
    /** Dashboard has no auth gate — just needs a session token. */
    TOKEN_ONLY,

    /** Dashboard has basic auth — needs username + password (and possibly a token). */
    BASIC_AUTH,

    /** Dashboard requires both basic auth credentials and a session token. */
    ALL,
}

data class AuthLoginUiState(
    val host: String = "127.0.0.1",
    val port: String = "9119",
    val token: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val probing: Boolean = false,
    val authMode: DashboardAuthMode? = null,
    val connectionSuccess: Boolean = false,
    val errorMessage: String? = null,
)

class AuthLoginViewModel(private val app: Application) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthLoginUiState())
    val uiState: StateFlow<AuthLoginUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "AuthLoginVM"
    }

    private val probeClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

    fun onHostChange(value: String) {
        _uiState.update { it.copy(host = value.trim(), errorMessage = null, authMode = null) }
    }

    fun onPortChange(value: String) {
        _uiState.update {
            it.copy(
                port = value.filter { c -> c.isDigit() },
                errorMessage = null,
                authMode = null,
            )
        }
    }

    fun onTokenChange(value: String) {
        _uiState.update { it.copy(token = value.trim(), errorMessage = null) }
    }

    fun onUsernameChange(value: String) {
        _uiState.update { it.copy(username = value.trim(), errorMessage = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    /**
     * Step 1: Probe the dashboard to detect what auth it needs.
     */
    fun probe() {
        val state = _uiState.value
        if (state.host.isBlank()) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.auth_login_error_host_required)) }
            return
        }
        val port = state.port.toIntOrNull()
        if (port == null || port !in 1..65535) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.auth_login_error_port_invalid)) }
            return
        }

        _uiState.update { it.copy(probing = true, errorMessage = null, authMode = null) }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    probeDashboardInternal(state.host, port)
                }
            _uiState.update {
                it.copy(
                    probing = false,
                    authMode = result,
                    errorMessage =
                        if (result == null) {
                            app.getString(R.string.auth_login_error_unreachable)
                        } else {
                            null
                        },
                )
            }
        }
    }

    /**
     * Probes the dashboard to determine [DashboardAuthMode].
     * Returns null if the dashboard is unreachable.
     */
    private fun probeDashboardInternal(
        host: String,
        port: Int,
    ): DashboardAuthMode? {
        val baseUrl = "http://$host:$port"

        // Step 1: Check if dashboard is reachable via /api/status (always public)
        val statusOk =
            try {
                val req = Request.Builder().url("$baseUrl/api/status").get().build()
                val resp = probeClient.newCall(req).execute()
                resp.isSuccessful
            } catch (e: Exception) {
                Log.w("AuthLoginVM", "Status probe failed: ${e.message}")
                return null // Dashboard unreachable
            }

        if (!statusOk) return null

        // Step 2: Probe / to see if it redirects to /login (basic auth) or returns SPA
        val needsBasicAuth =
            try {
                val req = Request.Builder().url(baseUrl).get().build()
                val resp = probeClient.newCall(req).execute()
                val code = resp.code
                val location = resp.header("location", "")
                // 302 to /login means basic auth is active
                code == 302 && location?.contains("/login", ignoreCase = true) == true
            } catch (e: Exception) {
                Log.w("AuthLoginVM", "SPA probe failed: ${e.message}")
                false
            }

        // Step 3: Try extracting token from SPA
        val hasTokenInSpa =
            if (!needsBasicAuth) {
                try {
                    val req = Request.Builder().url(baseUrl).get().build()
                    val resp = probeClient.newCall(req).execute()
                    val body = resp.body?.string() ?: ""
                    body.contains("__HERMES_SESSION_TOKEN__")
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }

        return if (needsBasicAuth) {
            DashboardAuthMode.ALL
        } else if (hasTokenInSpa) {
            DashboardAuthMode.TOKEN_ONLY
        } else {
            DashboardAuthMode.ALL
        }
    }

    /**
     * Step 2: Connect using the detected auth mode.
     */
    fun connect() {
        val state = _uiState.value
        val port = state.port.toIntOrNull() ?: return

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    when (state.authMode) {
                        DashboardAuthMode.TOKEN_ONLY -> connectTokenOnly(state.host, port, state.token)
                        DashboardAuthMode.BASIC_AUTH ->
                            connectBasicAuth(
                                state.host,
                                port,
                                state.username,
                                state.password,
                            )
                        DashboardAuthMode.ALL -> {
                            // First authenticate with basic auth, then use entered token
                            val session = connectBasicAuth(state.host, port, state.username, state.password)
                            if (session != null) session else connectTokenOnly(state.host, port, state.token)
                        }
                        null -> null
                    }
                }

            if (result != null) {
                AuthManager.setHost(state.host)
                AuthManager.setPort(port)
                AuthManager.setToken(result)
                ApiClient.rebuild()
                _uiState.update { it.copy(isLoading = false, connectionSuccess = true) }
            }
        }
    }

    /**
     * Validate the token by calling /api/status with it.
     */
    private suspend fun connectTokenOnly(
        host: String,
        port: Int,
        token: String,
    ): String? {
        if (token.isBlank()) {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = app.getString(R.string.auth_login_error_token_required))
            }
            return null
        }

        val tempApi = ApiClient.createTempService(host, port, token)
        val result = safeApiCall { tempApi.getStatus() }

        return when (result) {
            is com.m57.hermescontrol.data.remote.NetworkResult.Success -> {
                token
            }
            is com.m57.hermescontrol.data.remote.NetworkResult.Failure -> {
                val msg =
                    when (val err = result.error) {
                        is com.m57.hermescontrol.data.remote.NetworkError.Http -> {
                            when (err.code) {
                                401 -> app.getString(R.string.connect_error_401)
                                403 -> app.getString(R.string.connect_error_403)
                                else -> app.getString(R.string.connect_error_http_code, err.code)
                            }
                        }
                        is com.m57.hermescontrol.data.remote.NetworkError.Connection -> {
                            app.getString(R.string.connect_error_connection_failed, err.cause.message ?: "")
                        }
                        is com.m57.hermescontrol.data.remote.NetworkError.Unknown -> {
                            app.getString(R.string.connect_error_connection_failed, err.cause.message ?: "")
                        }
                    }
                _uiState.update { it.copy(isLoading = false, errorMessage = msg) }
                null
            }
        }
    }

    /**
     * Authenticate with basic auth, then extract the session token from the SPA.
     */
    private fun connectBasicAuth(
        host: String,
        port: Int,
        username: String,
        password: String,
    ): String? {
        if (username.isBlank()) {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = app.getString(R.string.auth_login_error_username_required))
            }
            return null
        }
        if (password.isBlank()) {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = app.getString(R.string.auth_login_error_password_required))
            }
            return null
        }

        val baseUrl = "http://$host:$port"
        val credentials = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
        val authHeader = "Basic $credentials"

        try {
            // Use a client that follows redirects — / always redirects to /login
            val authClient =
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
            val authReq =
                Request.Builder()
                    .url("$baseUrl/login")
                    .header("Authorization", authHeader)
                    .get()
                    .build()
            val authResp = authClient.newCall(authReq).execute()

            if (!authResp.isSuccessful) {
                val msg =
                    when (authResp.code) {
                        401 -> app.getString(R.string.connect_error_401)
                        403 -> app.getString(R.string.connect_error_403)
                        else -> app.getString(R.string.connect_error_http_code, authResp.code)
                    }
                _uiState.update { it.copy(isLoading = false, errorMessage = msg) }
                return null
            }

            // Extract session token from the SPA HTML
            val body = authResp.body?.string() ?: ""
            val tokenMatch = Regex("""__HERMES_SESSION_TOKEN__="([^"]+)"""").find(body)
            val sessionToken = tokenMatch?.groupValues?.getOrNull(1)

            if (sessionToken.isNullOrBlank()) {
                if (_uiState.value.token.isNotBlank()) {
                    return _uiState.value.token
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Session token not found — enter it manually",
                    )
                }
                return null
            }

            return sessionToken
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = app.getString(R.string.connect_error_connection_failed, e.message ?: ""),
                )
            }
            return null
        }
    }
}

class AuthLoginViewModelFactory(
    private val app: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AuthLoginViewModel(app) as T
    }
}
