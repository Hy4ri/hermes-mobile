package com.m57.hermescontrol.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.local.SwrCache
import com.m57.hermescontrol.data.model.LearningGraphResponse
import com.m57.hermescontrol.data.model.MemoryResetRequest
import com.m57.hermescontrol.data.model.MemoryResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemoryUiState(
    val isLoading: Boolean = false,
    val memory: MemoryResponse? = null,
    val learningGraph: LearningGraphResponse? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val resetting: String? = null,
)

/**
 * Memory management home (moved out of the System tab) — active provider,
 * builtin memory files with reset, and the provider list that drills into
 * per-provider config/setup (issue #783). Deliberately no explicit IO hop:
 * Retrofit suspend calls already run off the caller thread, keeping tests
 * free of the static Dispatchers mock that bleeds across test classes.
 */
class MemoryViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    init {
        // Scope transitions are driven by ProfileSwitchCoordinator (profile switch and
        // connection switch). An additional AuthManager.dataScopeFlow collector would
        // double-fire on the same transition and issue a duplicate request, so the
        // coordinator flows remain the single trigger.
        viewModelScope.launch {
            ProfileSwitchCoordinator.switched.collect {
                onDataScopeChanged(AuthManager.currentDataScope())
            }
        }
        viewModelScope.launch {
            ProfileSwitchCoordinator.connectionSwitched.collect {
                onDataScopeChanged(AuthManager.currentDataScope())
            }
        }
    }

    private var loadJob: Job? = null
    private val memoryCache = SwrCache<String, MemoryResponse>()

    private fun onDataScopeChanged(newScope: DataScope?) {
        loadJob?.cancel()
        _uiState.update {
            it.copy(
                isLoading = false,
                memory = null,
                learningGraph = null,
                resetting = null,
                errorMessage = null,
            )
        }
        // A scope switch must silently reload the new context's data. Clearing alone
        // would leave the screen empty when it is not the composition performing the load.
        load(silent = true)
    }

    fun load(
        silent: Boolean = false,
        forceRefresh: Boolean = false,
    ) {
        if (_uiState.value.isLoading) return
        val requestScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
        val scopedKey = requestScope?.inMemoryKey("default")
        if (forceRefresh && scopedKey != null) memoryCache.remove(scopedKey)
        val cached = if (!forceRefresh && scopedKey != null) memoryCache.get(scopedKey) else null
        if (cached != null) {
            _uiState.update { it.copy(isLoading = false, memory = cached, errorMessage = null) }
        } else if (!silent) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        }
        loadJob =
            viewModelScope.launch {
                coroutineScope {
                    val memoryDeferred = async { safeApiCall { ApiClient.hermesApi.getMemory() } }
                    val graphDeferred = async { safeApiCall { ApiClient.hermesApi.getLearningGraph() } }

                    val memoryResult = memoryDeferred.await()
                    val currentScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
                    if (currentScope != requestScope) return@coroutineScope

                    when (memoryResult) {
                        is NetworkResult.Success -> {
                            if (scopedKey != null) memoryCache.put(scopedKey, memoryResult.data)
                            _uiState.update { it.copy(isLoading = false, memory = memoryResult.data) }
                        }

                        is NetworkResult.Failure -> {
                            if (cached == null || forceRefresh) {
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        errorMessage = memoryResult.error.message,
                                    )
                                }
                            }
                        }
                    }

                    val graphResult = graphDeferred.await()
                    if (graphResult is NetworkResult.Success) {
                        _uiState.update { it.copy(learningGraph = graphResult.data) }
                    }
                }
            }
    }

    fun resetMemory(target: String) {
        if (_uiState.value.resetting != null) return
        _uiState.update { it.copy(resetting = target) }
        viewModelScope.launch {
            val result = safeApiCall { ApiClient.hermesApi.resetMemory(MemoryResetRequest(target = target)) }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            resetting = null,
                            toastMessage = "Memory ($target) reset successfully",
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            resetting = null,
                            toastMessage = "Failed to reset memory: ${result.error.message}",
                        )
                    }
                }
            }
            load(forceRefresh = true)
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
