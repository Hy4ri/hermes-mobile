package com.m57.hermescontrol.ui.workspace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.config.ServerStoreState
import com.m57.hermescontrol.data.config.WorkspaceDefinition
import com.m57.hermescontrol.data.config.closeSessionTab
import com.m57.hermescontrol.data.config.createWorkspace
import com.m57.hermescontrol.data.config.moveSessionToWorkspace
import com.m57.hermescontrol.data.config.openSessionTab
import com.m57.hermescontrol.data.config.removeWorkspace
import com.m57.hermescontrol.data.config.renameWorkspace
import com.m57.hermescontrol.data.config.selectWorkspace
import com.m57.hermescontrol.data.config.setSessionModel
import com.m57.hermescontrol.data.config.toggleSessionPin
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

private const val SESSION_REFRESH_MS = 15_000L

data class WorkspaceUiState(
    val store: ServerStoreState = ServerStoreState(),
    val sessions: Map<String, SessionInfo> = emptyMap(),
    val activeRuns: Int = 0,
    val isRefreshing: Boolean = false,
    val lastRefreshEpochMs: Long? = null,
    val errorMessage: String? = null,
) {
    val selectedWorkspace: WorkspaceDefinition?
        get() = store.workspaces.firstOrNull { it.id == store.selectedWorkspaceId }

    val openSessions: List<SessionInfo>
        get() = store.openSessionIds.map { id -> sessions[id] ?: SessionInfo(id = id) }

    val pinnedModels: List<PinnedModel>
        get() = store.pinnedModels
}

class WorkspaceViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val serverStore = AuthManager.serverStore
    private val _uiState = MutableStateFlow(WorkspaceUiState(store = serverStore.getLatestState()))
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            serverStore.stateFlow.collect { state -> _uiState.update { it.copy(store = state) } }
        }
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(SESSION_REFRESH_MS)
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            val sessionsResult =
                safeApiCall { ApiClient.hermesApi.getSessions(limit = 100, offset = 0, order = "recent") }
            val statsResult = safeApiCall { ApiClient.hermesApi.getSessionStats() }
            when (sessionsResult) {
                is NetworkResult.Success -> {
                    _uiState.update { current ->
                        current.copy(
                            sessions = sessionsResult.data.sessions.associateBy { it.id },
                            activeRuns = (statsResult as? NetworkResult.Success)?.data?.active ?: current.activeRuns,
                            isRefreshing = false,
                            lastRefreshEpochMs = System.currentTimeMillis(),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            errorMessage = sessionsResult.error.message ?: "Workspace status unavailable",
                        )
                    }
                }
            }
        }
    }

    fun openSession(sessionId: String) =
        mutate { state ->
            var updated = state.openSessionTab(sessionId)
            val assigned = updated.workspaces.any { sessionId in it.sessionIds }
            val workspaceId = updated.selectedWorkspaceId
            if (!assigned && workspaceId != null) updated = updated.moveSessionToWorkspace(sessionId, workspaceId)
            updated
        }

    fun closeSession(sessionId: String) = mutate { it.closeSessionTab(sessionId) }

    fun togglePin(sessionId: String) = mutate { it.toggleSessionPin(sessionId) }

    fun setSessionModel(
        sessionId: String,
        modelAlias: String?,
    ) = mutate { it.setSessionModel(sessionId, modelAlias) }

    fun createWorkspace(name: String) = mutate { it.createWorkspace(UUID.randomUUID().toString(), name) }

    fun renameWorkspace(
        id: String,
        name: String,
    ) = mutate { it.renameWorkspace(id, name) }

    fun deleteWorkspace(id: String) = mutate { it.removeWorkspace(id) }

    fun selectWorkspace(id: String) = mutate { it.selectWorkspace(id) }

    fun moveSession(
        sessionId: String,
        workspaceId: String,
    ) = mutate { it.moveSessionToWorkspace(sessionId, workspaceId) }

    private fun mutate(transform: (ServerStoreState) -> ServerStoreState) {
        serverStore.update(transform)
    }
}
