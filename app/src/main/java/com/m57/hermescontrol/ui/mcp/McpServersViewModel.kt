package com.m57.hermescontrol.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.data.model.McpServerToggleRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class McpServersUiState(
    val isLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    val servers: List<McpServer> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class McpServersViewModel : ViewModel(), ToastHost {
    private val _uiState = MutableStateFlow(McpServersUiState())
    val uiState: StateFlow<McpServersUiState> = _uiState.asStateFlow()

    fun loadServers() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getMcpServers() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        servers = data.servers.orEmpty(),
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
                        ApiClient.hermesApi.toggleMcpServer(server.name, McpServerToggleRequest(targetEnabled))
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
        _uiState.update { it.copy(isActionRunning = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.testMcpServer(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Server '$name' tested successfully",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Server '$name' test failed: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

    fun deleteServer(name: String) {
        _uiState.update { it.copy(isActionRunning = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.deleteMcpServer(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Server '$name' deleted successfully",
                        )
                    }
                    loadServers()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Failed to delete server: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }

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
