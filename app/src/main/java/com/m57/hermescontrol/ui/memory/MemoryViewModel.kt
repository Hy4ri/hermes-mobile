package com.m57.hermescontrol.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.LearningGraphResponse
import com.m57.hermescontrol.data.model.MemoryResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.ui.common.ToastHost
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
class MemoryViewModel : ViewModel(), ToastHost {
    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    fun load() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = safeApiCall { ApiClient.hermesApi.getMemory() }
            when (result) {
                is NetworkResult.Success ->
                    _uiState.update { it.copy(isLoading = false, memory = result.data) }
                is NetworkResult.Failure ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.error.message,
                        )
                    }
            }
            loadLearningGraph()
        }
    }

    private fun loadLearningGraph() {
        viewModelScope.launch {
            val result = safeApiCall { ApiClient.hermesApi.getLearningGraph() }
            if (result is NetworkResult.Success) {
                _uiState.update { it.copy(learningGraph = result.data) }
            }
        }
    }

    fun resetMemory(target: String) {
        if (_uiState.value.resetting != null) return
        _uiState.update { it.copy(resetting = target) }
        viewModelScope.launch {
            val result = safeApiCall { ApiClient.hermesApi.resetMemory(mapOf("target" to target)) }
            when (result) {
                is NetworkResult.Success ->
                    _uiState.update {
                        it.copy(
                            resetting = null,
                            toastMessage = "Memory ($target) reset successfully",
                        )
                    }
                is NetworkResult.Failure ->
                    _uiState.update {
                        it.copy(
                            resetting = null,
                            toastMessage = "Failed to reset memory: ${result.error.message}",
                        )
                    }
            }
            load()
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
