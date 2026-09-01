package com.m57.hermescontrol.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.AddMcpServerRequest
import com.m57.hermescontrol.data.model.McpCatalogEntry
import com.m57.hermescontrol.data.model.McpCatalogInstallRequest
import com.m57.hermescontrol.data.model.McpOAuthFlowResponse
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.data.model.McpServerTestResponse
import com.m57.hermescontrol.data.model.McpServerToggleRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AddServerMode { HTTP, Stdio }

data class McpServersUiState(
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

class McpServersViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(McpServersUiState())
    val uiState: StateFlow<McpServersUiState> = _uiState.asStateFlow()

    // ── Data loading ──────────────────────────────────────────

    fun loadServers() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getMcpServers() } },
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
                withContext(Dispatchers.IO) {
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
                    revertToggle(server.name, originalEnabled, "Failed to toggle server: ${result.error.message}")
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
                withContext(Dispatchers.IO) {
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
                    async(Dispatchers.IO) {
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
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.deleteMcpServer(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Server '$name' deleted") }
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to delete server: ${result.error.message}") }
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
                    async(Dispatchers.IO) {
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
                val errSummary = errors.joinToString("; ")
                val msg =
                    when {
                        errors.isEmpty() -> "Successfully imported $successCount server(s)"
                        successCount > 0 -> "Imported $successCount server(s), ${errors.size} failed: $errSummary"
                        else -> "Failed to import: $errSummary"
                    }
                state.copy(
                    isImportingJson = false,
                    showImportDialog = false,
                    importJsonInput = "",
                    toastMessage = msg,
                )
            }
            loadServers()
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
            val request =
                AddMcpServerRequest(
                    name = state.addServerName.trim(),
                    url = if (state.addMode == AddServerMode.HTTP) state.addServerUrl.trim().ifBlank { null } else null,
                    command =
                        if (state.addMode == AddServerMode.Stdio) {
                            state.addServerCommand.trim().ifBlank { null }
                        } else {
                            null
                        },
                    args =
                        if (state.addMode == AddServerMode.Stdio && state.addServerArgs.isNotBlank()) {
                            state.addServerArgs
                                .trim()
                                .split("\\s+".toRegex())
                                .filter { it.isNotEmpty() }
                        } else {
                            null
                        },
                    auth =
                        if (state.addMode == AddServerMode.HTTP && state.addServerAuth != "none") {
                            state.addServerAuth
                        } else {
                            null
                        },
                    bearerToken =
                        if (state.addMode == AddServerMode.HTTP && state.addServerAuth == "header") {
                            state.addServerBearerToken.trim().ifBlank { null }
                        } else {
                            null
                        },
                )
            val result =
                withContext(Dispatchers.IO) {
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
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            addingServer = false,
                            toastMessage = "Failed to add server: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Env var editing ──────────────────────────────────────

    fun startEditingEnv(server: McpServer) {
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
        val state = _uiState.value
        val key = state.envKeyInput.trim()
        val value = state.envValueInput.trim()
        if (key.isBlank()) {
            _uiState.update { it.copy(toastMessage = "Key is required") }
            return
        }
        viewModelScope.launch {
            val existingEnv = state.servers.find { it.name == serverName }?.env ?: emptyMap()
            val updatedEnv = existingEnv + (key to value)
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updateMcpServer(serverName, mapOf("env" to updatedEnv)) }
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
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to add env var: ${result.error.message}") }
                }
            }
        }
    }

    fun removeEnvVar(
        serverName: String,
        key: String,
    ) {
        viewModelScope.launch {
            val state = _uiState.value
            val existingEnv = state.servers.find { it.name == serverName }?.env ?: emptyMap()
            val updatedEnv = existingEnv - key
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updateMcpServer(serverName, mapOf("env" to updatedEnv)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Env var '$key' removed") }
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to remove env var: ${result.error.message}") }
                }
            }
        }
    }

    // ── Catalog ──────────────────────────────────────────────

    fun loadCatalog() {
        _uiState.update { it.copy(catalogLoading = true, catalogError = null) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
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
                withContext(Dispatchers.IO) {
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
                    loadServers()
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

    private var oauthPollJob: kotlinx.coroutines.Job? = null

    fun startMcpOAuthFlow(
        server: McpServer,
        onOpenBrowser: (String) -> Unit,
    ) {
        oauthPollJob?.cancel()
        _uiState.update { it.copy(toastMessage = "Starting OAuth authorization…") }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.authMcpServer(server.name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val flow = result.data
                    _uiState.update { it.copy(activeOAuthFlow = flow) }
                    flow.authorizationUrl?.let { url ->
                        onOpenBrowser(url)
                        startPollingOAuthFlow(flow.flowId)
                    } ?: run {
                        _uiState.update {
                            it.copy(
                                toastMessage = "Failed to start OAuth: No authorization URL returned",
                            )
                        }
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = "Failed to start OAuth: ${result.error.message}") }
                }
            }
        }
    }

    private fun startPollingOAuthFlow(flowId: String) {
        oauthPollJob?.cancel()
        oauthPollJob =
            viewModelScope.launch {
                var polling = true
                while (polling) {
                    kotlinx.coroutines.delay(2000)
                    val result =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getMcpOAuthFlowStatus(flowId) }
                        }
                    when (result) {
                        is NetworkResult.Success -> {
                            val flow = result.data
                            _uiState.update { it.copy(activeOAuthFlow = flow) }
                            when (flow.status) {
                                "approved", "completed" -> {
                                    polling = false
                                    _uiState.update {
                                        it.copy(
                                            activeOAuthFlow = null,
                                            toastMessage = "OAuth authorization successful!",
                                        )
                                    }
                                    loadServers()
                                }

                                "error" -> {
                                    polling = false
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
                                    // Check if worker is done or expired
                                    // Continue polling until status transitions
                                }
                            }
                        }

                        is NetworkResult.Failure -> {
                            // Keep polling or stop after too many failures? Let's just log/toast n retry a few times
                        }
                    }
                }
            }
    }

    fun dismissOAuthFlow() {
        oauthPollJob?.cancel()
        oauthPollJob = null
        _uiState.update { it.copy(activeOAuthFlow = null) }
    }

    // ── Helpers ──────────────────────────────────────────────

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
