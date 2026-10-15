package com.m57.hermescontrol.ui.localmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.LocalModelJob
import com.m57.hermescontrol.data.model.LocalModelJobIdBody
import com.m57.hermescontrol.data.model.LocalModelsHardware
import com.m57.hermescontrol.data.model.LocalModelsStatus
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.remote.safeApiRead
import com.m57.hermescontrol.ui.common.ToastHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LocalModelsUiState(
    val isLoading: Boolean = false,
    val status: LocalModelsStatus? = null,
    val hardware: LocalModelsHardware? = null,
    val jobs: List<LocalModelJob> = emptyList(),
    /** The gateway predates the managed local-models API (HTTP 404). */
    val unavailable: Boolean = false,
    val errorMessage: String? = null,
    /** Job ids with a pause/resume request in flight. */
    val busyJobIds: Set<String> = emptySet(),
    val toastMessage: String? = null,
)

class LocalModelsViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(LocalModelsUiState())
    val uiState: StateFlow<LocalModelsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var pollJob: Job? = null

    fun clearScopeOwnedState() {
        loadJob?.cancel()
        _uiState.value = LocalModelsUiState()
    }

    fun load() {
        if (loadJob?.isActive == true) return
        loadJob =
            viewModelScope.launch {
                val first = _uiState.value.status == null
                if (first) _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                fetchAll()
            }
    }

    private suspend fun fetchAll() {
        val api = ApiClient.hermesApi
        val (status, hardware, jobs) =
            coroutineScope {
                val s = async { safeApiRead { api.getLocalModelsStatus() } }
                val h = async { safeApiRead { api.getLocalModelsHardware() } }
                val j = async { safeApiRead { api.getLocalModelJobs() } }
                Triple(s.await(), h.await(), j.await())
            }
        _uiState.update { state ->
            when (status) {
                is NetworkResult.Failure -> {
                    val notFound = (status.error as? NetworkError.Http)?.code == 404
                    state.copy(
                        isLoading = false,
                        unavailable = notFound,
                        // Keep showing the last good snapshot if a poll tick fails.
                        errorMessage = if (notFound || state.status != null) null else status.error.message,
                    )
                }

                is NetworkResult.Success -> {
                    state.copy(
                        isLoading = false,
                        unavailable = false,
                        errorMessage = null,
                        status = status.data,
                        hardware = (hardware as? NetworkResult.Success)?.data ?: state.hardware,
                        jobs = (jobs as? NetworkResult.Success)?.data?.jobs ?: state.jobs,
                    )
                }
            }
        }
    }

    fun pause(job: LocalModelJob) =
        act(job) { ApiClient.hermesApi.pauseLocalModelDownload(LocalModelJobIdBody(job.jobId)) }

    fun resume(job: LocalModelJob) =
        act(job) {
            ApiClient.hermesApi.resumeLocalModelDownload(LocalModelJobIdBody(job.jobId))
        }

    private fun act(
        job: LocalModelJob,
        call: suspend () -> retrofit2.Response<com.m57.hermescontrol.data.model.LocalModelJobActionResponse>,
    ) {
        if (job.jobId in _uiState.value.busyJobIds) return
        _uiState.update { it.copy(busyJobIds = it.busyJobIds + job.jobId) }
        viewModelScope.launch {
            val result = safeApiCall { call() }
            _uiState.update { state ->
                val failure = (result as? NetworkResult.Failure)?.error?.message
                state.copy(busyJobIds = state.busyJobIds - job.jobId, toastMessage = failure ?: state.toastMessage)
            }
            fetchAll()
        }
    }

    /** Poll while the screen is visible: downloads move fast, the engine state slowly. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    fetchAll()
                }
            }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
    }
}
