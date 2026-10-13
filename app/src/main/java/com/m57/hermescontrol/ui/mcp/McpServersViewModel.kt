package com.m57.hermescontrol.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.local.SwrCache
import com.m57.hermescontrol.data.model.AddMcpServerRequest
import com.m57.hermescontrol.data.model.McpCatalogEntry
import com.m57.hermescontrol.data.model.McpCatalogInstallRequest
import com.m57.hermescontrol.data.model.McpOAuthFlowResponse
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.data.model.McpServerTestResponse
import com.m57.hermescontrol.data.model.McpServerToggleRequest
import com.m57.hermescontrol.data.model.McpServersResponse
import com.m57.hermescontrol.data.model.replaceMcpEnvValue
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.remote.safeApiRead
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import com.m57.hermescontrol.ui.common.safeLaunchSwrLoad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import retrofit2.Response

enum class AddServerMode { HTTP, Stdio }

enum class McpTab { INSTALLED, CATALOG }

data class McpServersUiState(
    val selectedTab: McpTab = McpTab.INSTALLED,
    val isLoading: Boolean = false,
    val servers: List<McpServer> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    // Add server form
    val showAddForm: Boolean = false,
    val addMode: AddServerMode = AddServerMode.HTTP,
    val addServerName: String = "",
    val addServerUrl: String = "",
    val addServerCommand: String = "",
    val addServerArgs: String = "",
    val addServerAuth: String = "none", // "none" | "header" | "oauth"
    val addServerBearerToken: String = "",
    val addingServer: Boolean = false,
    // JSON Import (issue #1029)
    val showImportDialog: Boolean = false,
    val importJsonInput: String = "",
    val isImportingJson: Boolean = false,
    // Batch health & tool test results (issue #1029)
    val serverTestResults: Map<String, McpServerTestResponse> = emptyMap(),
    val testingServers: Set<String> = emptySet(),
    val isTestingAll: Boolean = false,
    // Env vars for editing
    val editingEnvFor: String? = null,
    val envKeyInput: String = "",
    val envValueInput: String = "",
    // Catalog
    val catalogQuery: String = "",
    val catalogEntries: List<McpCatalogEntry> = emptyList(),
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val installingCatalogEntry: String? = null,
    val catalogInstallEnv: Map<String, String> = emptyMap(),
    val activeOAuthFlow: McpOAuthFlowResponse? = null,
)

class McpServersViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scopedServiceFactory: (
        scope: DataScope,
        token: String?,
        cookies: List<Cookie>,
    ) -> HermesApiService = { scope, token, cookies ->
        ApiClient.createScopedService(scope, token, cookies)
    },
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(McpServersUiState())
    val uiState: StateFlow<McpServersUiState> = _uiState.asStateFlow()

    private val mcpCache = SwrCache<String, McpServersResponse>()

    fun clearScopeOwnedState() {
        cancelCurrentOAuthFlowInternal()
        _uiState.update {
            it.copy(
                isLoading = false,
                servers = emptyList(),
                serverTestResults = emptyMap(),
                testingServers = emptySet(),
                isTestingAll = false,
                errorMessage = null,
                activeOAuthFlow = null,
            )
        }
    }

    // ── Data loading ──────────────────────────────────────────

    fun loadServers(forceRefresh: Boolean = false) {
        safeLaunchSwrLoad(
            cache = mcpCache,
            forceRefresh = forceRefresh,
            onCacheHit = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        servers = data.servers.orEmpty(),
                        serverTestResults = emptyMap(),
                        testingServers = emptySet(),
                        errorMessage = null,
                    )
                }
            },
            apiCall = { safeApiRead { ApiClient.hermesApi.getMcpServers() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        servers = data.servers.orEmpty(),
                        serverTestResults = emptyMap(),
                        testingServers = emptySet(),
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load MCP servers: $errorMsg",
                    )
                }
            },
        )
    }

    // ── Server toggle ────────────────────────────────────────

    fun toggleServer(server: McpServer) {
        if (server.isPluginOwned || isPluginOwned(server.name)) return
        val originalEnabled = server.enabled
        val targetEnabled = !originalEnabled

        _uiState.update { state ->
            state.copy(
                servers =
                    state.servers.map {
                        if (it.name == server.name) it.copy(enabled = targetEnabled) else it
                    },
            )
        }

        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.toggleMcpServer(
                            server.name,
                            McpServerToggleRequest(targetEnabled),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Server '${server.name}' ${if (targetEnabled) "enabled" else "disabled"}",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    revertToggle(server.name, originalEnabled, mutationError(result.error, "Failed to toggle server"))
                }
            }
        }
    }

    fun testServer(name: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    testingServers = it.testingServers + name,
                    toastMessage = "Testing server '$name'…",
                )
            }
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.testMcpServer(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val response = result.data
                    val toolCount = response.tools.size
                    val tokenEst = McpTokenEstimator.estimateTokens(response.tools)
                    val tokenStr = McpTokenEstimator.formatTokenOverhead(toolCount, tokenEst)
                    _uiState.update { state ->
                        state.copy(
                            serverTestResults = state.serverTestResults + (name to response),
                            testingServers = state.testingServers - name,
                            toastMessage =
                                if (response.ok) {
                                    "Server '$name' tested — OK ($tokenStr)"
                                } else {
                                    "Server '$name' test failed: ${response.error ?: "unknown error"}"
                                },
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    val errResp = McpServerTestResponse(ok = false, error = result.error.message)
                    _uiState.update { state ->
                        state.copy(
                            serverTestResults = state.serverTestResults + (name to errResp),
                            testingServers = state.testingServers - name,
                            toastMessage = "Server '$name' test failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun testAllServers() {
        val enabledServers = _uiState.value.servers.filter { it.enabled }
        if (enabledServers.isEmpty()) {
            _uiState.update { it.copy(toastMessage = "No enabled servers to test") }
            return
        }

        viewModelScope.launch {
            val names = enabledServers.map { it.name }.toSet()
            _uiState.update {
                it.copy(
                    isTestingAll = true,
                    testingServers = it.testingServers + names,
                    toastMessage = "Testing ${enabledServers.size} server(s)…",
                )
            }

            val testJobs =
                enabledServers.map { server ->
                    async(ioDispatcher) {
                        val res = safeApiCall { ApiClient.hermesApi.testMcpServer(server.name) }
                        server.name to res
                    }
                }

            val results = testJobs.awaitAll()
            val newResults = mutableMapOf<String, McpServerTestResponse>()
            var passCount = 0

            for ((name, result) in results) {
                when (result) {
                    is NetworkResult.Success -> {
                        newResults[name] = result.data
                        if (result.data.ok) passCount++
                    }

                    is NetworkResult.Failure -> {
                        newResults[name] = McpServerTestResponse(ok = false, error = result.error.message)
                    }
                }
            }

            val failCount = enabledServers.size - passCount
            _uiState.update { state ->
                state.copy(
                    isTestingAll = false,
                    serverTestResults = state.serverTestResults + newResults,
                    testingServers = state.testingServers - names,
                    toastMessage =
                        "Tested ${enabledServers.size} servers: $passCount passed, $failCount failed",
                )
            }
        }
    }

    fun deleteServer(name: String) {
        if (isPluginOwned(name)) return
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.deleteMcpServer(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Server '$name' deleted") }
                    loadServers(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = mutationError(result.error, "Failed to delete server")) }
                }
            }
        }
    }

    // ── JSON Import (issue #1029) ────────────────────────────

    fun toggleImportDialog() {
        _uiState.update { it.copy(showImportDialog = !it.showImportDialog, importJsonInput = "") }
    }

    fun updateImportJsonInput(v: String) {
        _uiState.update { it.copy(importJsonInput = v) }
    }

    fun submitImportJson() {
        val input = _uiState.value.importJsonInput
        val requests = McpJsonParser.parse(input)
        if (requests.isEmpty()) {
            _uiState.update { it.copy(toastMessage = "Invalid JSON or no MCP servers found in input") }
            return
        }

        _uiState.update { it.copy(isImportingJson = true) }
        viewModelScope.launch {
            val importJobs =
                requests.map { req ->
                    async(ioDispatcher) {
                        val result = safeApiCall { ApiClient.hermesApi.addMcpServer(req) }
                        req to result
                    }
                }

            val results = importJobs.awaitAll()
            var successCount = 0
            val errors = mutableListOf<String>()

            for ((req, result) in results) {
                when (result) {
                    is NetworkResult.Success -> successCount++
                    is NetworkResult.Failure -> errors.add("${req.name}: ${result.error.message}")
                }
            }

            _uiState.update { state ->
                val msg = McpImportSummary.formatSummary(successCount, errors)
                state.copy(
                    isImportingJson = false,
                    showImportDialog = false,
                    importJsonInput = "",
                    toastMessage = msg,
                )
            }
            if (successCount > 0) {
                loadServers(forceRefresh = true)
            }
        }
    }

    // ── Add server form ──────────────────────────────────────

    fun toggleAddForm() {
        _uiState.update { it.copy(showAddForm = !it.showAddForm) }
    }

    fun setAddMode(mode: AddServerMode) {
        _uiState.update { it.copy(addMode = mode) }
    }

    fun updateAddServerName(v: String) {
        _uiState.update { it.copy(addServerName = v) }
    }

    fun updateAddServerUrl(v: String) {
        _uiState.update { it.copy(addServerUrl = v) }
    }

    fun updateAddServerCommand(v: String) {
        _uiState.update { it.copy(addServerCommand = v) }
    }

    fun updateAddServerArgs(v: String) {
        _uiState.update { it.copy(addServerArgs = v) }
    }

    fun updateAddServerAuth(v: String) {
        _uiState.update { it.copy(addServerAuth = v) }
    }

    fun updateAddServerBearerToken(v: String) {
        _uiState.update { it.copy(addServerBearerToken = v) }
    }

    fun submitAddServer() {
        val state = _uiState.value
        if (state.addServerName.isBlank()) {
            _uiState.update { it.copy(toastMessage = "Server name is required") }
            return
        }
        _uiState.update { it.copy(addingServer = true) }
        viewModelScope.launch {
            val request = AddServerRequestBuilder.build(state)
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.addMcpServer(request) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            addingServer = false,
                            showAddForm = false,
                            addServerName = "",
                            addServerUrl = "",
                            addServerCommand = "",
                            addServerArgs = "",
                            addServerAuth = "none",
                            addServerBearerToken = "",
                            toastMessage = "Server '${request.name}' added",
                        )
                    }
                    loadServers(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            addingServer = false,
                            toastMessage = mutationError(result.error, "Failed to add server"),
                        )
                    }
                }
            }
        }
    }

    // ── Env var editing ──────────────────────────────────────

    fun startEditingEnv(server: McpServer) {
        if (server.isPluginOwned || isPluginOwned(server.name)) return
        _uiState.update { it.copy(editingEnvFor = server.name, envKeyInput = "", envValueInput = "") }
    }

    fun stopEditingEnv() {
        _uiState.update { it.copy(editingEnvFor = null, envKeyInput = "", envValueInput = "") }
    }

    fun updateEnvKey(v: String) {
        _uiState.update { it.copy(envKeyInput = v) }
    }

    fun updateEnvValue(v: String) {
        _uiState.update { it.copy(envValueInput = v) }
    }

    fun addEnvVar(serverName: String) {
        if (isPluginOwned(serverName)) return
        val state = _uiState.value
        val key = state.envKeyInput.trim()
        val value = state.envValueInput.trim()
        if (key.isBlank()) {
            _uiState.update { it.copy(toastMessage = "Key is required") }
            return
        }
        val scope = AuthManager.currentDataScope()
        val profile = AuthManager.activeProfileId.value
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall(retries = 0) {
                        updateServerEnv(serverName, key, value, scope, profile)
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            envKeyInput = "",
                            envValueInput = "",
                            toastMessage = "Env var '$key' added",
                        )
                    }
                    loadServers(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = mutationError(result.error, "Failed to add env var")) }
                }
            }
        }
    }

    fun removeEnvVar(
        serverName: String,
        key: String,
    ) {
        if (isPluginOwned(serverName)) return
        val scope = AuthManager.currentDataScope()
        val profile = AuthManager.activeProfileId.value
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall(retries = 0) {
                        updateServerEnv(serverName, key, null, scope, profile)
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Env var '$key' removed") }
                    loadServers(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = mutationError(result.error, "Failed to remove env var")) }
                }
            }
        }
    }

    // ── Catalog ──────────────────────────────────────────────

    // #1469: the dashboard only supports replacing the complete saved MCP map.
    private val envMutationMutex = Mutex()

    private suspend fun updateServerEnv(
        serverName: String,
        key: String,
        value: String?,
        scope: DataScope?,
        profile: String?,
    ): Response<Unit> =
        envMutationMutex.withLock {
            check(AuthManager.currentDataScope() == scope && AuthManager.activeProfileId.value == profile) {
                "Server or profile changed; retry this edit in the selected profile"
            }
            val api = ApiClient.hermesApi
            val saved = api.getSavedConfig(profile)
            if (!saved.isSuccessful) {
                return@withLock Response.error(
                    saved.code(),
                    saved.errorBody() ?: error("Could not read saved MCP configuration"),
                )
            }
            val config = saved.body() ?: error("Saved MCP configuration is empty")
            val replacement = replaceMcpEnvValue(config, serverName, key, value, profile)
            check(
                AuthManager.currentDataScope() == scope && AuthManager.activeProfileId.value == profile,
            ) { "Server or profile changed; retry this edit in the selected profile" }
            api.replaceMcpServers(replacement)
        }

    fun loadCatalog() {
        _uiState.update { it.copy(catalogLoading = true, catalogError = null) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.getMcpCatalog() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            catalogLoading = false,
                            catalogEntries = result.data.entries.orEmpty(),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            catalogLoading = false,
                            catalogError = "Failed to load catalog: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun setTab(tab: McpTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        val s = _uiState.value
        if (tab == McpTab.CATALOG && s.catalogEntries.isEmpty() && !s.catalogLoading) {
            loadCatalog()
        }
    }

    fun updateCatalogQuery(v: String) {
        _uiState.update { it.copy(catalogQuery = v) }
    }

    fun installCatalogEntry(entry: McpCatalogEntry) {
        val state = _uiState.value
        _uiState.update { it.copy(installingCatalogEntry = entry.name) }
        viewModelScope.launch {
            val request =
                McpCatalogInstallRequest(
                    name = entry.name,
                    env = state.catalogInstallEnv.ifEmpty { null },
                )
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.installMcpCatalogEntry(request) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            installingCatalogEntry = null,
                            catalogInstallEnv = emptyMap(),
                            toastMessage = "Catalog entry '${entry.name}' installed",
                        )
                    }
                    loadServers(forceRefresh = true)
                    loadCatalog()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            installingCatalogEntry = null,
                            toastMessage = "Failed to install: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun updateCatalogEnvVar(
        key: String,
        value: String,
    ) {
        _uiState.update { it.copy(catalogInstallEnv = it.catalogInstallEnv + (key to value)) }
    }

    // ── OAuth ─────────────────────────────────────────────────

    private data class OAuthFlowSession(
        val generation: Long,
        val service: HermesApiService,
        val scope: DataScope?,
        val flowIdDeferred: CompletableDeferred<String?> = CompletableDeferred(),
        var terminal: Boolean = false,
    )

    // #1560: starts survive presentation dismissal long enough to recover and cancel their returned ID.
    // State transitions stay on Main; only HTTP work moves to the injected IO dispatcher.
    private val oauthScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentOAuthGeneration: Long = 0L
    private var currentOAuthSession: OAuthFlowSession? = null
    private var oauthPollJob: Job? = null
    private var oauthCleanupTail: Deferred<Boolean>? = null
    private var failedCleanupSession: OAuthFlowSession? = null
    private var oauthCleared = false

    private fun ownsOAuthSession(session: OAuthFlowSession): Boolean =
        !oauthCleared && currentOAuthSession === session && session.generation == currentOAuthGeneration &&
            AuthManager.currentDataScope() == session.scope

    private fun captureCurrentOAuthService(): Pair<HermesApiService, DataScope?> {
        val scope = AuthManager.currentDataScope()
        val token = if (AuthManager.isGatedMode()) null else AuthManager.getToken()
        val cookies =
            if (CookieManager.isInitialized()) {
                val url = runCatching { AuthManager.endpointForBuild().baseUrl }.getOrNull()
                if (url != null) CookieManager.cookieJar.loadForRequest(url) else emptyList()
            } else {
                emptyList()
            }
        val effectiveScope =
            scope ?: DataScope(
                connectionProfileId = AuthManager.getSelectedProfileId() ?: AuthManager.DEFAULT_PROFILE_ID,
                baseUrl =
                    AuthManager
                        .endpointForBuild()
                        .baseUrl
                        .toString()
                        .trimEnd('/'),
                activeProfileId = AuthManager.activeProfileId.value ?: AuthManager.DEFAULT_PROFILE_ID,
                inMemoryAuthGeneration = 0,
            )
        check(AuthManager.currentDataScope() == scope) { "Connection changed while preparing OAuth" }
        val service = scopedServiceFactory(effectiveScope, token, cookies)
        return Pair(service, scope)
    }

    private fun cancelCurrentOAuthFlowInternal(): Deferred<Boolean>? {
        val generation = ++currentOAuthGeneration
        oauthPollJob?.cancel()
        oauthPollJob = null
        val session = currentOAuthSession ?: failedCleanupSession
        val previous = oauthCleanupTail
        currentOAuthSession = null
        _uiState.update { it.copy(activeOAuthFlow = null) }

        if (session == null || session.terminal) return previous
        val retryingFailedCleanup = failedCleanupSession === session
        val cleanup =
            oauthScope.async {
                try {
                    withTimeout(OAUTH_CLEANUP_TIMEOUT_MS) {
                        if (previous?.await() == false && !retryingFailedCleanup) return@withTimeout false
                        val flowId = session.flowIdDeferred.await()
                        if (flowId.isNullOrBlank()) return@withTimeout true
                        val result =
                            withContext(ioDispatcher) {
                                safeApiCall(retries = 0) { session.service.cancelMcpOAuthFlow(flowId) }
                            }
                        val released = result is NetworkResult.Success && result.data.ok
                        if (released) {
                            if (failedCleanupSession === session) failedCleanupSession = null
                        } else {
                            failedCleanupSession = session
                            if (!oauthCleared && generation == currentOAuthGeneration &&
                                AuthManager.currentDataScope() == session.scope
                            ) {
                                val message =
                                    when (result) {
                                        is NetworkResult.Failure -> {
                                            mutationError(
                                                result.error,
                                                "Failed to cancel OAuth",
                                            )
                                        }

                                        is NetworkResult.Success -> {
                                            "Failed to cancel OAuth: Gateway refused cancellation"
                                        }
                                    }
                                _uiState.update { it.copy(toastMessage = message) }
                            }
                        }
                        released
                    }
                } catch (e: Exception) {
                    failedCleanupSession = session
                    if (!oauthCleared && generation == currentOAuthGeneration &&
                        AuthManager.currentDataScope() == session.scope
                    ) {
                        _uiState.update {
                            it.copy(
                                toastMessage = "Failed to cancel OAuth: ${e.message ?: "Unknown error"}",
                            )
                        }
                    }
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    false
                }
            }
        oauthCleanupTail = cleanup
        return cleanup
    }

    fun startMcpOAuthFlow(
        server: McpServer,
        onOpenBrowser: (String) -> Unit,
    ) {
        if (oauthCleared || server.isPluginOwned || isPluginOwned(server.name)) return
        val captured =
            try {
                captureCurrentOAuthService()
            } catch (e: Exception) {
                _uiState.update { it.copy(toastMessage = "Failed to start OAuth: ${e.message}") }
                return
            }
        val priorCleanup = cancelCurrentOAuthFlowInternal()
        val session = OAuthFlowSession(currentOAuthGeneration, captured.first, captured.second)
        currentOAuthSession = session
        _uiState.update { it.copy(toastMessage = "Starting OAuth authorization…") }
        oauthScope.launch {
            try {
                if (priorCleanup?.await() == false) {
                    if (ownsOAuthSession(session)) {
                        _uiState.update { it.copy(toastMessage = "Failed to cancel previous OAuth authorization") }
                    }
                    if (currentOAuthSession === session) currentOAuthSession = null
                    return@launch
                }
                if (oauthCleared || currentOAuthSession !== session || session.generation != currentOAuthGeneration) {
                    return@launch
                }
                val result =
                    withTimeout(OAUTH_START_TIMEOUT_MS) {
                        withContext(
                            ioDispatcher,
                        ) { safeApiCall(retries = 0) { session.service.authMcpServer(server.name) } }
                    }
                when (result) {
                    is NetworkResult.Success -> {
                        val flow = result.data
                        session.terminal =
                            flow.status in setOf("approved", "completed", "error", "cancelled", "expired")
                        session.flowIdDeferred.complete(flow.flowId)
                        if (!ownsOAuthSession(session)) {
                            if (currentOAuthSession === session) cancelCurrentOAuthFlowInternal()
                            return@launch
                        }
                        if (session.terminal) {
                            currentOAuthSession = null
                            _uiState.update {
                                it.copy(
                                    activeOAuthFlow = null,
                                    toastMessage = flow.error ?: flow.status,
                                )
                            }
                            if (flow.status == "approved" ||
                                flow.status == "completed"
                            ) {
                                loadServers(forceRefresh = true)
                            }
                            return@launch
                        }
                        val url = flow.authorizationUrl
                        if (url.isNullOrBlank() || flow.flowId.isBlank()) {
                            _uiState.update {
                                it.copy(
                                    toastMessage = "Failed to start OAuth: No authorization URL returned",
                                )
                            }
                            cancelCurrentOAuthFlowInternal()
                            return@launch
                        }
                        _uiState.update { it.copy(activeOAuthFlow = flow) }
                        onOpenBrowser(url)
                        if (ownsOAuthSession(session)) startPollingOAuthFlow(flow.flowId, session)
                    }

                    is NetworkResult.Failure -> {
                        if (ownsOAuthSession(session)) {
                            _uiState.update {
                                it.copy(
                                    toastMessage = mutationError(result.error, "Failed to start OAuth"),
                                )
                            }
                        }
                        if (currentOAuthSession === session) currentOAuthSession = null
                    }
                }
            } catch (e: Exception) {
                if (ownsOAuthSession(session)) {
                    _uiState.update { it.copy(toastMessage = "Failed to start OAuth: ${e.message ?: "Unknown error"}") }
                }
                if (currentOAuthSession === session) cancelCurrentOAuthFlowInternal()
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
            } finally {
                session.flowIdDeferred.complete(null)
            }
        }
    }

    private fun startPollingOAuthFlow(
        flowId: String,
        session: OAuthFlowSession,
    ) {
        oauthPollJob?.cancel()
        oauthPollJob =
            viewModelScope.launch {
                var polling = true
                var consecutiveFailures = 0
                while (polling) {
                    delay(2000)
                    if (!ownsOAuthSession(session)) {
                        if (currentOAuthSession === session) cancelCurrentOAuthFlowInternal()
                        return@launch
                    }
                    val result =
                        withContext(ioDispatcher) {
                            safeApiCall { session.service.getMcpOAuthFlowStatus(flowId) }
                        }
                    if (!ownsOAuthSession(session)) {
                        if (currentOAuthSession === session) cancelCurrentOAuthFlowInternal()
                        return@launch
                    }
                    when (result) {
                        is NetworkResult.Success -> {
                            val flow = result.data
                            consecutiveFailures = 0
                            _uiState.update { it.copy(activeOAuthFlow = flow) }
                            when (flow.status) {
                                "approved", "completed" -> {
                                    polling = false
                                    session.terminal = true
                                    currentOAuthSession = null
                                    _uiState.update {
                                        it.copy(
                                            activeOAuthFlow = null,
                                            toastMessage = "OAuth authorization successful!",
                                        )
                                    }
                                    loadServers(forceRefresh = true)
                                }

                                "error", "cancelled", "expired" -> {
                                    polling = false
                                    session.terminal = true
                                    currentOAuthSession = null
                                    _uiState.update {
                                        it.copy(
                                            activeOAuthFlow = null,
                                            toastMessage = "OAuth failed: ${flow.error ?: "Unknown error"}",
                                        )
                                    }
                                }

                                "authorization_required" -> {
                                    // Keep polling
                                }

                                else -> {
                                    // Continue polling until status transitions
                                }
                            }
                        }

                        is NetworkResult.Failure -> {
                            consecutiveFailures++
                            if (consecutiveFailures >= 10) {
                                polling = false
                                _uiState.update {
                                    it.copy(
                                        activeOAuthFlow = null,
                                        toastMessage = "OAuth polling timed out after repeated network failures",
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }

    fun dismissOAuthFlow() {
        cancelCurrentOAuthFlowInternal()
    }

    override fun onCleared() {
        oauthCleared = true
        val cleanup = cancelCurrentOAuthFlowInternal()
        oauthScope.launch {
            try {
                withTimeoutOrNull(OAUTH_CLEANUP_TIMEOUT_MS) { cleanup?.await() }
            } finally {
                oauthScope.cancel()
            }
        }
        super.onCleared()
    }

    private companion object {
        const val OAUTH_START_TIMEOUT_MS = 30_000L
        const val OAUTH_CLEANUP_TIMEOUT_MS = 65_000L
    }

    // ── Helpers ──────────────────────────────────────────────

    // #1283: re-check current ownership so stale row callbacks cannot mutate a plugin server.
    private fun isPluginOwned(name: String): Boolean =
        _uiState.value.servers.any { it.name == name && it.isPluginOwned }

    private fun mutationError(
        error: NetworkError,
        prefix: String,
    ): String = if (error is NetworkError.Http && error.code == 409) error.message else "$prefix: ${error.message}"

    private fun revertToggle(
        name: String,
        originalEnabled: Boolean,
        errorMsg: String,
    ) {
        _uiState.update { state ->
            state.copy(
                servers =
                    state.servers.map {
                        if (it.name == name) it.copy(enabled = originalEnabled) else it
                    },
                toastMessage = errorMsg,
            )
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
