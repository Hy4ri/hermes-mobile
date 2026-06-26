package com.m57.hermescontrol.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.safeLaunchLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsUiState(
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val sessions: List<SessionInfo> = emptyList(),
    val total: Int = 0,
    val errorMessage: String? = null,
) {
    val hasMore: Boolean get() = total > sessions.size
}

class SessionsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /** Page size sent to the server — matches the default the gateway uses. */
    private companion object {
        const val PAGE_SIZE = 20
    }

    /** Load (or reload) sessions from page 0. Used by pull-to-refresh and initial load. */
    fun loadSessions() {
        loadJob =
            safeLaunchLoad(
                currentJob = loadJob,
                apiCall = {
                    safeApiCall {
                        ApiClient.hermesApi.getSessions(
                            limit = PAGE_SIZE,
                            offset = 0,
                            order = "recent",
                        )
                    }
                },
                onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
                onSuccess = { data ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            sessions = data?.sessions.orEmpty(),
                            total = data?.total ?: 0,
                        )
                    }
                },
                onError = { errorMsg ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            errorMessage = "Failed to load sessions: $errorMsg",
                        )
                    }
                },
            )
    }

    /** Load the next page and append to the existing session list. */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || !state.hasMore) return

        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.getSessions(
                        limit = PAGE_SIZE,
                        offset = state.sessions.size,
                        order = "recent",
                    )
                }
            when (result) {
                is com.m57.hermescontrol.data.remote.NetworkResult.Success -> {
                    val data = result.data
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            sessions = it.sessions + data.sessions,
                            total = data.total,
                        )
                    }
                }

                is com.m57.hermescontrol.data.remote.NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            errorMessage = "Failed to load more: ${result.error.message}",
                        )
                    }
                }
            }
        }
    }
}
