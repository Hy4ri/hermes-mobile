package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.config.resolveBaseUrl
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.AuthPayloads
import com.m57.hermescontrol.data.remote.CertificateOrigin
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.ClientCertificates
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.remote.await
import com.m57.hermescontrol.data.remote.isClientCertificateAuthenticationFailure
import com.m57.hermescontrol.data.remote.requiresLocalNetworkPermission
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * What auth the dashboard requires. Derived authoritatively from
 * `GET /api/status` (`auth_required` + `auth_providers`), not by sniffing
 * redirects — the status JSON is the source of truth.
 */
enum class DashboardAuthMode {
    /** Dashboard has no auth gate (loopback / `--insecure`) — just needs a session token. */
    TOKEN_ONLY,

    /** Dashboard has basic auth (gated `0.0.0.0` bind) — needs username + password. */
    BASIC_AUTH,

    /** Dashboard requires both basic auth credentials and a session token. */
    ALL,

    /**
     * Dashboard signs in through the system browser (OIDC, Nous Portal, ...): the
     * gateway's RFC 8252 native flow (loopback redirect + PKCE, issue #1515).
     */
    BROWSER,
}

data class AuthLoginUiState(
    val baseUrl: String = ServerEndpoint.DEFAULT_BASE_URL,
    val transportWarning: String? = null,
    val token: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val probing: Boolean = false,
    val lanPermissionNeeded: Boolean = false,
    val authMode: DashboardAuthMode? = null,
    /** Interactive sign-in options the dashboard advertises; empty until a gated probe succeeds. */
    val providers: List<AuthProviderOption> = emptyList(),
    val selectedProvider: String? = null,
    /** False when the gateway predates the native (browser) login flow. */
    val browserSupported: Boolean = true,
    /** A browser sign-in is in flight and waiting for the redirect back to the app. */
    val browserWaiting: Boolean = false,
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

    private var browserJob: Job? = null
    private var refreshedConnectionId: String? = null
    private var refreshedBaseUrl: String? = null
    internal var sdkVersion: Int = Build.VERSION.SDK_INT
    private var permissionAttempt = 0
    private var pendingLanAction: (() -> Unit)? = null
    private var pendingLanUrl: String? = null
    private var pendingLanProfileId: String? = null

    init {
        loadLoggedInProfiles()
    }

    /** Refresh cached login state when the selected local connection changes. */
    fun refreshForSelectedConnection() {
        val selectedConnectionId = AuthManager.getSelectedProfileId()
        val currentBaseUrl = AuthManager.getBaseUrl()
        if (selectedConnectionId == refreshedConnectionId && currentBaseUrl == refreshedBaseUrl) {
            loadLoggedInProfiles()
            return
        }

        refreshedConnectionId = selectedConnectionId
        refreshedBaseUrl = currentBaseUrl
        cancelLanPermissionRequest()
        browserJob?.cancel()
        certificatePrompt.reset()
        _uiState.value = AuthLoginUiState(baseUrl = currentBaseUrl)
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
        cancelLanPermissionRequest()
        viewModelScope.launch {
            ProfileSwitchCoordinator.switchConnectionProfile(profileId)
            _uiState.update { it.copy(connectionSuccess = true) }
        }
    }

    companion object {
        private const val TAG = "AuthLoginVM"
    }

    private val probeClient: OkHttpClient =
        com.m57.hermescontrol.data.remote.OkHttpProvider.probe

    internal val certificatePrompt = CertificatePromptController()

    internal fun saveCertificate() {
        val expected = ClientCertificates.state.value
        certificatePrompt.save(viewModelScope, ClientCertificates::verifyCandidate) { url, alias ->
            val previous = url.takeIf { CertificateOrigin.from(it)!!.storageKey in expected }
            ClientCertificates.save(previous, url, alias, expected)
            _uiState.update { it.copy(errorMessage = null) }
        }
    }

    fun onBaseUrlChange(value: String) {
        cancelLanPermissionRequest()
        certificatePrompt.reset()
        browserJob?.cancel()
        val trimmed = value.trim()
        val warning =
            runCatching {
                ServerEndpoint.parse(trimmed, CleartextPolicy.ALLOW_WITH_WARNING).securityWarning
            }.getOrNull()
        _uiState.update {
            it.copy(
                baseUrl = trimmed,
                transportWarning = warning,
                errorMessage = null,
                authMode = null,
                providers = emptyList(),
                selectedProvider = null,
                browserWaiting = false,
                isLoading = false,
            )
        }
    }

    /** Reset ephemeral connection state (called when screen leaves composition). */
    fun clearConnectionState() {
        cancelLanPermissionRequest()
        certificatePrompt.reset()
        // A browser sign-in keeps running (and keeps its spinner) while the user is away in the browser.
        _uiState.update {
            it.copy(connectionSuccess = false, errorMessage = null, isLoading = it.browserWaiting)
        }
    }

    fun onProviderSelected(name: String) {
        val state = _uiState.value
        val provider = state.providers.firstOrNull { it.name == name } ?: return
        if (state.browserWaiting) return
        _uiState.update {
            it.copy(selectedProvider = provider.name, authMode = modeForProvider(provider), errorMessage = null)
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

    /** Resolve LAN hosts off the UI thread before any dashboard network operation. */
    private fun requestWithLanPermission(
        context: Context,
        url: String,
        profileId: String? = null,
        action: () -> Unit,
    ) {
        val attempt = ++permissionAttempt
        pendingLanAction = null
        pendingLanUrl = null
        pendingLanProfileId = null
        _uiState.update { it.copy(lanPermissionNeeded = false) }
        viewModelScope.launch {
            val required = withContext(Dispatchers.IO) { requiresLocalNetworkPermission(sdkVersion, url) }
            if (attempt != permissionAttempt || !isCurrentLanTarget(url, profileId)) return@launch
            if (required &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                pendingLanUrl = url
                pendingLanProfileId = profileId
                pendingLanAction = action
                _uiState.update { it.copy(lanPermissionNeeded = true, errorMessage = null) }
            } else {
                action()
            }
        }
    }

    private fun isCurrentLanTarget(
        url: String,
        profileId: String?,
    ): Boolean =
        if (profileId == null) {
            _uiState.value.baseUrl == url
        } else {
            AuthManager
                .getConnectionProfiles()
                .firstOrNull { it.id == profileId }
                ?.let { runCatching { it.resolveBaseUrl(AuthManager.getBaseUrl()) }.getOrNull() == url } == true &&
                !AuthManager.getProfileToken(profileId).isNullOrBlank()
        }

    private fun cancelLanPermissionRequest() {
        permissionAttempt++
        pendingLanAction = null
        pendingLanUrl = null
        pendingLanProfileId = null
        _uiState.update { it.copy(lanPermissionNeeded = false) }
    }

    fun onLanPermissionResult(granted: Boolean) {
        val action = pendingLanAction ?: return
        val url = pendingLanUrl
        val profileId = pendingLanProfileId
        cancelLanPermissionRequest()
        if (url == null || !isCurrentLanTarget(url, profileId)) return
        if (granted) {
            action()
        } else {
            _uiState.update {
                it.copy(errorMessage = app.getString(R.string.connect_error_lan_permission_denied))
            }
        }
    }

    fun requestProbe(context: Context) {
        requestWithLanPermission(context, _uiState.value.baseUrl, action = ::probe)
    }

    fun requestConnect(context: Context) {
        requestWithLanPermission(context, _uiState.value.baseUrl, action = ::connect)
    }

    fun requestBrowserLogin(
        context: Context,
        openBrowser: (String) -> Unit,
    ) {
        requestWithLanPermission(context, _uiState.value.baseUrl) { startBrowserLogin(openBrowser) }
    }

    fun requestExistingProfile(
        context: Context,
        profileId: String,
    ) {
        val profile = AuthManager.getConnectionProfiles().firstOrNull { it.id == profileId } ?: return
        if (AuthManager.getProfileToken(profileId).isNullOrBlank()) return
        val url = runCatching { profile.resolveBaseUrl(AuthManager.getBaseUrl()) }.getOrNull() ?: return
        requestWithLanPermission(context, url, profileId) { useExistingProfile(profileId) }
    }

    /**
     * Step 1: Probe the dashboard to detect what auth it needs.
     *
     * Uses the public `GET /api/status` endpoint as the authoritative source:
     * it reports `auth_required` (gate engaged on non-loopback binds), the
     * provider names and the advertised `auth_flows`. For a gated dashboard the
     * sign-in options come from `GET /api/auth/providers`; each one maps to a
     * [DashboardAuthMode] (password form or system-browser sign-in).
     */
    fun probe() {
        val certificateAttempt = certificatePrompt.reset()
        val state = _uiState.value
        val endpoint =
            runCatching { ServerEndpoint.parseForBuild(state.baseUrl) }.getOrNull()
        if (endpoint == null) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.connect_error_url_invalid)) }
            return
        }

        _uiState.update { it.copy(probing = true, errorMessage = null, authMode = null) }

        viewModelScope.launch {
            var certificateFailure = false
            val result =
                withContext(Dispatchers.IO) {
                    probeDashboardInternal(endpoint) { certificateFailure = true }
                }
            _uiState.update {
                it.copy(
                    probing = false,
                    authMode = result?.authMode,
                    providers = result?.providers.orEmpty(),
                    selectedProvider = result?.selectedProvider,
                    browserSupported = result?.browserSupported ?: true,
                    token = result?.extractedToken ?: it.token,
                    errorMessage =
                        if (result == null) {
                            app.getString(
                                if (certificateFailure) {
                                    R.string.mtls_prompt_connection_error
                                } else {
                                    R.string.auth_login_error_unreachable
                                },
                            )
                        } else {
                            null
                        },
                )
            }
            // Publish the failed probe state before offering the dialog so a fast save cannot be overwritten by it.
            if (certificateFailure) certificatePrompt.offer(certificateAttempt, endpoint.baseUrl)
        }
    }

    /**
     * Result of probing the dashboard.
     */
    private data class ProbeResult(
        val authMode: DashboardAuthMode,
        val extractedToken: String? = null,
        val providers: List<AuthProviderOption> = emptyList(),
        val selectedProvider: String? = null,
        val browserSupported: Boolean = true,
    )

    /**
     * Derives the initial [DashboardAuthMode] from the authoritative `/api/status` gate flag and the
     * advertised sign-in options.
     *
     * - Gate down (loopback / `--insecure`): caller decides TOKEN_ONLY vs ALL
     *   based on whether the SPA embedded a token, so this returns a sentinel
     *   [DashboardAuthMode.ALL] placeholder that [probeDashboardInternal] refines.
     * - Gate up: the first advertised provider decides (password form vs system browser); the user
     *   can switch when several are offered. No providers at all keeps the legacy password form.
     *
     * Internal + pure so it is unit-testable without a live server.
     */
    internal fun deriveAuthMode(
        authRequired: Boolean,
        providers: List<AuthProviderOption>,
    ): DashboardAuthMode =
        if (!authRequired) {
            // Refined by the caller once it knows whether the SPA embedded a token.
            DashboardAuthMode.ALL
        } else {
            providers.firstOrNull()?.let(::modeForProvider) ?: DashboardAuthMode.BASIC_AUTH
        }

    /** A provider that takes a password gets the form; every other provider signs in via the browser. */
    internal fun modeForProvider(provider: AuthProviderOption): DashboardAuthMode =
        if (provider.supportsPassword) DashboardAuthMode.BASIC_AUTH else DashboardAuthMode.BROWSER

    /**
     * Probes `GET /api/status` and derives the [DashboardAuthMode] from the
     * authoritative `auth_required` + `auth_providers` fields.
     *
     * Returns null if the dashboard is unreachable. When [ProbeResult.extractedToken]
     * is non-null, the session token was found embedded in the dashboard SPA HTML
     * (loopback mode only) and can be auto-populated.
     */
    private suspend fun probeDashboardInternal(
        endpoint: ServerEndpoint,
        onCertificateFailure: () -> Unit,
    ): ProbeResult? {
        // Step 1: reachability + sign-in options from the public status endpoint.
        val info =
            try {
                probeGatewayAuth(probeClient, endpoint)
            } catch (e: Exception) {
                if (isClientCertificateAuthenticationFailure(endpoint.baseUrl, e)) {
                    onCertificateFailure()
                }
                Log.w(TAG, "Status probe failed: ${e.message}")
                null
            } ?: return null // Dashboard unreachable or status unreadable

        // No gate (loopback / --insecure): token mode. Try to grab the embedded token.
        if (!info.authRequired) {
            var extractedToken: String? = null
            try {
                val spaReq =
                    Request
                        .Builder()
                        .url(endpoint.resolve("").toString())
                        .get()
                        .build()
                val spaResp = probeClient.newCall(spaReq).await()
                val body = spaResp.body.string()
                val tokenMatch = Regex("""__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"""").find(body)
                extractedToken = tokenMatch?.groupValues?.getOrNull(1)
            } catch (e: Exception) {
                Log.w(TAG, "SPA token extraction failed: ${e.message}")
            }
            val mode =
                if (extractedToken != null) {
                    DashboardAuthMode.TOKEN_ONLY
                } else {
                    DashboardAuthMode.ALL
                }
            return ProbeResult(authMode = mode, extractedToken = extractedToken)
        }

        // Gate engaged (non-loopback bind): each advertised provider maps to a sign-in mode.
        val selected = info.providers.firstOrNull()
        return ProbeResult(
            authMode = deriveAuthMode(true, info.providers),
            providers = info.providers,
            selectedProvider = selected?.name,
            browserSupported = info.browserSupported,
        )
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
                            connectBasicAuth(endpoint, state.username, state.password, state.selectedProvider)
                        }

                        DashboardAuthMode.ALL -> {
                            connectBasicAuth(endpoint, state.username, state.password, state.selectedProvider)
                        }

                        DashboardAuthMode.BROWSER -> {
                            // Started through startBrowserLogin(), which needs an Activity to open the browser.
                            _uiState.update { it.copy(isLoading = false) }
                            null
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
                ProfileSwitchCoordinator.switchConnectionProfile(AuthManager.getSelectedProfileId())
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
    private suspend fun connectBasicAuth(
        endpoint: ServerEndpoint,
        username: String,
        password: String,
        provider: String?,
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

        val jsonBody = AuthPayloads.passwordLogin(username, password, provider ?: "basic")

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
            val loginResp = OkHttpProvider.probe.newCall(loginReq).await()

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

            return mintTicket(endpoint)
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

    /** Mint a WebSocket ticket from the session cookie already in the shared jar. */
    private suspend fun mintTicket(endpoint: ServerEndpoint): ConnectResult? =
        when (val result = mintWsTicket(OkHttpProvider.probe, endpoint)) {
            is WsTicketResult.Success -> {
                ConnectResult(wsCredential = result.ticket)
            }

            is WsTicketResult.HttpError -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = app.getString(R.string.connect_error_http_code, result.code),
                    )
                }
                null
            }

            WsTicketResult.Missing -> {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to obtain WebSocket ticket") }
                null
            }
        }

    /**
     * Sign in through the system browser (RFC 8252: loopback redirect + PKCE) against the selected
     * provider. [openBrowser] receives the authorize URL and must show it in a browser; it may throw
     * if none is available. The flow keeps running while the app is in the background and finishes
     * by installing the same session cookies and WebSocket ticket the password login produces.
     */
    fun startBrowserLogin(openBrowser: (String) -> Unit) {
        val state = _uiState.value
        val provider = state.selectedProvider ?: return
        if (state.authMode != DashboardAuthMode.BROWSER || state.browserWaiting) return
        if (!state.browserSupported) {
            _uiState.update { it.copy(errorMessage = app.getString(R.string.auth_login_error_browser_unsupported)) }
            return
        }
        val endpoint =
            runCatching { ServerEndpoint.parseForBuild(state.baseUrl) }.getOrNull() ?: return

        _uiState.update { it.copy(isLoading = true, browserWaiting = true, errorMessage = null) }
        browserJob =
            viewModelScope.launch {
                val outcome =
                    withContext(Dispatchers.IO) {
                        runNativeBrowserLogin(OkHttpProvider.probe, endpoint, provider, openBrowser)
                    }
                val connected = outcome is BrowserLoginResult.Success
                if (outcome is BrowserLoginResult.Success) {
                    AuthManager.setBaseUrl(state.baseUrl)
                    AuthManager.setToken(outcome.wsTicket)
                    AuthManager.setWsAuthParam("ticket")
                    ProfileSwitchCoordinator.switchConnectionProfile(AuthManager.getSelectedProfileId())
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        browserWaiting = false,
                        connectionSuccess = connected,
                        errorMessage = browserLoginErrorText(app, outcome) ?: it.errorMessage,
                    )
                }
            }
    }

    /** Abort a browser sign-in that is waiting for the redirect. */
    fun cancelBrowserLogin() {
        browserJob?.cancel()
        browserJob = null
        _uiState.update { it.copy(isLoading = false, browserWaiting = false, errorMessage = null) }
    }
}

class AuthLoginViewModelFactory(
    private val app: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthLoginViewModel(app) as T
}
