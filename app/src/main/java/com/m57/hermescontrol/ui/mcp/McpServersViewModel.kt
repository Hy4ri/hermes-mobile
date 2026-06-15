package com.m57.hermescontrol.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.data.model.McpServerToggleRequest
import com.m57.hermescontrol.data.remote.ApiClient
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

class McpServersViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(McpServersUiState())
    val uiState: StateFlow<McpServersUiState> = _uiState.asStateFlow()

    fun loadServers() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.getMcpServers()
                    }
                if (response.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            servers = response.body()?.servers.orEmpty(),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load MCP servers: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load MCP servers: ${e.message}",
                    )
                }
            }
        }
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
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.toggleMcpServer(server.name, McpServerToggleRequest(targetEnabled))
                    }
                if (!response.isSuccessful) {
                    revertToggle(server.name, originalEnabled, "Failed to toggle server: HTTP ${response.code()}")
                } else {
                    _uiState.update {
                        it.copy(
                            toastMessage = "Server '${server.name}' ${if (targetEnabled) "enabled" else "disabled"}",
                        )
                    }
                }
            } catch (e: Exception) {
                revertToggle(server.name, originalEnabled, "Failed to toggle server: ${e.message}")
            }
        }
    }

    fun testServer(name: String) {
        _uiState.update { it.copy(isActionRunning = true) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.testMcpServer(name)
                    }
                if (response.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Server '$name' tested successfully",
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Server '$name' test failed: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionRunning = false,
                        toastMessage = "Server '$name' test failed: ${e.message}",
                    )
                }
            }
        }
    }

    fun deleteServer(name: String) {
        _uiState.update { it.copy(isActionRunning = true) }
        viewModelScope.launch {
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        ApiClient.hermesApi.deleteMcpServer(name)
                    }
                if (response.isSuccessful) {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Server '$name' deleted successfully",
                        )
                    }
                    loadServers()
                } else {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Failed to delete server: HTTP ${response.code()}",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionRunning = false,
                        toastMessage = "Failed to delete server: ${e.message}",
                    )
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

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
