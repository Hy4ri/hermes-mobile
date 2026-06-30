package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.HermesWsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

class AuthLoginViewModel(
    private val app: Application,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthLoginUiState())
    val uiState: StateFlow<AuthLoginUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "AuthLoginVM"
    }

    private val probeClient: OkHttpClient =
        com.m57.hermescontrol.data.remote.OkHttpProvider.probe

    fun onHostChange(value: String) {
        _uiState.update { it.copy(host = value.trim(), errorMessage = null, authMode = null) }
    }

    /** Reset ephemeral connection state (called when screen leaves composition). */
    fun clearConnectionState() {
        _uiState.update { it.copy(connectionSuccess = false, errorMessage = null, isLoading = false) }
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
                    authMode = result?.authMode,
                    token = result?.extractedToken ?: it.token,
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
     * Result of probing the dashboard.
     */
    private data class ProbeResult(
        val authMode: DashboardAuthMode,
        val extractedToken: String? = null,
    )

    /**
     * Probes the dashboard to determine [DashboardAuthMode].
     * Returns null if the dashboard is unreachable.
     * When [ProbeResult.extractedToken] is non-null, the session token
     * was found embedded in the dashboard SPA HTML and can be auto-populated.
     */
    private fun probeDashboardInternal(
        host: String,
        port: Int,
    ): ProbeResult? {
        val baseUrl = "http://$host:$port"

        // Step 1: Check if dashboard is reachable via /api/status (always public)
        val statusOk =
            try {
                val req =
                    Request
                        .Builder()
                        .url("$baseUrl/api/status")
                        .get()
                        .build()
                val resp = probeClient.newCall(req).execute()
                resp.isSuccessful
            } catch (e: Exception) {
                Log.w(TAG, "Status probe failed: ${e.message}")
                return null // Dashboard unreachable
            }

        if (!statusOk) return null

        // Step 2: Probe / to see if it redirects to /login (basic auth) or returns SPA
        val needsBasicAuth =
            try {
                val req =
                    Request
                        .Builder()
                        .url(baseUrl)
                        .get()
                        .build()
                val resp = probeClient.newCall(req).execute()
                val code = resp.code
                val location = resp.header("location", "")
                // 302 to /login means basic auth is active
                code == 302 && location?.contains("/login", ignoreCase = true) == true
            } catch (e: Exception) {
                Log.w(TAG, "SPA probe failed: ${e.message}")
                false
            }

        // Step 3: Extract token from SPA HTML (if reachable without redirect)
        var extractedToken: String? = null
        if (!needsBasicAuth) {
            try {
                val req =
                    Request
                        .Builder()
                        .url(baseUrl)
                        .get()
                        .build()
                val resp = probeClient.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                // Extract __HERMES_SESSION_TOKEN__ from the SPA HTML
                val tokenMatch = Regex("""__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"""").find(body)
                extractedToken = tokenMatch?.groupValues?.getOrNull(1)
                if (extractedToken == null) {
                    Log.w(TAG, "SPA has no __HERMES_SESSION_TOKEN__ — mode might require both auth")
                }
            } catch (e: Exception) {
                Log.w(TAG, "SPA token extraction failed: ${e.message}")
            }
        }

        val authMode =
            if (needsBasicAuth) {
                DashboardAuthMode.BASIC_AUTH
            } else if (extractedToken != null) {
                DashboardAuthMode.TOKEN_ONLY
            } else {
                DashboardAuthMode.ALL
            }

        return ProbeResult(authMode = authMode, extractedToken = extractedToken)
    }

    /**
     * Result of a successful connect attempt.
     */
    private data class ConnectResult(
        /** WS credential: session token (loopback) or WS ticket (gated). */
        val wsCredential: String,
        /** Session cookie for REST API auth in gated mode (null in loopback). */
        val sessionCookie: String? = null,
    )

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
                        DashboardAuthMode.TOKEN_ONLY -> {
                            val token = connectTokenOnly(state.host, port, state.token)
                            if (token != null) ConnectResult(wsCredential = token) else null
                        }

                        DashboardAuthMode.BASIC_AUTH -> {
                            connectBasicAuth(state.host, port, state.username, state.password)
                        }

                        DashboardAuthMode.ALL -> {
                            connectBasicAuth(state.host, port, state.username, state.password)
                        }

                        null -> {
                            null
                        }
                    }
                }

            if (result != null) {
                AuthManager.setHost(state.host)
                AuthManager.setPort(port)
                AuthManager.setToken(result.wsCredential)
                if (result.sessionCookie != null) {
                    AuthManager.setSessionCookie(result.sessionCookie)
                    AuthManager.setWsAuthParam("ticket")
                } else {
                    AuthManager.setSessionCookie(null)
                    AuthManager.setWsAuthParam("token")
                }
                ApiClient.rebuild()
                HermesWsClient.connect()
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
        val result = safeApiCall { tempApi.getSessions() }

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

                        is com.m57.hermescontrol.data.remote.NetworkError.AuthExpired -> {
                            app.getString(R.string.connect_error_401)
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
     * Authenticate with basic auth, then mint a WS ticket.
     * Returns the WS ticket and the session cookie for REST auth.
     */
    private fun connectBasicAuth(
        host: String,
        port: Int,
        username: String,
        password: String,
    ): ConnectResult? {
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
        val jsonBody = """{"provider":"basic","username":"$username","password":"$password","next":""}"""

        try {
            // Step 1: Authenticate via the password login endpoint to get a session cookie
            val loginClient =
                com.m57.hermescontrol.data.remote.OkHttpProvider.probe
                    .newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

            val loginReq =
                Request
                    .Builder()
                    .url("$baseUrl/auth/password-login")
                    .header("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody())
                    .build()
            val loginResp = loginClient.newCall(loginReq).execute()

            if (!loginResp.isSuccessful) {
                val msg =
                    when (loginResp.code) {
                        401 -> app.getString(R.string.connect_error_401)
                        403 -> app.getString(R.string.connect_error_403)
                        else -> app.getString(R.string.connect_error_http_code, loginResp.code)
                    }
                _uiState.update { it.copy(isLoading = false, errorMessage = msg) }
                return null
            }

            // Step 2: Extract hermes_session_at cookie from Set-Cookie headers
            val setCookieHeaders = loginResp.headers("Set-Cookie")
            var sessionAccessToken: String? = null
            for (header in setCookieHeaders) {
                if (header.startsWith("hermes_session_at=")) {
                    sessionAccessToken = header.split(";")[0].removePrefix("hermes_session_at=").trim()
                    break
                }
            }

            if (sessionAccessToken.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Login succeeded but no session cookie received",
                    )
                }
                return null
            }

            val cookieHeader = "hermes_session_at=$sessionAccessToken"

            // Step 3: Mint a WebSocket ticket using the session cookie
            val ticketClient =
                com.m57.hermescontrol.data.remote.OkHttpProvider.base
                    .newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

            val ticketReq =
                Request
                    .Builder()
                    .url("$baseUrl/api/auth/ws-ticket")
                    .header("Cookie", cookieHeader)
                    .post("{}".toRequestBody())
                    .build()
            val ticketResp = ticketClient.newCall(ticketReq).execute()

            if (!ticketResp.isSuccessful) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = app.getString(R.string.connect_error_http_code, ticketResp.code),
                    )
                }
                return null
            }

            val ticketBody = ticketResp.body?.string() ?: ""
            val ticketMatch = Regex(""""ticket":"([^"]+)"""").find(ticketBody)
            val ticket = ticketMatch?.groupValues?.getOrNull(1)

            if (ticket.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to obtain WebSocket ticket",
                    )
                }
                return null
            }

            return ConnectResult(wsCredential = ticket, sessionCookie = sessionAccessToken)
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
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthLoginViewModel(app) as T
}
