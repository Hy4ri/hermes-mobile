package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.AuthPayloads
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ServerEndpoint
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
    val baseUrl: String = ServerEndpoint.DEFAULT_BASE_URL,
    val transportWarning: String? = null,
    val token: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val probing: Boolean = false,
    val authMode: DashboardAuthMode? = null,
    val connectionSuccess: Boolean = false,
    val errorMessage: String? = null,
    val loggedInProfiles: List<ConnectionProfile> = emptyList(),
)

class AuthLoginViewModel(
    private val app: Application,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            AuthLoginUiState(
                baseUrl = AuthManager.getBaseUrl(),
            ),
        )
    val uiState: StateFlow<AuthLoginUiState> = _uiState.asStateFlow()

    init {
        loadLoggedInProfiles()
    }

    fun loadLoggedInProfiles() {
        val allProfiles = AuthManager.getConnectionProfiles()
        val loggedIn =
            allProfiles.filter { profile ->
                val token = AuthManager.getProfileToken(profile.id)
                !token.isNullOrBlank()
            }
        _uiState.update { it.copy(loggedInProfiles = loggedIn) }
    }

    fun useExistingProfile(profileId: String) {
        AuthManager.setSelectedProfileId(profileId)
        ApiClient.rebuild()
        HermesWsClient.connect()
        _uiState.update { it.copy(connectionSuccess = true) }
    }

    companion object {
        private const val TAG = "AuthLoginVM"
    }

    private val probeClient: OkHttpClient =
        com.m57.hermescontrol.data.remote.OkHttpProvider.probe

    fun onBaseUrlChange(value: String) {
        val trimmed = value.trim()
        val warning =
            runCatching {
                ServerEndpoint.parse(trimmed, CleartextPolicy.ALLOW_WITH_WARNING).securityWarning
            }.getOrNull()
        _uiState.update { it.copy(baseUrl = trimmed, transportWarning = warning, errorMessage = null, authMode = null) }
    }

    /** Reset ephemeral connection state (called when screen leaves composition). */
    fun clearConnectionState() {
        _uiState.update { it.copy(connectionSuccess = false, errorMessage = null, isLoading = false) }
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
        val endpoint =
            runCatching { ServerEndpoint.parseForBuild(state.baseUrl) }.getOrNull()
        if (endpoint == null) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.connect_error_url_invalid)) }
            return
        }

        _uiState.update { it.copy(probing = true, errorMessage = null, authMode = null) }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    probeDashboardInternal(endpoint)
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
    private fun probeDashboardInternal(endpoint: ServerEndpoint): ProbeResult? {
        // Step 1: Check if dashboard is reachable via /api/status (always public)
        val statusOk =
            try {
                val req =
                    Request
                        .Builder()
                        .url(endpoint.resolve("api/status").toString())
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
                        .url(endpoint.resolve("").toString())
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
                        .url(endpoint.resolve("").toString())
                        .get()
                        .build()
                val resp = probeClient.newCall(req).execute()
                val body = resp.body.string()
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
    )

    /**
     * Step 2: Connect using the detected auth mode.
     */
    fun connect() {
        val state = _uiState.value
        val endpoint =
            runCatching { ServerEndpoint.parseForBuild(state.baseUrl) }.getOrNull()
                ?: return

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    when (state.authMode) {
                        DashboardAuthMode.TOKEN_ONLY -> {
                            val token = connectTokenOnly(endpoint, state.token)
                            if (token != null) ConnectResult(wsCredential = token) else null
                        }

                        DashboardAuthMode.BASIC_AUTH -> {
                            connectBasicAuth(endpoint, state.username, state.password)
                        }

                        DashboardAuthMode.ALL -> {
                            connectBasicAuth(endpoint, state.username, state.password)
                        }

                        null -> {
                            null
                        }
                    }
                }

            if (result != null) {
                AuthManager.setBaseUrl(state.baseUrl)
                AuthManager.setToken(result.wsCredential)
                if (state.authMode == DashboardAuthMode.TOKEN_ONLY) {
                    // Loopback mode — no session cookie; ensure any stale one
                    // is cleared so the jar only sends the Bearer token.
                    AuthManager.setSessionCookie(null)
                    AuthManager.setWsAuthParam("token")
                } else {
                    // Gated (BASIC_AUTH / ALL): the session cookie was captured
                    // automatically by the shared CookieJar during the login
                    // call (issue #470), so we keep it and switch the WS auth
                    // param to the ticket minted above.
                    AuthManager.setWsAuthParam("ticket")
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
        endpoint: ServerEndpoint,
        token: String,
    ): String? {
        if (token.isBlank()) {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = app.getString(R.string.auth_login_error_token_required))
            }
            return null
        }

        val tempApi = ApiClient.createTempService(endpoint.baseUrl.toString(), token)
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
        endpoint: ServerEndpoint,
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

        val jsonBody = AuthPayloads.passwordLogin(username, password)

        try {
            // Step 1: Authenticate via the password login endpoint to get a session cookie.
            // IMPORTANT: use the SHARED OkHttpProvider.probe client (it carries the
            // persistent CookieManager.cookieJar). Do NOT build a fresh client here —
            // a separate client would not share the jar, so the Set-Cookie from this
            // login would never reach the ws-ticket request below and auth would fail.
            val loginReq =
                Request
                    .Builder()
                    .url(endpoint.resolve("auth/password-login").toString())
                    .header("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody())
                    .build()
            val loginResp = OkHttpProvider.probe.newCall(loginReq).execute()

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
            // The session cookie is now captured in the shared CookieManager.cookieJar
            // (issue #470). The ws-ticket request below uses the same jar, so the
            // authenticated session carries over automatically.

            // Step 3: Mint a WebSocket ticket using the session cookie
            val ticketReq =
                Request
                    .Builder()
                    .url(endpoint.resolve("api/auth/ws-ticket").toString())
                    .post("{}".toRequestBody())
                    .build()
            val ticketResp = OkHttpProvider.probe.newCall(ticketReq).execute()

            if (!ticketResp.isSuccessful) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = app.getString(R.string.connect_error_http_code, ticketResp.code),
                    )
                }
                return null
            }

            val ticketBody = ticketResp.body.string()
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

            return ConnectResult(wsCredential = ticket)
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
